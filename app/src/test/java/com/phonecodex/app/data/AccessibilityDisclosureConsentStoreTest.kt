package com.phonecodex.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityDisclosureConsentStoreTest {

    @Test
    fun noRecordDoesNotGrantConsent() {
        val store = AccessibilityDisclosureConsentStore(InMemorySharedPreferences())

        assertFalse(store.hasAcceptedCurrentDisclosure())
    }

    @Test
    fun acceptedVersionPersistsAcrossStoreInstances() {
        val prefs = InMemorySharedPreferences()
        val firstStore = AccessibilityDisclosureConsentStore(prefs)
        val secondStore = AccessibilityDisclosureConsentStore(prefs)

        assertTrue(firstStore.acceptCurrentDisclosure())

        assertTrue(secondStore.hasAcceptedCurrentDisclosure())
    }

    @Test
    fun olderDisclosureVersionDoesNotGrantCurrentConsent() {
        val prefs = InMemorySharedPreferences().apply {
            edit().putInt(
                AccessibilityDisclosureConsentStore.KEY_ACCEPTED_VERSION,
                AccessibilityDisclosureConsentStore.CURRENT_DISCLOSURE_VERSION - 1
            ).commit()
        }

        assertFalse(AccessibilityDisclosureConsentStore(prefs).hasAcceptedCurrentDisclosure())
    }

    @Test
    fun wrongStoredTypeDoesNotGrantConsent() {
        val prefs = InMemorySharedPreferences().apply {
            edit().putString(AccessibilityDisclosureConsentStore.KEY_ACCEPTED_VERSION, "1").commit()
        }

        assertFalse(AccessibilityDisclosureConsentStore(prefs).hasAcceptedCurrentDisclosure())
    }

    @Test
    fun failedWriteDoesNotClaimConsent() {
        val prefs = InMemorySharedPreferences().apply { commitSucceeds = false }
        val store = AccessibilityDisclosureConsentStore(prefs)

        assertFalse(store.acceptCurrentDisclosure())
        assertFalse(store.hasAcceptedCurrentDisclosure())
    }
}
