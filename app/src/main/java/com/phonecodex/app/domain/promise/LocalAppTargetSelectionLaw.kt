package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.InstalledApp
import com.phonecodex.app.domain.model.LocalAppTargetSelectionCandidate
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.PackageMatchMode
import com.phonecodex.app.domain.scope.ScopeCanonicalizer
import com.phonecodex.app.domain.scope.ScopeClause
import com.phonecodex.app.domain.scope.ScopeHost
import com.phonecodex.app.domain.scope.ScopeIdentity
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeResolution
import com.phonecodex.app.domain.scope.ScopeSurfaceClass
import com.phonecodex.app.domain.scope.UnresolvedReason
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

internal object LocalAppTargetSelectionLaw {

    private val packagePattern = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$""")

    fun candidate(response: CompiledPromiseResponse, promiseText: String): LocalAppTargetSelectionCandidate? {
        val candidate = response.localAppTargetSelection ?: return null
        val label = candidate.label.trim()
        if (!isSafeLabel(label) || !hasExactMention(label, promiseText)) return null
        if (response.scopeContract != CompiledPromiseMapper.SCOPE_CONTRACT_V1) return null
        if (response.canStartCommitment != false || !response.clarificationRequired) return null
        if (response.requiresStrongerConfirmation || response.isPermanentCommitment) return null
        if (!response.scopeKind.isNullOrBlank() || !response.optionVerification.isNullOrBlank()) return null
        if (!response.clarificationQuestion.isNullOrBlank() || response.clarificationOptions.isNotEmpty()) return null
        if (response.contentRules.isNotEmpty()) return null
        if (response.allowedSummaries.isNotEmpty() || response.blockedSummaries.isNotEmpty()) return null
        if (response.conditionalSummaries.isNotEmpty() || response.confirmationPreview.isNotEmpty()) return null
        if (response.cautionMessages.isNotEmpty() || response.interpretationNotes.isNotEmpty()) return null
        if (response.scopePackages.isNotEmpty() || response.contentBrands.isNotEmpty()) return null
        if (response.surfaceScope.isNotEmpty() || response.contentScope.isNotEmpty()) return null
        val confirmation = response.userFacingConfirmation
        if (confirmation != null && (
                confirmation.allowed.isNotEmpty() ||
                    confirmation.blocked.isNotEmpty() ||
                    confirmation.checkThis.isNotEmpty() ||
                    confirmation.safetyNotes.isNotEmpty()
                )
        ) return null

        val rawScope = response.scopeJson ?: return null
        val rawScopeObject = runCatching { JSONObject(rawScope) }.getOrNull() ?: return null
        if (rawScopeObject.optInt("version", -1) != EnforcementScope.CURRENT_VERSION) return null
        if (rawScopeObject.optString("kind") != ScopeKind.UNRESOLVED.name) return null
        if (rawScopeObject.optString("unresolvedReason") != UnresolvedReason.MISSING_EVIDENCE.name) return null
        val parsedScope = ScopeCanonicalizer.fromCompilerJson(rawScope, promiseText)
        if (parsedScope.kind != ScopeKind.UNRESOLVED ||
            parsedScope.unresolvedReason != UnresolvedReason.MISSING_EVIDENCE
        ) return null

        val appRule = response.suggestedAppRules.singleOrNull() ?: return null
        if (!packagePattern.matches(appRule.packageName)) return null
        if (appRule.behavior.trim().uppercase(Locale.ROOT) != AppRuleBehavior.BLOCK.name) return null
        if (appRule.label.trim() != label) return null
        if (EffectivePromisePolicy.classifyAppScope(appRule.scope) != EffectivePromisePolicy.AppScope.WHOLE_APP) {
            return null
        }
        return candidate.copy(label = label)
    }

    fun exactMatches(apps: List<InstalledApp>, label: String): List<InstalledApp> {
        if (!isSafeLabel(label)) return emptyList()
        val requested = normalizeLabel(label)
        return apps.asSequence()
            .filter { packagePattern.matches(it.packageName) && isSafeLabel(it.label) }
            .filter { normalizeLabel(it.label) == requested }
            .distinctBy { it.packageName }
            .sortedBy { normalizeLabel(it.label) }
            .toList()
    }

    fun canSelect(app: InstalledApp): Boolean =
        packagePattern.matches(app.packageName) &&
            isSafeLabel(app.label) &&
            !SafeAppsCatalog.isNonOverridableDefaultPackage(app.packageName)

    fun bindSelectedApp(draft: FocusPromise, selectedApp: InstalledApp): FocusPromise? {
        val candidate = draft.localAppTargetSelection ?: return null
        if (!isSafeLabel(candidate.label) || !hasExactMention(candidate.label, draft.rawText)) return null
        if (!canSelect(selectedApp) || normalizeLabel(selectedApp.label) != normalizeLabel(candidate.label)) {
            return null
        }
        if (!isOnlyMissingTargetGate(draft)) return null

        val scope = ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                kind = ScopeKind.PACKAGE_PINNED,
                resolution = ScopeResolution.EXCLUSIVE,
                identity = ScopeIdentity.ANY,
                include = listOf(
                    ScopeClause(
                        id = "i1",
                        host = ScopeHost.ANY_HOST,
                        identity = ScopeIdentity.ANY,
                        surfaces = setOf(ScopeSurfaceClass.ANY),
                        packages = listOf(selectedApp.packageName),
                        packageMatch = PackageMatchMode.PINNED
                    )
                ),
                confidence = 1.0,
                evidenceSpans = listOf(candidate.label)
            )
        )
        if (!scope.isEnforceable || scope.kind != ScopeKind.PACKAGE_PINNED) return null

        val bound = draft.copy(
            suggestedAppRules = listOf(
                AppRule(
                    packageName = selectedApp.packageName,
                    label = selectedApp.label.trim(),
                    behavior = AppRuleBehavior.BLOCK
                )
            ),
            scopePackages = listOf(selectedApp.packageName),
            userFacingAppliesTo = "Only the ${selectedApp.label.trim()} app",
            clarificationQuestion = null,
            warnings = draft.warnings.filterNot {
                it == CompiledPromiseMapper.TYPED_SCOPE_UNRESOLVED_CAUTION
            },
            clarificationRequired = false,
            canStartCommitment = true,
            enforcementScope = scope,
            localAppTargetSelection = null
        )
        return bound.takeUnless { it.needsClarification || it.appRuleScopeConflict }
    }

    private fun isOnlyMissingTargetGate(draft: FocusPromise): Boolean {
        val scope = draft.enforcementScope ?: return false
        return draft.scopeContractV1 &&
            scope.kind == ScopeKind.UNRESOLVED &&
            scope.unresolvedReason == UnresolvedReason.MISSING_EVIDENCE &&
            draft.canStartCommitment == false &&
            draft.clarificationRequired &&
            draft.clarificationQuestion.isNullOrBlank() &&
            draft.clarificationOptions.isEmpty() &&
            draft.scopeKind.isNullOrBlank() &&
            draft.legacyContractGap == null &&
            !draft.choiceVerificationFailed &&
            !draft.requiresStrongerConfirmation &&
            !draft.isPermanentCommitment &&
            draft.contentRules.isEmpty() &&
            draft.scopePackages.isEmpty() &&
            draft.contentBrands.isEmpty() &&
            draft.surfaceScope.isEmpty() &&
            draft.contentScope.isEmpty() &&
            draft.unrepresentableAppScopes.isEmpty() &&
            draft.allowedSummaries.isEmpty() &&
            draft.blockedSummaries.isEmpty() &&
            draft.conditionalSummaries.isEmpty() &&
            draft.checkThisNotes.isEmpty() &&
            draft.safetyNotes.isEmpty() &&
            draft.warnings.all { it == CompiledPromiseMapper.TYPED_SCOPE_UNRESOLVED_CAUTION } &&
            draft.suggestedAppRules.singleOrNull()?.let { rule ->
                rule.behavior == AppRuleBehavior.BLOCK &&
                    rule.label.trim() == draft.localAppTargetSelection?.label
            } == true
    }

    private fun isSafeLabel(label: String): Boolean =
        label.isNotBlank() &&
            label.length <= 64 &&
            label == label.trim() &&
            label.none(Char::isISOControl) &&
            !ScopeAppliesToPackageGuard.containsPackageId(label)

    private fun hasExactMention(label: String, promiseText: String): Boolean {
        val regex = Regex(
            "(?<![\\p{L}\\p{N}])${Regex.escape(label)}(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE
        )
        return regex.containsMatchIn(promiseText)
    }

    private fun normalizeLabel(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC)
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

    private object ScopeAppliesToPackageGuard {
        private val packageId = Regex("""\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b""")
        fun containsPackageId(value: String): Boolean = packageId.containsMatchIn(value)
    }
}
