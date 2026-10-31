package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.model.PermanentGuardrail

/**
 * Promises with no end date. Kept out of the main flow so the home screen stays about
 * "what am I promising right now".
 */
@Composable
internal fun PermanentCommitmentsSection(
    guardrails: List<PermanentGuardrail>,
    onToggleEnabled: (PermanentGuardrail, Boolean) -> Unit
) {
    MicroLabel(text = "Permanent safety rules")
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "These stay on between sessions, with no timer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(14.dp))

    guardrails.forEachIndexed { index, guardrail ->
        if (index > 0) {
            Spacer(modifier = Modifier.height(12.dp))
        }
        PermanentCommitmentRow(
            guardrail = guardrail,
            onToggleEnabled = { enabled -> onToggleEnabled(guardrail, enabled) }
        )
    }
}

@Composable
private fun PermanentCommitmentRow(
    guardrail: PermanentGuardrail,
    onToggleEnabled: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = guardrailDisplayName(guardrail),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (guardrail.enabled) "Always on" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = if (guardrail.enabled) colors.secondary else colors.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = guardrail.enabled,
            onCheckedChange = onToggleEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = colors.surface,
                uncheckedBorderColor = colors.outline
            )
        )
    }
}
