package com.phonecodex.app.domain.model

data class FocusSession(
    val id: String,
    val worldId: String,
    val goal: String,
    val startTimeMillis: Long,
    val deadlineMillis: Long,
    val status: SessionStatus,
    val attemptCount: Int,
    val strictness: StrictnessLevel,
    /**
     * Strictness the user confirmed on the promise. Drives exit friction only;
     * PolicyEngine keeps using [strictness]. Null for sessions saved before this field.
     */
    val promiseStrictness: StrictnessLevel? = null,
    /** Exact app/content rules shown on the confirmation preview for this session. */
    val confirmedPolicy: ConfirmedSessionPolicy? = null
) {
    /**
     * What the user confirmed. Every user-facing friction reads this: exit friction, the
     * WARN overlay's Continue, the active card. [strictness] is the world default and only
     * feeds PolicyEngine / classifier context for apps no promise rule covers.
     */
    val confirmedStrictness: StrictnessLevel
        get() = promiseStrictness ?: strictness
}
