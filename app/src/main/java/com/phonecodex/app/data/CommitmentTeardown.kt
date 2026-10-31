package com.phonecodex.app.data

import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.session.SessionExpiryPolicy

/**
 * The only way a running commitment ends: early end, emergency exit and expiry all clear
 * the stored session and put back the rules this commitment changed, exactly once.
 * Permanent guardrails are never touched here; they outlive every session.
 */
class CommitmentTeardown(
    private val sessionStore: SessionStore,
    private val appRulesStore: AppRulesStore,
    private val addEvent: (String) -> Unit
) {

    private var studyWorldSettingsStore: StudyWorldSettingsStore? = null

    constructor(
        sessionStore: SessionStore,
        appRulesStore: AppRulesStore,
        addEvent: (String) -> Unit,
        studyWorldSettingsStore: StudyWorldSettingsStore
    ) : this(sessionStore, appRulesStore, addEvent) {
        this.studyWorldSettingsStore = studyWorldSettingsStore
    }

    enum class Kind { EARLY_END, EMERGENCY_EXIT, EXPIRED }

    enum class Outcome { ENDED, ALREADY_ENDED }

    data class Result(
        val outcome: Outcome,
        val ended: FocusSession?,
        val rules: CommitmentRuleRestore.Outcome
    )

    /**
     * [expectedSessionId] is the session the caller saw. A stale tap or a late expiry check
     * for a session that is already gone (or replaced by a new one) changes nothing.
     */
    fun end(kind: Kind, expectedSessionId: String?, detail: String = ""): Result = synchronized(LOCK) {
        val stored = sessionStore.getStoredSession()
        if (stored == null || (expectedSessionId != null && stored.id != expectedSessionId)) {
            val rules = if (stored == null) {
                appRulesStore.restoreStaleCommitmentRules(hasActiveSession = false)
                    .also { restore -> restore.eventMessage?.let(addEvent) }
                    .outcome
            } else {
                CommitmentRuleRestore.Outcome.SESSION_ACTIVE
            }
            if (stored == null) {
                studyWorldSettingsStore
                    ?.restoreStaleCommitmentTuningDefaults(hasActiveSession = false)
                    ?.eventMessage
                    ?.let(addEvent)
            }
            addEvent(line(kind, Outcome.ALREADY_ENDED, expectedSessionId, null, rules, detail))
            return Result(Outcome.ALREADY_ENDED, null, rules)
        }
        sessionStore.clearSession()
        val restore = appRulesStore.restoreCommitmentRules()
        restore.eventMessage?.let(addEvent)
        studyWorldSettingsStore
            ?.restoreCommitmentTuningDefaults()
            ?.eventMessage
            ?.let(addEvent)
        if (kind == Kind.EXPIRED) addEvent(SessionExpiryPolicy.EXPIRED_EVENT_MESSAGE)
        addEvent(line(kind, Outcome.ENDED, stored.id, stored, restore.outcome, detail))
        Result(Outcome.ENDED, stored, restore.outcome)
    }

    companion object {
        private val LOCK = Any()

        const val EVENT_PREFIX = "COMMITMENT_END"

        fun line(
            kind: Kind,
            outcome: Outcome,
            sessionId: String?,
            session: FocusSession?,
            rules: CommitmentRuleRestore.Outcome,
            detail: String
        ): String = buildString {
            append("$EVENT_PREFIX kind=${kind.name} outcome=${outcome.name} session=${sessionId ?: "none"}")
            session?.let { append(" status=${it.status.name} strictness=${it.confirmedStrictness.name}") }
            append(" rules=${rules.name} guardrails=kept")
            if (detail.isNotBlank()) append(" $detail")
        }
    }
}
