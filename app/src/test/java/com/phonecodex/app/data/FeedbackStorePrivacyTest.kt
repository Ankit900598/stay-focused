package com.phonecodex.app.data

import com.phonecodex.app.domain.model.FeedbackEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackStorePrivacyTest {

    @Test
    fun existingFeedbackIsMigratedWithoutScreenTextOrFreeformReason() {
        val privateText = "private-message-body"
        val preferences = InMemorySharedPreferences().apply {
            edit()
                .putString(
                    "entries",
                    listOf(
                        listOf(
                            "1000",
                            "com.whatsapp",
                            privateText,
                            "WARN",
                            "ALLOW",
                            "model explanation may echo content"
                        ).joinToString("\u001E"),
                        listOf("1001", "com.instagram.android", "private-caption", "BLOCK", "ALLOW", "")
                            .joinToString("\u001E")
                    ).joinToString("\n")
                )
                .apply()
        }

        val store = FeedbackStore(preferences)
        val saved = store.getRecentFeedback()

        assertEquals(2, saved.size)
        assertEquals("", saved[0].screenTextPreview)
        assertEquals("", saved[0].reason)
        assertEquals("", saved[1].screenTextPreview)
        assertEquals("", saved[1].reason)
        assertFalse(preferences.getString("entries", "").orEmpty().contains(privateText))
        assertEquals(1, preferences.getInt("privacyVersion", 0))
    }

    @Test
    fun newFeedbackStoreCanRetainContextOnlyWhenCallerExplicitlySuppliesIt() {
        val preferences = InMemorySharedPreferences()
        val store = FeedbackStore(preferences)
        val entry = FeedbackEntry(
            timestampMillis = 2000,
            packageName = "com.whatsapp",
            screenTextPreview = "private-message-body",
            originalDecision = "WARN",
            correctedDecision = "ALLOW",
            reason = "model explanation may echo content"
        )

        store.addFeedback(entry.copy(screenTextPreview = "", reason = ""))
        assertEquals("", store.getRecentFeedback().single().screenTextPreview)
        assertFalse(preferences.getString("entries", "").orEmpty().contains("private-message-body"))

        store.addFeedback(entry)
        assertTrue(store.getRecentFeedback().first().screenTextPreview.contains("private-message-body"))
    }

    @Test
    fun clearingFeedbackRemovesAllStoredTextAndLabels() {
        val preferences = InMemorySharedPreferences()
        val store = FeedbackStore(preferences)
        store.addFeedback(
            FeedbackEntry(
                timestampMillis = 3000,
                packageName = "com.whatsapp",
                screenTextPreview = "private-message-body",
                originalDecision = "WARN",
                correctedDecision = "ALLOW",
                reason = "private model explanation"
            )
        )

        store.clearFeedback()

        assertTrue(store.getRecentFeedback().isEmpty())
        assertFalse(preferences.contains("entries"))
    }
}
