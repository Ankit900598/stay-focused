package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.PromiseEnforcementCoordinator
import com.phonecodex.app.domain.enforcement.PromiseEvalInput
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.StudyWorldSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Browser video" = video players in any listed browser. Real [SurfaceDetector] +
 * [ScopeMatcher] + coordinator replay; no hand-picked detection results.
 */
class BrowserVideoScopeTest {

    private fun typed(kind: ScopeKind): EnforcementScope = ScopeCanonicalizer.canonicalize(
        EnforcementScope(
            version = EnforcementScope.CURRENT_VERSION,
            kind = kind,
            identity = ScopeIdentity.VIDEO_ANY,
            include = ScopeKindPresets.expand(kind).include,
            confidence = 0.9,
            evidenceSpans = listOf("Google video")
        )
    )

    private fun match(pkg: String, text: String, scope: EnforcementScope): ScopeMatch =
        ScopeMatcher.match(pkg, text, SurfaceDetector.detect(pkg, text), scope)

    private fun settings(scope: EnforcementScope, minMinutes: Int? = 30) = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        minVideoLengthBlockMinutes = minMinutes,
        enforcementScope = scope
    )

    private fun replay(pkg: String, text: String, scope: EnforcementScope) =
        PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = "Google video: block videos shorter than 30 min for 1 hour",
                settings = settings(scope),
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = pkg,
                screenText = text
            )
        )

    @Test
    fun browserVideoPreset_isBrowserHostVideoPlayerOnly_notChromePinned() {
        val clause = ScopeKindPresets.expand(ScopeKind.BROWSER_VIDEO).include.single()
        assertEquals(ScopeHost.BROWSER, clause.host)
        assertEquals(ScopeIdentity.VIDEO_ANY, clause.identity)
        assertEquals(setOf(ScopeSurfaceClass.PLAYER_ANY), clause.surfaces)
        assertEquals(PackageMatchMode.CLASS_RESOLVED, clause.packageMatch)
        assertTrue(clause.packages.isEmpty())
        assertTrue(clause.sites.isEmpty())
    }

    @Test
    fun playerAnyCoversShortAndLongPlayers() {
        val scope = typed(ScopeKind.BROWSER_VIDEO)
        val long = match(ScopeTraceScreens.CHROME, "vimeo.com/1 Pause 03:12 / 41:05 Playback speed", scope)
        val short = match(ScopeTraceScreens.CHROME, "m.youtube.com/shorts/abc123 Shorts player 0:10 / 0:40", scope)
        assertTrue("long: $long", long is ScopeMatch.InScope)
        assertTrue("short: $short", short is ScopeMatch.InScope)
    }

    @Test
    fun browserVideo_matchesPlayersInChromeFirefoxBraveSamsung() {
        val scope = typed(ScopeKind.BROWSER_VIDEO)
        val seen = mutableSetOf<String>()
        for ((pkg, text) in ScopeTraceScreens.BROWSER_PLAYERS) {
            assertTrue("$pkg '$text' not a player", SurfaceDetector.detect(pkg, text).isActivePlayer)
            val m = match(pkg, text, scope)
            assertTrue("$pkg player must be in scope, got $m", m is ScopeMatch.InScope)
            seen += pkg
        }
        assertEquals(
            setOf(ScopeTraceScreens.CHROME, ScopeTraceScreens.FIREFOX, ScopeTraceScreens.BRAVE, ScopeTraceScreens.SAMSUNG),
            seen
        )
    }

    @Test
    fun browserVideo_searchAndHomeAreVerifiedOutOfScope() {
        val scope = typed(ScopeKind.BROWSER_VIDEO)
        val search = ScopeTraceScreens.BROWSER_NON_PLAYER[0]
        val home = ScopeTraceScreens.BROWSER_NON_PLAYER[1]
        assertEquals(ScopeMatch.OutOfScope, match(search.first, search.second, scope))
        assertEquals(ScopeMatch.OutOfScope, match(home.first, home.second, scope))
    }

    @Test
    fun browserVideo_nonPlayerPagesNeverInScopeAndNeverBlocked() {
        val scope = typed(ScopeKind.BROWSER_VIDEO)
        for ((pkg, text) in ScopeTraceScreens.BROWSER_NON_PLAYER) {
            val m = match(pkg, text, scope)
            assertFalse("$pkg '$text' -> $m", m is ScopeMatch.InScope)
            val res = replay(pkg, text, scope)
            assertNotEquals("$pkg '$text' blocked: ${res.reasonCode}", DecisionType.BLOCK, res.decision)
        }
    }

    @Test
    fun unknownSurfaceInBrowser_isIndeterminateWait_notVerifiedOutOfScope() {
        val scope = typed(ScopeKind.BROWSER_VIDEO)
        val docs = "docs.google.com/document/d/abc Untitled document Editing"
        val m = match(ScopeTraceScreens.CHROME, docs, scope)
        assertEquals(ScopeMatch.Indeterminate("i1", IndeterminateReason.SURFACE_UNKNOWN), m)
        val res = replay(ScopeTraceScreens.CHROME, docs, scope)
        assertEquals(EnforcementReasonCodes.SCOPE_EVIDENCE_INDETERMINATE, res.reasonCode)
        assertNotEquals(DecisionType.BLOCK, res.decision)
    }

    @Test
    fun browserVideo_nativeVideoAppIsOutOfScope() {
        val scope = typed(ScopeKind.BROWSER_VIDEO)
        for ((pkg, text) in ScopeTraceScreens.VIDEO_APP_PLAYERS) {
            assertEquals("$pkg", ScopeMatch.OutOfScope, match(pkg, text, scope))
        }
    }

    @Test
    fun nameOnlyBrowserPackage_isHostUnverified_notInScope() {
        val text = "Pause 03:12 / 41:05 Playback speed"
        for (kind in listOf(ScopeKind.BROWSER_VIDEO, ScopeKind.VIDEO_CLASS_ANY_HOST)) {
            val m = match("com.example.browserquest", text, typed(kind))
            assertEquals("$kind", ScopeMatch.Indeterminate("i1", IndeterminateReason.HOST_UNVERIFIED), m)
        }
        assertFalse(HostResolver.isVerifiedBrowser("com.example.browserquest"))
        assertTrue(HostResolver.looksLikeBrowserByNameOnly("com.example.browserquest"))
        assertTrue(HostResolver.isVerifiedBrowser("com.sec.android.app.sbrowser"))
    }

    @Test
    fun legacyAdaptedScopes_keepOldHostAndSurfaceBehaviour() {
        val legacy = LegacyScopeAdapter.from(
            StudyWorldSettings(
                durationMinutes = 60,
                lockAttemptThreshold = 3,
                blockAttemptCooldownMinutes = 2,
                enforcementScopePackages = listOf("com.example.browserquest")
            )
        )
        val m = match("com.example.browserquest", "docs page", legacy)
        assertTrue("legacy must not become Indeterminate: $m", m is ScopeMatch.InScope)
    }

    @Test
    fun replay_shortBrowserVideoBlocks_searchAllowed() {
        val scope = typed(ScopeKind.BROWSER_VIDEO)
        val shortChrome = "m.youtube.com/watch?v=abc123xyz Hide player controls 01:00 / 05:00"
        val blocked = replay(ScopeTraceScreens.CHROME, shortChrome, scope)
        assertEquals(blocked.reasonCode, DecisionType.BLOCK, blocked.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, blocked.reasonCode)

        val search = replay(ScopeTraceScreens.CHROME, ScopeTraceScreens.BROWSER_NON_PLAYER[0].second, scope)
        assertEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, search.reasonCode)
    }

    @Test
    fun replay_shortVideoBlocksInFirefoxBraveSamsung() {
        val scope = typed(ScopeKind.BROWSER_VIDEO)
        val screens = listOf(
            ScopeTraceScreens.FIREFOX to "m.youtube.com/watch?v=abc123xyz Hide player controls 01:00 / 05:00",
            ScopeTraceScreens.BRAVE to "dailymotion.com/video/x8 Pause 0:45 / 5:10 Playback settings",
            ScopeTraceScreens.SAMSUNG to "Show player controls 1:00 / 4:00 Pause"
        )
        for ((pkg, text) in screens) {
            val res = replay(pkg, text, scope)
            assertEquals("$pkg -> ${res.reasonCode} stage=${res.stage}", EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, res.reasonCode)
        }
    }

    @Test
    fun replay_firefoxNonPlayerAndLegacySession_doNotBlock() {
        val docs = replay(ScopeTraceScreens.FIREFOX, ScopeTraceScreens.BROWSER_NON_PLAYER[0].second, typed(ScopeKind.BROWSER_VIDEO))
        assertNotEquals(DecisionType.BLOCK, docs.decision)

        val legacyChromeOnly = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = "Google video: block videos shorter than 30 min for 1 hour",
                settings = StudyWorldSettings(
                    durationMinutes = 60,
                    lockAttemptThreshold = 3,
                    blockAttemptCooldownMinutes = 2,
                    minVideoLengthBlockMinutes = 30,
                    enforcementScopePackages = listOf("com.android.chrome")
                ),
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = ScopeTraceScreens.FIREFOX,
                screenText = "m.youtube.com/watch?v=abc123xyz Hide player controls 01:00 / 05:00"
            )
        )
        assertNotEquals("legacy Chrome-pinned session keeps old Firefox behaviour", DecisionType.BLOCK, legacyChromeOnly.decision)
    }
}
