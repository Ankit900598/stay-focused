package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.promise.PromiseIntentRules
import com.phonecodex.app.domain.signals.ContentSignalDetector

enum class EntertainmentBanAction {
    NONE,
    BLOCK,
    ALLOW_STUDY
}

/**
 * Category-first entertainment law. GAME / DATING chips need no AI.
 * Monk fail-closes unknown. Study lectures can still play on media apps.
 */
object EntertainmentBanGate {

    private val signals = ContentSignalDetector()

    fun evaluate(
        packageName: String,
        screenText: String,
        goal: String,
        dossier: AppDossier? = null
    ): EntertainmentBanAction {
        if (!PromiseIntentRules.hasCategoryOrLifestyleBan(goal)) {
            return EntertainmentBanAction.NONE
        }
        if (EntertainmentClassDetector.isMonkExemptPackage(packageName)) {
            return EntertainmentBanAction.NONE
        }
        if (isStudyLecture(packageName, screenText) &&
            AppClassResolver.resolve(packageName, screenText) == AppClass.MEDIA
        ) {
            return EntertainmentBanAction.ALLOW_STUDY
        }
        if (PromiseCategoryLaw.shouldBlock(packageName, screenText, goal, dossier)) {
            return EntertainmentBanAction.BLOCK
        }
        return EntertainmentBanAction.NONE
    }

    fun shouldBlock(
        packageName: String,
        screenText: String,
        goal: String,
        dossier: AppDossier? = null
    ): Boolean = evaluate(packageName, screenText, goal, dossier) == EntertainmentBanAction.BLOCK

    /**
     * Phase 2A: scope preflight has moved to [com.phonecodex.app.domain.scope.ScopeGate],
     * which runs in both live callers (coordinator + AccessibilityService) BEFORE
     * this function. `enforcementScopePackages` and `contentBrands` are kept as
     * `@Deprecated` no-op parameters so the 80+ trace callers compile unchanged.
     * Do not re-introduce [EnforcementScopeLaw.matches] here -- see
     * docs/scope-semantics-v1-phase2-review.md §1.3, §3.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mustEvaluate(
        packageName: String,
        screenText: String,
        goal: String,
        // @Deprecated: Scope handled by ScopeGate in Phase 2A. Kept for source compatibility
        // with the 80+ existing trace callers. Do not read; ScopeGate ran upstream.
        enforcementScopePackages: Collection<String> = emptyList(),
        contentBrands: Collection<String> = emptyList()
    ): Boolean {
        if (EntertainmentClassDetector.isMonkExemptPackage(packageName)) return false
        if (PromiseIntentRules.isLifestyleEntertainmentBan(goal) &&
            !PromiseIntentRules.namesThisAppOnly(goal)
        ) {
            return true
        }
        // Scope-in verdict now comes from ScopeGate; if we got here, ScopeGate said
        // InScope (or monk-overrode OutOfScope). Downstream logic that used to piggy-
        // back on EnforcementScopeLaw.matches for "named scope allow" is preserved.
        return shouldOverrideNamedScopeAllow(packageName, screenText, goal)
    }

    fun shouldOverrideNamedScopeAllow(
        packageName: String,
        screenText: String,
        goal: String
    ): Boolean {
        if (!PromiseIntentRules.hasCategoryOrLifestyleBan(goal)) return false
        if (PromiseIntentRules.namesThisAppOnly(goal)) return false
        if (EntertainmentClassDetector.isMonkExemptPackage(packageName)) return false
        if (PromiseIntentRules.isLifestyleEntertainmentBan(goal)) return true
        return PromiseCategoryLaw.listingOrClassIsBanned(
            AppClassResolver.resolve(packageName, screenText),
            goal
        )
    }

    private fun isStudyLecture(packageName: String, screenText: String): Boolean {
        val surface = SurfaceDetector.detect(packageName, screenText)
        val detected = signals.detect(packageName, screenText)
        return surface.isActivePlayer &&
            (detected.isLikelySearchOrLecture || detected.isChromeStudyLike)
    }
}
