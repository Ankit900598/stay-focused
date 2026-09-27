package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.SurfaceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "All video apps and browsers" semantics against the REAL [SurfaceDetector] mapping.
 *
 * A video scope means a playing video. Chrome Search, YouTube Home in a browser,
 * and Docs are not video and must stay usable; players in any browser and any
 * video app are covered. Unrelated apps (WhatsApp voice notes, music) are not.
 */
class VideoScopeSemanticsTest {

    private fun allVideo(): EnforcementScope {
        val exp = ScopeKindPresets.expand(ScopeKind.VIDEO_CLASS_ANY_HOST)
        return EnforcementScope(
            version = EnforcementScope.CURRENT_VERSION,
            kind = ScopeKind.VIDEO_CLASS_ANY_HOST,
            identity = ScopeIdentity.VIDEO_ANY,
            include = exp.include,
            exempt = exp.exempt,
            confidence = 0.9,
            evidenceSpans = listOf("all video")
        )
    }

    private fun match(pkg: String, text: String, scope: EnforcementScope): ScopeMatch =
        ScopeMatcher.match(pkg, text, SurfaceDetector.detect(pkg, text), scope)

    @Test
    fun allVideoPresetIsPlayerOnly() {
        val clause = ScopeKindPresets.expand(ScopeKind.VIDEO_CLASS_ANY_HOST).include.single()
        assertEquals(ScopeHost.ANY_HOST, clause.host)
        assertEquals(ScopeIdentity.VIDEO_ANY, clause.identity)
        assertEquals(setOf(ScopeSurfaceClass.PLAYER_ANY), clause.surfaces)
        assertTrue(clause.packages.isEmpty())
    }

    @Test
    fun allVideo_matchesBrowserPlayersInEveryKnownBrowser() {
        for ((pkg, text) in ScopeTraceScreens.BROWSER_PLAYERS) {
            val m = match(pkg, text, allVideo())
            assertTrue("$pkg player must be in scope, got $m", m is ScopeMatch.InScope)
        }
    }

    @Test
    fun allVideo_matchesVideoAppPlayers() {
        for ((pkg, text) in ScopeTraceScreens.VIDEO_APP_PLAYERS) {
            val m = match(pkg, text, allVideo())
            assertTrue("$pkg player must be in scope, got $m", m is ScopeMatch.InScope)
        }
    }

    @Test
    fun allVideo_chromeSearchHomeDocsAreNotInScope() {
        for ((pkg, text) in ScopeTraceScreens.BROWSER_NON_PLAYER) {
            val m = match(pkg, text, allVideo())
            assertFalse("$pkg non-player page must not be in scope: '$text' -> $m", m is ScopeMatch.InScope)
        }
    }

    @Test
    fun allVideo_unrelatedAppsAreOutOfScope() {
        for ((pkg, text) in ScopeTraceScreens.UNRELATED_APPS) {
            val m = match(pkg, text, allVideo())
            assertTrue("$pkg must be OutOfScope, got $m", m == ScopeMatch.OutOfScope)
        }
    }

    @Test
    fun appliesToCopyIgnoresModelProse() {
        val base = allVideo()
        val a = ScopeAppliesToCopy.forScope(base.copy(appliesToText = "Chrome in ."))
        val b = ScopeAppliesToCopy.forScope(base.copy(appliesToText = "every video thing, trust me"))
        val c = ScopeAppliesToCopy.forScope(base)
        assertEquals(c, a)
        assertEquals(c, b)
        assertFalse(a.contains(" in ."))
        assertFalse(ScopeAppliesToCopy.containsPackageId(a))
    }
}

/** Accessibility text shapes seen on phone traces, reduced to the tokens the detector reads. */
object ScopeTraceScreens {
    const val CHROME = "com.android.chrome"
    const val FIREFOX = "org.mozilla.firefox"
    const val BRAVE = "com.brave.browser"
    const val SAMSUNG = "com.sec.android.app.sbrowser"

    val BROWSER_PLAYERS = listOf(
        CHROME to "m.youtube.com/watch?v=abc123xyz Hide player controls 03:12 / 41:05",
        CHROME to "vimeo.com/12345 Pause 03:12 / 41:05 Playback speed",
        FIREFOX to "m.youtube.com/watch?v=abc123xyz Hide player controls 1:02 / 12:40",
        BRAVE to "dailymotion.com/video/x8 Pause 0:45 / 22:10 Playback settings",
        SAMSUNG to "Show player controls 10:00 / 1:02:00 Pause"
    )

    val VIDEO_APP_PLAYERS = listOf(
        "com.google.android.youtube" to "Hide player controls 3:00 / 45:00 Subscribe",
        "org.schabi.newpipe" to "Pause 1:00 / 30:00 Playback position"
    )

    val BROWSER_NON_PLAYER = listOf(
        CHROME to "google.com/search?q=lofi+music About 1,20,000 results Videos Images News",
        CHROME to "m.youtube.com Home Shorts Subscriptions Library",
        CHROME to "docs.google.com/document/d/abc Untitled document Editing",
        FIREFOX to "google.com/search?q=linear+algebra About 3,000 results",
        CHROME to "New tab Search or type web address"
    )

    val UNRELATED_APPS = listOf(
        "com.whatsapp" to "Voice message Pause 0:12 / 1:30 Type a message",
        "com.google.android.gm" to "Inbox Primary Compose",
        "com.spotify.music" to "Pause 1:02 / 3:30 Now playing"
    )
}
