package com.phonecodex.app.ui.home

import com.phonecodex.app.data.DebugStateStore
import com.phonecodex.app.data.InMemorySharedPreferences
import com.phonecodex.app.domain.enforcement.CommitmentTransparency
import com.phonecodex.app.domain.enforcement.DecisionPromiseContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastDecisionSnapshotMapperTest {

    @Test
    fun decisionFromOldSessionDoesNotBorrowNewSessionGoal() {
        val state = recordedDecision(sessionId = "old-session")

        val snapshot = state.toLastDecisionSnapshot(
            currentSessionId = "new-session",
            currentSessionGoal = "Allow email during work",
            nowMillis = System.currentTimeMillis() + 1_000L
        )
        val explained = CommitmentTransparency.explain(snapshot)

        assertEquals(DecisionPromiseContext.DIFFERENT_SESSION, snapshot.promiseContextStatus)
        assertEquals(null, snapshot.promiseText)
        assertTrue(explained.userPromise.contains("different or ended commitment"))
        assertFalse(explained.userPromise.contains("Allow email during work"))
        assertTrue(explained.userNext.contains("different or ended commitment"))
    }

    @Test
    fun decisionFromCurrentSessionUsesThatSessionsGoal() {
        val state = recordedDecision(sessionId = "session-123")

        val snapshot = state.toLastDecisionSnapshot(
            currentSessionId = "session-123",
            currentSessionGoal = "Study calculus for one hour",
            nowMillis = System.currentTimeMillis() + 1_000L
        )
        val explained = CommitmentTransparency.explain(snapshot)

        assertEquals(DecisionPromiseContext.CURRENT_SESSION, snapshot.promiseContextStatus)
        assertEquals("Study calculus for one hour", explained.userPromise)
    }

    private fun recordedDecision(sessionId: String) = DebugStateStore(
        InMemorySharedPreferences()
    ).also { store ->
        store.recordDecision(
            packageName = "com.android.chrome",
            screenText = "Lecture player",
            decision = "ALLOW",
            reason = "Allowed by promise",
            source = "Policy",
            confidence = 1.0,
            sessionId = sessionId
        )
    }.getDebugState()
}
