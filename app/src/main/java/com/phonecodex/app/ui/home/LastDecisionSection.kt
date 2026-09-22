package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import com.phonecodex.app.data.DebugState
import com.phonecodex.app.domain.enforcement.CommitmentTransparency
import com.phonecodex.app.domain.enforcement.LastDecisionSnapshot

@Composable
internal fun LastDecisionCard(
    debugState: DebugState,
    promiseText: String?,
    nowMillis: Long
) {
    val snapshot = remember(debugState, promiseText, nowMillis) {
        debugState.toLastDecisionSnapshot(promiseText, nowMillis)
    }
    val explained = remember(snapshot) { CommitmentTransparency.explain(snapshot) }
    val context = LocalContext.current
    var showEngineering by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    CalmCard {
        MicroLabel(text = "Last decision")
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Why Strlix allowed, warned, blocked, or locked the last screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = explained.authorityLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        explained.emptyOrStaleMessage?.let { note ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (explained.hasDecision) {
            Spacer(modifier = Modifier.height(14.dp))
            LastDecisionField(label = "Active promise", value = explained.userPromise)
            LastDecisionField(label = "Decision", value = explained.userDecision)
            LastDecisionField(label = "Why Strlix acted", value = explained.userWhy)
            LastDecisionField(label = "What evidence mattered", value = explained.userEvidence)
            LastDecisionField(label = "What you can do next", value = explained.userNext)
        }
        Spacer(modifier = Modifier.height(12.dp))
        QuietAction(
            text = if (showEngineering) "Hide engineering detail" else "Engineering detail",
            onClick = { showEngineering = !showEngineering }
        )
        if (showEngineering) {
            Spacer(modifier = Modifier.height(12.dp))
            val eng = explained.engineering
            LastDecisionField(label = "App", value = eng.appLabel)
            LastDecisionField(label = "Package", value = eng.packageName ?: "—")
            LastDecisionField(label = "Reason code", value = eng.reasonCode ?: "—")
            LastDecisionField(label = "Surface", value = eng.surface ?: "—")
            LastDecisionField(label = "Activity", value = eng.activity ?: "—")
            LastDecisionField(label = "Duration source", value = eng.durationSource ?: "—")
            LastDecisionField(
                label = "Matched signals",
                value = eng.matchedSignals.joinToString(", ").ifBlank { "—" }
            )
            LastDecisionField(label = "Source", value = "${eng.sourceKind} (${eng.rawSource ?: "—"})")
            LastDecisionField(label = "Age", value = eng.ageLabel)
        }
        Spacer(modifier = Modifier.height(8.dp))
        QuietAction(
            text = if (copied) "Copied sanitized report" else "Copy debug report",
            onClick = {
                val report = CommitmentTransparency.debugReport(
                    snapshot = snapshot,
                    includeScreenPreview = false
                )
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("Strlix last decision", report)
                )
                copied = true
            }
        )
        Text(
            text = "Copy is sanitized. Private screen text is not included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LastDecisionField(label: String, value: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(10.dp))
}

internal fun DebugState.toLastDecisionSnapshot(
    promiseText: String?,
    nowMillis: Long
): LastDecisionSnapshot = LastDecisionSnapshot(
    promiseText = promiseText,
    decision = lastDecision,
    reason = lastReason,
    source = lastSource,
    packageName = lastPackageName,
    reasonCode = lastReasonCode,
    surface = lastSurface,
    activity = lastActivity,
    durationSource = lastDurationSource,
    matchedSignals = lastMatchedSignals,
    updatedMillis = lastUpdatedMillis,
    screenTextPreview = lastScreenTextPreview,
    nowMillis = nowMillis
)
