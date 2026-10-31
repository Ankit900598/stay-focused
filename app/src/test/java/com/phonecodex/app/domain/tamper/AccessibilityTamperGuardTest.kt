package com.phonecodex.app.domain.tamper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTamperGuardTest {

    private val guard = AccessibilityTamperGuard()

    @Test
    fun evaluatePhoneCodexAccessibilityControls_ignoresNonSettingsPackage() {
        assertNull(
            guard.evaluatePhoneCodexAccessibilityControls(
                packageName = "com.android.chrome",
                screenText = "PhoneCodex accessibility turn off"
            )
        )
    }

    @Test
    fun evaluatePhoneCodexAccessibilityControls_ignoresGeneralSettings() {
        assertNull(
            guard.evaluatePhoneCodexAccessibilityControls(
                packageName = AccessibilityTamperGuard.SETTINGS_PACKAGE,
                screenText = "Wi-Fi Bluetooth Display Battery Accessibility"
            )
        )
    }

    @Test
    fun evaluatePhoneCodexAccessibilityControls_ignoresAccessibilityWithoutPhoneCodex() {
        assertNull(
            guard.evaluatePhoneCodexAccessibilityControls(
                packageName = AccessibilityTamperGuard.SETTINGS_PACKAGE,
                screenText = "Accessibility downloaded apps use service turn off"
            )
        )
    }

    @Test
    fun evaluatePhoneCodexAccessibilityControls_matchesPhoneCodexSignals() {
        val match = guard.evaluatePhoneCodexAccessibilityControls(
            packageName = AccessibilityTamperGuard.SETTINGS_PACKAGE,
            screenText = "Downloaded apps PhoneCodex Use PhoneCodex"
        )

        assertNotNull(match)
        assertTrue(match!!.matchedSignals.contains("phonecodex"))
        assertTrue(match.matchedSignals.contains("use phonecodex"))
        assertEquals(
            TamperGuardMatch.USER_CONTROL_REASON,
            match.reason
        )
    }

    @Test
    fun evaluatePhoneCodexAccessibilityControls_isCaseInsensitive() {
        val match = guard.evaluatePhoneCodexAccessibilityControls(
            packageName = AccessibilityTamperGuard.SETTINGS_PACKAGE,
            screenText = "PHONE CODEX"
        )

        assertNotNull(match)
        assertTrue(match!!.matchedSignals.contains("phone codex"))
    }

    @Test
    fun evaluatePhoneCodexAccessibilityControls_matchesMiuiSettingsPackage() {
        val match = guard.evaluatePhoneCodexAccessibilityControls(
            packageName = "com.xiaomi.misettings",
            screenText = "PhoneCodex accessibility turn off"
        )

        assertNotNull(match)
        assertTrue(match!!.matchedSignals.contains("phonecodex"))
    }

    @Test
    fun evaluatePhoneCodexAccessibilityControls_matchesStrlixLabel() {
        val match = guard.evaluatePhoneCodexAccessibilityControls(
            packageName = AccessibilityTamperGuard.SETTINGS_PACKAGE,
            screenText = "Downloaded apps Strlix Use Strlix"
        )
        assertNotNull(match)
        assertTrue(match!!.matchedSignals.contains("strlix"))
        assertTrue(match.matchedSignals.contains("use strlix"))
        assertEquals(
            TamperGuardMatch.USER_CONTROL_REASON,
            match.reason
        )
    }
}
