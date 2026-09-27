package com.phonecodex.app.domain.scope

/**
 * Compiler scope -> the one clause set [ScopeKindPresets] defines for its kind.
 *
 * The model picks a kind and its parameters (identity, sites, packages); it never
 * authors enforcement clauses. Model-written clauses are discarded and rebuilt, so
 * a "browser video" scope cannot smuggle in `surfaces=ANY` and block Chrome Search.
 * Parameters a kind cannot use safely -> UNRESOLVED, never a broader scope.
 *
 * Mirrors `canonicalizeScope` in backend/promiseCompilerService.js; the
 * cross-language fixture test keeps both in lockstep.
 */
object ScopeCanonicalizer {

    private val NAMED_IDENTITIES = setOf(
        ScopeIdentity.YOUTUBE,
        ScopeIdentity.INSTAGRAM,
        ScopeIdentity.FACEBOOK,
        ScopeIdentity.MESSENGER,
        ScopeIdentity.TIKTOK,
        ScopeIdentity.SNAPCHAT,
        ScopeIdentity.WHATSAPP
    )
    private val SOCIAL_IDENTITIES = NAMED_IDENTITIES - ScopeIdentity.YOUTUBE + ScopeIdentity.SOCIAL_ANY
    private val SHORT_FORM_APP_IDENTITIES = setOf(
        ScopeIdentity.YOUTUBE,
        ScopeIdentity.INSTAGRAM,
        ScopeIdentity.FACEBOOK,
        ScopeIdentity.TIKTOK,
        ScopeIdentity.SNAPCHAT
    )
    private val SITE_PATTERN = Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+$""")
    private val PACKAGE_PATTERN = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$""")

    fun canonicalize(scope: EnforcementScope): EnforcementScope {
        if (scope.version != EnforcementScope.CURRENT_VERSION) {
            return EnforcementScope.unresolved(UnresolvedReason.BAD_VERSION, scope.evidenceSpans)
        }
        if (scope.kind == ScopeKind.UNRESOLVED) {
            return scope.copy(
                include = emptyList(),
                exempt = emptyList(),
                appliesToText = null,
                unresolvedReason = scope.unresolvedReason ?: UnresolvedReason.MISSING_EVIDENCE,
                candidateScopes = scope.candidateScopes.map(::canonicalize)
            )
        }
        if (!scope.isEnforceable) {
            return EnforcementScope.unresolved(UnresolvedReason.MALFORMED_CLAUSE, scope.evidenceSpans)
        }
        val identity = scope.identity ?: scope.include.first().identity
        val sites = scope.include.flatMap { it.sites }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val packages = scope.include.flatMap { it.packages }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        fun fail(reason: UnresolvedReason) = EnforcementScope.unresolved(reason, scope.evidenceSpans)

        val canonicalIdentity: ScopeIdentity?
        val expansion: ScopeKindPresets.Expansion
        when (scope.kind) {
            ScopeKind.APP_ONLY, ScopeKind.CONTENT_ANY_HOST -> {
                if (identity !in NAMED_IDENTITIES) return fail(UnresolvedReason.IDENTITY_SCOPE_AMBIGUOUS)
                canonicalIdentity = identity
                expansion = ScopeKindPresets.expand(scope.kind, identity)
            }
            ScopeKind.BROWSER_ONLY -> {
                if (!sites.all { SITE_PATTERN.matches(it) }) return fail(UnresolvedReason.SITE_LIST_UNRESOLVED)
                val named = identity in NAMED_IDENTITIES
                if (!named && (identity != ScopeIdentity.ANY || sites.isEmpty())) {
                    return fail(UnresolvedReason.SITE_LIST_UNRESOLVED)
                }
                canonicalIdentity = identity
                expansion = ScopeKindPresets.expand(ScopeKind.BROWSER_ONLY, identity, sites = sites)
            }
            ScopeKind.BROWSER_VIDEO, ScopeKind.VIDEO_CLASS_ANY_HOST -> {
                canonicalIdentity = ScopeIdentity.VIDEO_ANY
                expansion = ScopeKindPresets.expand(scope.kind)
            }
            ScopeKind.SHORT_FORM_ANY_HOST -> {
                canonicalIdentity = ScopeIdentity.SHORT_FORM_ANY
                expansion = ScopeKindPresets.expand(scope.kind)
            }
            ScopeKind.APP_SHORT_FORM_ONLY -> {
                if (identity !in SHORT_FORM_APP_IDENTITIES) return fail(UnresolvedReason.IDENTITY_SCOPE_AMBIGUOUS)
                canonicalIdentity = identity
                expansion = ScopeKindPresets.expand(scope.kind, identity)
            }
            ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeKind.SOCIAL_FEED_AND_REELS_ONLY -> {
                if (identity !in SOCIAL_IDENTITIES) return fail(UnresolvedReason.IDENTITY_SCOPE_AMBIGUOUS)
                canonicalIdentity = identity
                expansion = ScopeKindPresets.expand(scope.kind, identity)
            }
            ScopeKind.SITE_SCOPED -> {
                if (sites.isEmpty() || !sites.all { SITE_PATTERN.matches(it) }) {
                    return fail(UnresolvedReason.SITE_LIST_UNRESOLVED)
                }
                canonicalIdentity = ScopeIdentity.ANY
                expansion = ScopeKindPresets.expand(ScopeKind.SITE_SCOPED, sites = sites)
            }
            ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS -> {
                canonicalIdentity = null
                expansion = ScopeKindPresets.expand(scope.kind)
            }
            ScopeKind.PACKAGE_PINNED -> {
                if (packages.isEmpty() || !packages.all { PACKAGE_PATTERN.matches(it) }) {
                    return fail(UnresolvedReason.MALFORMED_CLAUSE)
                }
                canonicalIdentity = null
                expansion = ScopeKindPresets.expand(ScopeKind.PACKAGE_PINNED, packages = packages)
            }
            ScopeKind.UNRESOLVED -> return fail(UnresolvedReason.MISSING_EVIDENCE)
        }
        return EnforcementScope(
            version = EnforcementScope.CURRENT_VERSION,
            kind = scope.kind,
            resolution = scope.resolution,
            identity = canonicalIdentity,
            include = expansion.include,
            exempt = expansion.exempt,
            confidence = scope.confidence,
            appliesToText = null,
            unresolvedReason = null,
            evidenceSpans = scope.evidenceSpans,
            candidateScopes = emptyList()
        )
    }

    /** Parse at a trust boundary and canonicalize. Never partial, never broader. */
    fun fromCompilerJson(raw: String?, userPromise: String?): EnforcementScope =
        canonicalize(ScopeSerialization.fromJsonWithUserPromise(raw, userPromise))
}
