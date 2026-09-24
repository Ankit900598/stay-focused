package com.phonecodex.app.domain.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostResolverTest {

    @Test
    fun browserPackagesResolveToBrowser() {
        val browsers = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.microsoft.emmx",
            "com.duckduckgo.mobile.android",
            "com.opera.browser",
            "com.vivaldi.browser"
        )
        for (pkg in browsers) {
            assertEquals("expected $pkg to be BROWSER", ScopeHost.BROWSER, HostResolver.host(pkg))
            assertTrue(pkg, HostResolver.isBrowser(pkg))
        }
    }

    @Test
    fun videoNativeAppsResolveToNativeApp() {
        val natives = listOf(
            "com.google.android.youtube",
            "com.vanced.android.youtube",
            "org.schabi.newpipe",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.snapchat.android",
            "com.whatsapp",
            "com.facebook.katana",
            "com.facebook.orca",
            "app.netmirror.newtv"
        )
        for (pkg in natives) {
            assertEquals("expected $pkg to be NATIVE_APP", ScopeHost.NATIVE_APP, HostResolver.host(pkg))
            assertFalse(pkg, HostResolver.isBrowser(pkg))
        }
    }

    @Test
    fun unknownAppDefaultsToNativeApp() {
        assertEquals(ScopeHost.NATIVE_APP, HostResolver.host("com.random.unknown"))
        assertEquals(ScopeHost.NATIVE_APP, HostResolver.host(""))
    }
}
