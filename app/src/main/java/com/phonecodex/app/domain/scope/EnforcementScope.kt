package com.phonecodex.app.domain.scope

/**
 * Scope Semantics v1 — the one typed algebra for "does this screen fall under this promise?".
 *
 * PolicyEngine is law. Scope is a filter *in front of* the gates. Scope never carries a
 * verdict on its own; verdicts stay in the existing gates (media length, quota,
 * entertainment ban, adult guardrail, etc.).
 *
 * Design contract: [docs/scope-semantics-v1.md] sections 2, 3, 5, 8.
 *
 * Phase 1: this model is dead code in the app. Consumed by tests + serialization only.
 * Phase 2 wires it into the coordinator.
 */

/** One of eleven named user-facing scope shapes. */
enum class ScopeKind {
    APP_ONLY,
    BROWSER_ONLY,
    CONTENT_ANY_HOST,
    VIDEO_CLASS_ANY_HOST,
    SHORT_FORM_ANY_HOST,
    SOCIAL_MESSAGING_ONLY,
    SOCIAL_FEED_AND_REELS_ONLY,
    SITE_SCOPED,
    HOST_WITH_CATEGORY_EXEMPTIONS,
    PACKAGE_PINNED,
    UNRESOLVED
}

/** Where the screen lives. Browser includes WebView shells. */
enum class ScopeHost {
    NATIVE_APP,
    BROWSER,
    ANY_HOST
}

/**
 * Content family, not package. `*_ANY` values are class-resolved via
 * [com.phonecodex.app.domain.enforcement.VideoPlatformRegistry] and other resolvers.
 */
enum class ScopeIdentity {
    YOUTUBE,
    INSTAGRAM,
    FACEBOOK,
    MESSENGER,
    TIKTOK,
    SNAPCHAT,
    WHATSAPP,
    VIDEO_ANY,
    SHORT_FORM_ANY,
    MOVIE_OTT_ANY,
    ADULT_ANY,
    SOCIAL_ANY,
    ANY
}

/**
 * Grouping over the existing 20-value [com.phonecodex.app.domain.enforcement.SurfaceDetectionResult].
 * The mapping lives in `SurfaceScopeMapping.kt` — a pure extension. Do not add detection
 * heuristics here.
 */
enum class ScopeSurfaceClass {
    PLAYER_ANY,
    PLAYER_SHORT,
    PLAYER_LONG,
    FEED,
    DM_LIST,
    DM_THREAD,
    SEARCH,
    BROWSE,
    INSTALL,
    PAYMENT,
    SYSTEM,
    ANY
}

/**
 * Package-list semantics inside one clause.
 * - [PINNED]: literal set only. An unknown app is out of this clause.
 * - [CLASS_RESOLVED]: the listed set is joined with identity class heuristics —
 *   an unlisted app that is class-resolvable to the identity is still in the clause.
 */
enum class PackageMatchMode {
    PINNED,
    CLASS_RESOLVED
}

/**
 * How this scope interacts with other active promises.
 * - [EXCLUSIVE]: anything not in the include set becomes `OutOfScope` -> ENFORCEMENT_SCOPE_ALLOW.
 * - [ADDITIVE]: this promise widens scope for stacked commitments. Permanent guardrails
 *   remain outside scope entirely (§8).
 */
enum class ScopeResolution {
    EXCLUSIVE,
    ADDITIVE
}

/**
 * Why scope is [ScopeKind.UNRESOLVED]. Drives the clarification question (§5.2).
 */
enum class UnresolvedReason {
    HOST_AMBIGUOUS,
    IDENTITY_SCOPE_AMBIGUOUS,
    BRAND_VS_CATEGORY,
    SURFACE_AMBIGUOUS,
    COMPANION_APP_AMBIGUOUS,
    SITE_LIST_UNRESOLVED,
    EXEMPTION_UNCLEAR,
    COMPOUND_SCOPE_CONFLICT,
    MISSING_EVIDENCE,
    UNKNOWN_KIND,
    BAD_VERSION,
    MALFORMED_CLAUSE,
    PACKAGE_LEAK_IN_APPLIES_TO
}

/**
 * One selector clause. All axes are AND-ed; a null / default value on an axis means
 * "don't care".
 *
 * `id` must be unique inside the scope object and MUST appear verbatim in every decision
 * log — this is what makes an in/out-of-scope decision debuggable.
 *
 * `packages` is INTERNAL only. It never renders in user-facing copy.
 */
data class ScopeClause(
    val id: String,
    val host: ScopeHost = ScopeHost.ANY_HOST,
    val identity: ScopeIdentity = ScopeIdentity.ANY,
    val surfaces: Set<ScopeSurfaceClass> = setOf(ScopeSurfaceClass.ANY),
    val sites: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val packageMatch: PackageMatchMode = PackageMatchMode.CLASS_RESOLVED
)

/**
 * The one versioned scope object attached to a promise. `version=1` is Scope Semantics v1.
 * Unknown/mismatched version, unknown kind, malformed clauses => rewritten to
 * [ScopeKind.UNRESOLVED] by [ScopeSerialization]; Start is refused by Phase 2's clarification law.
 */
data class EnforcementScope(
    val version: Int = 1,
    val kind: ScopeKind,
    val resolution: ScopeResolution = ScopeResolution.EXCLUSIVE,
    val identity: ScopeIdentity? = null,
    val include: List<ScopeClause> = emptyList(),
    val exempt: List<ScopeClause> = emptyList(),
    val confidence: Double = 0.0,
    /** Category-level user copy. Never contains package IDs. */
    val appliesToText: String? = null,
    val unresolvedReason: UnresolvedReason? = null,
    /** Verbatim substrings of the user promise that pinned this scope. */
    val evidenceSpans: List<String> = emptyList(),
    /** Optional alternatives for clarification A/B/C. */
    val candidateScopes: List<EnforcementScope> = emptyList()
) {
    val isEnforceable: Boolean
        get() = version == 1 &&
            kind != ScopeKind.UNRESOLVED &&
            include.isNotEmpty()

    companion object {
        const val CURRENT_VERSION = 1

        /** Fail-open enforcement, fail-closed trust. Nothing matches; Start is blocked. */
        fun unresolved(reason: UnresolvedReason, evidenceSpans: List<String> = emptyList()) =
            EnforcementScope(
                version = CURRENT_VERSION,
                kind = ScopeKind.UNRESOLVED,
                resolution = ScopeResolution.EXCLUSIVE,
                include = emptyList(),
                exempt = emptyList(),
                confidence = 0.0,
                appliesToText = null,
                unresolvedReason = reason,
                evidenceSpans = evidenceSpans
            )
    }
}

/** Result of matching one screen snapshot against one scope. */
sealed interface ScopeMatch {
    /** In one include clause. Existing gates may now run. */
    data class InScope(val clauseId: String) : ScopeMatch

    /** In an exempt clause. Enforce nothing on this screen. */
    data class Exempt(val clauseId: String) : ScopeMatch

    /** Not in any include clause. ENFORCEMENT_SCOPE_ALLOW in Phase 2. */
    data object OutOfScope : ScopeMatch

    /** Scope was UNRESOLVED. Enforce nothing; Home should say "not protecting". */
    data class Unresolved(val reason: UnresolvedReason?) : ScopeMatch
}
