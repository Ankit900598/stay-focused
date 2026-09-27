package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.classifier.AiConfidenceGate
import com.phonecodex.app.domain.classifier.ContentClassification
import com.phonecodex.app.domain.model.DecisionType

/**
 * Vision is counsel. Local law wins.
 *
 * Never clear a local BLOCK/LOCK. Never create BLOCK/WARN after Surface Gate PASS.
 * Never convert a WAIT (no real player clock) into a proven ALLOW.
 */
data class VisionMergeResult(
    val applyToOverlay: Boolean,
    val classification: ContentClassification?,
    val reasonCode: String,
    val detail: String
)

object VisionCounselMerge {

    private val confidenceGate = AiConfidenceGate()

    fun merge(
        localDecision: DecisionType?,
        localReasonCode: String,
        surfaceIsPass: Boolean,
        vision: ContentClassification,
        backendUsedImage: Boolean
    ): VisionMergeResult {
        if (surfaceIsPass) {
            return ignored("surface_pass")
        }
        if (localDecision == DecisionType.BLOCK || localDecision == DecisionType.LOCK) {
            return ignored("local_block")
        }
        if (localReasonCode in PROVEN_LOCAL_WINS) {
            return ignored("local_law_wins")
        }
        if (!backendUsedImage) {
            return ignored("backend_image_unused")
        }

        val gated = confidenceGate.apply(vision)
        val waitLike = VisionCaptureGate.isWaitOrDurationUnavailable(localReasonCode)
        val advisory = VisionAdvisoryKinds.normalize(gated.reasonCategory)

        if (!waitLike) {
            return ignored("local_not_wait")
        }

        if (advisory == VisionAdvisoryKinds.UNKNOWN ||
            advisory == VisionAdvisoryKinds.LIKELY_LONG_FORM
        ) {
            return VisionMergeResult(
                applyToOverlay = false,
                classification = gated,
                reasonCode = EnforcementReasonCodes.VISION_COUNSEL,
                detail = "wait_${advisory}_not_applied"
            )
        }

        if (waitLike && gated.decision == DecisionType.ALLOW) {
            return VisionMergeResult(
                applyToOverlay = false,
                classification = gated,
                reasonCode = EnforcementReasonCodes.VISION_COUNSEL,
                detail = "wait_allow_not_applied"
            )
        }

        // Movie guess may WARN. It must never become MEDIA_BLOCK_OVER_MAX (no clock).
        val overlayDecision =
            if (advisory == VisionAdvisoryKinds.LIKELY_MOVIE &&
                (gated.decision == DecisionType.BLOCK || gated.decision == DecisionType.LOCK)
            ) {
                DecisionType.WARN
            } else {
                gated.decision
            }

        val apply = overlayDecision == DecisionType.WARN ||
            overlayDecision == DecisionType.ASK ||
            overlayDecision == DecisionType.BLOCK ||
            overlayDecision == DecisionType.LOCK

        return VisionMergeResult(
            applyToOverlay = apply,
            classification = gated.copy(decision = overlayDecision),
            reasonCode = EnforcementReasonCodes.VISION_COUNSEL,
            detail = "counsel_${advisory}_${overlayDecision.name.lowercase()}"
        )
    }

    private fun ignored(detail: String): VisionMergeResult =
        VisionMergeResult(
            applyToOverlay = false,
            classification = null,
            reasonCode = EnforcementReasonCodes.VISION_EXPERIMENT_SKIPPED,
            detail = detail
        )

    private val PROVEN_LOCAL_WINS = setOf(
        EnforcementReasonCodes.SURFACE_PASS_PASSIVE,
        EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX,
        EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
        EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN,
        EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT,
        EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
        EnforcementReasonCodes.EMERGENCY_ALLOW,
        EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED,
        EnforcementReasonCodes.QUOTA_BLOCK_ADULT,
        EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE,
        EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE
    )
}
