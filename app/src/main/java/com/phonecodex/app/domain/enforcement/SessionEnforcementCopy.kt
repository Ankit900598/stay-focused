package com.phonecodex.app.domain.enforcement

/**
 * User-visible session status and overlay evidence.
 * Promise clocks in StudyWorldSettings do nothing until a commitment is started.
 */
object SessionEnforcementCopy {
    const val NO_ACTIVE_COMMITMENT = "No active commitment — rules are not enforcing."

    const val DURATION_UNAVAILABLE =
        "The player clock is unreadable, so we cannot prove this is over or under your time limit."

    const val YOUTUBE_CLOCK_UNREADABLE_BLOCK =
        "YouTube hid the video length. Your promise only allows longer videos, so this is blocked."

    const val BLANK_TREE_MOVIE_BLOCK =
        "This movie/OTT app hid its screen. Your promise bans movies, so playback is blocked."

    const val MONK_ENTERTAINMENT_BLOCK =
        "Monk mode allows calls and study only. Social, games, dating, random chat, and installs are blocked."

    const val SESSION_LOCK_ALLOW_UNRELATED =
        "Session is locked on entertainment only. This app is still usable."

    const val ADULT_GUARDRAIL_BLOCK =
        "This looks like adult content. Your adult-content rule is on."

    const val QUOTA_REMINDER_COMPANION =
        "Reminder only. Keep watching until 10, then shorts stop."

    const val QUOTA_KEEP_WATCHING = "Keep watching"

    const val QUOTA_STATE_UNREADABLE_BLOCK =
        "Strlix can't read today's shorts count, so shorts are paused. " +
            "Other apps and non-short videos are not affected."

    fun shortsRemaining(count: Int, limit: Int): String {
        val left = (limit - count).coerceAtLeast(0)
        return if (left <= 0) {
            "0 shorts left. This commitment blocks more short videos."
        } else {
            "$left shorts left of $limit."
        }
    }

    fun shouldShowNoActiveCommitmentWarning(hasActiveSession: Boolean): Boolean =
        !hasActiveSession

    fun diagnosticsSessionStatus(hasActiveSession: Boolean, sessionStatusName: String?): String {
        if (hasActiveSession) {
            return sessionStatusName ?: "ACTIVE"
        }
        return "none — $NO_ACTIVE_COMMITMENT"
    }

    fun overlayEvidence(
        reasonCode: String?,
        fallback: String,
        hardBlock: Boolean = false
    ): String = when (reasonCode) {
        EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE ->
            if (hardBlock) YOUTUBE_CLOCK_UNREADABLE_BLOCK else DURATION_UNAVAILABLE
        EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE -> BLANK_TREE_MOVIE_BLOCK
        EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK -> MONK_ENTERTAINMENT_BLOCK
        EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
        EnforcementReasonCodes.QUOTA_BLOCK_ADULT -> ADULT_GUARDRAIL_BLOCK
        EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE -> QUOTA_STATE_UNREADABLE_BLOCK
        else -> fallback
    }

    fun hasDedicatedOverlayCopy(reasonCode: String?): Boolean = when (reasonCode) {
        EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
        EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE,
        EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK,
        EnforcementReasonCodes.STRUCTURED_SURFACE_BLOCK,
        EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
        EnforcementReasonCodes.QUOTA_BLOCK_ADULT,
        EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE,
        EnforcementReasonCodes.QUOTA_ALLOW_COUNT -> true
        else -> false
    }

    fun hidePromiseLine(reasonCode: String?, hardBlock: Boolean = false): Boolean =
        reasonCode == EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE && !hardBlock

    fun forceContinue(reasonCode: String?, hardBlock: Boolean = false): Boolean =
        (reasonCode == EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE && !hardBlock) ||
            reasonCode == EnforcementReasonCodes.QUOTA_ALLOW_COUNT

    fun isQuotaReminder(reasonCode: String?): Boolean =
        reasonCode == EnforcementReasonCodes.QUOTA_ALLOW_COUNT
}
