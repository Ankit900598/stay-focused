package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise

/**
 * Start-gating law for confirmation.
 *
 * Ghost `clarificationRequired` with no A/B/C options must not lock a concrete
 * policy. The unrecognized-choice warning is only legal after a real invalid pick.
 */
object PromiseClarificationLaw {

    const val UNRECOGNIZED_CLARIFICATION_CHOICE =
        "That clarification choice was not recognized. Pick an option again."

    /** Backend `optionVerification` when the tapped option could not be proven. */
    const val CHOICE_FAIL_CLOSED = "unrecognized_fail_closed"

    fun isUnrecognizedClarificationChoice(text: String): Boolean =
        text.contains("clarification choice was not recognized", ignoreCase = true)

    fun shouldShowUnrecognizedClarificationChoice(
        selectedClarificationOptionId: String?,
        optionIds: Collection<String>
    ): Boolean {
        val selected = selectedClarificationOptionId?.trim().orEmpty()
        if (selected.isEmpty()) return false
        val ids = optionIds.map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return false
        return ids.none { it.equals(selected, ignoreCase = true) }
    }

    /**
     * Session clock present + min-length (shorter videos blocked) present.
     * Real ambiguity (no duration / no clocks / real options) stays fail-closed.
     */
    fun isConcreteClockPolicy(draft: FocusPromise): Boolean {
        if (draft.sessionDurationMinutes <= 0) return false
        val min = PromiseContentRuleSupport.minVideoLengthBlockMinutes(draft)
        if (min == null || min <= 0) return false
        val hasShorterBlockRule = draft.contentRules.any { rule ->
            rule.action == ContentRuleAction.BLOCK &&
                (rule.operator == "lt" || rule.operator == "lte")
        }
        val blob = listOfNotNull(draft.rawText, draft.understoodSummary)
            .joinToString(" ")
            .lowercase()
        val textBlockShorter = blob.contains("shorter") && blob.contains("block")
        val onlyAllowLonger = ONLY_ALLOW_LONGER.containsMatchIn(draft.rawText)
        return hasShorterBlockRule || textBlockShorter || onlyAllowLonger
    }

    private val ONLY_ALLOW_LONGER = Regex(
        """\b(?:only\s+allow|allow\s+only|only\s+(?:videos?|youtube))\b""",
        RegexOption.IGNORE_CASE
    )

    fun apply(
        draft: FocusPromise,
        selectedClarificationOptionId: String? = null,
        stripUnrecognizedWarnings: Boolean = true
    ): FocusPromise {
        val selected = selectedClarificationOptionId?.trim().orEmpty()
        if (draft.choiceVerificationFailed || draft.typedScopeBlocksStart) {
            // Unproven pick or unresolved v1 scope; no local law may unlock Start.
            return draft.copy(clarificationRequired = true, canStartCommitment = false)
        }
        if (draft.appRuleScopeConflict) {
            return draft.copy(
                clarificationRequired = true,
                canStartCommitment = false,
                clarificationQuestion = draft.clarificationQuestion?.takeIf { it.isNotBlank() }
                    ?: EffectivePromisePolicy.conflictQuestion(draft)
            )
        }
        val working = injectCompoundIfNeeded(draft, selected)
        val optionIds = working.clarificationOptions.map { it.id }
        val keepUnrecognized = shouldShowUnrecognizedClarificationChoice(
            selectedClarificationOptionId,
            optionIds
        )
        val warnings = if (!stripUnrecognizedWarnings) {
            draft.warnings
        } else {
            draft.warnings.filter {
                keepUnrecognized || !isUnrecognizedClarificationChoice(it)
            }
        }
        val checkThis = if (!stripUnrecognizedWarnings) {
            draft.checkThisNotes
        } else {
            draft.checkThisNotes.filter {
                keepUnrecognized || !isUnrecognizedClarificationChoice(it)
            }
        }

        if (keepUnrecognized) {
            return working.copy(
                warnings = warnings,
                checkThisNotes = checkThis,
                clarificationRequired = true,
                canStartCommitment = false
            )
        }

        return clearGhostClarification(
            working.copy(
                warnings = warnings,
                checkThisNotes = checkThis
            )
        )
    }

    internal fun injectCompoundIfNeeded(draft: FocusPromise, selected: String): FocusPromise {
        val hit = CompoundIntentLaw.clarificationFor(draft.rawText) ?: return draft
        if (selected.isNotEmpty()) {
            val canStart = if (draft.requiresStrongerConfirmation) {
                draft.canStartCommitment
            } else {
                true
            }
            return draft.copy(
                clarificationRequired = false,
                canStartCommitment = canStart,
                understoodSummary = draft.understoodSummary ?: hit.understood
            )
        }
        if (draft.clarificationOptions.isNotEmpty()) return draft
        return draft.copy(
            clarificationRequired = true,
            clarificationQuestion = hit.question,
            clarificationOptions = hit.options,
            understoodSummary = hit.understood,
            canStartCommitment = false
        )
    }

    fun clearGhostClarification(draft: FocusPromise): FocusPromise {
        if (draft.choiceVerificationFailed) return draft
        // Concrete clocks say nothing about WHERE the promise applies.
        if (draft.typedScopeBlocksStart) return draft
        if (draft.appRuleScopeConflict) return draft
        if (draft.clarificationOptions.isNotEmpty()) return draft
        if (!isConcreteClockPolicy(draft)) return draft
        return draft.copy(
            clarificationRequired = false,
            clarificationQuestion = null,
            canStartCommitment = if (draft.requiresStrongerConfirmation) {
                draft.canStartCommitment
            } else {
                true
            }
        )
    }
}
