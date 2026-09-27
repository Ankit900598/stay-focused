package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.safeapps.SafeAppsCatalog

/**
 * User-facing explanation of the last ALLOW / WARN / BLOCK / LOCK / WAIT.
 *
 * Display only. Does not change PolicyEngine. AI is counsel; PolicyEngine is law.
 */
data class LastDecisionSnapshot(
    val promiseText: String?,
    val decision: String?,
    val reason: String?,
    val source: String?,
    val packageName: String?,
    val reasonCode: String?,
    val surface: String?,
    val activity: String?,
    val durationSource: String?,
    val matchedSignals: List<String>,
    val updatedMillis: Long,
    val screenTextPreview: String? = null,
    val nowMillis: Long = 0L
)

data class LastDecisionEngineering(
    val packageName: String?,
    val appLabel: String,
    val reasonCode: String?,
    val surface: String?,
    val activity: String?,
    val durationSource: String?,
    val matchedSignals: List<String>,
    val timestampLabel: String,
    val ageLabel: String,
    val sourceKind: String,
    val rawSource: String?
)

data class LastDecisionExplanation(
    val hasDecision: Boolean,
    val isStale: Boolean,
    val userPromise: String,
    val userDecision: String,
    val userWhy: String,
    val userEvidence: String,
    val userNext: String,
    val authorityLine: String,
    val emptyOrStaleMessage: String?,
    val engineering: LastDecisionEngineering
)

object CommitmentTransparency {

    const val STALE_AFTER_MS = 30L * 60L * 1000L
    const val SCREEN_PREVIEW_COPY_LIMIT = 80
    const val REASON_COPY_LIMIT = 240

    private val PACKAGE_NAME = Regex(
        """\b(?:(?:com|org|io|net)\.[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)*""" +
            """|(?:app|me|tv|dev)\.[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)\b"""
    )

    fun explain(snapshot: LastDecisionSnapshot): LastDecisionExplanation {
        val now = if (snapshot.nowMillis > 0L) snapshot.nowMillis else snapshot.updatedMillis
        val code = inferReasonCode(snapshot.reasonCode, snapshot.matchedSignals)
        val decision = snapshot.decision?.trim()?.uppercase().orEmpty()
        val hasDecision = decision.isNotEmpty() && snapshot.updatedMillis > 0L
        val isStale = hasDecision && now - snapshot.updatedMillis > STALE_AFTER_MS
        val sourceKind = sourceKind(snapshot.source, code)
        val emptyMessage = when {
            !hasDecision ->
                "No recent decision. Start a promise, then open an app. " +
                    "Strlix will explain the last allow, warn, block, or lock here."
            isStale ->
                "This decision is older than 30 minutes. It may not match the current screen."
            else -> null
        }
        val userPromise = sanitizeUserText(
            snapshot.promiseText?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (hasDecision) "No active promise text stored." else "No promise yet."
        )
        val userDecision = if (hasDecision) userDecisionLabel(decision) else "none"
        val userWhy = if (hasDecision) {
            sanitizeUserText(userWhy(snapshot, code, decision))
        } else {
            "Strlix has not enforced a screen yet."
        }
        val userEvidence = if (hasDecision) {
            sanitizeUserText(userEvidence(snapshot, code, decision))
        } else {
            "No evidence yet."
        }
        val userNext = if (hasDecision) userNext(decision) else "Start a promise, then open an app."
        return LastDecisionExplanation(
            hasDecision = hasDecision,
            isStale = isStale,
            userPromise = userPromise,
            userDecision = userDecision,
            userWhy = userWhy,
            userEvidence = userEvidence,
            userNext = userNext,
            authorityLine = authorityLine(sourceKind),
            emptyOrStaleMessage = emptyMessage,
            engineering = LastDecisionEngineering(
                packageName = snapshot.packageName,
                appLabel = userFacingAppName(snapshot.packageName),
                reasonCode = code,
                surface = snapshot.surface,
                activity = snapshot.activity,
                durationSource = snapshot.durationSource,
                matchedSignals = snapshot.matchedSignals,
                timestampLabel = timestampLabel(snapshot.updatedMillis),
                ageLabel = ageLabel(snapshot.updatedMillis, now),
                sourceKind = sourceKind,
                rawSource = snapshot.source
            )
        )
    }

