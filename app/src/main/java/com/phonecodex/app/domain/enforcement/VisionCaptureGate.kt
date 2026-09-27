package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog

/**
 * Decides whether a single downscaled JPEG may be requested.
 * Pure domain — no Android, no image bytes.
 *
 * Capture is legal only when:
 * - [VisionExperiment.isActive]
 * - session is active
 * - local law is WAIT / duration-unavailable (not PASS, not proven BLOCK)
 * - package/text is not a forbidden private surface
 */
data class VisionCaptureInput(
    val nowEpochMs: Long,
    val visionExperimentEnabled: Boolean,
    val hasActiveSession: Boolean,
    val packageName: String,
    val screenText: String,
    val localReasonCode: String,
    val localDecision: DecisionType? = null,
    val surfaceIsPass: Boolean = false,
    /** True when a real CURRENT_PLAYER clock was parsed — vision must not run. */
    val hasCurrentPlayerClock: Boolean = false
)

data class VisionCaptureVerdict(
    val shouldCapture: Boolean,
    val reasonCode: String,
    val detail: String
)

object VisionCaptureGate {

    fun evaluate(input: VisionCaptureInput): VisionCaptureVerdict {
        if (!input.visionExperimentEnabled) {
            return skipped("toggle_off")
        }
        if (!VisionExperiment.isCalendarWindowOpen(input.nowEpochMs)) {
            return skipped("window_expired")
        }
        if (!input.hasActiveSession) {
            return skipped("no_active_session")
        }
        if (input.surfaceIsPass) {
            return skipped("surface_pass")
        }
        if (input.hasCurrentPlayerClock) {
            return skipped("current_player_clock")
        }
        if (isProvenLocalBlock(input.localDecision, input.localReasonCode)) {
            return skipped("local_block")
        }
        if (input.localReasonCode == EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT) {
            return skipped("current_player_clock")
        }
        if (isEmergencyOrForbiddenSurface(input.packageName, input.screenText)) {
            return skipped("forbidden_or_emergency_surface")
        }
        if (!isWaitOrDurationUnavailable(input.localReasonCode)) {
            return skipped("local_not_wait")
        }
        return VisionCaptureVerdict(
            shouldCapture = true,
            reasonCode = EnforcementReasonCodes.VISION_COUNSEL,
            detail = "wait_or_duration_unavailable"
        )
    }

    fun isEmergencyOrForbiddenSurface(packageName: String, screenText: String): Boolean {
        val pkg = packageName.lowercase()
        if (SafeAppsCatalog.isDefaultPackage(pkg)) return true
        if (FORBIDDEN_PACKAGE_MARKERS.any { marker -> pkg.contains(marker) }) return true
        val text = screenText.lowercase()
        if (FORBIDDEN_TEXT_MARKERS.any { marker -> text.contains(marker) }) return true
        return false
    }

    fun isWaitOrDurationUnavailable(reasonCode: String): Boolean =
        reasonCode == EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK ||
            reasonCode == EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE

    private fun isProvenLocalBlock(
        localDecision: DecisionType?,
        reasonCode: String
    ): Boolean {
        if (localDecision == DecisionType.BLOCK || localDecision == DecisionType.LOCK) {
            return true
        }
        return reasonCode in PROVEN_BLOCK_CODES
    }

    private fun skipped(detail: String): VisionCaptureVerdict =
        VisionCaptureVerdict(
            shouldCapture = false,
            reasonCode = EnforcementReasonCodes.VISION_EXPERIMENT_SKIPPED,
            detail = detail
        )

    private val PROVEN_BLOCK_CODES = setOf(
        EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX,
        EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
        EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN,
        EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
        EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED,
        EnforcementReasonCodes.QUOTA_BLOCK_ADULT,
        EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE,
        EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE,
        EnforcementReasonCodes.APP_RULE_BLOCK,
        EnforcementReasonCodes.CONTENT_SIGNAL_BLOCK,
        EnforcementReasonCodes.EMERGENCY_ALLOW
    )

    private val FORBIDDEN_PACKAGE_MARKERS = listOf(
        "dialer",
        ".phone",
        "settings",
        "mms",
        "messaging",
        "sms",
        "bank",
        "hdfc",
        "icici",
        "kotak",
        "axis",
        "paytm",
        "phonepe",
        "bhim",
        "paisa",
        "nbu.paisa",
        "authenticator",
        "lastpass",
        "bitwarden",
        "1password",
        "keepass",
        "password"
    )

    private val FORBIDDEN_TEXT_MARKERS = listOf(
        "otp",
        "one-time password",
        "one time password",
        "verification code",
        "password",
        "passcode",
        "upi pin",
        "enter pin",
        "cvv",
        "netbanking",
        "ifsc",
        "account number",
        "otp sms"
    )
}
