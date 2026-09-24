package com.phonecodex.app.domain.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeKindPresetsTest {

    @Test
    fun appOnlyPinnedToIdentityPackage() {
        val e = ScopeKindPresets.expand(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE)
        assertEquals(1, e.include.size)
        val c = e.include.first()
        assertEquals(ScopeHost.NATIVE_APP, c.host)
        assertEquals(ScopeIdentity.YOUTUBE, c.identity)
        assertEquals(PackageMatchMode.PINNED, c.packageMatch)
        assertTrue("com.google.android.youtube" in c.packages)
        assertTrue(e.exempt.isEmpty())
    }

    @Test
    fun browserOnlyDefaultsSitesForIdentity() {
        val e = ScopeKindPresets.expand(ScopeKind.BROWSER_ONLY, ScopeIdentity.YOUTUBE)
        val c = e.include.first()
        assertEquals(ScopeHost.BROWSER, c.host)
        assertEquals(ScopeIdentity.YOUTUBE, c.identity)
        assertEquals(PackageMatchMode.CLASS_RESOLVED, c.packageMatch)
        assertTrue(c.sites.any { it == "youtube.com" })
        assertTrue(c.sites.any { it == "youtu.be" })
        assertTrue(c.packages.isEmpty())
    }

    @Test
    fun contentAnyHostSpansAllHostsWithClassResolve() {
        val e = ScopeKindPresets.expand(ScopeKind.CONTENT_ANY_HOST, ScopeIdentity.YOUTUBE)
        val c = e.include.first()
        assertEquals(ScopeHost.ANY_HOST, c.host)
        assertEquals(PackageMatchMode.CLASS_RESOLVED, c.packageMatch)
        assertTrue(c.packages.isEmpty())
    }

    @Test
    fun videoClassAnyHostUsesVideoAny() {
        val c = ScopeKindPresets.expand(ScopeKind.VIDEO_CLASS_ANY_HOST).include.first()
        assertEquals(ScopeIdentity.VIDEO_ANY, c.identity)
        assertEquals(ScopeHost.ANY_HOST, c.host)
        assertEquals(PackageMatchMode.CLASS_RESOLVED, c.packageMatch)
    }

    @Test
    fun shortFormAnyHostConstrainsToPlayerShort() {
        val c = ScopeKindPresets.expand(ScopeKind.SHORT_FORM_ANY_HOST).include.first()
        assertEquals(ScopeIdentity.SHORT_FORM_ANY, c.identity)
        assertTrue(ScopeSurfaceClass.PLAYER_SHORT in c.surfaces)
        assertTrue(ScopeSurfaceClass.ANY !in c.surfaces)
    }

    @Test
    fun socialMessagingOnlyIncludesAndExemptsDmSurfaces() {
        val e = ScopeKindPresets.expand(ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeIdentity.INSTAGRAM)
        val inc = e.include.first()
        val exempt = e.exempt.first()
        assertEquals(ScopeIdentity.INSTAGRAM, inc.identity)
        assertTrue(ScopeSurfaceClass.ANY in inc.surfaces)
        assertEquals(ScopeIdentity.INSTAGRAM, exempt.identity)
        assertTrue(ScopeSurfaceClass.DM_THREAD in exempt.surfaces)
        assertTrue(ScopeSurfaceClass.DM_LIST in exempt.surfaces)
    }

    @Test
    fun socialFeedAndReelsOnlyRestrictsToFeedAndShortPlayer() {
        val e = ScopeKindPresets.expand(
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY,
            ScopeIdentity.FACEBOOK,
            companionPackage = "com.facebook.orca"
        )
        val inc = e.include.first()
        assertTrue(ScopeSurfaceClass.FEED in inc.surfaces)
        assertTrue(ScopeSurfaceClass.PLAYER_SHORT in inc.surfaces)
        assertTrue(ScopeSurfaceClass.ANY !in inc.surfaces)
        val exempt = e.exempt.first()
        assertEquals(ScopeIdentity.MESSENGER, exempt.identity)
        assertTrue("com.facebook.orca" in exempt.packages)
    }

    @Test
    fun siteScopedRequiresBrowserAndSites() {
        val e = ScopeKindPresets.expand(ScopeKind.SITE_SCOPED, sites = listOf("nesoacademy.org"))
        val c = e.include.first()
        assertEquals(ScopeHost.BROWSER, c.host)
        assertTrue("nesoacademy.org" in c.sites)
    }

    @Test
    fun hostWithCategoryExemptionsHasThreeIncludeClauses() {
        val e = ScopeKindPresets.expand(ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS)
        assertEquals(3, e.include.size)
        val ids = e.include.map { it.identity }.toSet()
        assertTrue(ScopeIdentity.ADULT_ANY in ids)
        assertTrue(ScopeIdentity.MOVIE_OTT_ANY in ids)
        assertTrue(ScopeIdentity.VIDEO_ANY in ids)
        for (c in e.include) assertEquals(ScopeHost.BROWSER, c.host)
    }

    @Test
    fun packagePinnedRestrictsToNamedPackages() {
        val e = ScopeKindPresets.expand(ScopeKind.PACKAGE_PINNED, packages = listOf("app.netmirror.newtv"))
        val c = e.include.first()
        assertEquals(PackageMatchMode.PINNED, c.packageMatch)
        assertTrue("app.netmirror.newtv" in c.packages)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unresolvedHasNoPresetExpansion() {
        ScopeKindPresets.expand(ScopeKind.UNRESOLVED)
    }
}
