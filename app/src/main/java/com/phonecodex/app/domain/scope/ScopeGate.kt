package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.EntertainmentClassDetector
import com.phonecodex.app.domain.enforcement.SurfaceDetection
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.PromiseIntentRules

/**
 * The one preflight both live callers -- [com.phonecodex.app.domain.enforcement.PromiseEnforcementCoordinator]
 * (unit-test law) and [com.phonecodex.app.accessibility.PhoneCodexAccessibilityService.inspectAndEvaluate]
 * (real phone path) -- must run before the direct gate chain
 * (blank tree, media length, entertainment ban, short-form quota, app rule).
 *
 * Two outputs:
 *   - [ScopeGateOutcome.match]     -- the typed [ScopeMatch] verdict
 *   - [ScopeGateOutcome.monkOverridesScope] -- whether an [OutOfScope] result
 *     should still be forced through the gates because the promise is a lifestyle
 *     / no-entertainment monk ban that fails-closed on unknown apps
 *
 * Callers must handle the outcome themselves:
 *
 *   Unresolved -> ALLOW SCOPE_UNRESOLVED_NO_ENFORCE     (scope isn't trusted)
 *   Exempt     -> ALLOW SCOPE_EXEMPT_ALLOW              (in scope but exempted)
 *   OutOfScope && !monkOverride -> appRule BLOCK ? BLOCK : ALLOW ENFORCEMENT_SCOPE_ALLOW
 *   otherwise (InScope or monk-override OutOfScope) -> continue existing gates
 *
 * PolicyEngine remains law: [ScopeGate] never produces a verdict on its own; it
 * gates whether the existing gates are allowed to run. See
 * docs/scope-semantics-v1.md §2.3 and docs/scope-semantics-v1-phase2-review.md §3.
 *
 * Purity: no Android imports beyond the pure `enforcement` package. `SurfaceDetector.detect`
 * is a deterministic string parser (already used from coordinator + tests).
 */
object ScopeGate {

    data class ScopeGateOutcome(
        val match: ScopeMatch,
        val monkOverridesScope: Boolean,
        val scopeKind: ScopeKind,
        val scopeClauseId: String?
    ) {
        /**
         * True when the outcome is a definitive scope verdict the caller must honour
         * (Unresolved / Exempt / OutOfScope without monk-override). False means the
         * caller should continue the downstream gate chain unchanged.
         */
        val shortCircuits: Boolean
            get() = when (match) {
                is ScopeMatch.Unresolved -> true
                is ScopeMatch.Exempt -> true
                ScopeMatch.OutOfScope -> !monkOverridesScope
                is ScopeMatch.InScope -> false
            }
    }

    /**
     * Convenience: caller supplies the raw pieces; the gate builds detection and
     * chooses the effective scope (explicit `settings.enforcementScope` overrides
     * the [LegacyScopeAdapter] fallback so Phase 2B can enable the new path per-promise
     * without touching call sites).
     */
    fun evaluate(
        packageName: String,
        screenText: String,
        goal: String,
        settings: StudyWorldSettings
    ): ScopeGateOutcome = evaluate(
        packageName = packageName,
        screenText = screenText,
        detection = SurfaceDetector.detect(packageName, screenText),
        goal = goal,
        scope = settings.enforcementScope ?: LegacyScopeAdapter.from(settings)
    )

    fun evaluate(
        packageName: String,
        screenText: String,
        detection: SurfaceDetection,
        goal: String,
        scope: EnforcementScope
    ): ScopeGateOutcome {
        val match = ScopeMatcher.match(packageName, screenText, detection, scope)
        val monk = monkOverridesScope(packageName, goal)
        val clauseId = when (match) {
            is ScopeMatch.InScope -> match.clauseId
            is ScopeMatch.Exempt -> match.clauseId
            else -> null
        }
        return ScopeGateOutcome(
            match = match,
            monkOverridesScope = monk,
            scopeKind = scope.kind,
            scopeClauseId = clauseId
        )
    }

    /**
     * Monk / lifestyle-ban promises deliberately fail-closed on unknown apps even
     * when scope would otherwise say OutOfScope -- otherwise "monk mode" would
     * miss any package we haven't listed. Preserves the historical
     * `EntertainmentBanGate.mustEvaluate` semantic exactly:
     *
     *   1. monk-exempt packages (contacts, phone, launcher, banking, camera, safe
     *      apps, assistants) are NEVER overridden -- they get the normal OutOfScope
     *      ALLOW so a monk promise doesn't silently break the dialer.
     *   2. `only this app` / `lock this app` promises are intentionally narrow --
     *      the override is off there too.
     *
     * See docs/scope-semantics-v1-phase2-review.md §1.3 and §3 (F3).
     */
    fun monkOverridesScope(packageName: String, goal: String): Boolean {
        if (EntertainmentClassDetector.isMonkExemptPackage(packageName)) return false
        if (PromiseIntentRules.namesThisAppOnly(goal)) return false
        return PromiseIntentRules.isLifestyleEntertainmentBan(goal)
    }
}
