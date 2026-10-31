package com.phonecodex.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DebugStateStoreTest {

    @Test
    fun packageDetectionDoesNotReattributeLastDecision() {
        val store = DebugStateStore(InMemorySharedPreferences())
        store.recordDecision(
            packageName = "com.android.chrome",
            screenText = "Lecture player",
            decision = "ALLOW",
            reason = "Video length is inside the promise",
            source = "Duration Rule",
            confidence = 1.0
        )
        val originalDecision = store.getDebugState()

        store.recordPackageDetected("com.whatsapp")

        val afterDetection = store.getDebugState()
        assertEquals(originalDecision.lastPackageName, afterDetection.lastPackageName)
        assertEquals(originalDecision.lastScreenTextPreview, afterDetection.lastScreenTextPreview)
        assertEquals(originalDecision.lastDecision, afterDetection.lastDecision)
        assertEquals(originalDecision.lastReason, afterDetection.lastReason)
        assertEquals(originalDecision.lastUpdatedMillis, afterDetection.lastUpdatedMillis)
        assertEquals("com.whatsapp", afterDetection.lastDetectedPackageName)
        assertNull(afterDetection.lastDetectedScreenTextPreview)
        assertEquals("com.android.chrome", originalDecision.lastDetectedPackageName)
    }

    @Test
    fun packageDetectionClearsPreviouslyPersistedScreenTextPreview() {
        val preferences = InMemorySharedPreferences().apply {
            edit()
                .putString("lastDetectedScreenTextPreview", "Private message content")
                .apply()
        }

        val store = DebugStateStore(preferences)
        store.recordPackageDetected("com.whatsapp")

        assertNull(store.getDebugState().lastDetectedScreenTextPreview)
        assertFalse(preferences.contains("lastDetectedScreenTextPreview"))
    }

    @Test
    fun unversionedDecisionContextIsClearedBecauseAttributionCannotBeTrusted() {
        val preferences = InMemorySharedPreferences().apply {
            edit()
                .putString("lastPackageName", "com.whatsapp")
                .putString("lastDecision", "ALLOW")
                .putString("lastReason", "Old reason")
                .putString("lastSessionId", "old-session")
                .putLong("lastUpdatedMillis", 1_800_000_000_000L)
                .apply()
        }

        val state = DebugStateStore(preferences).getDebugState()

        assertNull(state.lastPackageName)
        assertNull(state.lastDecision)
        assertNull(state.lastReason)
        assertEquals(0L, state.lastUpdatedMillis)
        assertNull(state.lastDetectedPackageName)
        assertNull(state.lastSessionId)
    }

    @Test
    fun versionOneDecisionContextIsClearedWhenSessionAttributionIsIntroduced() {
        val preferences = InMemorySharedPreferences().apply {
            edit()
                .putInt("decisionContextVersion", 1)
                .putString("lastPackageName", "com.android.chrome")
                .putString("lastDecision", "ALLOW")
                .putString("lastSessionId", "old-session")
                .putLong("lastUpdatedMillis", 1_800_000_000_000L)
                .apply()
        }

        val state = DebugStateStore(preferences).getDebugState()

        assertNull(state.lastPackageName)
        assertNull(state.lastDecision)
        assertNull(state.lastSessionId)
        assertEquals(2, preferences.getInt("decisionContextVersion", 0))
    }

    @Test
    fun decisionPersistsSessionIdentityWithoutPersistingPromiseText() {
        val preferences = InMemorySharedPreferences()
        val store = DebugStateStore(preferences)

        store.recordDecision(
            packageName = "com.android.chrome",
            screenText = "Lecture player",
            decision = "ALLOW",
            reason = "Allowed by promise",
            source = "Policy",
            confidence = 1.0,
            sessionId = "session-123"
        )

        assertEquals("session-123", store.getDebugState().lastSessionId)
        assertFalse(preferences.contains("lastPromiseText"))
    }

    @Test
    fun decisionTextAndFreeformReasonAreOnlyKeptInProcessMemory() {
        val preferences = InMemorySharedPreferences().apply {
            edit()
                .putInt("decisionContextVersion", 2)
                .putString("lastScreenTextPreview", "Old private message")
                .putString("lastDetectedScreenTextPreview", "Old private message")
                .putString("lastReason", "Old model explanation")
                .apply()
        }
        val store = DebugStateStore(preferences)

        store.recordDecision(
            packageName = "com.whatsapp",
            screenText = "New private message body",
            decision = "WARN",
            reason = "The model explanation can contain screen-derived text",
            source = "Backend AI",
            confidence = 0.7
        )

        val currentState = store.getDebugState()
        assertEquals("New private message body", currentState.lastScreenTextPreview)
        assertEquals(
            "The model explanation can contain screen-derived text",
            currentState.lastReason
        )
        assertFalse(preferences.contains("lastScreenTextPreview"))
        assertFalse(preferences.contains("lastDetectedScreenTextPreview"))
        assertFalse(preferences.contains("lastReason"))

        val afterProcessRestart = DebugStateStore(InMemorySharedPreferences())
            .getDebugState()
        assertNull(afterProcessRestart.lastScreenTextPreview)
        assertNull(afterProcessRestart.lastReason)
    }

    @Test
    fun privateDecisionContextExpiresAfterTenMinutes() {
        val preferences = InMemorySharedPreferences()
        DecisionPrivateContextCache.record(
            prefs = preferences,
            screenTextPreview = "Private screen text",
            reason = "Private model explanation",
            nowMillis = 1_000L
        )

        val expired = DecisionPrivateContextCache.get(
            prefs = preferences,
            nowMillis = 1_000L + DecisionPrivateContextCache.PRIVATE_CONTEXT_RETENTION_MILLIS + 1L
        )

        assertNull(expired)
    }
}
