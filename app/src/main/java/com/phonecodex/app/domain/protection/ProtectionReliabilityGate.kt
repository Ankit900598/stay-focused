package com.phonecodex.app.domain.protection

import com.phonecodex.app.domain.diagnostics.BackendHealthProbe
import com.phonecodex.app.domain.diagnostics.BackendProbeKind

enum class ProtectionReliabilityLevel {
    PROTECTED,
    NOT_PROTECTING,
    NEEDS_SETUP
}

enum class ProtectionPrimaryAction {
    NONE,
    OPEN_ACCESSIBILITY
}

data class ProtectionReliabilityInput(
    val accessibilityEnabled: Boolean,
    /** Fresh service lease from RuntimeDiagStore, not the persisted raw flag. */
    val accessibilityAlive: Boolean,
    val lastEventMillis: Long,
    val lastEventPackage: String?,
    val lastEventReason: String?,
    val hasActiveCommitment: Boolean,
    val permanentGuardrailOn: Boolean,
    val backendKind: BackendProbeKind,
    val backendLoopback: Boolean = true,
    val nowMillis: Long,
    /** Active commitment whose saved typed scope is unreadable (SCOPE_PERSISTENCE_CORRUPT). */
    val commitmentScopeUnverified: Boolean = false
)

data class ProtectionReliabilityUiModel(
    val level: ProtectionReliabilityLevel,
    val headline: String,
    val summary: String,
    val accessibilityLine: String,
    val commitmentLine: String,
    val guardrailLine: String,
    val backendLine: String,
    val lastEventLine: String,
    val primaryAction: ProtectionPrimaryAction,
    val primaryActionLabel: String?,
    val nothingEnforced: Boolean,
    val showAdbReverseHint: Boolean,
    val adbReverseHint: String,
    val checkLogLine: String,
    val detailsExpandedByDefault: Boolean,
    val idleGreeting: String
) {
    val isProtected: Boolean get() = level == ProtectionReliabilityLevel.PROTECTED
}

/**
 * Proves the enforcement loop is alive before Chrome/YouTube tests.
 * Does not start a promise. Does not change PolicyEngine or emergency allows.
 */
object ProtectionReliabilityGate {

    const val HEADLINE_PROTECTED = "Protected"
    const val HEADLINE_NOT_PROTECTING = "Not protecting"
    const val HEADLINE_NEEDS_SETUP = "Needs setup"
    const val NOTHING_ENFORCED = "Nothing is enforced."
    const val ADB_REVERSE = "adb reverse tcp:8787 tcp:8787"
    const val ACTION_TURN_ON = "Turn on Accessibility"
    const val ACTION_RECONNECT = "Reconnect Accessibility"
    const val SUMMARY_NEEDS_SETUP =
        "After install or update, Accessibility is off. You are not protected until you turn it on."
    const val GREETING_NEEDS_SETUP =
        "Turn on Accessibility first. You are not protected yet."
    const val GREETING_NOT_PROTECTING = "Strlix is not protecting this phone."
    const val GREETING_READY = "Ready to keep a promise?"
    const val SUMMARY_ACCESSIBILITY_NOT_CONNECTED =
        "PhoneCodex Accessibility is enabled but not connected. Phone rules are not being enforced."
    const val SUMMARY_COMMITMENT_ACTIVE = "Your active commitment is being enforced."
    const val SUMMARY_COMMITMENT_AND_GUARDRAIL_ACTIVE =
        "Your active commitment and permanent life rules are being enforced."
    const val SUMMARY_GUARDRAIL_ONLY_ACTIVE =
        "A permanent life rule is active. No timed commitment is running."
    const val COMMITMENT_SCOPE_UNVERIFIED = "Active, scope unverified (SCOPE_PERSISTENCE_CORRUPT)"
    const val SUMMARY_SCOPE_UNVERIFIED =
        "This commitment's saved scope could not be read, so Strlix is not enforcing it. " +
            "Emergency calls and permanent rules still work. End it and start it again."

