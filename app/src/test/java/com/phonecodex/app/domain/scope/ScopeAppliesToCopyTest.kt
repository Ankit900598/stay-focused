package com.phonecodex.app.domain.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeAppliesToCopyTest {

    @Test
    fun everyEnforceableKindProducesNonEmptyPackageFreeCopy() {
        // A representative enforceable scope per kind. UNRESOLVED is tested separately.
        val perKind = mapOf(
            ScopeKind.APP_ONLY to buildScope(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE),
            ScopeKind.BROWSER_ONLY to buildScope(ScopeKind.BROWSER_ONLY, ScopeIdentity.YOUTUBE, sites = listOf("youtube.com")),
            ScopeKind.BROWSER_VIDEO to buildScope(ScopeKind.BROWSER_VIDEO, ScopeIdentity.VIDEO_ANY),
            ScopeKind.CONTENT_ANY_HOST to buildScope(ScopeKind.CONTENT_ANY_HOST, ScopeIdentity.YOUTUBE),
            ScopeKind.VIDEO_CLASS_ANY_HOST to buildScope(ScopeKind.VIDEO_CLASS_ANY_HOST, ScopeIdentity.VIDEO_ANY),
            ScopeKind.SHORT_FORM_ANY_HOST to buildScope(ScopeKind.SHORT_FORM_ANY_HOST, ScopeIdentity.SHORT_FORM_ANY),
            ScopeKind.APP_SHORT_FORM_ONLY to buildScope(ScopeKind.APP_SHORT_FORM_ONLY, ScopeIdentity.YOUTUBE),
            ScopeKind.SOCIAL_MESSAGING_ONLY to buildScope(ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeIdentity.INSTAGRAM),
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY to buildScope(
                ScopeKind.SOCIAL_FEED_AND_REELS_ONLY,
                ScopeIdentity.FACEBOOK,
                companionPackage = "com.facebook.orca"
            ),
            ScopeKind.SITE_SCOPED to buildScope(ScopeKind.SITE_SCOPED, ScopeIdentity.ANY, sites = listOf("nesoacademy.org")),
            ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS to buildScope(ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS, ScopeIdentity.ANY),
            ScopeKind.PACKAGE_PINNED to buildScope(
                ScopeKind.PACKAGE_PINNED,
                ScopeIdentity.ANY,
                packages = listOf("app.netmirror.newtv")
            )
        )
        assertEquals(ScopeKind.values().toSet() - ScopeKind.UNRESOLVED, perKind.keys)
        for ((kind, scope) in perKind) {
            assertNotNull("$kind option label", ScopeAppliesToCopy.optionLabel(scope))
            val copy = ScopeAppliesToCopy.forScope(scope)
            assertTrue("$kind copy must be non-empty", copy.isNotBlank())
            assertFalse(
                "$kind copy leaks a package id: '$copy'",
                ScopeAppliesToCopy.containsPackageId(copy)
            )
            assertNotEquals("$kind must not fall back to unresolved copy", "Not set yet — finish setting up this promise.", copy)
        }
    }

    @Test
    fun unresolvedScopeSaysNotProtecting() {
        val unresolved = EnforcementScope.unresolved(UnresolvedReason.HOST_AMBIGUOUS, listOf("only YouTube"))
        val copy = ScopeAppliesToCopy.forScope(unresolved)
        assertTrue(copy.contains("finish setting up", ignoreCase = true))
        assertFalse(ScopeAppliesToCopy.containsPackageId(copy))
    }

    @Test
    fun nullScopeAlsoSaysNotProtecting() {
        val copy = ScopeAppliesToCopy.forScope(null)
        assertTrue(copy.contains("finish setting up", ignoreCase = true))
    }

    @Test
    fun packageIdInProvidedAppliesToTextIsStripped() {
        val scope = buildScope(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE).copy(
            appliesToText = "YouTube (com.google.android.youtube) only"
        )
        val copy = ScopeAppliesToCopy.forScope(scope)
        assertFalse("leak: '$copy'", ScopeAppliesToCopy.containsPackageId(copy))
    }

    private fun buildScope(
        kind: ScopeKind,
        identity: ScopeIdentity,
        sites: List<String> = emptyList(),
        packages: List<String> = emptyList(),
        companionPackage: String? = null
    ): EnforcementScope {
        val exp = ScopeKindPresets.expand(kind, identity, sites, packages, companionPackage)
        return EnforcementScope(
            version = EnforcementScope.CURRENT_VERSION,
            kind = kind,
            identity = identity,
            include = exp.include,
            exempt = exp.exempt,
            confidence = 0.9,
            appliesToText = null,
            evidenceSpans = listOf("test evidence")
        )
    }
}
