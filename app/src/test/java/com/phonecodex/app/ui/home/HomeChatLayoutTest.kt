package com.phonecodex.app.ui.home

import com.phonecodex.app.domain.protection.ProtectionReliabilityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeChatLayoutTest {

    @Test
    fun idleHome_usesChatLayout() {
        assertTrue(usesChatHome(hasStoredSession = false, showAdvancedControls = false))
    }

    @Test
    fun activeSession_usesSettingsList() {
        assertFalse(usesChatHome(hasStoredSession = true, showAdvancedControls = false))
    }

    @Test
    fun advancedOpen_usesSettingsList() {
        assertFalse(usesChatHome(hasStoredSession = false, showAdvancedControls = true))
    }

    @Test
    fun idleList_alwaysShowsProtectionStatusAfterHeader() {
        val items = buildHomeListItems(
            hasStoredSession = false,
            hasPromiseUnderstanding = false,
            showAdvancedControls = false
        )
        assertEquals(HomeListItem.Header, items[0])
        assertEquals(HomeListItem.ProtectionStatus, items[1])
        assertFalse(items.contains(HomeListItem.ProtectionSetup))
    }

    @Test
    fun advancedList_putsReliabilityInsideHealthCardNotTwice() {
        val items = buildHomeListItems(
            hasStoredSession = false,
            hasPromiseUnderstanding = false,
            showAdvancedControls = true
        )
        assertEquals(HomeListItem.Header, items[0])
        assertFalse(items.contains(HomeListItem.ProtectionStatus))
        assertTrue(items.contains(HomeListItem.AdvancedProtectionHealth))
    }

    @Test
    fun confirmationPreview_hidesComposerAndKeepsUnderstanding() {
        assertTrue(confirmationReviewHidesComposer(hasUnderstanding = true))
        assertFalse(confirmationReviewHidesComposer(hasUnderstanding = false))
        val items = buildHomeListItems(
            hasStoredSession = false,
            hasPromiseUnderstanding = true,
            showAdvancedControls = false
        )
        assertTrue(items.contains(HomeListItem.PromiseUnderstanding))
        assertFalse(items.contains(HomeListItem.PromiseComposer))
    }

    @Test
    fun sessionHome_showsLastDecisionAfterActiveCommitment() {
        val items = buildHomeListItems(
            hasStoredSession = true,
            hasPromiseUnderstanding = false,
            showAdvancedControls = false
        )
        val active = items.indexOf(HomeListItem.ActiveCommitment)
        val last = items.indexOf(HomeListItem.LastDecision)
        assertTrue(active >= 0)
        assertEquals(active + 1, last)
    }

    @Test
    fun advancedWithoutSession_showsLastDecision() {
        val items = buildHomeListItems(
            hasStoredSession = false,
            hasPromiseUnderstanding = false,
            showAdvancedControls = true
        )
        assertTrue(items.contains(HomeListItem.LastDecision))
        assertTrue(items.indexOf(HomeListItem.LastDecision) > items.indexOf(HomeListItem.AdvancedIntro))
    }

    @Test
    fun sessionPlusAdvanced_showsLastDecisionOnce() {
        val items = buildHomeListItems(
            hasStoredSession = true,
            hasPromiseUnderstanding = false,
            showAdvancedControls = true
        )
        assertEquals(1, items.count { it == HomeListItem.LastDecision })
    }

    @Test
    fun idleHomeList_hidesLastDecision() {
        val items = buildHomeListItems(
            hasStoredSession = false,
            hasPromiseUnderstanding = false,
            showAdvancedControls = false
        )
        assertFalse(items.contains(HomeListItem.LastDecision))
    }

    @Test
    fun confirmationPreview_compactsOrHidesProtectionCard() {
        assertEquals(
            ConfirmationProtectionMode.HIDDEN,
            confirmationReviewProtectionMode(
                hasUnderstanding = true,
                protectionLevel = ProtectionReliabilityLevel.PROTECTED
            )
        )
        assertEquals(
            ConfirmationProtectionMode.COMPACT,
            confirmationReviewProtectionMode(
                hasUnderstanding = true,
                protectionLevel = ProtectionReliabilityLevel.NEEDS_SETUP
            )
        )
        assertEquals(
            ConfirmationProtectionMode.FULL,
            confirmationReviewProtectionMode(
                hasUnderstanding = false,
                protectionLevel = ProtectionReliabilityLevel.PROTECTED
            )
        )
    }
}