    fun evaluate(input: ProtectionReliabilityInput): ProtectionReliabilityUiModel {
        val ageMs = AccessibilityHeartbeatLaw.ageMs(input.nowMillis, input.lastEventMillis)
        val loopAlive = input.accessibilityEnabled && input.accessibilityAlive
        val hasLaw = input.hasActiveCommitment || input.permanentGuardrailOn
        val nothingEnforced = loopAlive && !hasLaw
        val scopeUnverified = input.hasActiveCommitment && input.commitmentScopeUnverified

        val level = when {
            !input.accessibilityEnabled -> ProtectionReliabilityLevel.NEEDS_SETUP
            !loopAlive -> ProtectionReliabilityLevel.NOT_PROTECTING
            !hasLaw -> ProtectionReliabilityLevel.NOT_PROTECTING
            scopeUnverified -> ProtectionReliabilityLevel.NOT_PROTECTING
            else -> ProtectionReliabilityLevel.PROTECTED
        }

        val headline = when (level) {
            ProtectionReliabilityLevel.PROTECTED -> HEADLINE_PROTECTED
            ProtectionReliabilityLevel.NOT_PROTECTING -> HEADLINE_NOT_PROTECTING
            ProtectionReliabilityLevel.NEEDS_SETUP -> HEADLINE_NEEDS_SETUP
        }

        val summary = when {
            !input.accessibilityEnabled -> SUMMARY_NEEDS_SETUP
            !loopAlive ->
                SUMMARY_ACCESSIBILITY_NOT_CONNECTED
            nothingEnforced ->
                "$NOTHING_ENFORCED Start a promise or turn on a life rule."
            scopeUnverified -> SUMMARY_SCOPE_UNVERIFIED
            input.hasActiveCommitment && input.permanentGuardrailOn ->
                SUMMARY_COMMITMENT_AND_GUARDRAIL_ACTIVE
            input.hasActiveCommitment -> SUMMARY_COMMITMENT_ACTIVE
            else -> SUMMARY_GUARDRAIL_ONLY_ACTIVE
        }
        val idleGreeting = when (level) {
            ProtectionReliabilityLevel.NEEDS_SETUP -> GREETING_NEEDS_SETUP
            ProtectionReliabilityLevel.NOT_PROTECTING -> GREETING_NOT_PROTECTING
            ProtectionReliabilityLevel.PROTECTED -> GREETING_READY
        }

        val accessibilityLine = when {
            !input.accessibilityEnabled -> "Off"
            loopAlive -> "Alive"
            else -> "Enabled but not alive"
        }
        val commitmentLine = when {
            scopeUnverified -> COMMITMENT_SCOPE_UNVERIFIED
            input.hasActiveCommitment -> "Active"
            else -> "None"
        }
        val guardrailLine = if (input.permanentGuardrailOn) "On" else "Off"
        val backendLine = backendLine(input.backendKind)
        val lastEventLine = AccessibilityHeartbeatLaw.formatAge(ageMs)

        val needsAccessibilityAction =
            !input.accessibilityEnabled || (input.accessibilityEnabled && !loopAlive)
        val primaryAction = if (needsAccessibilityAction) {
            ProtectionPrimaryAction.OPEN_ACCESSIBILITY
        } else {
            ProtectionPrimaryAction.NONE
        }
        val primaryActionLabel = when {
            !input.accessibilityEnabled -> ACTION_TURN_ON
            !loopAlive -> ACTION_RECONNECT
            else -> null
        }

        val showAdb = input.backendKind == BackendProbeKind.BRIDGE_MISSING &&
            input.backendLoopback

        val checkLogLine = listOf(
            headline,
            "a11y=$accessibilityLine",
            "commitment=$commitmentLine",
            "guardrail=$guardrailLine",
            "backend=$backendLine",
            lastEventLine
        ).joinToString("; ")

        return ProtectionReliabilityUiModel(
            level = level,
            headline = headline,
            summary = summary,
            accessibilityLine = accessibilityLine,
            commitmentLine = commitmentLine,
            guardrailLine = guardrailLine,
            backendLine = backendLine,
            lastEventLine = lastEventLine,
            primaryAction = primaryAction,
            primaryActionLabel = primaryActionLabel,
            nothingEnforced = nothingEnforced,
            showAdbReverseHint = showAdb,
            adbReverseHint = if (showAdb) ADB_REVERSE else "",
            checkLogLine = checkLogLine,
            detailsExpandedByDefault = level != ProtectionReliabilityLevel.PROTECTED,
            idleGreeting = idleGreeting
        )
    }

    private fun backendLine(kind: BackendProbeKind): String = when (kind) {
        BackendProbeKind.REACHABLE -> "Reachable"
        BackendProbeKind.STALE_OK -> "OK recently"
        BackendProbeKind.BRIDGE_MISSING -> "Bridge missing"
        BackendProbeKind.UNREACHABLE -> "Unreachable"
        BackendProbeKind.CHECKING -> "Checking"
        BackendProbeKind.IDLE -> "Not tested"
    }

    fun backendLoopback(baseUrl: String = BackendHealthProbe.BACKEND_BASE_URL): Boolean =
        BackendHealthProbe.isLoopbackBaseUrl(baseUrl)
}