    /**
     * Sanitized shareable report. Screen text is omitted unless [includeScreenPreview] is true,
     * and even then it is truncated and labeled.
     */
    fun debugReport(
        snapshot: LastDecisionSnapshot,
        includeScreenPreview: Boolean = false
    ): String {
        val explained = explain(snapshot)
        val eng = explained.engineering
        return buildString {
            appendLine("STRLIX LAST DECISION")
            appendLine("SANITIZED DEBUG REPORT — do not paste private messages.")
            appendLine("AI is counsel. PolicyEngine is law.")
            appendLine()
            appendLine("Promise: ${explained.userPromise}")
            appendLine("Decision: ${explained.userDecision}")
            appendLine("Why: ${explained.userWhy}")
            appendLine("Evidence: ${explained.userEvidence}")
            appendLine("Next: ${explained.userNext}")
            appendLine("Authority: ${explained.authorityLine}")
            explained.emptyOrStaleMessage?.let {
                appendLine("Note: $it")
            }
            appendLine()
            appendLine("--- Engineering ---")
            appendLine("App: ${eng.appLabel}")
            appendLine("Package: ${eng.packageName ?: "—"}")
            appendLine("Reason code: ${eng.reasonCode ?: "—"}")
            appendLine("Surface: ${eng.surface ?: "—"}")
            appendLine("Activity: ${eng.activity ?: "—"}")
            appendLine("Duration source: ${eng.durationSource ?: "—"}")
            appendLine("Matched signals: ${eng.matchedSignals.joinToString(", ").ifBlank { "—" }}")
            appendLine("Source kind: ${eng.sourceKind}")
            appendLine("Raw source: ${eng.rawSource ?: "—"}")
            appendLine("Timestamp: ${eng.timestampLabel}")
            appendLine("Age: ${eng.ageLabel}")
            appendLine()
            if (includeScreenPreview) {
                val preview = snapshot.screenTextPreview
                    ?.replace('\n', ' ')
                    ?.replace('\r', ' ')
                    ?.trim()
                    .orEmpty()
                    .take(SCREEN_PREVIEW_COPY_LIMIT)
                appendLine(
                    "SCREEN TEXT PREVIEW (truncated, may be private, ${SCREEN_PREVIEW_COPY_LIMIT} chars max):"
                )
                appendLine(preview.ifBlank { "(empty)" })
            } else {
                appendLine("Screen text: not included (private).")
                appendLine("Engineering inspector may show a truncated preview on-device.")
            }
            snapshot.reason?.trim()?.takeIf { it.isNotEmpty() }?.let { rawReason ->
                appendLine()
                appendLine("Raw reason (truncated): ${rawReason.take(REASON_COPY_LIMIT)}")
            }
        }.trimEnd()
    }

