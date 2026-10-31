package com.phonecodex.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.enforcement.SessionEnforcementCopy
import com.phonecodex.app.domain.protection.ProtectionReliabilityLevel

/**
 * App identity plus a calm headline. No numbers, no status noise — that lives in the pills below.
 */
@Composable
internal fun HomeScreenHeader(
    protectionLevel: ProtectionReliabilityLevel,
    permanentSafetyRuleCount: Int,
    hasActiveCommitment: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val isProtected = protectionLevel == ProtectionReliabilityLevel.PROTECTED

    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isProtected) colors.primary else colors.outline,
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        MicroLabel(text = "Strlix")
    }

    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = "Make a promise.\nI will help you keep it.",
        style = MaterialTheme.typography.headlineMedium,
        color = colors.onSurface
    )

    Spacer(modifier = Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusPill(
            text = protectionStatusPillText(protectionLevel),
            tone = protectionStatusPillTone(protectionLevel)
        )
        if (permanentSafetyRuleCount > 0) {
            StatusPill(
                text = permanentSafetyRuleCountLabel(permanentSafetyRuleCount),
                tone = PillTone.CALM
            )
        }
    }

    if (shouldShowNoActiveCommitmentWarning(hasActiveCommitment, permanentSafetyRuleCount)) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = SessionEnforcementCopy.NO_ACTIVE_COMMITMENT,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.error
        )
    }
}

internal fun shouldShowNoActiveCommitmentWarning(
    hasActiveCommitment: Boolean,
    permanentSafetyRuleCount: Int
): Boolean = !hasActiveCommitment && permanentSafetyRuleCount == 0

internal fun permanentSafetyRuleCountLabel(count: Int): String =
    if (count == 1) "1 permanent safety rule" else "$count permanent safety rules"

internal fun protectionStatusPillText(level: ProtectionReliabilityLevel): String = when (level) {
    ProtectionReliabilityLevel.CHECKING -> "Checking"
    ProtectionReliabilityLevel.PROTECTED -> "Protected"
    ProtectionReliabilityLevel.NOT_PROTECTING -> "Not protecting"
    ProtectionReliabilityLevel.NEEDS_SETUP -> "Needs setup"
}

internal fun protectionStatusPillTone(level: ProtectionReliabilityLevel): PillTone = when (level) {
    ProtectionReliabilityLevel.CHECKING -> PillTone.CALM
    ProtectionReliabilityLevel.PROTECTED -> PillTone.POSITIVE
    ProtectionReliabilityLevel.NOT_PROTECTING,
    ProtectionReliabilityLevel.NEEDS_SETUP -> PillTone.ALERT
}
