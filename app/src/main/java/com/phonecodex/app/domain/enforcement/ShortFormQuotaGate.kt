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
 * Unreadable saved state BLOCKs only an active short-form play; nothing else changes.
 */
enum class ShortFormQuotaAction {
    NONE,
    ALLOW_AND_COUNT,
    ALLOW_DUPLICATE,
    BLOCK_QUOTA_EXCEEDED,
    BLOCK_QUOTA_UNREADABLE,
    BLOCK_ADULT,
    ALLOW_LONG_EDUCATIONAL,
    SKIP_NOT_SHORT_PLAY
}

data class ShortFormQuotaDecision(
    val action: ShortFormQuotaAction,
    /** Plays counted today; null when the saved counter could not be read. */
    val count: Int?,
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
            ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE,
            ShortFormQuotaAction.BLOCK_ADULT -> "BLOCK"
        }

    val shouldApply: Boolean
        get() = action != ShortFormQuotaAction.NONE &&
            action != ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY
}

/**
 * Daily counter keyed by local day. The live service passes
 * [com.phonecodex.app.data.ShortFormQuotaStore] (SharedPreferences) as [ledger]; each day
 * key is read once, then kept in memory and written back on every counted play. Replay and
 * tests pass a fake or no ledger.
 *
 * Durability is best effort: the store writes with `SharedPreferences.apply()`, which is
 * asynchronous, so if the process dies before the disk write lands the latest counted plays
 * can be lost and the next process resumes from an older, lower count.
 *
 * Every save keeps `count == signatures.size` (one new distinct fingerprint per counted play).
 * An unreadable record is re-read at most once per [UNREADABLE_RETRY_MS] per day key.
 *
 * The local day is derived from [zone] on every call, so a device timezone change is
 * picked up without restarting the service. Pass [zoneId] to pin a zone in tests.
 */
class ShortFormQuotaGate(
    zoneId: ZoneId? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ledger: Ledger? = null,
    private val zone: () -> ZoneId = if (zoneId != null) ({ zoneId }) else ({ ZoneId.systemDefault() })
) {
    interface Ledger {
        fun load(dayKey: String): LedgerRead
        fun save(dayKey: String, count: Int, signatures: Set<String>)
    }

    sealed interface LedgerRead {
        data class Counted(val count: Int, val signatures: Set<String>) : LedgerRead
        data class Unreadable(val detail: String) : LedgerRead
    }

    private val countsByDayKey = mutableMapOf<String, Int>()
    private val signaturesByDayKey = mutableMapOf<String, MutableSet<String>>()
    private val recentPlayFingerprints = mutableMapOf<String, Long>()
    private val hydratedKeys = mutableSetOf<String>()
    private var lastUnreadable: Triple<String, LedgerRead.Unreadable, Long>? = null

    fun reset() {
        countsByDayKey.clear()
        signaturesByDayKey.clear()
        recentPlayFingerprints.clear()
        hydratedKeys.clear()
        lastUnreadable = null
    }

    fun currentCount(dayKey: String = todayKey()): Int =
        countsByDayKey.getOrDefault(dayKey, 0)

    fun todayKey(nowMillis: Long = clock()): String =
        java.time.Instant.ofEpochMilli(nowMillis).atZone(zone()).toLocalDate().toString() +
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
        val stored = hydrate(dayKey, nowMillis)
        val known = if (stored is LedgerRead.Counted) currentCount(dayKey) else null
        val surface = SurfaceDetector.detect(packageName, screenText)
        val titleHint = extractTitleHint(screenText)

        if (limit <= 0) {
            return ShortFormQuotaDecision(
                action = ShortFormQuotaAction.NONE,
                count = known,
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
                count = known,
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
                count = known,
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
                count = known,
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
                count = known,
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Adult/sexual short-form blocked without consuming quota",
                titleHint = titleHint
            )
        }

        if (stored is LedgerRead.Unreadable) {
            return ShortFormQuotaDecision(
                action = ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE,
                count = null,
                limit = limit,
                counted = false,
                surface = surface.surface,
                durationSeconds = surface.durationSeconds,
                dayKey = dayKey,
                reason = "Saved short-form count unreadable (${stored.detail}); shorts blocked, count unknown",
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

    /**
     * Unreadable state is never hydrated or overwritten, so no counted play can replace it
     * with a fabricated count. It is remembered for [UNREADABLE_RETRY_MS] so accessibility
     * events do not re-read preferences each time; after that (or on a new day key, or if the
     * clock moves backwards) the ledger is read again, so a repaired record recovers.
     */
    private fun hydrate(dayKey: String, nowMillis: Long): LedgerRead {
        if (dayKey in hydratedKeys || ledger == null) {
            hydratedKeys += dayKey
            return LedgerRead.Counted(currentCount(dayKey), signaturesByDayKey[dayKey].orEmpty())
        }
        lastUnreadable?.let { (key, read, at) ->
            if (key == dayKey && nowMillis - at in 0 until UNREADABLE_RETRY_MS) return read
        }
        val read = try {
            ledger.load(dayKey)
        } catch (e: RuntimeException) {
            LedgerRead.Unreadable("ledger threw ${e.javaClass.simpleName}")
        }
        val checked = if (read is LedgerRead.Counted &&
            (read.count < 0 || read.signatures.size > read.count)
        ) {
            LedgerRead.Unreadable("count=${read.count} signatures=${read.signatures.size}")
        } else {
            read
        }
        when (checked) {
            is LedgerRead.Counted -> {
                countsByDayKey[dayKey] = checked.count
                signaturesByDayKey[dayKey] = checked.signatures.toMutableSet()
                hydratedKeys += dayKey
                lastUnreadable = null
            }
            is LedgerRead.Unreadable -> lastUnreadable = Triple(dayKey, checked, nowMillis)
        }
        return checked
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
        const val UNREADABLE_RETRY_MS = 30_000L

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
                ?.replace(Regex("""\p{Cntrl}"""), " ")
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
                "count=${decision.count ?: "unreadable"}/${decision.limit} " +
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
