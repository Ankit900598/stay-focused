package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry

/**
 * Answers "is this screen running in a browser or a native app?" for one package.
 *
 * Generalises [com.phonecodex.app.domain.enforcement.EnforcementScopeLaw.looksLikeBrowserPackage]
 * to Chrome, Firefox, Brave, Samsung Internet, Edge, DuckDuckGo, Opera, Vivaldi, and
 * generic "browser" packages. Native players (YT, NewPipe, Vanced, IG, TikTok, etc.)
 * always resolve to [ScopeHost.NATIVE_APP] even when they render web content.
 */
object HostResolver {

    /** All known browser package IDs / hints we treat as [ScopeHost.BROWSER]. */
    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "org.mozilla.firefox_beta",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "com.duckduckgo.mobile.android",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "mozilla.components.browser"
    )

    /** Substrings inside a package ID that also imply a browser. */
    private val BROWSER_NAME_HINTS = listOf(
        "browser",
        "firefox",
        "fenix",
        "chrome",
        "chromium",
        "brave",
        "opera",
        "duckduckgo",
        "sbrowser",
        "emmx",
        "vivaldi",
        "webview"
    )

    fun host(packageName: String, @Suppress("UNUSED_PARAMETER") screenText: String = ""): ScopeHost {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return ScopeHost.NATIVE_APP
        // A known video platform is always a native app even if its ID contains "web"
        // (e.g. CHROME_WEB kind exists but is still a real browser — handled above).
        if (isBrowser(pkg)) return ScopeHost.BROWSER
        // Everything else — YT, NewPipe, IG, WhatsApp, movie apps, unknown clones — is native.
        return ScopeHost.NATIVE_APP
    }

    fun isBrowser(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return false
        if (pkg in BROWSER_PACKAGES) return true
        if (VideoPlatformRegistry.isChrome(pkg)) return true
        return BROWSER_NAME_HINTS.any { hint -> pkg.contains(hint) }
    }

    fun isNativeApp(packageName: String): Boolean = !isBrowser(packageName)
}
