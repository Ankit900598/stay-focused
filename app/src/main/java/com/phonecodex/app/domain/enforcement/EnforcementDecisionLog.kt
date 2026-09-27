package com.phonecodex.app.domain.enforcement

/**
 * Machine-readable, stable reason codes for every enforcement decision log line.
 * Grep contract: values never change once shipped; add new codes instead.
 */
object EnforcementReasonCodes {
    const val SURFACE_PASS_PASSIVE = "SURFACE_PASS_PASSIVE"
    const val MEDIA_WAIT_NO_PLAYER_CLOCK = "MEDIA_WAIT_NO_PLAYER_CLOCK"
    const val MEDIA_BLOCK_OVER_MAX = "MEDIA_BLOCK_OVER_MAX"
    const val MEDIA_BLOCK_UNDER_MIN = "MEDIA_BLOCK_UNDER_MIN"

    /**
     * Active short-form player blocked by a >1 min per-video floor with no
     * current-player clock. MEDIA_BLOCK_UNDER_MIN / OVER_MAX always imply a clock.
     */
    const val SHORT_FORM_BLOCK_UNDER_MIN = "SHORT_FORM_BLOCK_UNDER_MIN"
    const val MEDIA_ALLOW_WITHIN_LIMIT = "MEDIA_ALLOW_WITHIN_LIMIT"
    const val QUOTA_ALLOW_COUNT = "QUOTA_ALLOW_COUNT"
    const val QUOTA_BLOCK_EXCEEDED = "QUOTA_BLOCK_EXCEEDED"
    const val QUOTA_BLOCK_ADULT = "QUOTA_BLOCK_ADULT"
    /** Active short-form play blocked because today's saved count could not be read. */
    const val QUOTA_BLOCK_STATE_UNREADABLE = "QUOTA_BLOCK_STATE_UNREADABLE"
    const val QUOTA_SKIP_NOT_PLAY = "QUOTA_SKIP_NOT_PLAY"
    const val ADULT_BLOCK_GUARDRAIL = "ADULT_BLOCK_GUARDRAIL"
    const val AI_SUPPRESSED_BY_SURFACE_GATE = "AI_SUPPRESSED_BY_SURFACE_GATE"
    const val AI_WARN = "AI_WARN"
    const val AI_BLOCK = "AI_BLOCK"
    const val APP_RULE_BLOCK = "APP_RULE_BLOCK"
    /** Confirmed structured surface rule (e.g. YouTube Shorts) blocked this surface only. */
    const val STRUCTURED_SURFACE_BLOCK = "STRUCTURED_SURFACE_BLOCK"
    const val EMERGENCY_ALLOW = "EMERGENCY_ALLOW"
    const val OVERLAY_CLEAR_CONTEXT_EXIT = "OVERLAY_CLEAR_CONTEXT_EXIT"
    const val UNKNOWN_CLONE_WARN = "UNKNOWN_CLONE_WARN"

    // Supplementary codes for remaining deterministic call sites (same stability contract).
    const val AI_ALLOW = "AI_ALLOW"
    const val CONTENT_SIGNAL_ALLOW = "CONTENT_SIGNAL_ALLOW"
    const val CONTENT_SIGNAL_BLOCK = "CONTENT_SIGNAL_BLOCK"
    const val FEEDBACK_MEMORY_ALLOW = "FEEDBACK_MEMORY_ALLOW"
    const val FEEDBACK_MEMORY_BLOCK = "FEEDBACK_MEMORY_BLOCK"
    const val NO_STRUCTURED_MEDIA_RULE = "NO_STRUCTURED_MEDIA_RULE"
    const val APP_RULE_ALLOW_DEFERRED = "APP_RULE_ALLOW_DEFERRED"
    const val APP_RULE_ALLOW = "APP_RULE_ALLOW"
    const val APP_RULE_WARN = "APP_RULE_WARN"
    /** Deterministic law had no verdict; feedback memory / advisory AI may run next. */
    const val CONTINUE_TO_COUNSEL = "CONTINUE"
    const val PRODUCTIVITY_UNRELATED_ALLOW = "PRODUCTIVITY_UNRELATED_ALLOW"
    /** Own overlay / system UI event: no decision, the current overlay state is kept. */
    const val SYSTEM_NOISE_IGNORED = "SYSTEM_NOISE_IGNORED"
    /** Blank accessibility text: content law and AI are skipped, the last decision stays. */
    const val BLANK_TEXT_KEEP_LAST = "BLANK_TEXT_KEEP_LAST"
    /** Prefer [NO_ACTIVE_SESSION_ALLOW] in new logs; kept as a grep alias. */
    const val NO_SESSION_ALLOW = "NO_SESSION_ALLOW"
    const val NO_ACTIVE_SESSION_ALLOW = "NO_ACTIVE_SESSION_ALLOW"
    const val UNKNOWN_VIDEO_APP_BLANK_TREE = "UNKNOWN_VIDEO_APP_BLANK_TREE"
    const val VIDEO_APP_DURATION_UNAVAILABLE = "VIDEO_APP_DURATION_UNAVAILABLE"
    const val ENFORCEMENT_SCOPE_ALLOW = "ENFORCEMENT_SCOPE_ALLOW"