    fun inferReasonCode(explicit: String?, signals: List<String>): String? {
        val trimmed = explicit?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) return trimmed
        return signals.firstOrNull { looksLikeReasonCode(it) }
    }

    fun looksLikeReasonCode(value: String): Boolean {
        val token = value.trim()
        return token.length >= 6 &&
            token.contains('_') &&
            token == token.uppercase() &&
            token.all { it == '_' || it.isLetterOrDigit() }
    }

    fun containsPackageName(text: String): Boolean = PACKAGE_NAME.containsMatchIn(text)

    fun userFacingAppName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "this app"
        VideoPlatformRegistry.entry(packageName)?.label?.let { return it }
        val label = SafeAppsCatalog.defaultLabel(packageName)
        return if (label.contains('.') && label == packageName) "this app" else label
    }

    fun sourceKind(source: String?, reasonCode: String?): String {
        val s = source.orEmpty()
        val code = reasonCode.orEmpty()
        return when {
            code == EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL ||
                code == EnforcementReasonCodes.QUOTA_BLOCK_ADULT ||
                s.contains("Guardrail", ignoreCase = true) ->
                "Safety rule"
            code == EnforcementReasonCodes.EMERGENCY_ALLOW ||
                s.equals("Safe App", ignoreCase = true) ->
                "Safety / emergency rule"
            code == EnforcementReasonCodes.VISION_COUNSEL ||
                s.contains("Vision", ignoreCase = true) ->
                "AI counsel"
            code.startsWith("AI_") ||
                s.contains("Backend", ignoreCase = true) ||
                s.contains("Classifier", ignoreCase = true) ||
                s.contains("AI", ignoreCase = true) ->
                "AI counsel"
            s.contains("App Rule", ignoreCase = true) ->
                "Saved app rule"
            else ->
                "Deterministic policy"
        }
    }

    private fun authorityLine(sourceKind: String): String = when (sourceKind) {
        "AI counsel" ->
            "AI suggested this as counsel. PolicyEngine is the law and still made the recorded decision."
        "Safety rule",
        "Safety / emergency rule" ->
            "A safety rule decided this. PolicyEngine is the law. AI cannot override it."
        "Saved app rule" ->
            "Your saved app rule decided this. PolicyEngine is the law. AI is counsel, not the judge."
        else ->
            "PolicyEngine decided this. AI is counsel, not the judge."
    }

    private fun userDecisionLabel(decision: String): String = when (decision) {
        "ALLOW" -> "allowed"
        "WARN" -> "warned"
        "BLOCK" -> "blocked"
        "LOCK" -> "locked"
        "WAIT" -> "waiting"
        else -> decision.lowercase()
    }

    private fun userWhy(
        snapshot: LastDecisionSnapshot,
        code: String?,
        decision: String
    ): String {
        val app = userFacingAppName(snapshot.packageName)
        val overlay = SessionEnforcementCopy.overlayEvidence(
            reasonCode = code,
            fallback = snapshot.reason?.trim().orEmpty(),
            hardBlock = decision == "BLOCK" || decision == "LOCK"
        ).trim()
        return when (code) {
            EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT ->
                "Strlix allowed $app because the video length is inside your promise."
            EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
            EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN ->
                "Strlix blocked $app because this video is shorter than your promise allows."
            EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX ->
                "Strlix blocked $app because this video is longer than your promise allows."
            EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK ->
                "Strlix is waiting on $app because the player clock is not readable yet."
            EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE ->
                overlay.ifBlank {
                    SessionEnforcementCopy.YOUTUBE_CLOCK_UNREADABLE_BLOCK
                }
            EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE ->
                overlay.ifBlank { SessionEnforcementCopy.BLANK_TREE_MOVIE_BLOCK }
            EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED ->
                "Strlix blocked short-form video because today's short-form quota is used up."
            EnforcementReasonCodes.QUOTA_ALLOW_COUNT ->
                "Strlix allowed this short because it still counts inside today's quota."
            EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE ->
                SessionEnforcementCopy.QUOTA_STATE_UNREADABLE_BLOCK
            EnforcementReasonCodes.QUOTA_BLOCK_ADULT,
            EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL ->
                overlay.ifBlank { SessionEnforcementCopy.ADULT_GUARDRAIL_BLOCK }
            EnforcementReasonCodes.CONTENT_SIGNAL_BLOCK ->
                "Strlix blocked this because the screen matched a surface your promise forbids."
            EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW ->
                "Strlix allowed this because the screen matched what your promise permits."
            EnforcementReasonCodes.SURFACE_PASS_PASSIVE ->
                "Strlix allowed this $app screen because it is not an active player."
            EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK ->
                overlay.ifBlank { SessionEnforcementCopy.MONK_ENTERTAINMENT_BLOCK }
            EnforcementReasonCodes.EMERGENCY_ALLOW ->
                "Strlix allowed this because it is a safety or always-allowed app."
            EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW ->
                "Strlix allowed this because it is outside the apps your promise covers."
            EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED ->
                overlay.ifBlank { SessionEnforcementCopy.SESSION_LOCK_ALLOW_UNRELATED }
            EnforcementReasonCodes.AI_WARN,
            EnforcementReasonCodes.AI_BLOCK,
            EnforcementReasonCodes.AI_ALLOW,
            EnforcementReasonCodes.VISION_COUNSEL ->
                "AI counsel flagged this screen. PolicyEngine recorded the decision — AI is not the law."
            else ->
                overlay.ifBlank {
                    snapshot.reason?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "Strlix applied your confirmed promise."
                }
        }
    }

    private fun userEvidence(
        snapshot: LastDecisionSnapshot,
        code: String?,
        decision: String
    ): String {
        val app = userFacingAppName(snapshot.packageName)
        val duration = snapshot.durationSource?.uppercase()
        val activity = snapshot.activity?.uppercase().orEmpty()
        val signals = snapshot.matchedSignals.joinToString(" ").lowercase()
        val shortForm = activity.contains("SHORT") ||
            signals.contains("short") ||
            signals.contains("reel") ||
            code == EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED ||
            code == EnforcementReasonCodes.QUOTA_ALLOW_COUNT ||
            code == EnforcementReasonCodes.QUOTA_BLOCK_ADULT ||
            code == EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE
        val chromeLongAllow = decision == "ALLOW" &&
            code == EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT &&
            (VideoPlatformRegistry.isChrome(snapshot.packageName.orEmpty()) ||
                app.equals("Chrome", ignoreCase = true))
        val youtubeBlank = code == EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE &&
            (decision == "BLOCK" || decision == "LOCK" || decision == "WARN")

        return when {
            chromeLongAllow ->
                "Chrome showed a long video. The player clock was used" +
                    durationSourcePhrase(duration) +
                    ", so the length counted."
            youtubeBlank ->
                "The YouTube player hid its length clock, and your promise is strict about longer videos."
            code == EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE ->
                "This movie/OTT screen was unreadable, and your promise bans that kind of playback."
            shortForm && (decision == "BLOCK" || decision == "LOCK") ->
                "Short-form play was detected (Shorts/Reels-style). That surface matched your promise."
            shortForm && decision == "ALLOW" ->
                "Short-form play was detected and still fits the quota or promise."
            code == EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT ||
                code == EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN ||
                code == EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX ->
                "$app player length was readable" + durationSourcePhrase(duration) + "."
            code == EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK ->
                "No trustworthy current-player clock yet" +
                    durationSourcePhrase(duration) + "."
            code == EnforcementReasonCodes.SURFACE_PASS_PASSIVE ->
                "Home, search, shelf, or another passive $app surface — not an active player."
            snapshot.reason?.isNotBlank() == true ->
                snapshot.reason.trim()
            else ->
                "Matched signals were recorded in engineering detail."
        }
    }

    private fun durationSourcePhrase(duration: String?): String = when (duration) {
        "CURRENT_PLAYER" -> " (source: current player clock)"
        "RECOMMENDATION" -> " (source: recommendation cards — not used to block)"
        "AMBIGUOUS" -> " (source: ambiguous clock — not used to block)"
        "NONE" -> " (source: no clock)"
        else -> duration?.let { " (source: ${it.lowercase()})" }.orEmpty()
    }

    private fun userNext(decision: String): String = when (decision) {
        "ALLOW" -> "Nothing to do if this matches your promise. Keep going."
        "WARN" -> "Leave this screen, or continue if you meant to. Repeated warns can lock the session."
        "BLOCK" -> "Leave this app or open something your promise allows."
        "LOCK" -> "The session is locked. Calls and emergency apps still work. To end it, use End Commitment or emergency exit in Strlix."
        "WAIT" -> "Stay until the player clock is readable, or leave if this should not play."
        else -> "Open Strlix if the decision does not match your promise."
    }

    private fun sanitizeUserText(text: String): String {
        return PACKAGE_NAME.replace(text) { match ->
            userFacingAppName(match.value)
        }.replace(Regex("""\s{2,}"""), " ").trim()
    }

    private fun timestampLabel(updatedMillis: Long): String {
        if (updatedMillis <= 0L) return "none"
        return updatedMillis.toString()
    }

    private fun ageLabel(updatedMillis: Long, nowMillis: Long): String {
        if (updatedMillis <= 0L) return "no decision yet"
        val now = if (nowMillis > 0L) nowMillis else updatedMillis
        val age = (now - updatedMillis).coerceAtLeast(0L)
        return when {
            age < 60_000L -> "just now"
            age < 3_600_000L -> "${age / 60_000L} min ago"
            age < 86_400_000L -> "${age / 3_600_000L} h ago"
            else -> "${age / 86_400_000L} d ago"
        }
    }
}
