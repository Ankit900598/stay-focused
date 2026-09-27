package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.SurfaceDetection

/**
 * The single authority for "does this screen fall under this promise?".
 *
 * Pure function — no Android imports, no I/O, no capture, no side effects.
 * Match order (deterministic, docs/scope-semantics-v1.md §2.3):
 *
 * 1. scope == null || scope.kind == UNRESOLVED   -> Unresolved
 * 2. any exempt clause matches                   -> Exempt(clauseId)
 * 3. any include clause matches                  -> InScope(clauseId)
 * 4. otherwise                                   -> OutOfScope
 *
 * A clause matches iff every axis (host, identity, surfaces, sites, packages) matches.
 * Empty / default values on an axis mean "don't care".
 *
 * Phase 1: this is dead code in the app. Coordinator wiring is Phase 2.
 */
object ScopeMatcher {

    fun match(
        packageName: String,
        screenText: String,
        detection: SurfaceDetection,
        scope: EnforcementScope?
    ): ScopeMatch {
        if (scope == null || scope.kind == ScopeKind.UNRESOLVED || scope.version != EnforcementScope.CURRENT_VERSION) {
            return ScopeMatch.Unresolved(scope?.unresolvedReason)
        }
        if (!scope.isEnforceable) {
            return ScopeMatch.Unresolved(scope.unresolvedReason)
        }
        val ctx = MatchContext(
            packageName = packageName.trim(),
            screenText = screenText,
            surfaceClass = detection.surface.scopeClass,
            typed = !LegacyScopeAdapter.isLegacyAdapted(scope)
        )
        for (clause in scope.exempt) {
            if (clauseMatches(clause, ctx)) return ScopeMatch.Exempt(clause.id)
        }
        for (clause in scope.include) {
            if (clauseMatches(clause, ctx)) return ScopeMatch.InScope(clause.id)
        }
        if (ctx.typed) {
            for (clause in scope.include) {
                val reason = indeterminateReason(clause, ctx) ?: continue
                return ScopeMatch.Indeterminate(clause.id, reason)
            }
        }
        return ScopeMatch.OutOfScope
    }

    fun clauseMatches(clause: ScopeClause, ctx: MatchContext): Boolean {
        if (!hostAxisMatches(clause, ctx)) return false
        if (!identityAxisMatches(clause, ctx)) return false
        if (!surfaceAxisMatches(clause, ctx)) return false
        if (!siteAxisMatches(clause, ctx)) return false
        if (!packageAxisMatches(clause, ctx)) return false
        return true
    }

    /**
     * Typed scopes only. A clause that failed to match is uncertain — not OutOfScope —
     * when the only failing evidence is (a) an UNKNOWN surface under a player-only
     * clause, or (b) a browser-looking package that is not a listed browser.
     */
    private fun indeterminateReason(clause: ScopeClause, ctx: MatchContext): IndeterminateReason? {
        val nameOnlyBrowser = HostResolver.looksLikeBrowserByNameOnly(ctx.packageName)
        val browserAxisInPlay = clause.host == ScopeHost.BROWSER ||
            (clause.host == ScopeHost.ANY_HOST && clause.identity == ScopeIdentity.VIDEO_ANY)
        val surfaceUnknown = ctx.surfaceClass == ScopeSurfaceClass.ANY &&
            clause.surfaces.isNotEmpty() &&
            ScopeSurfaceClass.ANY !in clause.surfaces
        val surfaceOk = surfaceUnknown || surfaceAxisMatches(clause, ctx)
        if (!surfaceOk || !siteAxisMatches(clause, ctx) || !packageAxisMatches(clause, ctx)) return null

        if (nameOnlyBrowser && browserAxisInPlay) {
            val identityOk = clause.identity == ScopeIdentity.VIDEO_ANY || identityAxisMatches(clause, ctx)
            if (identityOk) return IndeterminateReason.HOST_UNVERIFIED
        }
        if (surfaceUnknown && hostAxisMatches(clause, ctx) && identityAxisMatches(clause, ctx)) {
            return IndeterminateReason.SURFACE_UNKNOWN
        }
        return null
    }

