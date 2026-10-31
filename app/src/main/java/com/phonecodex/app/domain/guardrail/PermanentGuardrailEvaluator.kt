package com.phonecodex.app.domain.guardrail

import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.GuardrailMatch
import com.phonecodex.app.domain.model.PermanentGuardrail

class PermanentGuardrailEvaluator {

    fun evaluate(
        packageName: String,
        screenText: String,
        guardrails: List<PermanentGuardrail>,
        nowMillis: Long = System.currentTimeMillis()
    ): GuardrailMatch? {
        if (AdultContentLaw.shouldSkipPackage(packageName)) {
            return null
        }

        val normalizedText = screenText.lowercase()

        guardrails.forEach { guardrail ->
            if (!guardrail.enabled || guardrail.expiresAtMillis?.let { nowMillis >= it } == true) {
                return@forEach
            }
            if (guardrail.blockedPackages.contains(packageName)) {
                return GuardrailMatch(
                    guardrailId = guardrail.id,
                    guardrailName = guardrail.name,
                    decision = DecisionType.BLOCK,
                    matchedStrongSignals = listOf("package:$packageName"),
                    matchedWeakSignals = emptyList(),
                    reason = buildReason(
                        guardrailName = guardrail.name,
                        strongSignals = listOf("package:$packageName"),
                        weakSignals = emptyList()
                    )
                )
            }

            val match = when (guardrail.id) {
                PORN_GUARDRAIL_ID -> evaluatePornGuardrail(guardrail, packageName, screenText)
                else -> evaluateGenericKeywordGuardrail(guardrail, normalizedText)
            }

            if (match != null) {
                return match
            }
        }

        return null
    }

    private fun evaluatePornGuardrail(
        guardrail: PermanentGuardrail,
        packageName: String,
        screenText: String
    ): GuardrailMatch? {
        val verdict = AdultContentLaw.inspect(packageName, screenText)
        val decision = when (verdict.decision) {
            AdultContentLaw.Decision.BLOCK -> DecisionType.BLOCK
            AdultContentLaw.Decision.WARN -> DecisionType.WARN
            AdultContentLaw.Decision.NONE -> return null
        }
        return GuardrailMatch(
            guardrailId = guardrail.id,
            guardrailName = guardrail.name,
            decision = decision,
            matchedStrongSignals = verdict.strongSites,
            matchedWeakSignals = verdict.weakWords,
            reason = buildReason(guardrail.name, verdict.strongSites, verdict.weakWords)
        )
    }

    private fun evaluateGenericKeywordGuardrail(
        guardrail: PermanentGuardrail,
        normalizedText: String
    ): GuardrailMatch? {
        val matchedKeywords = guardrail.blockedKeywords.filter { keyword ->
            normalizedText.contains(keyword)
        }

        if (matchedKeywords.isEmpty()) {
            return null
        }

        return GuardrailMatch(
            guardrailId = guardrail.id,
            guardrailName = guardrail.name,
            decision = DecisionType.BLOCK,
            matchedStrongSignals = matchedKeywords,
            matchedWeakSignals = emptyList(),
            reason = buildReason(guardrail.name, matchedKeywords, emptyList())
        )
    }

    private fun buildReason(
        guardrailName: String,
        strongSignals: List<String>,
        weakSignals: List<String>
    ): String {
        val matchedParts = mutableListOf<String>()
        if (strongSignals.isNotEmpty()) {
            matchedParts.add("strong: ${strongSignals.joinToString(", ")}")
        }
        if (weakSignals.isNotEmpty()) {
            matchedParts.add("weak: ${weakSignals.joinToString(", ")}")
        }

        val matchedLabel = matchedParts.joinToString("; ")
        return "Permanent guardrail: $guardrailName (matched: $matchedLabel)"
    }

    companion object {
        private const val PORN_GUARDRAIL_ID = "porn_guardrail"
    }
}
