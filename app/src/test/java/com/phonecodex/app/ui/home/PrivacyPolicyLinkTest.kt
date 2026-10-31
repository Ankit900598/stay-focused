package com.phonecodex.app.ui.home

import android.content.Intent
import com.phonecodex.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyLinkTest {

    @Test
    fun buildConfigMatchesPlaceholderConstant() {
        assertEquals(PrivacyPolicyLink.PLACEHOLDER_URL, BuildConfig.PRIVACY_POLICY_URL)
        assertTrue(BuildConfig.PRIVACY_POLICY_URL.startsWith("https://"))
    }

    @Test
    fun browserTarget_usesConfiguredStringForIntent() {
        val url = PrivacyPolicyLink.configuredUrl()
        val target = PrivacyPolicyLink.browserTarget(url)
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals(url, target.url)
        assertEquals("https://strlix.tech/privacy", target.url)
    }

    @Test
    fun advancedList_includesPrivacyPolicyRow() {
        val items = buildHomeListItems(
            hasStoredSession = false,
            hasPromiseUnderstanding = false,
            showAdvancedControls = true
        )
        assertTrue(HomeListItem.AdvancedPrivacyPolicy in items)
        assertTrue(
            items.indexOf(HomeListItem.AdvancedPrivacyPolicy) >
                items.indexOf(HomeListItem.AdvancedIntro)
        )
    }
}
