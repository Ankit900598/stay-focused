package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.guardrail.AdultContentLaw
import java.time.LocalDate
import java.time.ZoneId

/**
 * Deterministic short-form quota law (Promise Semantics v1).
 *
 * Boundary: plays 1..N ALLOW (counted); play N+1+ BLOCK until local day ends.
 * Adult/sexual short-form BLOCK immediately and does NOT consume quota.
 * Non-player surfaces never count.
 */
enum class ShortFormQuotaAction {
    NONE,
    ALLOW_AND_COUNT,
    ALLOW_DUPLICATE,
    BLOCK_QUOTA_EXCEEDED,
    BLOCK_ADULT,
    ALLOW_LONG_EDUCATIONAL,
    SKIP_NOT_SHORT_PLAY
}

data class ShortFormQuotaDecision(
    val action: ShortFormQuotaAction,
    val count: Int,
    val limit: Int,
    val counted: Boolean,
    val surface: SurfaceDetectionResult,
    val durationSeconds: Int?,
    val dayKey: String,
    val reason: String,
    val titleHint: String? = null
) {
    val decisionName: String
        get() = when (action) {
            ShortFormQuotaAction.NONE,
            ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY -> "PASS"
            ShortFormQuotaAction.ALLOW_AND_COUNT,
            ShortFormQuotaAction.ALLOW_DUPLICATE,
            ShortFormQuotaAction.ALLOW_LONG_EDUCATIONAL -> "ALLOW"
            ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED,
            ShortFormQuotaAction.BLOCK_ADULT -> "BLOCK"
        }

    val shouldApply: Boolean
        get() = action != ShortFormQuotaAction.NONE &&
            action != ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY
}

/**
 * In-memory daily counter. TODO: persist via SharedPreferences / DataStore so
 * process death does not reset mid-day quota.
 */
