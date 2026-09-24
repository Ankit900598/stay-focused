package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.EnforcementScopeLaw
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.StudyWorldSettings

/**
 * Phase 2A shim: builds a typed [EnforcementScope] from the three loose legacy
 * fields on [StudyWorldSettings]:
 *
 *   - enforcementScopePackages   (List<String>)
 *   - enforcementContentBrands   (List<String>)
 *   - enforcementScopeKind       (String? — "youtube_content", "youtube_app_only",
 *                                 "youtube_only", "youtube_shorts_only", …)
 *
 * The output is fed to [ScopeMatcher] via [ScopeGate], so both live callers
 * (coordinator + AccessibilityService) share one matcher. This is deliberately
 * behaviour-preserving: for every combination of legacy fields a caller has
 * historically seen, [ScopeMatcher] on the adapted scope returns the same
 * "in scope / out of scope" verdict as [EnforcementScopeLaw.matches]. That
 * equivalence is asserted as a property test in
 * `app/src/test/java/com/phonecodex/app/domain/scope/ScopeGateEquivalenceTest.kt`.
 *
 * Notable design choices:
 * 1. Empty packages + empty brands legacy state today means "unrestricted"
 *    ([EnforcementScopeLaw.matches] returns true). We preserve that with a
 *    single "all axes ANY" include clause of [ScopeKind.PACKAGE_PINNED] (see
 *    LEGACY_ALL below). We deliberately re-use PACKAGE_PINNED as the carrier
 *    rather than invent a new legacy-only enum value; the include-clause axes
 *    encode the semantics, [ScopeKind] is just the outer label.
 * 2. `youtube_only` is intentionally conflated with `youtube_app_only` here,
 *    exactly matching today's [EnforcementScopeLaw.isYouTubeAppOnlyScopeKind]
 *    behaviour. Un-conflating that is a compiler-side change belonging to
 *    Phase 2B / Phase 3 — do NOT "fix" it in this adapter.
 * 3. Package IDs live in the include clause's `packages` list, which the
 *    matcher consumes but which [ScopeAppliesToCopy] refuses to render.
 *
 * Kept in strict layering: pure Kotlin; no Android imports, no I/O.
 */
object LegacyScopeAdapter {

    /** Adapt a full settings object; convenience wrapper over [from] with fields. */
    fun from(settings: StudyWorldSettings): EnforcementScope = from(
        packages = settings.enforcementScopePackages,
        brands = settings.enforcementContentBrands,
        scopeKind = settings.enforcementScopeKind
    )

    fun from(
        packages: Collection<String>,
        brands: Collection<String>,
        scopeKind: String?
    ): EnforcementScope {
        val pkgList = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val brandSet = brands
            .map { EnforcementScopeLaw.normalizeBrand(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val kindLower = scopeKind?.trim()?.lowercase()

        // (1) YouTube "app only" family — official / shorts-only / conflated youtube_only.
        //     EnforcementScopeLaw treats these three kinds identically; so do we.
        if (kindLower != null && EnforcementScopeLaw.isYouTubeAppOnlyScopeKind(kindLower)) {
            val effectivePackages = when {
                pkgList.isNotEmpty() -> pkgList
                else -> listOf(VideoPlatformRegistry.YOUTUBE)
            }
            val exp = ScopeKindPresets.expand(
                kind = ScopeKind.APP_ONLY,
                identity = ScopeIdentity.YOUTUBE,
                packages = effectivePackages
            )
            return EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.APP_ONLY,
                identity = ScopeIdentity.YOUTUBE,
                include = exp.include,
                exempt = exp.exempt,
                confidence = LEGACY_CONFIDENCE,
                appliesToText = null,
                evidenceSpans = listOf(LEGACY_EVIDENCE_MARKER)
            )
        }

        // (2) YouTube content brand — official YT app, browser youtube.com, NewPipe,
        //     embeds. Matches EnforcementScopeLaw.matches when brands contains "youtube".
        val youtubeContent = EnforcementScopeLaw.BRAND_YOUTUBE in brandSet ||
            kindLower == EnforcementScopeLaw.SCOPE_YOUTUBE_CONTENT
        if (youtubeContent) {
            val exp = ScopeKindPresets.expand(
                kind = ScopeKind.CONTENT_ANY_HOST,
                identity = ScopeIdentity.YOUTUBE
            )
            // The include clause's package list stays empty (CLASS_RESOLVED handles it),
            // so any browser + youtube.com URL also matches -- mirroring the old law's
            // isYouTubeSurface() branch.
            val extraPackages = pkgList.filter { it !in exp.include.first().packages }
            val include = if (extraPackages.isEmpty()) {
                exp.include
            } else {
                // Any additional legacy package (rare) becomes a PACKAGE_PINNED
                // sibling include so those packages still match without needing YT.
                exp.include + ScopeClause(
                    id = "i2",
                    host = ScopeHost.ANY_HOST,
                    identity = ScopeIdentity.ANY,
                    surfaces = setOf(ScopeSurfaceClass.ANY),
                    packages = extraPackages,
                    packageMatch = PackageMatchMode.PINNED
                )
            }
            return EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.CONTENT_ANY_HOST,
                identity = ScopeIdentity.YOUTUBE,
                include = include,
                exempt = emptyList(),
                confidence = LEGACY_CONFIDENCE,
                appliesToText = null,
                evidenceSpans = listOf(LEGACY_EVIDENCE_MARKER)
            )
        }

        // (3) Package-pinned only (no brands, no yt-kind).
        if (pkgList.isNotEmpty()) {
            val exp = ScopeKindPresets.expand(
                kind = ScopeKind.PACKAGE_PINNED,
                packages = pkgList
            )
            return EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.PACKAGE_PINNED,
                identity = null,
                include = exp.include,
                exempt = emptyList(),
                confidence = LEGACY_CONFIDENCE,
                appliesToText = null,
                evidenceSpans = listOf(LEGACY_EVIDENCE_MARKER)
            )
        }

        // (4) Empty packages AND empty brands — unrestricted. Old law returned true.
        //     One all-ANY include clause preserves that as InScope for every screen.
        return LEGACY_ALL
    }

    /**
     * "Everything matches" — the neutral scope. Kind is [ScopeKind.PACKAGE_PINNED]
     * only to make it a valid enforceable scope object; the include clause uses
     * every-axis-ANY so [ScopeMatcher] returns InScope for every screen.
     */
    val LEGACY_ALL: EnforcementScope = EnforcementScope(
        version = EnforcementScope.CURRENT_VERSION,
        kind = ScopeKind.PACKAGE_PINNED,
        identity = null,
        include = listOf(
            ScopeClause(
                id = "legacy_all",
                host = ScopeHost.ANY_HOST,
                identity = ScopeIdentity.ANY,
                surfaces = setOf(ScopeSurfaceClass.ANY),
                packages = emptyList(),
                packageMatch = PackageMatchMode.CLASS_RESOLVED
            )
        ),
        exempt = emptyList(),
        confidence = LEGACY_CONFIDENCE,
        appliesToText = null,
        evidenceSpans = listOf(LEGACY_EVIDENCE_MARKER)
    )

    /**
     * Callers upstream of scope should treat this scope as adapted-legacy, not
     * compiler-emitted, when deciding whether to run clarification law.
     */
    fun isLegacyAdapted(scope: EnforcementScope?): Boolean =
        scope?.evidenceSpans?.contains(LEGACY_EVIDENCE_MARKER) == true

    private const val LEGACY_CONFIDENCE = 1.0
    private const val LEGACY_EVIDENCE_MARKER = "__legacy_adapted__"
}
