package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry

/**
 * The one place where a named [ScopeKind] becomes a concrete `(include, exempt)` clause pair.
 *
 * Adding a new user phrasing = adding a new preset row here, NEVER a new code branch in
 * the coordinator or gates. Presets follow docs/scope-semantics-v1.md §2.4.
 *
 * Package identifiers used here are INTERNAL only — presets are consumed by
 * [ScopeMatcher], never rendered to the user. Copy comes from [ScopeAppliesToCopy].
 */
object ScopeKindPresets {

    data class Expansion(
        val include: List<ScopeClause>,
        val exempt: List<ScopeClause>
    )

    /**
     * Expand a preset kind + parameters into concrete clauses.
     *
     * @param kind        the named scope kind (must not be [ScopeKind.UNRESOLVED])
     * @param identity    identity parameter (`{B}`); ignored for kinds that hardcode it
     * @param sites       host patterns for [ScopeKind.SITE_SCOPED]
     * @param packages    explicit packages for [ScopeKind.PACKAGE_PINNED]
     * @param companionPackage optional companion app package to carve out
     *                    (e.g. `com.facebook.orca` for a Facebook feed-only scope)
     */
    fun expand(
        kind: ScopeKind,
        identity: ScopeIdentity? = null,
        sites: List<String> = emptyList(),
        packages: List<String> = emptyList(),
        companionPackage: String? = null
    ): Expansion {
        require(kind != ScopeKind.UNRESOLVED) {
            "UNRESOLVED has no preset expansion — construct EnforcementScope.unresolved(...) instead"
        }
        return when (kind) {
            ScopeKind.APP_ONLY -> appOnly(identity, packages)
            ScopeKind.BROWSER_ONLY -> browserOnly(identity, sites)
            ScopeKind.BROWSER_VIDEO -> browserVideo()
            ScopeKind.CONTENT_ANY_HOST -> contentAnyHost(identity)
            ScopeKind.VIDEO_CLASS_ANY_HOST -> videoClassAnyHost()
            ScopeKind.SHORT_FORM_ANY_HOST -> shortFormAnyHost()
            ScopeKind.APP_SHORT_FORM_ONLY -> appShortFormOnly(identity)
            ScopeKind.SOCIAL_MESSAGING_ONLY -> socialMessagingOnly(identity)
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY -> socialFeedAndReelsOnly(identity, companionPackage)
            ScopeKind.SITE_SCOPED -> siteScoped(sites)
            ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS -> hostWithCategoryExemptions()
            ScopeKind.PACKAGE_PINNED -> packagePinned(packages)
            ScopeKind.UNRESOLVED -> Expansion(emptyList(), emptyList())
        }
    }

