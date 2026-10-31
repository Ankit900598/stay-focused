package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.model.StudyWorldSettings

private val durationQuickChoices = listOf(15, 30, 60)

internal fun sessionDurationChoices(currentMinutes: Int): List<Int> =
    if (currentMinutes in durationQuickChoices) {
        durationQuickChoices
    } else {
        listOf(currentMinutes) + durationQuickChoices
    }

/**
 * Raw session knobs. These are normally derived from the promise, so they live under
 * Advanced Controls for the cases where the user wants to override them by hand.
 */
@Composable
internal fun SessionTuningSection(
    settings: StudyWorldSettings,
    enabled: Boolean,
    onDurationSelected: (Int) -> Unit,
    onLockThresholdSelected: (Int?) -> Unit,
    onCooldownSelected: (Int) -> Unit
) {
    MicroLabel(text = "Session tuning")
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = if (enabled) {
            "A promise's stated duration or lock count wins. Otherwise duration and cooldown " +
                "use these saved values, while lock attempts use strictness unless you choose a custom threshold."
        } else {
            "Locked while a commitment is running."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = "Duration",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(8.dp))
    OptionRow(
        options = sessionDurationChoices(settings.durationMinutes),
        selected = settings.durationMinutes,
        label = { "$it min" },
        onSelected = onDurationSelected,
        enabled = enabled
    )

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Lock after attempts",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(8.dp))
    OptionRow(
        options = listOf<Int?>(null, 3, 5, 10),
        selected = settings.lockAttemptThreshold.takeIf { settings.lockAttemptThresholdCustomized },
        label = { it?.toString() ?: "Auto" },
        onSelected = onLockThresholdSelected,
        enabled = enabled
    )

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Block cooldown",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(8.dp))
    OptionRow(
        options = listOf(1, 2, 5),
        selected = settings.blockAttemptCooldownMinutes,
        label = { "$it min" },
        onSelected = onCooldownSelected,
        enabled = enabled
    )
}
