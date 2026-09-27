package com.phonecodex.app.domain.session

import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel

/** How much deliberate effort ending a running commitment early takes. */
enum class ExitFriction {
    /** Soft/Smart promise, or time is already up. */
    NONE,
    /** Strict: explicit confirm step. */
    CONFIRM,
    /** Locked promise or a session that got locked: type [CommitmentExitGate.LOCKED_EXIT_PHRASE]. */
    TYPED_PHRASE
}

/**
 * Pure law for ending a commitment early. Emergency and safety apps stay allowed
 * by PolicyEngine during a session; this gate only governs tearing the session down.
 */
object CommitmentExitGate {

    const val LOCKED_EXIT_PHRASE = "I am breaking my promise"

    fun frictionFor(session: FocusSession, nowMillis: Long): ExitFriction {
        if (nowMillis >= session.deadlineMillis) return ExitFriction.NONE
        val strictness = session.confirmedStrictness
        if (session.status == SessionStatus.LOCKED || strictness == StrictnessLevel.LOCKED) {
            return ExitFriction.TYPED_PHRASE
        }
        return when (strictness) {
            StrictnessLevel.STRICT -> ExitFriction.CONFIRM
            StrictnessLevel.SOFT, StrictnessLevel.SMART -> ExitFriction.NONE
            StrictnessLevel.LOCKED -> ExitFriction.TYPED_PHRASE
        }
    }

    /** [confirmed] = user pressed the confirm button; [typedPhrase] only matters for TYPED_PHRASE. */
    fun mayEnd(friction: ExitFriction, confirmed: Boolean, typedPhrase: String?): Boolean =
        when (friction) {
            ExitFriction.NONE -> true
            ExitFriction.CONFIRM -> confirmed
            ExitFriction.TYPED_PHRASE -> confirmed && phraseMatches(typedPhrase)
        }

    fun phraseMatches(typedPhrase: String?): Boolean =
        typedPhrase?.trim()?.replace(Regex("\\s+"), " ")
            ?.equals(LOCKED_EXIT_PHRASE, ignoreCase = true) == true
}
