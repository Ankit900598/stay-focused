package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.StudyWorldSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip contract: Advanced Controls values that the binder can read must win
 * over inferred strictness / parser defaults. Do not claim settings work without these.
 */
class ConfirmedPromiseBinderAdvancedControlsTest {

    private val parser = FocusPromiseParser()

    private fun defaults(
        durationMinutes: Int = 30,
        lockAttemptThreshold: Int = 10,
        lockAttemptThresholdCustomized: Boolean = false,
        blockAttemptCooldownMinutes: Int = 2
    ) = StudyWorldSettings(
        durationMinutes = durationMinutes,
        lockAttemptThreshold = lockAttemptThreshold,
        blockAttemptCooldownMinutes = blockAttemptCooldownMinutes,
        lockAttemptThresholdCustomized = lockAttemptThresholdCustomized
    )

    @Test
    fun customizedLockThreshold_beatsStrictnessDefault() {
        val draft = parser.parse("study quietly for focus")
        assertTrue(
            "fixture must not state an explicit lock count",
            draft.lockAttemptThreshold == null
        )
        val bound = ConfirmedPromiseBinder.bind(
            draft,
            defaults(lockAttemptThreshold = 5, lockAttemptThresholdCustomized = true)
        )
        assertEquals(5, bound.lockAttemptThreshold)
    }

    @Test
    fun uncustomizedLock_usesStrictnessMap_notSavedThreshold() {
        val draft = parser.parse("study quietly for focus")
        val expected = when (draft.strictness.name) {
            "LOCKED", "STRICT" -> 3
            else -> 10
        }
        val bound = ConfirmedPromiseBinder.bind(
            draft,
            defaults(lockAttemptThreshold = 5, lockAttemptThresholdCustomized = false)
        )
        assertEquals(expected, bound.lockAttemptThreshold)
    }

    @Test
    fun explicitPromiseLock_beatsCustomizedAdvanced() {
        val draft = parser.parse("study quietly for focus").copy(lockAttemptThreshold = 3)
        val bound = ConfirmedPromiseBinder.bind(
            draft,
            defaults(lockAttemptThreshold = 10, lockAttemptThresholdCustomized = true)
        )
        assertEquals(3, bound.lockAttemptThreshold)
    }

    @Test
    fun blockCooldown_alwaysFromAdvancedSavedDefaults() {
        val draft = parser.parse("study for 1 hour")
        val bound = ConfirmedPromiseBinder.bind(
            draft,
            defaults(blockAttemptCooldownMinutes = 5)
        )
        assertEquals(5, bound.blockAttemptCooldownMinutes)
    }

    @Test
    fun blockCooldown_defaultsToTwoWhenNoSavedDefaults() {
        val draft = parser.parse("study for 1 hour")
        val bound = ConfirmedPromiseBinder.bind(draft, savedDefaults = null)
        assertEquals(2, bound.blockAttemptCooldownMinutes)
    }

    @Test
    fun nonExplicitDuration_usesAdvancedDuration() {
        val draft = parser.parse("block youtube shorts")
        assertFalse(
            "fixture must leave duration inferred so Advanced can win",
            draft.sessionDurationWasExplicit
        )
        val bound = ConfirmedPromiseBinder.bind(
            draft,
            defaults(durationMinutes = 45)
        )
        assertEquals(45, bound.durationMinutes)
    }

    @Test
    fun explicitDuration_beatsAdvancedDuration() {
        val draft = parser.parse("study for 90 minutes no shorts")
        assertTrue(draft.sessionDurationWasExplicit)
        val bound = ConfirmedPromiseBinder.bind(
            draft,
            defaults(durationMinutes = 45)
        )
        assertEquals(90, bound.durationMinutes)
    }
}
