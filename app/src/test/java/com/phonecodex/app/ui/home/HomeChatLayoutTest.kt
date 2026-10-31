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
    fun permanentSafetyRuleCount_isNotPresentedAsATimedCommitment() {
        assertEquals("1 permanent safety rule", permanentSafetyRuleCountLabel(1))
        assertEquals("2 permanent safety rules", permanentSafetyRuleCountLabel(2))
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
        assertEquals(HomeListItem.TodayInsight, items[2])
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
        assertFalse(items.contains(HomeListItem.TodayInsight))
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
    fun sessionDurationChoices_includeAnExistingCustomValue() {
        assertEquals(listOf(5, 15, 30, 60), sessionDurationChoices(5))
        assertEquals(listOf(15, 30, 60), sessionDurationChoices(30))
    }

    @Test
    fun chatHome_compactsProtectionAndHidesItForProtectedConfirmation() {
        assertEquals(
            ChatHomeProtectionMode.HIDDEN,
            chatHomeProtectionMode(
                hasUnderstanding = true,
                protectionLevel = ProtectionReliabilityLevel.PROTECTED
            )
        )
        assertEquals(
            ChatHomeProtectionMode.COMPACT,
            chatHomeProtectionMode(
                hasUnderstanding = true,
                protectionLevel = ProtectionReliabilityLevel.NEEDS_SETUP
            )
        )
        assertEquals(
            ChatHomeProtectionMode.COMPACT,
            chatHomeProtectionMode(
                hasUnderstanding = false,
                protectionLevel = ProtectionReliabilityLevel.NEEDS_SETUP
            )
        )
        assertEquals(
            ChatHomeProtectionMode.COMPACT,
            chatHomeProtectionMode(
                hasUnderstanding = false,
                protectionLevel = ProtectionReliabilityLevel.PROTECTED
            )
        )
    }
}
