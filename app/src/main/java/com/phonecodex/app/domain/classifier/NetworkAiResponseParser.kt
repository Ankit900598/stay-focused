package com.phonecodex.app.domain.classifier

import com.phonecodex.app.domain.model.DecisionType
import org.json.JSONObject
import java.util.Locale

/**
 * Parses POST /classify JSON from PhoneCodex backend (Azure OpenAI v04).
 */
object NetworkAiResponseParser {

    private val SUPPORTED_DECISIONS = setOf(
        DecisionType.ALLOW,
        DecisionType.WARN,
        DecisionType.BLOCK,
        DecisionType.LOCK
    )
    private val TEXT_REASON_CATEGORIES = setOf(
        "study_aligned",
        "user_allowed_entertainment",
        "limit_not_reached",
        "limit_reached",
        "adult_content",
        "short_form_disallowed",
        "strict_monk_mode",
        "install_disallowed",
        "tamper_attempt",
        "ambiguous",
        "safe_app",
        "emergency_or_system",
        "neutral_navigation",
        "social_feed",
        "gaming",
        "shopping",
        "install_page_productivity"
    )
    private val VISION_REASON_CATEGORIES = setOf(
        "likely_short_form",
        "likely_long_form",
        "likely_movie",
        "unknown",
        "adult_signal"
    )

    fun parse(raw: String): ContentClassification? {
        return try {
            val json = JSONObject(raw)
            val decisionName = json.optString("decision", "")
            val decision = runCatching { DecisionType.valueOf(decisionName) }.getOrNull()
            if (decision !in SUPPORTED_DECISIONS) {
                return null
            }
            val confidence = (json.opt("confidence") as? Number)?.toDouble() ?: return null
            if (!confidence.isFinite() || confidence !in 0.0..1.0) {
                return null
            }

            val usedImage = json.optBoolean("usedImage", false)
            val rawCategory = json.optString("reasonCategory")
                .trim()
                .lowercase(Locale.ROOT)
            val allowedCategories = if (usedImage) VISION_REASON_CATEGORIES else TEXT_REASON_CATEGORIES
            val categoryValid = rawCategory in allowedCategories
            val verificationIncomplete = json.optBoolean("verificationIncomplete", false) || !categoryValid
            val reasonCategory = if (categoryValid) {
                rawCategory
            } else if (usedImage) {
                "unknown"
            } else {
                "ambiguous"
            }
            val parsedReason = json.optString("reason", "No reason provided.")

            val metaJson = json.optJSONObject("meta")
            val meta = metaJson?.let {
                ClassificationBackendMeta(
                    provider = it.optString("provider").ifBlank { null },
                    deployment = it.optString("deployment").ifBlank { null },
                    promptVersion = it.optString("promptVersion").ifBlank { null },
                    latencyMs = if (it.has("latencyMs")) it.optLong("latencyMs") else null
                )
            }

            ContentClassification(
                decision = if (verificationIncomplete) DecisionType.WARN else decision!!,
                confidence = confidence,
                reason = if (verificationIncomplete) {
                    "Could not verify this screen safely"
                } else {
                    parsedReason
                },
                source = AiConfidenceGate.NETWORK_AI_SOURCE,
                reasonCategory = reasonCategory,
                wouldEscalate = json.optBoolean("would_escalate", false) || !categoryValid,
                backendMeta = meta,
                usedImage = usedImage,
                whatOnScreen = json.optString("whatOnScreen").ifBlank { null },
                verificationIncomplete = verificationIncomplete
            )
        } catch (_: Exception) {
            null
        }
    }
}
