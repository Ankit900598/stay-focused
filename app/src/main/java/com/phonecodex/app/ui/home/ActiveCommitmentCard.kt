package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.RecoveryPolicy
import com.phonecodex.app.domain.session.EmergencyExitGate

/**
 * Shown while a commitment is running. Replaces the whole composer — during a session there is
 * nothing to configure, only a promise being kept.
 */
@Composable
internal fun ActiveCommitmentCard(
    session: FocusSession,
    isLocked: Boolean,
    nowMillis: Long,
    recoveryPolicy: RecoveryPolicy,
    onEndCommitment: () -> Unit,
    emergencyExit: EmergencyExitGate.Availability,
    emergencyExitNextAvailable: String?,
    onEmergencyExit: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var showRecovery by remember(isLocked) { mutableStateOf(false) }

    CalmCard(highlighted = true) {
        StatusPill(
            text = if (isLocked) "Locked" else "Commitment active",
            tone = if (isLocked) PillTone.ALERT else PillTone.POSITIVE
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = formatRemaining(session.deadlineMillis - nowMillis),
            style = MaterialTheme.typography.displaySmall,
            color = colors.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = session.goal,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(18.dp))
        HorizontalDivider(color = colors.outlineVariant)
        Spacer(modifier = Modifier.height(18.dp))

        SummaryRow(label = "Strictness", value = strictnessLabel(session.confirmedStrictness))
        Spacer(modifier = Modifier.height(12.dp))
        SummaryRow(
            label = "Drifts",
            value = if (session.attemptCount == 0) {
                "None yet. Keep going."
            } else {
                "${session.attemptCount} so far"
            }
        )

        if (isLocked) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This session is locked because the commitment was broken or blocked " +
                    "attempts kept repeating.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
            Spacer(modifier = Modifier.height(14.dp))
            QuietAction(
                text = if (showRecovery) "Hide recovery options" else "Recovery options",
                onClick = { showRecovery = !showRecovery }
            )
            if (showRecovery) {
                Spacer(modifier = Modifier.height(16.dp))
                LockedSessionRecoverySection(
                    policy = recoveryPolicy,
                    emergencyExit = emergencyExit,
                    emergencyExitNextAvailable = emergencyExitNextAvailable,
                    onEmergencyExit = onEmergencyExit
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        QuietAction(
            text = "End Commitment",
            onClick = onEndCommitment,
            destructive = true
        )
    }
}

internal fun formatRemaining(remainingMillis: Long): String {
    if (remainingMillis <= 0L) return "Time is up"
    val totalMinutes = (remainingMillis / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m left"
        totalMinutes > 0 -> "${totalMinutes}m left"
        else -> "Under a minute left"
    }
}
