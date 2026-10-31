package com.phonecodex.app.domain.classifier

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ConfirmedSessionPolicy
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.enforcement.SurfaceDetectionResult

enum class ClassifierBlockAuthority {
    NOT_A_BLOCK,
    CONFIRMED_APP_RULE,
    CONFIRMED_CONTENT_RULE,
    NO_MATCHING_CONFIRMED_RULE,
    MODEL_CANNOT_LOCK
}

data class ClassifierActionGateResult(
    val classification: ContentClassification,
    val authority: ClassifierBlockAuthority
)

/** A model block is enforceable only when it matches the policy confirmed for this session. */
object ConfirmedClassifierActionGate {

    fun evaluate(
        classification: ContentClassification,
        packageName: String,
        surface: SurfaceDetectionResult,
        confirmedPolicy: ConfirmedSessionPolicy?
    ): ClassifierActionGateResult {
        if (classification.decision != DecisionType.BLOCK &&
            classification.decision != DecisionType.LOCK
        ) {
            return ClassifierActionGateResult(classification, ClassifierBlockAuthority.NOT_A_BLOCK)
        }

        if (classification.decision == DecisionType.LOCK) {
            return downgrade(
                classification,
                ClassifierBlockAuthority.MODEL_CANNOT_LOCK,
                "A screen classifier can warn, but it cannot lock a commitment."
            )
        }

        val category = classification.reasonCategory?.trim()?.lowercase().orEmpty()
        if (confirmedPolicy == null || classification.verificationIncomplete || category.isBlank()) {
            return downgrade(
                classification,
                ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE,
                UNAUTHORIZED_BLOCK_REASON
            )
        }

        val matchingContentRules = confirmedPolicy.contentRules.filter { rule ->
            matchesContentRule(
                rule = rule,
                category = category,
                usedImage = classification.usedImage,
                packageName = packageName,
                surface = surface,
                confirmedPolicy = confirmedPolicy
            )
        }
        if (matchingContentRules.any {
                it.action == ContentRuleAction.ALLOW || it.action == ContentRuleAction.WARN
            }
        ) {
            return downgrade(
                classification,
                ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE,
                UNAUTHORIZED_BLOCK_REASON
            )
        }

        if (matchingContentRules.any {
                it.action == ContentRuleAction.BLOCK || it.action == ContentRuleAction.AI_DECIDE
            }
        ) {
            return ClassifierActionGateResult(classification, ClassifierBlockAuthority.CONFIRMED_CONTENT_RULE)
        }

        val matchingAiDecideAppRule = confirmedPolicy.appRules.firstOrNull { rule ->
            rule.packageName == packageName && rule.behavior == AppRuleBehavior.AI_DECIDE
        }
        val hasMoreSpecificContentPolicy = matchingAiDecideAppRule != null &&
            confirmedPolicy.contentRules.any { rule ->
                contentRuleTargetsPackage(rule, packageName, confirmedPolicy)
            }
        if (matchingAiDecideAppRule != null &&
            !hasMoreSpecificContentPolicy &&
            category in blockableCategories(classification.usedImage)
        ) {
            return ClassifierActionGateResult(classification, ClassifierBlockAuthority.CONFIRMED_APP_RULE)
        }

        return downgrade(
            classification,
            ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE,
            UNAUTHORIZED_BLOCK_REASON
        )
    }

    private fun matchesContentRule(
        rule: ContentRule,
        category: String,
        usedImage: Boolean,
        packageName: String,
        surface: SurfaceDetectionResult,
        confirmedPolicy: ConfirmedSessionPolicy
    ): Boolean {
        if (!contentRuleTargetsPackage(rule, packageName, confirmedPolicy)) return false
        return when (rule.contentType.trim().lowercase()) {
            "adult_sexual" ->
                matchesDeclaredSurface(rule.surface, surface) &&
                    category == if (usedImage) "adult_signal" else "adult_content"
            "short_form_video" ->
                surface == SurfaceDetectionResult.SHORT_FORM_PLAYER &&
                    matchesDeclaredSurface(rule.surface, surface) &&
                    category == (if (usedImage) "likely_short_form" else "short_form_disallowed") &&
                    !rule.unit.equals("count", ignoreCase = true)
            "social_feed" ->
                surface == SurfaceDetectionResult.SOCIAL_FEED &&
                    matchesDeclaredSurface(rule.surface, surface) &&
                    !usedImage && category == "social_feed"
            else -> false
        }
    }

    private fun matchesDeclaredSurface(
        declaredSurface: String,
        detectedSurface: SurfaceDetectionResult
    ): Boolean = when (declaredSurface.trim().lowercase()) {
        "any" -> true
        "video" -> detectedSurface.isActivePlayer
        "shorts", "reels" -> detectedSurface == SurfaceDetectionResult.SHORT_FORM_PLAYER
        "feed", "social_feed" -> detectedSurface == SurfaceDetectionResult.SOCIAL_FEED
        "messages", "dm" -> detectedSurface == SurfaceDetectionResult.SOCIAL_DM ||
            detectedSurface == SurfaceDetectionResult.MESSAGING_THREAD
        "search" -> detectedSurface == SurfaceDetectionResult.SEARCH
        "playlist" -> false
        else -> false
    }

    private fun contentRuleTargetsPackage(
        rule: ContentRule,
        packageName: String,
        confirmedPolicy: ConfirmedSessionPolicy
    ): Boolean {
        val rulePackage = rule.packageName?.trim().orEmpty()
        if (rulePackage.isNotEmpty()) return rulePackage == packageName

        val ruleLabel = rule.appLabel?.trim().orEmpty()
        if (ruleLabel.isEmpty()) return true

        return confirmedPolicy.appRules.any { appRule ->
            appRule.packageName == packageName &&
                appRule.label.trim().equals(ruleLabel, ignoreCase = true)
        }
    }

    private fun blockableCategories(usedImage: Boolean): Set<String> = if (usedImage) {
        setOf("adult_signal", "likely_short_form")
    } else {
        setOf(
            "adult_content",
            "short_form_disallowed",
            "strict_monk_mode",
            "install_disallowed",
            "social_feed",
            "gaming",
            "shopping"
        )
    }

    private fun downgrade(
        classification: ContentClassification,
        authority: ClassifierBlockAuthority,
        reason: String
    ) = ClassifierActionGateResult(
        classification = classification.copy(
            decision = DecisionType.WARN,
            reason = reason,
            wouldEscalate = true
        ),
        authority = authority
    )

    const val UNAUTHORIZED_BLOCK_REASON =
        "The classifier flagged this screen, but no matching block rule from your confirmed promise authorizes a block."
    const val MODEL_LOCK_REASON =
        "A screen classifier can warn, but it cannot lock a commitment."
}
