package com.phonecodex.app.ui.home

import com.phonecodex.app.data.DebugStateStore
import com.phonecodex.app.data.FeedbackStore
import com.phonecodex.app.data.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugSectionsPrivacyTest {

    @Test
    fun feedbackOmitsPrivateContextUnlessExplicitlyRequested() {
        val debugStore = DebugStateStore(InMemorySharedPreferences())
        debugStore.recordDecision(
            packageName = "com.whatsapp",
            screenText = "private-message-body",
            decision = "WARN",
            reason = "model explanation may echo content",
            source = "Backend AI",
            confidence = 0.7
        )
        val feedbackStore = FeedbackStore(InMemorySharedPreferences())

        saveFeedback(feedbackStore, debugStore.getDebugState(), "ALLOW")

        val privateByDefault = feedbackStore.getRecentFeedback().first()
        assertEquals("", privateByDefault.screenTextPreview)
        assertEquals("", privateByDefault.reason)

        saveFeedback(
            feedbackStore,
            debugStore.getDebugState(),
            "ALLOW",
            includePrivateContext = true
        )

        val explicitlyIncluded = feedbackStore.getRecentFeedback().first()
        assertEquals("private-message-body", explicitlyIncluded.screenTextPreview)
        assertEquals("model explanation may echo content", explicitlyIncluded.reason)
    }
}
