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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.protection.ProtectionPrimaryAction
import com.phonecodex.app.domain.protection.ProtectionReliabilityGate
import com.phonecodex.app.domain.protection.ProtectionReliabilityLevel
import com.phonecodex.app.domain.protection.ProtectionReliabilityUiModel

/**
 * One card: is the enforcement loop alive? Technical rows stay collapsed.
 */
@Composable
internal fun ProtectionReliabilityCard(
    model: ProtectionReliabilityUiModel,
    lastCheckSummary: String?,
    checkInProgress: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRunProtectionCheck: () -> Unit,
    compact: Boolean = false
) {
    var detailsOpen by remember(model.level) { mutableStateOf(model.detailsExpandedByDefault) }
    val colors = MaterialTheme.colorScheme
    val headlineColor = when (model.level) {
        ProtectionReliabilityLevel.PROTECTED -> colors.primary
        ProtectionReliabilityLevel.NEEDS_SETUP -> colors.error
        ProtectionReliabilityLevel.NOT_PROTECTING -> colors.error
    }

    CalmCard(
        modifier = Modifier.testTag("protection_reliability_card"),
        highlighted = model.level != ProtectionReliabilityLevel.PROTECTED
    ) {
        MicroLabel(text = "Protection status")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = model.headline,
            style = if (compact) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            color = headlineColor,
            modifier = Modifier.testTag("protection_reliability_headline")
        )
        if (!compact) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = model.summary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        if (model.nothingEnforced) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Nothing is enforced.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error
            )
        }
        if (!compact && !lastCheckSummary.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastCheckSummary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        if (model.primaryAction == ProtectionPrimaryAction.OPEN_ACCESSIBILITY) {
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryAction(
                text = model.primaryActionLabel ?: ProtectionReliabilityGate.ACTION_TURN_ON,
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.testTag("protection_reliability_primary")
            )
        }
        if (!compact) {
            Spacer(modifier = Modifier.height(10.dp))
            QuietAction(
                text = if (detailsOpen) "Hide details" else "Details",
                onClick = { detailsOpen = !detailsOpen }
            )
        }
        if (!compact && detailsOpen) {
            Spacer(modifier = Modifier.height(12.dp))
            SummaryRow(label = "Accessibility", value = model.accessibilityLine)
            Spacer(modifier = Modifier.height(8.dp))
            SummaryRow(label = "Commitment", value = model.commitmentLine)
            Spacer(modifier = Modifier.height(8.dp))
            SummaryRow(label = "Life rule", value = model.guardrailLine)
            Spacer(modifier = Modifier.height(8.dp))
            SummaryRow(label = "Backend", value = model.backendLine)
            Spacer(modifier = Modifier.height(8.dp))
            SummaryRow(label = "Last event", value = model.lastEventLine)
            if (model.showAdbReverseHint) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = model.adbReverseHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            QuietAction(
                text = if (checkInProgress) "Checking protection…" else "Run protection check",
                onClick = onRunProtectionCheck,
                enabled = !checkInProgress
            )
        }
    }
}

/** Kept for older call sites that only needed the Accessibility deep-link. */
@Composable
internal fun ProtectionSetupCard(onOpenAccessibilitySettings: () -> Unit) {
    CalmCard {
        Text(
            text = "I cannot see your screen yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = ProtectionReliabilityGate.SUMMARY_NEEDS_SETUP,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryAction(
            text = ProtectionReliabilityGate.ACTION_TURN_ON,
            onClick = onOpenAccessibilitySettings
        )
    }
}

/** Raw protection health. Lives under Advanced Controls. */
@Composable
internal fun ProtectionHealthDetails(
    isAccessibilityEnabled: Boolean,
    violationCount: Int,
    lastViolationReason: String?,
    onOpenAccessibilitySettings: () -> Unit,
    onClearViolations: () -> Unit
) {
    MicroLabel(text = "Protection status")
    Spacer(modifier = Modifier.height(10.dp))
    SummaryRow(
        label = "Service",
        value = if (isAccessibilityEnabled) "Connected" else "Disconnected"
    )
    Spacer(modifier = Modifier.height(8.dp))
    SummaryRow(label = "Violations", value = violationCount.toString())
    if (!lastViolationReason.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        SummaryRow(label = "Last", value = lastViolationReason)
    }
    Spacer(modifier = Modifier.height(14.dp))
    if (!isAccessibilityEnabled) {
        QuietAction(
            text = "Open accessibility settings",
            onClick = onOpenAccessibilitySettings
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    QuietAction(
        text = "Clear violations",
        onClick = onClearViolations,
        enabled = violationCount > 0
    )
}
