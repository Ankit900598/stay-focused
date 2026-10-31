package com.phonecodex.app.domain.safeapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeAppsCatalogTest {

    @Test
    fun defaultPackages_includeEssentialApps() {
        assertTrue(SafeAppsCatalog.isDefaultPackage("com.phonecodex.app"))
        assertTrue(SafeAppsCatalog.isDefaultPackage("com.google.android.dialer"))
        assertTrue(SafeAppsCatalog.isDefaultPackage("com.whatsapp"))
        assertTrue(SafeAppsCatalog.isDefaultPackage("com.android.contacts"))
        assertTrue(SafeAppsCatalog.isDefaultPackage("com.openai.chatgpt"))
    }

    @Test
    fun explicitBlockOverride_onlyAppliesToConvenienceSafeDefaults() {
        assertTrue(SafeAppsCatalog.isNonOverridableDefaultPackage("com.phonecodex.app"))
        assertTrue(SafeAppsCatalog.isNonOverridableDefaultPackage("com.google.android.dialer"))
        assertFalse(SafeAppsCatalog.canExplicitBlockOverrideSafeDefault("com.google.android.dialer"))

        assertFalse(SafeAppsCatalog.isNonOverridableDefaultPackage("com.whatsapp"))
        assertTrue(SafeAppsCatalog.canExplicitBlockOverrideSafeDefault("com.whatsapp"))
        assertTrue(SafeAppsCatalog.canExplicitBlockOverrideSafeDefault("com.google.android.gm"))
        assertTrue(SafeAppsCatalog.canExplicitBlockOverrideSafeDefault("com.openai.chatgpt"))
    }

    @Test
    fun androidAndMiuiSettingsControls_areNonOverridableSafePackages() {
        val settingsPackages = listOf(
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.xiaomi.misettings",
            "com.miui.securitycenter",
            "com.miui.permcenter"
        )
        for (packageName in settingsPackages) {
            assertTrue("$packageName should remain available", SafeAppsCatalog.isDefaultPackage(packageName))
            assertTrue("$packageName should ignore whole-app BLOCK", SafeAppsCatalog.isNonOverridableDefaultPackage(packageName))
        }
    }

    @Test
    fun defaultPackages_rejectUnknownPackage() {
        assertFalse(SafeAppsCatalog.isDefaultPackage("com.instagram.android"))
    }

    @Test
    fun defaultLabel_returnsReadableName() {
        assertEquals("Strlix", SafeAppsCatalog.defaultLabel("com.phonecodex.app"))
        assertEquals("WhatsApp", SafeAppsCatalog.defaultLabel("com.whatsapp"))
    }

    @Test
    fun defaultLabel_fallsBackToPackageName() {
        assertEquals("com.example.app", SafeAppsCatalog.defaultLabel("com.example.app"))
    }
}
