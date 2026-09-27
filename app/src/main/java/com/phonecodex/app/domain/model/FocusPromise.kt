package com.phonecodex.app.domain.model

import com.phonecodex.app.domain.promise.EffectivePromisePolicy
import com.phonecodex.app.domain.scope.EnforcementScope

/**
 * Local preview of a compiled promise.
 *
 * [sessionDurationMinutes] is how long the commitment runs.
 * [contentRules] are intra-app / length / surface rules — never confuse them with session length.
 * [clarificationQuestion] non-null means: do not start enforcement yet.
 *
 * Human summary lists are for the confirmation card (no package names).
 * When Azure Promise Compiler lands, map its JSON into this type and keep the UI unchanged.
 */
data class FocusPromise(
    val rawText: String,
    val sessionDurationMinutes: Int,
    val allowedKeywords: List<String>,
    val blockedKeywords: List<String>,
    val strictness: StrictnessLevel,
    val suggestedAppRules: List<AppRule>,
    val contentRules: List<ContentRule> = emptyList(),
    /**
     * Package-exclusive scope. Never show raw in user-facing copy.
     * Empty unless the user named a specific app ("YouTube app only", NetMirror, …).
     */
    val scopePackages: List<String> = emptyList(),
    /**
     * Content brands (youtube, …). Separate from [scopePackages].
     * "YouTube video" → youtube brand, not the official package alone.
     */
    val contentBrands: List<String> = emptyList(),
    /** Alias of [contentBrands] from compiler DTO `surfaceScope`. */
    val surfaceScope: List<String> = emptyList(),
    /** Alias of [contentBrands] from compiler DTO `contentScope`. */
    val contentScope: List<String> = emptyList(),
    /** Compiler scope kind: youtube_content | youtube_app_only | youtube_only | … */
    val scopeKind: String? = null,
    val clarificationQuestion: String? = null,
    val warnings: List<String> = emptyList(),
    val allowedSummaries: List<String> = emptyList(),
    val blockedSummaries: List<String> = emptyList(),
    val conditionalSummaries: List<String> = emptyList(),
    val clarificationOptions: List<ClarificationOption> = emptyList(),
    val clarificationRequired: Boolean = false,
    /** Server gate when present; null for local offline preview. */
    val canStartCommitment: Boolean? = null,
    /** Backend could not verify the tapped clarification option; Start stays blocked. */
    val choiceVerificationFailed: Boolean = false,
    val requiresStrongerConfirmation: Boolean = false,
    val isPermanentCommitment: Boolean = false,
    val understoodSummary: String? = null,
    val userFacingTime: String? = null,
    val userFacingAppliesTo: String? = null,
    val checkThisNotes: List<String> = emptyList(),
    val safetyNotes: List<String> = emptyList(),
    /**
     * Backend `scopeContract == "v1"`: this promise must carry an enforceable typed
     * scope before Start. False for legacy compiles (v07/v08) and offline previews.
     */
    val scopeContractV1: Boolean = false,
    /** Canonical typed scope for v1 compiles; null on the legacy path. */
    val enforcementScope: EnforcementScope? = null,
    /** Labels of apps whose rule named a surface scope we cannot enforce; Start stays blocked. */
    val unrepresentableAppScopes: List<String> = emptyList(),
    /** Backend `meta.promptVersion` verbatim; null for offline previews. Never inferred. */
    val compilerPromptVersion: String? = null,
    /** [com.phonecodex.app.domain.promise.LegacyScopeContractGuard] reason; Start stays blocked. */
    val legacyContractGap: String? = null
) {
    /** A v1 promise whose typed scope is missing or unresolved cannot start. */
    val typedScopeBlocksStart: Boolean
        get() = scopeContractV1 && enforcementScope?.isEnforceable != true

    /** WHERE (scope) and WHAT (app/content rules) disagree; Start stays blocked. */
    val appRuleScopeConflict: Boolean
        get() = EffectivePromisePolicy.conflictReason(this) != null

    /** Alias used by older call sites that meant session duration. */
    val durationMinutes: Int
        get() = sessionDurationMinutes

    val needsClarification: Boolean
        get() = when {
            clarificationRequired -> true
            choiceVerificationFailed -> true
            typedScopeBlocksStart -> true
            legacyContractGap != null -> true
            appRuleScopeConflict -> true
            clarificationOptions.isNotEmpty() && canStartCommitment != true -> true
            !clarificationQuestion.isNullOrBlank() && clarificationOptions.isEmpty() -> true
            else -> false
        }

    /** Product alias for warnings shown on the confirmation card. */
    val cautionMessages: List<String>
        get() = warnings

    /** Alias for content duration / surface rules. */
    val contentDurationRules: List<ContentRule>
        get() = contentRules
}

/** A/B/C clarification choice from Promise Compiler v07+. */
data class ClarificationOption(
    val id: String,
    val label: String,
    val description: String,
    val recommended: Boolean = false,
    val policyPreview: String? = null
)