    private fun appOnly(identity: ScopeIdentity?, explicitPackages: List<String>): Expansion {
        val id = identity ?: ScopeIdentity.ANY
        val pkgs = when {
            explicitPackages.isNotEmpty() -> explicitPackages
            id == ScopeIdentity.YOUTUBE -> listOf(VideoPlatformRegistry.YOUTUBE)
            id == ScopeIdentity.INSTAGRAM -> listOf(VideoPlatformRegistry.INSTAGRAM)
            id == ScopeIdentity.FACEBOOK -> listOf(VideoPlatformRegistry.FACEBOOK)
            id == ScopeIdentity.MESSENGER -> listOf("com.facebook.orca")
            id == ScopeIdentity.TIKTOK -> listOf(VideoPlatformRegistry.TIKTOK)
            id == ScopeIdentity.SNAPCHAT -> listOf(VideoPlatformRegistry.SNAPCHAT)
            id == ScopeIdentity.WHATSAPP -> listOf("com.whatsapp")
            else -> emptyList()
        }
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.NATIVE_APP,
            identity = id,
            surfaces = setOf(ScopeSurfaceClass.ANY),
            packages = pkgs,
            packageMatch = PackageMatchMode.PINNED
        )
        return Expansion(listOf(include), emptyList())
    }

    private fun browserOnly(identity: ScopeIdentity?, sites: List<String>): Expansion {
        val id = identity ?: ScopeIdentity.ANY
        val resolvedSites = if (sites.isNotEmpty()) sites else defaultSitesForIdentity(id)
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.BROWSER,
            identity = id,
            surfaces = setOf(ScopeSurfaceClass.PLAYER_ANY, ScopeSurfaceClass.BROWSE, ScopeSurfaceClass.SEARCH),
            sites = resolvedSites,
            packages = emptyList(),
            packageMatch = PackageMatchMode.CLASS_RESOLVED
        )
        return Expansion(listOf(include), emptyList())
    }

    private fun contentAnyHost(identity: ScopeIdentity?): Expansion {
        val id = identity ?: ScopeIdentity.ANY
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.ANY_HOST,
            identity = id,
            surfaces = setOf(ScopeSurfaceClass.ANY),
            packages = emptyList(),
            packageMatch = PackageMatchMode.CLASS_RESOLVED
        )
        return Expansion(listOf(include), emptyList())
    }

    /**
     * "Browser video": players in any listed browser. Not Chrome-pinned, and never
     * search / browse / docs — those stay usable while the promise runs.
     */
    private fun browserVideo(): Expansion {
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.BROWSER,
            identity = ScopeIdentity.VIDEO_ANY,
            surfaces = setOf(ScopeSurfaceClass.PLAYER_ANY),
            packages = emptyList(),
            packageMatch = PackageMatchMode.CLASS_RESOLVED
        )
        return Expansion(listOf(include), emptyList())
    }

    /** "All video apps and browsers": a playing video anywhere. Player-only, not surfaces=ANY. */
    private fun videoClassAnyHost(): Expansion {
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.ANY_HOST,
            identity = ScopeIdentity.VIDEO_ANY,
            surfaces = setOf(ScopeSurfaceClass.PLAYER_ANY),
            packages = emptyList(),
            packageMatch = PackageMatchMode.CLASS_RESOLVED
        )
        return Expansion(listOf(include), emptyList())
    }

    private fun shortFormAnyHost(): Expansion {
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.ANY_HOST,
            identity = ScopeIdentity.SHORT_FORM_ANY,
            surfaces = setOf(ScopeSurfaceClass.PLAYER_SHORT),
            packages = emptyList(),
            packageMatch = PackageMatchMode.CLASS_RESOLVED
        )
        return Expansion(listOf(include), emptyList())
    }

    /** "YouTube Shorts only": the short player in that app. Long-form, browser, other apps out. */
    private fun appShortFormOnly(identity: ScopeIdentity?): Expansion {
        val id = identity ?: ScopeIdentity.ANY
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.NATIVE_APP,
            identity = id,
            surfaces = setOf(ScopeSurfaceClass.PLAYER_SHORT),
            packages = defaultPackagesForIdentity(id),
            packageMatch = PackageMatchMode.PINNED
        )
        return Expansion(listOf(include), emptyList())
    }

    private fun socialMessagingOnly(identity: ScopeIdentity?): Expansion {
        val id = identity ?: ScopeIdentity.SOCIAL_ANY
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.ANY_HOST,
            identity = id,
            surfaces = setOf(ScopeSurfaceClass.ANY),
            packages = defaultPackagesForIdentity(id),
            packageMatch = PackageMatchMode.PINNED
        )
        val exempt = ScopeClause(
            id = "x1",
            host = ScopeHost.ANY_HOST,
            identity = id,
            surfaces = setOf(ScopeSurfaceClass.DM_LIST, ScopeSurfaceClass.DM_THREAD),
            packages = defaultPackagesForIdentity(id),
            packageMatch = PackageMatchMode.PINNED
        )
        return Expansion(listOf(include), listOf(exempt))
    }

    private fun socialFeedAndReelsOnly(
        identity: ScopeIdentity?,
        companionPackage: String?
    ): Expansion {
        val id = identity ?: ScopeIdentity.SOCIAL_ANY
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.ANY_HOST,
            identity = id,
            surfaces = setOf(ScopeSurfaceClass.FEED, ScopeSurfaceClass.PLAYER_SHORT),
            packages = defaultPackagesForIdentity(id),
            packageMatch = PackageMatchMode.PINNED
        )
        val exempt = companionPackage?.takeIf { it.isNotBlank() }?.let { comp ->
            ScopeClause(
                id = "x1",
                host = ScopeHost.NATIVE_APP,
                identity = ScopeIdentity.MESSENGER,
                surfaces = setOf(ScopeSurfaceClass.ANY),
                packages = listOf(comp),
                packageMatch = PackageMatchMode.PINNED
            )
        }
        return Expansion(listOf(include), listOfNotNull(exempt))
    }

    private fun siteScoped(sites: List<String>): Expansion {
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.BROWSER,
            identity = ScopeIdentity.ANY,
            surfaces = setOf(ScopeSurfaceClass.ANY),
            sites = sites,
            packages = emptyList(),
            packageMatch = PackageMatchMode.CLASS_RESOLVED
        )
        return Expansion(listOf(include), emptyList())
    }

    /**
     * "Chrome is fine except adult, movie, and video sites" — three include clauses.
     * Search / docs / SERP have no clause and become OutOfScope -> ENFORCEMENT_SCOPE_ALLOW.
     */
    private fun hostWithCategoryExemptions(): Expansion {
        val include = listOf(
            ScopeClause(
                id = "i1",
                host = ScopeHost.BROWSER,
                identity = ScopeIdentity.ADULT_ANY,
                surfaces = setOf(ScopeSurfaceClass.ANY),
                sites = listOf("*"),
                packages = emptyList(),
                packageMatch = PackageMatchMode.CLASS_RESOLVED
            ),
            ScopeClause(
                id = "i2",
                host = ScopeHost.BROWSER,
                identity = ScopeIdentity.MOVIE_OTT_ANY,
                surfaces = setOf(ScopeSurfaceClass.ANY),
                sites = listOf("*"),
                packages = emptyList(),
                packageMatch = PackageMatchMode.CLASS_RESOLVED
            ),
            ScopeClause(
                id = "i3",
                host = ScopeHost.BROWSER,
                identity = ScopeIdentity.VIDEO_ANY,
                surfaces = setOf(ScopeSurfaceClass.PLAYER_ANY),
                sites = listOf("*"),
                packages = emptyList(),
                packageMatch = PackageMatchMode.CLASS_RESOLVED
            )
        )
        return Expansion(include, emptyList())
    }

    private fun packagePinned(packages: List<String>): Expansion {
        val include = ScopeClause(
            id = "i1",
            host = ScopeHost.ANY_HOST,
            identity = ScopeIdentity.ANY,
            surfaces = setOf(ScopeSurfaceClass.ANY),
            packages = packages,
            packageMatch = PackageMatchMode.PINNED
        )
        return Expansion(listOf(include), emptyList())
    }

    fun defaultSitesForIdentity(identity: ScopeIdentity): List<String> = when (identity) {
        ScopeIdentity.YOUTUBE -> listOf("youtube.com", "m.youtube.com", "youtu.be", "youtube-nocookie.com")
        ScopeIdentity.INSTAGRAM -> listOf("instagram.com", "instagr.am")
        ScopeIdentity.FACEBOOK -> listOf("facebook.com", "fb.com", "fb.watch")
        ScopeIdentity.MESSENGER -> listOf("messenger.com", "m.me")
        ScopeIdentity.TIKTOK -> listOf("tiktok.com", "vm.tiktok.com")
        ScopeIdentity.SNAPCHAT -> listOf("snapchat.com")
        ScopeIdentity.WHATSAPP -> listOf("wa.me", "whatsapp.com")
        else -> emptyList()
    }

    fun defaultPackagesForIdentity(identity: ScopeIdentity): List<String> = when (identity) {
        ScopeIdentity.YOUTUBE -> listOf(VideoPlatformRegistry.YOUTUBE)
        ScopeIdentity.INSTAGRAM -> listOf(VideoPlatformRegistry.INSTAGRAM)
        ScopeIdentity.FACEBOOK -> listOf(VideoPlatformRegistry.FACEBOOK)
        ScopeIdentity.MESSENGER -> listOf("com.facebook.orca")
        ScopeIdentity.TIKTOK -> listOf(VideoPlatformRegistry.TIKTOK)
        ScopeIdentity.SNAPCHAT -> listOf(VideoPlatformRegistry.SNAPCHAT)
        ScopeIdentity.WHATSAPP -> listOf("com.whatsapp")
        else -> emptyList()
    }
}
