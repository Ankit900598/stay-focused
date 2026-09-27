package com.phonecodex.app.domain.session

import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone P0 2026-09-25: Strict and Locked commitments ended with one casual tap.
 */
class CommitmentExitGateTest {

    private val now = 1_000_000L

    private fun session(
        promise: StrictnessLevel?,
        status: SessionStatus = SessionStatus.ACTIVE,
        deadline: Long = now + 30 * 60_000L,
        attempts: Int = 0
    ) = FocusSession(
        id = "s",
        worldId = "study",
        goal = "do not allow videos shorter than 30 minutes for next 1 hour",
        startTimeMillis = now - 60_000L,
        deadlineMillis = deadline,
        status = status,
        attemptCount = attempts,
        strictness = StrictnessLevel.STRICT,
        promiseStrictness = promise
    )

    @Test
    fun strictSession_oneTapDoesNotEnd() {
        val friction = CommitmentExitGate.frictionFor(session(StrictnessLevel.STRICT), now)
        assertEquals(ExitFriction.CONFIRM, friction)
        assertFalse(CommitmentExitGate.mayEnd(friction, confirmed = false, typedPhrase = null))
        assertTrue(CommitmentExitGate.mayEnd(friction, confirmed = true, typedPhrase = null))
    }

    @Test
    fun lockedStatusAfterThreeAttempts_needsTypedPhrase_evenForStrictPromise() {
        val locked = session(StrictnessLevel.STRICT, status = SessionStatus.LOCKED, attempts = 3)
        val friction = CommitmentExitGate.frictionFor(locked, now)
        assertEquals(ExitFriction.TYPED_PHRASE, friction)
        assertFalse(CommitmentExitGate.mayEnd(friction, confirmed = true, typedPhrase = null))
        assertFalse(CommitmentExitGate.mayEnd(friction, confirmed = true, typedPhrase = "ok"))
        assertFalse(
            CommitmentExitGate.mayEnd(
                friction,
                confirmed = false,
                typedPhrase = CommitmentExitGate.LOCKED_EXIT_PHRASE
            )
        )
        assertTrue(
            CommitmentExitGate.mayEnd(
                friction,
                confirmed = true,
                typedPhrase = "  i am breaking   my promise "
            )
        )
    }

    @Test
    fun lockedPromise_needsTypedPhrase_whileStillActive() {
        assertEquals(
            ExitFriction.TYPED_PHRASE,
            CommitmentExitGate.frictionFor(session(StrictnessLevel.LOCKED), now)
        )
    }

    @Test
    fun legacySessionWithoutPromiseStrictness_fallsBackToSessionStrict() {
        assertEquals(ExitFriction.CONFIRM, CommitmentExitGate.frictionFor(session(null), now))
    }

    @Test
    fun softAndSmartPromises_endWithOneTap() {
        assertEquals(ExitFriction.NONE, CommitmentExitGate.frictionFor(session(StrictnessLevel.SOFT), now))
        assertEquals(ExitFriction.NONE, CommitmentExitGate.frictionFor(session(StrictnessLevel.SMART), now))
    }

    @Test
    fun timeAlreadyUp_endsWithoutFriction_evenWhenLocked() {
        val expired = session(StrictnessLevel.LOCKED, status = SessionStatus.LOCKED, deadline = now - 1)
        assertEquals(ExitFriction.NONE, CommitmentExitGate.frictionFor(expired, now))
    }
}
