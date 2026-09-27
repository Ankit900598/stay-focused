package com.phonecodex.app.ui.home

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.diagnostics.BackendProbeKind
import com.phonecodex.app.domain.model.ContextSnapshot
import com.phonecodex.app.domain.model.RecoveryPolicy

@Composable
internal fun AdvancedControlsToggle(
    showAdvancedControls: Boolean,
    onToggle: () -> Unit
) {
    QuietAction(
        text = if (showAdvancedControls) "Hide Advanced Controls" else "Advanced Controls",
        onClick = onToggle
    )
}

@Composable
internal fun AdvancedEngineeringToggle(
    showEngineering: Boolean,
    onToggle: () -> Unit
) {
    QuietAction(
        text = if (showEngineering) {
            "Hide engineering tools"
        } else {
            "Show engineering tools (app rules, debug, events)"
        },
        onClick = onToggle
    )
}

@Composable
internal fun AdvancedIntroCard() {
    Text(
        text = "Session tuning and permanent commitments. Engineering tools stay collapsed " +
            "so Home stays light.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun AdvancedSessionTuningCard(state: HomeScreenState) {
    CalmCard {
        SessionTuningSection(
            settings = state.studyWorldSettings,
            enabled = !state.hasStoredSession,
            onDurationSelected = state::setDurationMinutes,
            onLockThresholdSelected = state::setLockAttemptThreshold,
            onCooldownSelected = state::setBlockAttemptCooldownMinutes
        )
    }
}

@Composable
internal fun AdvancedPermanentCommitmentsCard(state: HomeScreenState) {
    CalmCard {
        PermanentCommitmentsSection(
            guardrails = state.permanentGuardrails,
            onToggleEnabled = state::toggleGuardrail
        )
    }
}

@Composable
internal fun AdvancedProtectionHealthCard(state: HomeScreenState) {
    ProtectionReliabilityCard(
        model = state.protectionReliability,
        lastCheckSummary = state.lastProtectionCheckSummary,
        checkInProgress = state.protectionCheckInProgress,
        onOpenAccessibilitySettings = state::openAccessibilitySettings,
        onRunProtectionCheck = state::runProtectionCheck
    )
    Spacer(modifier = Modifier.height(14.dp))
    CalmCard {
        ProtectionHealthDetails(
            isAccessibilityEnabled = state.isAccessibilityEnabled,
            violationCount = state.protectionViolationState.violationCount,
            lastViolationReason = state.protectionViolationState.lastReason,
            onOpenAccessibilitySettings = state::openAccessibilitySettings,
            onClearViolations = state::clearViolations
        )
    }
}

@Composable
internal fun AdvancedActiveSessionCard(state: HomeScreenState) {
    val activeSession = state.session ?: return
    CalmCard {
        MicroLabel(text = "Active session")
        Spacer(modifier = Modifier.height(10.dp))
        SummaryRow(label = "Status", value = activeSession.status.name)
        Spacer(modifier = Modifier.height(8.dp))
        SummaryRow(label = "Attempts", value = activeSession.attemptCount.toString())
        Spacer(modifier = Modifier.height(8.dp))
        SummaryRow(label = "Strictness", value = activeSession.confirmedStrictness.name)
        Spacer(modifier = Modifier.height(8.dp))
        SummaryRow(label = "World strictness", value = activeSession.strictness.name)

        if (state.isSessionActive) {
            Spacer(modifier = Modifier.height(14.dp))
            QuietAction(
                text = "Test Instagram decision",
                onClick = {
                    val decision = state.policyEngine.evaluate(
                        world = state.studyWorld,
                        session = activeSession,
                        context = ContextSnapshot(
                            timestampMillis = System.currentTimeMillis(),
                            packageName = "com.instagram.android",
                            appLabel = "Instagram",
                            screenText = null
                        )
                    )
                    state.lastDecisionText = "Decision: ${decision.decision}\n" +
                        "Reason: ${decision.reason}\n" +
                        "Risk: ${decision.riskLevel}\n" +
                        "Source: ${decision.source}"
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = state.lastDecisionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun AdvancedRecoveryPolicyCard(recoveryPolicy: RecoveryPolicy) {
    CalmCard {
        StrictZoneRecoverySection(policy = recoveryPolicy)
    }
}

@Composable
internal fun AdvancedAppRulesCard(
    state: HomeScreenState,
    onLoadInstalledApps: () -> Unit
) {
    CalmCard {
        AppRulesSection(
            appRules = state.appRules,
            isDefaultRule = state::isDefaultAppRule,
            installedApps = state.installedApps,
            installedAppsLoading = state.installedAppsLoading,
            installedAppsSearch = state.installedAppsSearch,
            onInstalledAppsSearchChange = { state.installedAppsSearch = it },
            onLoadInstalledApps = onLoadInstalledApps,
            onSelectBehavior = { rule, behavior ->
                state.applyAppRuleBehavior(rule, behavior)
            },
            onAddAppRule = { app, behavior ->
                state.addAppRule(app, behavior)
            },
            onRemoveRule = { packageName ->
                state.removeAppRule(packageName)
            }
        )
    }
}

@Composable
internal fun AdvancedSafeAppsCard(
    state: HomeScreenState,
    onLoadInstalledApps: () -> Unit
) {
    CalmCard {
        SafeAppsSection(
            safeApps = state.safeApps,
            installedApps = state.installedApps,
            installedAppsLoading = state.installedAppsLoading,
            safeAppsSearch = state.safeAppsSearch,
            onSafeAppsSearchChange = { state.safeAppsSearch = it },
            onLoadInstalledApps = onLoadInstalledApps,
            onAddSafeApp = { app -> state.addSafeApp(app) },
            onRemoveSafeApp = { packageName -> state.removeSafeApp(packageName) }
        )
    }
}

@Composable
internal fun AdvancedEventsCard(state: HomeScreenState) {
    CalmCard {
        MicroLabel(text = "Recent events")
        Spacer(modifier = Modifier.height(12.dp))
        QuietAction(
            text = "Refresh events",
            onClick = { state.refreshEventsFromStore() }
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = state.recentEvents.joinToString("\n\n").ifEmpty { "No events yet" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun AdvancedDebugCard(state: HomeScreenState) {
    CalmCard {
        val probe = state.backendProbeResult
        val backendStatus = when {
            state.backendTestInProgress || probe.kind == BackendProbeKind.CHECKING ->
                probe.statusLine.ifBlank { "Checking backend…" }
            probe.kind == BackendProbeKind.IDLE ->
                "Not probed yet — auto-check runs on open, or use Test Backend"
            probe.kind == BackendProbeKind.BRIDGE_MISSING ->
                buildString {
                    append(probe.statusLine)
                    probe.detail?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                }
            else -> buildString {
                append(probe.statusLine)
                when (probe.kind) {
                    BackendProbeKind.REACHABLE -> append(" (reachable)")
                    BackendProbeKind.STALE_OK -> append(" (stale OK)")
                    BackendProbeKind.UNREACHABLE -> append(" (unreachable)")
                    else -> Unit
                }
                probe.detail?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
            }
        }
        DecisionDebugPanel(
            debugState = state.debugState,
            activePromise = state.session?.goal ?: state.goal.ifBlank { null },
            backendStatusLine = backendStatus,
            onRefresh = state::refreshDebugState
        )
    }
}

@Composable
internal fun AdvancedFeedbackCard(state: HomeScreenState) {
    CalmCard {
        MicroLabel(text = "Decision feedback")
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Correct the last decision so we can grade the classifier later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))

        val canSubmitFeedback = !state.debugState.lastPackageName.isNullOrBlank() &&
            !state.debugState.lastDecision.isNullOrBlank()

        QuietAction(
            text = "That was correct",
            onClick = {
                state.submitFeedback(state.debugState.lastDecision.orEmpty())
            },
            enabled = canSubmitFeedback
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietAction(
                text = "Should allow",
                onClick = { state.submitFeedback("ALLOW") },
                modifier = Modifier.weight(1f),
                enabled = canSubmitFeedback
            )
            QuietAction(
                text = "Should block",
                onClick = { state.submitFeedback("BLOCK") },
                modifier = Modifier.weight(1f),
                enabled = canSubmitFeedback
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Feedback saved: ${state.recentFeedback.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.recentFeedback.take(5).forEach { entry ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatFeedbackEntry(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Kept for any legacy call sites; prefer per-card composables in LazyColumn. */
@Composable
internal fun AdvancedControlsContent(
    state: HomeScreenState,
    recoveryPolicy: RecoveryPolicy,
    onLoadInstalledApps: () -> Unit
) {
    val openedAt = SystemClock.elapsedRealtime()
    Log.d("PhoneCodexUIPerf", "legacy AdvancedControlsContent used")
    AdvancedIntroCard()
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedSessionTuningCard(state)
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedPermanentCommitmentsCard(state)
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedProtectionHealthCard(state)
    if (state.hasStoredSession) {
        Spacer(modifier = Modifier.height(14.dp))
        AdvancedActiveSessionCard(state)
    }
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedRecoveryPolicyCard(recoveryPolicy)
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedAppRulesCard(state, onLoadInstalledApps)
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedSafeAppsCard(state, onLoadInstalledApps)
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedEventsCard(state)
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedDebugCard(state)
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedDeveloperDiagnosticsCard(state)
    Spacer(modifier = Modifier.height(14.dp))
    AdvancedFeedbackCard(state)
    Log.d(
        "PhoneCodexUIPerf",
        "legacy AdvancedControlsContent done ${SystemClock.elapsedRealtime() - openedAt}ms"
    )
}
