package com.phonecodex.app.domain.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A F9 / F10: `host` and `site` become load-bearing once scope is the
 * live authority. Prove that:
 *
 *   - a package whose id merely CONTAINS a browser substring (e.g. "brave") is
 *     not classified as [ScopeHost.BROWSER] unless it actually is a browser.
 *     Today HostResolver uses substring hints; the test locks in the hint list
 *     so a "brave-lookalike" without those tokens stays NATIVE_APP.
 *   - [SiteResolver.hostMatchesAny] ignores dotted tokens that are not real
 *     hosts (version numbers, tokenised filenames, decimal counters).
 */
class HostSiteResolverNegativeTest {

    // ---- HostResolver -----------------------------------------------------

    @Test
    fun packageWithoutBrowserHint_isNativeApp_notBrowser() {
        // No "browser" / "chrome" / "firefox" / etc. substring anywhere.
        val fakeBraveLookalike = "com.example.hero.launcher"
        assertEquals(ScopeHost.NATIVE_APP, HostResolver.host(fakeBraveLookalike))
        assertFalse(HostResolver.isBrowser(fakeBraveLookalike))
    }

    @Test
    fun knownBrowserPackage_isBrowser() {
        assertTrue(HostResolver.isBrowser("com.android.chrome"))
        assertTrue(HostResolver.isBrowser("org.mozilla.firefox"))
        assertTrue(HostResolver.isBrowser("com.brave.browser"))
    }

    @Test
    fun blankPackage_isNativeAppNotBrowser() {
        assertEquals(ScopeHost.NATIVE_APP, HostResolver.host(""))
        assertFalse(HostResolver.isBrowser(""))
    }

    // ---- SiteResolver -----------------------------------------------------

    @Test
    fun siteResolver_ignoresNonHostDottedTokens() {
        // Version-number-shaped tokens (all digits with dots) and single-letter
        // TLD junk must not be treated as hosts. The two-letter-min TLD constraint
        // in HOST_TOKEN is the guard.
        val text = "Build 12.4.5 downloaded  1.0.0 released  Version 2.3"
        val patterns = listOf("youtube.com", "instagram.com")
        assertFalse(SiteResolver.hostMatchesAny(text, patterns))
    }

    @Test
    fun siteResolver_matchesRealHostFromUrl() {
        val text = "Chrome tab https://www.youtube.com/watch?v=abcd"
        assertTrue(SiteResolver.hostMatchesAny(text, listOf("youtube.com")))
    }

    @Test
    fun siteResolver_doesNotMatchBareBrandWord() {
        // "youtube" as a brand word without a .com/.be tld isn't a host match.
        val text = "watching a youtube lecture on chess"
        assertFalse(SiteResolver.hostMatchesAny(text, listOf("youtube.com")))
    }
}
