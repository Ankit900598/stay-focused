package com.phonecodex.app.domain.scope

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityResolverTest {

    @Test
    fun youtubeIdentityMatchesOfficialAndClients() {
        assertTrue(IdentityResolver.matches(ScopeIdentity.YOUTUBE, "com.google.android.youtube", ""))
        assertTrue(IdentityResolver.matches(ScopeIdentity.YOUTUBE, "com.vanced.android.youtube", ""))
        assertTrue(IdentityResolver.matches(ScopeIdentity.YOUTUBE, "org.schabi.newpipe", ""))
    }

    @Test
    fun youtubeIdentityMatchesBrowserByUrl() {
        val chromeText = "Chrome tab https://www.youtube.com/watch?v=abcd"
        assertTrue(IdentityResolver.matches(ScopeIdentity.YOUTUBE, "com.android.chrome", chromeText))
    }

    @Test
    fun instagramFacebookMessengerAreDistinct() {
        assertTrue(IdentityResolver.matches(ScopeIdentity.INSTAGRAM, "com.instagram.android", ""))
        assertTrue(IdentityResolver.matches(ScopeIdentity.FACEBOOK, "com.facebook.katana", ""))
        assertTrue(IdentityResolver.matches(ScopeIdentity.MESSENGER, "com.facebook.orca", ""))
        assertFalse(IdentityResolver.matches(ScopeIdentity.FACEBOOK, "com.facebook.orca", ""))
        assertFalse(IdentityResolver.matches(ScopeIdentity.MESSENGER, "com.facebook.katana", ""))
    }

    @Test
    fun videoAnyMatchesRegisteredAndUnknownStreaming() {
        assertTrue(IdentityResolver.matches(ScopeIdentity.VIDEO_ANY, "com.google.android.youtube", ""))
        assertTrue(IdentityResolver.matches(ScopeIdentity.VIDEO_ANY, "app.netmirror.newtv", ""))
        // Unregistered clone matching streaming token hint should still resolve as VIDEO_ANY.
        assertTrue(IdentityResolver.matches(ScopeIdentity.VIDEO_ANY, "com.random.moviestream", ""))
        assertFalse(IdentityResolver.matches(ScopeIdentity.VIDEO_ANY, "com.whatsapp", ""))
    }

    @Test
    fun shortFormAnyMatchesQuotaAndShortsUrl() {
        assertTrue(IdentityResolver.matches(ScopeIdentity.SHORT_FORM_ANY, "com.zhiliaoapp.musically", ""))
        assertTrue(IdentityResolver.matches(ScopeIdentity.SHORT_FORM_ANY, "com.instagram.android", ""))
        // Unregistered but clearly short-form-adjacent by name.
        assertTrue(IdentityResolver.matches(ScopeIdentity.SHORT_FORM_ANY, "com.xyz.reelsvideo", ""))
        // A YouTube /shorts/ URL in browser text still resolves.
        assertTrue(
            IdentityResolver.matches(
                ScopeIdentity.SHORT_FORM_ANY,
                "com.android.chrome",
                "https://www.youtube.com/shorts/abc123"
            )
        )
    }

    @Test
    fun adultAnyMatchesStrongSiteText() {
        assertTrue(
            IdentityResolver.matches(
                ScopeIdentity.ADULT_ANY,
                "com.android.chrome",
                "pornhub.com — trending"
            )
        )
        assertFalse(IdentityResolver.matches(ScopeIdentity.ADULT_ANY, "com.android.chrome", "dsa tutorial"))
    }

    @Test
    fun movieOttAnyMatchesKnownAndBrowserSignals() {
        assertTrue(IdentityResolver.matches(ScopeIdentity.MOVIE_OTT_ANY, "app.netmirror.newtv", ""))
        assertTrue(
            IdentityResolver.matches(
                ScopeIdentity.MOVIE_OTT_ANY,
                "com.android.chrome",
                "Watch full movie: Interstellar"
            )
        )
        assertTrue(
            IdentityResolver.matches(
                ScopeIdentity.MOVIE_OTT_ANY,
                "com.android.chrome",
                "Netflix hotstar.com — new release"
            )
        )
    }

    @Test
    fun socialAnyMatchesSocialPackages() {
        val socialPkgs = listOf(
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.zhiliaoapp.musically",
            "com.snapchat.android",
            "com.whatsapp"
        )
        for (pkg in socialPkgs) {
            assertTrue(pkg, IdentityResolver.matches(ScopeIdentity.SOCIAL_ANY, pkg, ""))
        }
        assertFalse(IdentityResolver.matches(ScopeIdentity.SOCIAL_ANY, "com.google.android.youtube", ""))
    }

    @Test
    fun anyMatchesEverything() {
        assertTrue(IdentityResolver.matches(ScopeIdentity.ANY, "whatever.package", ""))
    }
}
