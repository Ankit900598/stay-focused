package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.model.RecoveryPolicy
import com.phonecodex.app.domain.session.EmergencyExitGate

/**
 * Escape hatches for a locked session. Deliberately quiet: this is the path the user should
 * have to think about before taking. Every action here does what its label says.
 */
@Composable
internal fun LockedSessionRecoverySection(
    policy: RecoveryPolicy,
    emergencyExit: EmergencyExitGate.Availability,
    emergencyExitNextAvailable: String?,
    onEmergencyExit: () -> Unit
) {
    MicroLabel(text = "Recovery")
    Spacer(modifier = Modifier.height(10.dp))

    SummaryRow(label = "Cooldown unlock", value = "Not available yet")
    Spacer(modifier = Modifier.height(8.dp))
    SummaryRow(
        label = "Emergency exit",
        value = if (policy.emergencyOverrideAllowed) "Once every 24 hours" else "Not allowed"
    )
    Spacer(modifier = Modifier.height(8.dp))
    SummaryRow(
        label = "Trusted admin",
        value = if (policy.trustedAdminRequired) "Required" else "Not required"
    )

    Spacer(modifier = Modifier.height(16.dp))
    QuietAction(
        text = "Emergency exit",
        onClick = onEmergencyExit,
        enabled = emergencyExit == EmergencyExitGate.Availability.AVAILABLE,
        destructive = true
    )
    emergencyExitNote(emergencyExit, emergencyExitNextAvailable)?.let { note ->
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = "Calls, contacts and emergency apps always work during a commitment.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

internal fun formatClockTime(millis: Long): String =
    java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

internal fun emergencyExitNote(
    availability: EmergencyExitGate.Availability,
    nextAvailable: String?
): String? = when (availability) {
    EmergencyExitGate.Availability.AVAILABLE ->
        "Ends this commitment now. Permanent rules stay on."
    EmergencyExitGate.Availability.DISABLED_BY_POLICY ->
        "You turned emergency exit off when you made this promise."
    EmergencyExitGate.Availability.USED_RECENTLY ->
        "Emergency exit was already used in the last 24 hours" +
            (nextAvailable?.let { ". Available again $it." } ?: ".")
    EmergencyExitGate.Availability.NO_SESSION -> null
}
