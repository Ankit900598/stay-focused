package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonecodex.app.data.DebugState
import com.phonecodex.app.data.FeedbackStore
import com.phonecodex.app.domain.model.FeedbackEntry
import com.phonecodex.app.domain.model.RecoveryPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun saveFeedback(
    feedbackStore: FeedbackStore,
    debugState: DebugState,
    correctedDecision: String,
    includePrivateContext: Boolean = false
) {
    val originalDecision = debugState.lastDecision.orEmpty()
    if (originalDecision.isBlank() || debugState.lastPackageName.isNullOrBlank()) return

    feedbackStore.addFeedback(
        FeedbackEntry(
            timestampMillis = System.currentTimeMillis(),
            packageName = debugState.lastPackageName,
            screenTextPreview = if (includePrivateContext) {
                debugState.lastScreenTextPreview.orEmpty()
            } else {
                ""
            },
            originalDecision = originalDecision,
            correctedDecision = correctedDecision,
            reason = if (includePrivateContext) debugState.lastReason.orEmpty() else ""
        )
    )
}

internal fun formatFeedbackEntry(entry: FeedbackEntry): String {
    return buildString {
        append(entry.timestampMillis)
        append(" | ")
        append(entry.packageName)
        append(" | ")
        append(entry.originalDecision)
        append(" -> ")
        append(entry.correctedDecision)
        if (entry.reason.isNotBlank()) {
            append(" | ")
            append(entry.reason)
        }
    }
}

@Composable
internal fun DecisionDebugPanel(
    debugState: DebugState,
    activePromise: String?,
    backendStatusLine: String?,
    onRefresh: () -> Unit
) {
    val displaySource = formatDecisionSourceDisplay(debugState.lastSource)
    val displayConfidence = debugState.lastConfidence?.toString() ?: "—"
    val displayPackage = debugState.lastPackageName?.ifBlank { null } ?: "—"
    val displayDecision = debugState.lastDecision?.ifBlank { null } ?: "—"
    val displayReason = debugState.lastReason?.ifBlank { null } ?: "—"
    val displayScreenText = debugState.lastScreenTextPreview?.ifBlank { null }
        ?: "No recent preview; private text is transient"
    val displayTimestamp = formatDebugTimestamp(debugState.lastUpdatedMillis)
    val displaySignals = if (debugState.lastMatchedSignals.isEmpty()) {
        "—"
    } else {
        debugState.lastMatchedSignals.joinToString(", ")
    }
    val displayPromise = activePromise?.ifBlank { null } ?: "—"

    MicroLabel(text = "Decision inspector")
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Why Strlix allowed, warned, or blocked the last detected screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(14.dp))
    QuietAction(text = "Refresh inspector", onClick = onRefresh)
    Spacer(modifier = Modifier.height(16.dp))

    InspectorField(label = "Current app", value = displayPackage)
    InspectorField(
        label = "Screen text preview (in memory up to 10 minutes; may be private)",
        value = displayScreenText
    )
    InspectorField(label = "Promise / goal", value = displayPromise)
    InspectorField(label = "Final decision", value = displayDecision)
    InspectorField(
        label = "Source",
        value = displaySource,
        helperText = "Where the decision came from: Safe App, App Rule, Content Signals, " +
            "Permanent Guardrail, Backend AI, or Policy."
    )
    if (debugState.lastSource == "Permanent Guardrail") {
        Text(
            text = "This came from an always-on commitment, not the active session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    InspectorField(label = "Why", value = displayReason)
    InspectorField(label = "Matched signals", value = displaySignals)
    InspectorField(
        label = "Confidence",
        value = displayConfidence,
        helperText = "How sure that source was."
    )
    InspectorField(label = "Backend status", value = backendStatusLine ?: "—")
    InspectorField(label = "Timestamp", value = displayTimestamp)

    val hasBackendMeta = debugState.lastBackendProvider != null ||
        debugState.lastBackendDeployment != null ||
        debugState.lastBackendPromptVersion != null ||
        debugState.lastReasonCategory != null

    if (hasBackendMeta) {
        Spacer(modifier = Modifier.height(4.dp))
        MicroLabel(text = "Backend advisor")
        Spacer(modifier = Modifier.height(12.dp))
        debugState.lastReasonCategory?.let { category ->
            InspectorField(label = "Reason category", value = category)
        }
        debugState.lastWouldEscalate?.let { escalate ->
            InspectorField(
                label = "Would escalate",
                value = if (escalate) "Yes" else "No"
            )
        }
        debugState.lastBackendProvider?.let { provider ->
            InspectorField(label = "Provider", value = provider)
        }
        debugState.lastBackendDeployment?.let { deployment ->
            InspectorField(label = "Deployment", value = deployment)
        }
        debugState.lastBackendPromptVersion?.let { promptVersion ->
            InspectorField(label = "Prompt version", value = promptVersion)
        }
        debugState.lastBackendLatencyMs?.let { latencyMs ->
            InspectorField(label = "Backend latency (ms)", value = latencyMs.toString())
        }
    }
}

@Composable
private fun InspectorField(
    label: String,
    value: String,
    helperText: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        helperText?.let { text ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

internal fun formatDecisionSourceDisplay(rawSource: String?): String {
    if (rawSource.isNullOrBlank()) return "—"
    return when (rawSource) {
        "Permanent Guardrail" -> "Permanent Guardrail"
        "Safe App" -> "Safe App"
        "App Rule" -> "App Rule"
        "Content Signal" -> "Content Signals"
        "Policy" -> "Policy"
        "openai_backend", "fake_ai", "keyword" -> "Backend AI"
        else -> rawSource
    }
}

internal fun formatDebugTimestamp(millis: Long): String {
    if (millis <= 0L) return "—"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(millis))
}

@Composable
internal fun StrictZoneRecoverySection(policy: RecoveryPolicy) {
    MicroLabel(text = "Strict zone recovery policy")
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Hard commitments need humane exits: mistake window, cooldown, trusted admin, " +
            "emergency override, and an optional break fee later. These are fixed product " +
            "defaults — not Advanced Controls overrides (no editable binders yet).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(14.dp))
    SummaryRow(label = "Mistakes", value = "${policy.mistakeWindowMinutes} minute window")
    Spacer(modifier = Modifier.height(8.dp))
    SummaryRow(label = "Cooldown", value = "${policy.cooldownHours} hours")
    Spacer(modifier = Modifier.height(8.dp))
    SummaryRow(
        label = "Trusted admin",
        value = if (policy.trustedAdminRequired) "Required" else "Not required"
    )
    Spacer(modifier = Modifier.height(8.dp))
    SummaryRow(
        label = "Break fee",
        value = if (policy.breakFeeEnabled) {
            policy.breakFeeAmountLabel ?: "Enabled, amount not set"
        } else {
            "Disabled"
        }
    )
    Spacer(modifier = Modifier.height(8.dp))
    SummaryRow(
        label = "Override",
        value = if (policy.emergencyOverrideAllowed) "Allowed" else "Not allowed"
    )
}
