package com.phonecodex.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EventLogStorePrivacyTest {

    @Test
    fun existingFreeformBlockExplanationsAreRedactedFromPersistentHistory() {
        val privateText = "private-message-body"
        val preferences = InMemorySharedPreferences().apply {
            edit()
                .putString(
                    "events",
                    "1000|Blocked com.whatsapp because $privateText\n" +
                        "1001|Study World locked because protection was disabled"
                )
                .apply()
        }

        val store = EventLogStore(preferences)
        val events = store.getRecentEvents()

        assertEquals("1000: Blocked com.whatsapp after policy evaluation", events[0])
        assertEquals("1001: Study World locked because protection was disabled", events[1])
        assertFalse(preferences.getString("events", "").orEmpty().contains(privateText))
    }
}