    // --- axes ---------------------------------------------------------------

    private fun hostAxisMatches(clause: ScopeClause, ctx: MatchContext): Boolean {
        if (clause.host == ScopeHost.ANY_HOST) return true
        if (ctx.typed && clause.host == ScopeHost.BROWSER) {
            return HostResolver.isVerifiedBrowser(ctx.packageName)
        }
        return HostResolver.host(ctx.packageName, ctx.screenText) == clause.host
    }

    private fun identityAxisMatches(clause: ScopeClause, ctx: MatchContext): Boolean {
        if (clause.identity == ScopeIdentity.ANY) return true
        return IdentityResolver.matches(clause.identity, ctx.packageName, ctx.screenText)
    }

    /**
     * A clause listing [ScopeSurfaceClass.PLAYER_ANY] means the whole player family
     * (`PLAYER_ANY | PLAYER_SHORT | PLAYER_LONG`). Detection maps concrete short/long
     * players to `PLAYER_SHORT` / `PLAYER_LONG` — never back to `PLAYER_ANY` — so an
     * unqualified `PLAYER_ANY` in a preset clause must widen to cover them or the
     * clause silently misses every real playback. Every other class is exact-match:
     * a clause listing `PLAYER_SHORT` must NOT match a long-form player.
     * See docs/scope-semantics-v1-phase2-review.md §2 F1.
     */
    private fun surfaceAxisMatches(clause: ScopeClause, ctx: MatchContext): Boolean {
        if (clause.surfaces.isEmpty()) return true
        if (ScopeSurfaceClass.ANY in clause.surfaces) return true
        if (ctx.surfaceClass in clause.surfaces) return true
        return ScopeSurfaceClass.PLAYER_ANY in clause.surfaces && ctx.surfaceClass.isPlayerFamily()
    }

    private fun ScopeSurfaceClass.isPlayerFamily(): Boolean =
        this == ScopeSurfaceClass.PLAYER_ANY ||
            this == ScopeSurfaceClass.PLAYER_SHORT ||
            this == ScopeSurfaceClass.PLAYER_LONG

    private fun siteAxisMatches(clause: ScopeClause, ctx: MatchContext): Boolean {
        if (clause.sites.isEmpty()) return true
        return SiteResolver.hostMatchesAny(ctx.screenText, clause.sites)
    }

    /**
     * PINNED with a non-empty list: package must be literally listed. Exception —
     * when a browser package is viewing an in-scope site, sites+identity carry the match.
     * CLASS_RESOLVED with a non-empty list: package must be listed OR class-resolvable
     * to the identity.
     * Empty list on either mode: no package restriction; identity/host/surface carry.
     */
    private fun packageAxisMatches(clause: ScopeClause, ctx: MatchContext): Boolean {
        val listed = clause.packages
        return when (clause.packageMatch) {
            PackageMatchMode.PINNED -> {
                if (listed.isEmpty()) return true
                if (ctx.packageName in listed) return true
                // Browser viewing a listed site: identity/site already matched, package is
                // the browser (not the app in `listed`). This is example 2 in the design.
                HostResolver.host(ctx.packageName, ctx.screenText) == ScopeHost.BROWSER &&
                    clause.sites.isNotEmpty() &&
                    SiteResolver.hostMatchesAny(ctx.screenText, clause.sites)
            }
            PackageMatchMode.CLASS_RESOLVED -> {
                if (listed.isEmpty()) return true
                if (ctx.packageName in listed) return true
                IdentityResolver.classResolvedIncludes(clause.identity, ctx.packageName, ctx.screenText)
            }
        }
    }

    data class MatchContext(
        val packageName: String,
        val screenText: String,
        val surfaceClass: ScopeSurfaceClass,
        /** Compiler-emitted scope (not [LegacyScopeAdapter] output): strict host evidence. */
        val typed: Boolean = false
    )
}
