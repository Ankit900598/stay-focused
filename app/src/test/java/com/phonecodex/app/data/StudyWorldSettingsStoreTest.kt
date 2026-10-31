package com.phonecodex.app.data

import com.phonecodex.app.domain.model.StudyWorldSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyWorldSettingsStoreTest {

    @Test
    fun customThreshold_canBeResetToAutomatic() {
        val store = StudyWorldSettingsStore(InMemorySharedPreferences())

        store.setLockAttemptThreshold(5)
        assertEquals(5, store.getSettings().lockAttemptThreshold)
        assertTrue(store.getSettings().lockAttemptThresholdCustomized)

        store.setLockAttemptThreshold(null)
        assertEquals(StudyWorldSettingsStore.DEFAULT_LOCK_ATTEMPT_THRESHOLD, store.getSettings().lockAttemptThreshold)
        assertFalse(store.getSettings().lockAttemptThresholdCustomized)
    }

    @Test
    fun commitmentSettings_restoreTuningDefaults_butKeepPromiseRulesOutOfTheDefaultSnapshot() {
        val prefs = InMemorySharedPreferences()
        val store = StudyWorldSettingsStore(prefs)
        store.setDurationMinutes(45)
        store.setLockAttemptThreshold(5)
        store.setBlockAttemptCooldownMinutes(5)
        val active = store.getSettings().copy(
            durationMinutes = 15,
            lockAttemptThreshold = 3,
            lockAttemptThresholdCustomized = false,
            blockAttemptCooldownMinutes = 2,
            maxVideoLengthBlockMinutes = 30
        )

        assertTrue(store.beginCommitment(active))
        assertEquals(15, store.getSettings().durationMinutes)
        assertEquals(3, store.getSettings().lockAttemptThreshold)
        assertFalse(store.getSettings().lockAttemptThresholdCustomized)
        assertEquals(2, store.getSettings().blockAttemptCooldownMinutes)
        assertEquals(CommitmentTuningRestore.Outcome.RESTORED, store.restoreCommitmentTuningDefaults().outcome)

        val restored = store.getSettings()
        assertEquals(45, restored.durationMinutes)
        assertEquals(5, restored.lockAttemptThreshold)
        assertTrue(restored.lockAttemptThresholdCustomized)
        assertEquals(5, restored.blockAttemptCooldownMinutes)
        assertEquals(30, restored.maxVideoLengthBlockMinutes)
        assertFalse(prefs.contains("commitment_tuning_ledger_v1"))
    }

    @Test
    fun interruptedCommitment_restoresTuningOnlyAfterSessionIsGone() {
        val prefs = InMemorySharedPreferences()
        val store = StudyWorldSettingsStore(prefs)
        store.setDurationMinutes(60)
        assertTrue(store.beginCommitment(store.getSettings().copy(durationMinutes = 15)))

        assertEquals(
            CommitmentTuningRestore.Outcome.ACTIVE_SESSION,
            store.restoreStaleCommitmentTuningDefaults(hasActiveSession = true).outcome
        )
        assertEquals(15, store.getSettings().durationMinutes)
        assertEquals(
            CommitmentTuningRestore.Outcome.RESTORED,
            store.restoreStaleCommitmentTuningDefaults(hasActiveSession = false).outcome
        )
        assertEquals(60, store.getSettings().durationMinutes)
    }

    @Test
    fun unreadableLedger_isRetainedAndDoesNotGuessAtPriorValues() {
        val prefs = InMemorySharedPreferences()
        val store = StudyWorldSettingsStore(prefs)
        store.replaceSettings(
            StudyWorldSettings(
                durationMinutes = 15,
                lockAttemptThreshold = 3,
                blockAttemptCooldownMinutes = 2
            )
        )
        prefs.edit().putString("commitment_tuning_ledger_v1", "malformed").commit()

        val result = store.restoreCommitmentTuningDefaults()

        assertEquals(CommitmentTuningRestore.Outcome.UNREADABLE_LEDGER, result.outcome)
        assertEquals(15, store.getSettings().durationMinutes)
        assertTrue(prefs.contains("commitment_tuning_ledger_v1"))
    }

    @Test
    fun beginCommitment_refusesToReplaceAnExistingRecoveryLedger() {
        val prefs = InMemorySharedPreferences()
        val store = StudyWorldSettingsStore(prefs)
        assertTrue(store.beginCommitment(store.getSettings().copy(durationMinutes = 15)))
        val before = store.getSettings()

        assertFalse(store.beginCommitment(before.copy(durationMinutes = 60)))
        assertEquals(15, store.getSettings().durationMinutes)
    }
}
