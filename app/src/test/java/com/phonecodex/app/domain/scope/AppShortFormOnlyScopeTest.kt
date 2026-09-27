package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.SurfaceDetectionResult
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "YouTube Shorts only" = APP_SHORT_FORM_ONLY(YOUTUBE): Shorts in the YouTube app are
 * in scope; long-form YouTube, other apps' reels, and browser shorts are not.
 * Runs the real [SurfaceDetector] -> [ScopeMatcher] path on recorded screen text.
 */
class AppShortFormOnlyScopeTest {

    private val youtubeShorts = ScopeCanonicalizer.canonicalize(
        EnforcementScope(
            version = EnforcementScope.CURRENT_VERSION,
            kind = ScopeKind.APP_SHORT_FORM_ONLY,
            identity = ScopeIdentity.YOUTUBE,
            include = ScopeKindPresets.expand(ScopeKind.APP_SHORT_FORM_ONLY, ScopeIdentity.YOUTUBE).include,
            confidence = 0.9,
            evidenceSpans = listOf("shorts")
        )
    )

    private fun matchReal(pkg: String, text: String, expectedSurface: SurfaceDetectionResult): ScopeMatch {
        val detection = SurfaceDetector.detect(pkg, text)
        assertEquals("detector precondition for '$text'", expectedSurface, detection.surface)
        return ScopeMatcher.match(pkg, text, detection, youtubeShorts)
    }

    @Test
    fun canonicalPresetIsYouTubeShortPlayerOnly() {
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, youtubeShorts.kind)
        val clause = youtubeShorts.include.single()
        assertEquals(ScopeHost.NATIVE_APP, clause.host)
        assertEquals(setOf(ScopeSurfaceClass.PLAYER_SHORT), clause.surfaces)
        assertEquals(listOf(VideoPlatformRegistry.YOUTUBE), clause.packages)
        assertEquals(PackageMatchMode.PINNED, clause.packageMatch)
        assertEquals("YouTube short videos only", ScopeAppliesToCopy.optionLabel(youtubeShorts))
    }

    @Test
    fun youtubeShortsPlayerIsInScope() {
        val m = matchReal(
            VideoPlatformRegistry.YOUTUBE,
            "Home Shorts Subscriptions Remix this short Pause like comment",
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )
        assertTrue("expected InScope, got $m", m is ScopeMatch.InScope)
    }

    @Test
    fun youtubeLongFormPlayerIsOutOfScope() {
        val m = matchReal(
            VideoPlatformRegistry.YOUTUBE,
            "Hide player controls Time duration 1 hour 25 minutes Subscribe",
            SurfaceDetectionResult.LONG_FORM_PLAYER
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun instagramReelIsOutOfScope() {
        val m = matchReal(
            VideoPlatformRegistry.INSTAGRAM,
            "https://www.instagram.com/reel/CxyzABC123 audio original",
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun youtubeShortsInChromeIsOutOfScope() {
        val m = matchReal(
            VideoPlatformRegistry.CHROME,
            "Address bar https://www.youtube.com/shorts/dQw4w9WgXcQ youtube.com/shorts/dQw4w9WgXcQ",
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun nonShortFormIdentitiesStayUnresolved() {
        for (identity in listOf(ScopeIdentity.WHATSAPP, ScopeIdentity.MESSENGER, ScopeIdentity.VIDEO_ANY, ScopeIdentity.SHORT_FORM_ANY)) {
            val raw = EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.APP_SHORT_FORM_ONLY,
                identity = identity,
                include = ScopeKindPresets.expand(ScopeKind.APP_SHORT_FORM_ONLY, identity).include,
                confidence = 0.9,
                evidenceSpans = listOf("shorts")
            )
            val canonical = ScopeCanonicalizer.canonicalize(raw)
            assertEquals("$identity must not broaden", ScopeKind.UNRESOLVED, canonical.kind)
        }
    }

    @Test
    fun serializationRoundTripsTheNewKind() {
        val json = ScopeSerialization.toJson(youtubeShorts).toString()
        val back = ScopeCanonicalizer.fromCompilerJson(json, "I waste my evenings on shorts")
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, back.kind)
        assertEquals(ScopeIdentity.YOUTUBE, back.identity)
        assertEquals(youtubeShorts.include, back.include)
    }
}