class ShortFormQuotaGate(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ledger: Ledger? = null
) {
    interface Ledger {
        fun load(dayKey: String): Pair<Int, Set<String>>
        fun save(dayKey: String, count: Int, signatures: Set<String>)
    }

    private val countsByDayKey = mutableMapOf<String, Int>()
    private val signaturesByDayKey = mutableMapOf<String, MutableSet<String>>()
    private val recentPlayFingerprints = mutableMapOf<String, Long>()
    private val hydratedKeys = mutableSetOf<String>()

    fun reset() {
        countsByDayKey.clear()
        signaturesByDayKey.clear()
        recentPlayFingerprints.clear()
        hydratedKeys.clear()
    }

    fun currentCount(dayKey: String = todayKey()): Int =
        countsByDayKey.getOrDefault(dayKey, 0)

    fun todayKey(nowMillis: Long = clock()): String =
        java.time.Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().toString() +
            ":" + QUOTA_TYPE

    /**
     * Phase 2A: scope preflight moved to [com.phonecodex.app.domain.scope.ScopeGate].
     * `enforcementScopePackages` and `contentBrands` remain as `@Deprecated` no-op
     * parameters so the 80+ trace callers compile unchanged.
     * See docs/scope-semantics-v1-phase2-review.md §3.
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluate(
        packageName: String,
        screenText: String,
        limit: Int,
        allowLongEducational: Boolean = true,
        nowMillis: Long = clock(),
        // @Deprecated: Scope handled by ScopeGate in Phase 2A. Kept for source
        // compatibility with the 80+ existing trace callers.
        enforcementScopePackages: Collection<String> = emptyList(),
        contentBrands: Collection<String> = emptyList()
    ): ShortFormQuotaDecision {
        val dayKey = todayKey(nowMillis)
        hydrate(dayKey)
        val surface = SurfaceDetector.detect(packageName, screenText)
        val titleHint = extractTitleHint(screenText)

        if (limit <= 0) {
            return ShortFormQuotaDecision(
                action = ShortFormQuotaAction.NONE,
                count = currentCount(dayKey),
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "No short-form daily quota configured",
                titleHint = titleHint
            )
        }

        if (!VideoPlatformRegistry.participatesInShortFormQuota(packageName)) {
            return ShortFormQuotaDecision(
                action = ShortFormQuotaAction.NONE,
                count = currentCount(dayKey),
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Package not in short-form quota scope",
                titleHint = titleHint
            )
        }

        // Long educational exception — never consumes short-form quota.
        if (
            allowLongEducational &&
            surface.surface == SurfaceDetectionResult.LONG_FORM_PLAYER &&
            looksEducational(screenText)
        ) {
            return ShortFormQuotaDecision(
                action = ShortFormQuotaAction.ALLOW_LONG_EDUCATIONAL,
                count = currentCount(dayKey),
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Long educational video allowed outside short-form quota",
                titleHint = titleHint
            )
        }

        if (surface.surface != SurfaceDetectionResult.SHORT_FORM_PLAYER) {
            return ShortFormQuotaDecision(
                action = ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY,
                count = currentCount(dayKey),
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Not an active short-form play (home/search/shelf/comments ignored)",
                titleHint = titleHint
            )
        }

        // Adult short-form: hard BLOCK, do not consume friendly quota.
        if (isAdultSexual(screenText)) {
            return ShortFormQuotaDecision(
                action = ShortFormQuotaAction.BLOCK_ADULT,
                count = currentCount(dayKey),
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Adult/sexual short-form blocked without consuming quota",
                titleHint = titleHint
            )
        }

        val fingerprint = playFingerprint(packageName, screenText, surface)
        pruneRecent(nowMillis)
        val lastSeen = recentPlayFingerprints[fingerprint]
        if (lastSeen != null && nowMillis - lastSeen < DEDUPE_WINDOW_MS) {
            val count = currentCount(dayKey)
            val action = if (count >= limit) {
                ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED
            } else {
                ShortFormQuotaAction.ALLOW_DUPLICATE
            }
            return ShortFormQuotaDecision(
                action = action,
                count = count,
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Duplicate short-form play within debounce window",
                titleHint = titleHint
            )
        }

        val signatures = signaturesByDayKey.getOrPut(dayKey) { linkedSetOf() }
        val current = countsByDayKey.getOrDefault(dayKey, 0)

        if (fingerprint in signatures) {
            recentPlayFingerprints[fingerprint] = nowMillis
            val action = if (current >= limit) {
                ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED
            } else {
                ShortFormQuotaAction.ALLOW_DUPLICATE
            }
            return ShortFormQuotaDecision(
                action = action,
                count = current,
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Same short-form play already counted today",
                titleHint = titleHint
            )
        }

        // Boundary law: if already at/over N, block without counting further.
        if (current >= limit) {
            recentPlayFingerprints[fingerprint] = nowMillis
            return ShortFormQuotaDecision(
                action = ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED,
                count = current,
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Short-form daily quota $limit reached",
                titleHint = titleHint
            )
        }

        // Count this play (1..N) and ALLOW.
        signatures += fingerprint
        val next = current + 1
        countsByDayKey[dayKey] = next
        recentPlayFingerprints[fingerprint] = nowMillis
        persist(dayKey)

        return ShortFormQuotaDecision(
            action = ShortFormQuotaAction.ALLOW_AND_COUNT,
            count = next,
            limit = limit,
            counted = true,
            surface = surface.surface,
            durationSeconds = surface.durationSeconds,
            dayKey = dayKey,
            reason = "Short-form play counted $next/$limit",
            titleHint = titleHint
        )
    }

    private fun hydrate(dayKey: String) {
        if (dayKey in hydratedKeys) return
        ledger?.load(dayKey)?.let { (count, signatures) ->
            countsByDayKey[dayKey] = count
            signaturesByDayKey[dayKey] = signatures.toMutableSet()
        }
        hydratedKeys += dayKey
    }

    private fun persist(dayKey: String) {
        ledger?.save(
            dayKey,
            countsByDayKey.getOrDefault(dayKey, 0),
            signaturesByDayKey[dayKey].orEmpty()
        )
    }

    private fun pruneRecent(nowMillis: Long) {
        val cutoff = nowMillis - DEDUPE_WINDOW_MS * 4
        recentPlayFingerprints.entries.removeAll { it.value < cutoff }
    }

    companion object {
        const val QUOTA_TYPE = "short_form_video"
        const val DEDUPE_WINDOW_MS = 8_000L

        fun parseDailyShortFormLimit(goal: String): Int? {
            return ShortFormLanguage.parseLimit(goal)
                ?: QuotaEnforcementGate.parseShortsQuotaLimit(goal)
        }

        fun isDailyShortFormPromise(goal: String): Boolean {
            val normalized = goal.lowercase()
            if (parseDailyShortFormLimit(normalized) == null) return false
            return normalized.contains("today") ||
                normalized.contains("per day") ||
                normalized.contains("daily") ||
                normalized.contains("rest of the day") ||
                normalized.contains("for the day")
        }

        fun allowsLongEducational(goal: String): Boolean {
            val normalized = goal.lowercase()
            if (normalized.contains("only shorts") || normalized.contains("shorts only")) {
                return false
            }
            // Default for this promise class: long educational stays allowed.
            return normalized.contains("long") ||
                normalized.contains("lecture") ||
                normalized.contains("educational") ||
                normalized.contains("study") ||
                isDailyShortFormPromise(normalized) ||
                !PromiseIntentRulesProxy.blocksAllVideo(normalized)
        }

        fun isAdultSexual(screenText: String): Boolean {
            return AdultContentLaw.isHardBlock(screenText)
        }

        fun looksEducational(screenText: String): Boolean {
            val normalized = screenText.lowercase()
            return EDUCATIONAL_KEYWORDS.any { normalized.contains(it) }
        }

        fun playFingerprint(
            packageName: String,
            screenText: String,
            surface: SurfaceDetection
        ): String {
            // Do NOT include duration/clocks — a11y thrash would double-count the same play.
            val title = extractTitleHint(screenText)
                ?.lowercase()
                ?.replace(CLOCK_ANYWHERE, " ")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.take(80)
                .orEmpty()
            return "$packageName|$title|${surface.surface.name}"
        }

        fun extractTitleHint(screenText: String): String? {
            val lines = screenText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return lines.firstOrNull { line ->
                line.length in 4..120 &&
                    !line.equals("play", ignoreCase = true) &&
                    !line.equals("pause", ignoreCase = true) &&
                    line.lowercase() !in TITLE_SKIP_WORDS &&
                    !CLOCK_ONLY.matches(line)
            } ?: lines.firstOrNull { line ->
                line.lowercase() !in TITLE_SKIP_WORDS && !CLOCK_ONLY.matches(line)
            }
        }

        fun formatLog(decision: ShortFormQuotaDecision, packageName: String): String {
            val title = decision.titleHint?.take(40)?.let { " title=$it" }.orEmpty()
            val dur = decision.durationSeconds?.let { " durationSec=$it" }.orEmpty()
            return "ShortQuota pkg=$packageName surface=${decision.surface.name} " +
                "count=${decision.count}/${decision.limit} " +
                "counted=${decision.counted} action=${decision.decisionName}" +
                dur + title + " reason=${decision.reason}"
        }

        private val EDUCATIONAL_KEYWORDS = listOf(
            "lecture",
            "tutorial",
            "course",
            "neso",
            "exam",
            "dsa",
            "algorithm",
            "educational",
            "university",
            "semester"
        )

        private val TITLE_SKIP_WORDS = setOf(
            "like",
            "dislike",
            "comment",
            "comments",
            "share",
            "remix",
            "subscribe",
            "home",
            "shorts",
            "subscriptions",
            "you",
            "search"
        )

        private val CLOCK_ONLY = Regex("""^\d{1,2}:\d{2}(?::\d{2})?(?:\s*/\s*\d{1,2}:\d{2}(?::\d{2})?)?$""")
        private val CLOCK_ANYWHERE =
            Regex("""\b\d{1,2}:\d{2}(?::\d{2})?(?:\s*/\s*\d{1,2}:\d{2}(?::\d{2})?)?\b""")
    }
}

/**
 * Tiny shim so ShortFormQuotaGate companion stays free of a hard import cycle
 * while still respecting "block all video" style goals.
 */
internal object PromiseIntentRulesProxy {
    fun blocksAllVideo(goal: String): Boolean {
        val normalized = goal.lowercase()
        return (normalized.contains("no video") || normalized.contains("block all video")) &&
            !normalized.contains("short")
    }
}

/**
 * Pure gate: backend AI must not override deterministic quota/guardrail/safe-app law.
 */
object DeterministicDecisionPriority {
    fun finalDecision(
        deterministicDecision: String?,
        aiDecision: String?,
        isSafeOrEmergencyApp: Boolean
    ): String {
        if (isSafeOrEmergencyApp) return "ALLOW"
        if (deterministicDecision == "BLOCK" || deterministicDecision == "LOCK") {
            return deterministicDecision
        }
        if (deterministicDecision == "ALLOW") {
            // Deterministic allow (quota under limit / long edu) still wins over AI block
            // only when it is an explicit quota/edu allow — callers pass null otherwise.
            return deterministicDecision
        }
        return aiDecision ?: deterministicDecision ?: "ALLOW"
    }

    fun aiMayRun(deterministicDecision: String?): Boolean =
        deterministicDecision.isNullOrBlank() ||
            deterministicDecision == "PASS" ||
            deterministicDecision == "NONE"
}
