package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.EnforcementScopeLaw
import com.phonecodex.app.domain.enforcement.SurfaceDetection
import com.phonecodex.app.domain.enforcement.SurfaceDetectionResult
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.StudyWorldSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A: [LegacyScopeAdapter] must be behaviour-preserving. Every legacy
 * settings shape must map to an [EnforcementScope] that [ScopeMatcher] scores
 * the same way as [EnforcementScopeLaw.matches] did before. This suite pins
 * down the four legacy shapes; the full 80+ trace equivalence property lives
 * in [ScopeGateEquivalenceTest].
 */
class LegacyScopeAdapterTest {

    private val YT = VideoPlatformRegistry.YOUTUBE
    private val CHROME = VideoPlatformRegistry.CHROME
    private val NEWPIPE = VideoPlatformRegistry.NEWPIPE

    // ---- (1) empty + empty -> LEGACY_ALL matches everything ---------------

    @Test
    fun emptyPackagesAndBrands_produceLegacyAll_matchesEveryScreen() {
        val scope = LegacyScopeAdapter.from(
            packages = emptyList(),
            brands = emptyList(),
            scopeKind = null
        )
        assertTrue(scope.isEnforceable)
        assertEquals(ScopeKind.PACKAGE_PINNED, scope.kind)
        for (pkg in listOf(YT, CHROME, NEWPIPE, "com.whatsapp", "com.unknown.clone")) {
            val m = ScopeMatcher.match(
                pkg,
                "",
                SurfaceDetection(SurfaceDetectionResult.UNKNOWN, emptyList()),
                scope
            )
            assertTrue("expected InScope for $pkg, got $m", m is ScopeMatch.InScope)
        }
    }

    @Test
    fun legacyAll_isMarkedLegacyAdapted() {
        val scope = LegacyScopeAdapter.from(
            packages = emptyList(),
            brands = emptyList(),
            scopeKind = null
        )
        assertTrue(LegacyScopeAdapter.isLegacyAdapted(scope))
    }

    // ---- (2) package-pinned only ------------------------------------------

    @Test
    fun packagesOnly_producesPackagePinned() {
        val scope = LegacyScopeAdapter.from(
            packages = listOf(YT, NEWPIPE),
            brands = emptyList(),
            scopeKind = null
        )
        assertEquals(ScopeKind.PACKAGE_PINNED, scope.kind)
        val inYt = ScopeMatcher.match(YT, "", detection(SurfaceDetectionResult.HOME), scope)
        val inNp = ScopeMatcher.match(NEWPIPE, "", detection(SurfaceDetectionResult.HOME), scope)
        val outCh = ScopeMatcher.match(CHROME, "", detection(SurfaceDetectionResult.HOME), scope)
        assertTrue(inYt is ScopeMatch.InScope)
        assertTrue(inNp is ScopeMatch.InScope)
        assertEquals(ScopeMatch.OutOfScope, outCh)
    }

    // ---- (3) youtube content brand ----------------------------------------

    @Test
    fun youtubeContentBrand_matchesOfficialYouTubeAndBrowserYouTubeUrl() {
        val scope = LegacyScopeAdapter.from(
            packages = emptyList(),
            brands = listOf(EnforcementScopeLaw.BRAND_YOUTUBE),
            scopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_CONTENT
        )
        assertEquals(ScopeKind.CONTENT_ANY_HOST, scope.kind)
        assertEquals(ScopeIdentity.YOUTUBE, scope.identity)
        val inYt = ScopeMatcher.match(YT, "", detection(SurfaceDetectionResult.SHORT_FORM_PLAYER), scope)
        val inChYt = ScopeMatcher.match(
            CHROME,
            "https://www.youtube.com/watch?v=abcd Time elapsed 00:01",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        val inChBrandPlayer = ScopeMatcher.match(
            CHROME,
            "Hide player controls YouTube 0:01 / 40:01",
            detection(SurfaceDetectionResult.LONG_FORM_PLAYER),
            scope
        )
        val inNp = ScopeMatcher.match(
            NEWPIPE,
            "Time elapsed 00:15 Time duration 00:59",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        val outCh = ScopeMatcher.match(
            CHROME,
            "https://mail.google.com/mail Inbox Primary",
            detection(SurfaceDetectionResult.HOME),
            scope
        )
        assertTrue(inYt is ScopeMatch.InScope)
        assertTrue(inChYt is ScopeMatch.InScope)
        assertTrue(inChBrandPlayer is ScopeMatch.InScope)
        assertTrue(inNp is ScopeMatch.InScope)
        assertEquals(ScopeMatch.OutOfScope, outCh)
    }

    // ---- (4) youtube_app_only / youtube_only / youtube_shorts_only --------

    @Test
    fun youtubeAppOnlyKind_producesAppOnlyPinnedToYoutube() {
        val scope = LegacyScopeAdapter.from(
            packages = emptyList(),
            brands = emptyList(),
            scopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
        )
        assertEquals(ScopeKind.APP_ONLY, scope.kind)
        val inYt = ScopeMatcher.match(YT, "", detection(SurfaceDetectionResult.SHORT_FORM_PLAYER), scope)
        val outCh = ScopeMatcher.match(
            CHROME,
            "https://www.youtube.com/watch?v=abcd",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        val outNp = ScopeMatcher.match(NEWPIPE, "", detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER), scope)
        assertTrue(inYt is ScopeMatch.InScope)
        assertEquals(ScopeMatch.OutOfScope, outCh)
        assertEquals(ScopeMatch.OutOfScope, outNp)
    }

    @Test
    fun youtubeOnlyIsIntentionallyConflatedWithAppOnly() {
        // Design decision: adapter preserves today's conflation. Fixing it belongs
        // to the compiler (Phase 2B / Phase 3), never the adapter. See §7.2 of the
        // Phase 2 review doc.
        val scopeYouTubeOnly = LegacyScopeAdapter.from(
            packages = emptyList(),
            brands = emptyList(),
            scopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_ONLY
        )
        val scopeYouTubeAppOnly = LegacyScopeAdapter.from(
            packages = emptyList(),
            brands = emptyList(),
            scopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
        )
        assertEquals(scopeYouTubeOnly.kind, scopeYouTubeAppOnly.kind)
        assertEquals(scopeYouTubeOnly.identity, scopeYouTubeAppOnly.identity)
        // Both refuse Chrome playing youtube.com
        val outChYo = ScopeMatcher.match(
            CHROME,
            "https://www.youtube.com/watch?v=abcd",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scopeYouTubeOnly
        )
        assertEquals(ScopeMatch.OutOfScope, outChYo)
    }

    // ---- StudyWorldSettings convenience overload ---------------------------

    @Test
    fun settingsOverload_readsThreeFields() {
        val s = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScopePackages = listOf(CHROME),
            enforcementScopeKind = null
        )
        val scope = LegacyScopeAdapter.from(s)
        assertEquals(ScopeKind.PACKAGE_PINNED, scope.kind)
        assertFalse(scope.include.first().packages.isEmpty())
    }

    private fun detection(surface: SurfaceDetectionResult): SurfaceDetection =
        SurfaceDetection(surface = surface, evidence = emptyList())
}