    /**
     * Scope Semantics v1: no trusted scope for this promise (compiler returned UNRESOLVED
     * or the legacy adapter yielded an unenforceable shape). Enforce nothing; Home
     * shows "not protecting". Grep contract: value is append-only.
     */
    const val SCOPE_UNRESOLVED_NO_ENFORCE = "SCOPE_UNRESOLVED_NO_ENFORCE"

    /**
     * Scope Semantics v1: the confirmed typed scope was persisted but cannot be read
     * back. This promise is unverified and not enforced; the legacy mirror is never
     * substituted. Home must not say "Protected". Grep contract: value is append-only.
     */
    const val SCOPE_PERSISTENCE_CORRUPT = "SCOPE_PERSISTENCE_CORRUPT"

    /**
     * Scope Semantics v1: an exempt clause matched (e.g. DM inside a
     * SOCIAL_FEED_AND_REELS_ONLY promise). Allow without touching the gates.
     * Grep contract: value is append-only.
     */
    const val SCOPE_EXEMPT_ALLOW = "SCOPE_EXEMPT_ALLOW"

    /**
     * Typed scope: host/content matched but the deciding evidence is uncertain
     * (UNKNOWN surface under a player-only clause, or a browser-by-name package).
     * WAIT: nothing is blocked and nothing is claimed as verified out of scope.
     * Grep contract: value is append-only.
     */
    const val SCOPE_EVIDENCE_INDETERMINATE = "SCOPE_EVIDENCE_INDETERMINATE"
    const val VISION_EXPERIMENT_SKIPPED = "VISION_EXPERIMENT_SKIPPED"
    const val A11Y_BLIP_NO_LOCK = "A11Y_BLIP_NO_LOCK"
    const val MONK_ENTERTAINMENT_BLOCK = "MONK_ENTERTAINMENT_BLOCK"
    const val SESSION_LOCK_ALLOW_UNRELATED = "SESSION_LOCK_ALLOW_UNRELATED"
    const val VISION_COUNSEL = "VISION_COUNSEL"
}

/**
 * Stable, grep-friendly enforcement log lines.
 * Format: pkg=… surface=… activity=… rule=… durationSource=… decision=… code=… reason=…
 */
object EnforcementDecisionLog {

    fun format(
        packageName: String,
        surface: String,
        rule: String,
        decision: String,
        reasonCode: String,
        reason: String,
        count: Int? = null,
        limit: Int? = null,
        evidence: List<String> = emptyList(),
        activity: String? = null,
        durationSource: String? = null,
        ruleId: String? = null,
        scopeKind: String? = null,
        scopeClause: String? = null
    ): String {
        val activityPart = activity?.let { " activity=$it" }.orEmpty()
        val ruleIdPart = ruleId?.let { " ruleId=$it" }.orEmpty()
        val durationSourcePart = durationSource?.let { " durationSource=$it" }.orEmpty()
        val countPart = when {
            count != null && limit != null -> " count=$count/$limit"
            count != null -> " count=$count"
            else -> ""
        }
        val evidencePart = if (evidence.isEmpty()) {
            ""
        } else {
            " evidence=${evidence.take(4).joinToString("|")}"
        }
        val scopeKindPart = scopeKind?.let { " scopeKind=$it" }.orEmpty()
        val scopeClausePart = scopeClause?.let { " scopeClause=$it" }.orEmpty()
        return "pkg=$packageName surface=$surface$activityPart rule=$rule$ruleIdPart" +
            "$durationSourcePart$countPart$scopeKindPart$scopeClausePart " +
            "decision=$decision code=$reasonCode reason=$reason$evidencePart"
    }
}
