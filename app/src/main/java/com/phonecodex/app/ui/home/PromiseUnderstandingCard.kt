package com.phonecodex.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.model.ClarificationOption
import com.phonecodex.app.domain.model.InstalledApp
import com.phonecodex.app.domain.promise.LocalAppTargetSelectionLaw

@Composable
internal fun PromiseUnderstandingCard(
    understanding: PromiseUnderstanding,
    startBlockedMessage: String?,
    onStartCommitment: () -> Unit,
    onEditPromise: () -> Unit,
    onSelectClarificationOption: (String) -> Unit,
    onStrongerConfirmChange: (Boolean) -> Unit,
    extraSection: (@Composable () -> Unit)? = null,
    localAppTargetMatches: List<InstalledApp> = emptyList(),
    localAppTargetLookupRequested: Boolean = false,
    localAppTargetLookupLoading: Boolean = false,
    localAppTargetLookupError: String? = null,
    onFindLocalAppTarget: () -> Unit = {},
    onRefreshLocalAppTarget: () -> Unit = {},
    onSelectLocalAppTarget: (InstalledApp) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val needsClarification = understanding.localAppTargetSelectionCandidate != null ||
        understanding.clarificationOptions.isNotEmpty() ||
        !understanding.clarificationQuestion.isNullOrBlank()
    val isOffline = understanding.source == UnderstandingSource.LOCAL_PREVIEW

    CalmCard(highlighted = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (needsClarification) "Let's clarify" else "I understood",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface
            )
            StatusPill(
                text = understanding.statusLabel,
                tone = when {
                    needsClarification -> PillTone.ALERT
                    understanding.requiresStrongerConfirmation &&
                        !understanding.strongerConfirmAcknowledged -> PillTone.ALERT
                    isOffline -> PillTone.CALM
                    else -> PillTone.POSITIVE
                }
            )
        }

        understanding.understoodSummary?.let { summary ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = understanding.statusDetail,
            style = MaterialTheme.typography.bodySmall,
            color = if (needsClarification || isOffline) {
                colors.error
            } else {
                colors.onSurfaceVariant
            }
        )

        if (understanding.understoodSummary.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "\u201C${understanding.promiseText}\u201D",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = colors.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
        HorizontalDivider(color = colors.outlineVariant)
        Spacer(modifier = Modifier.height(18.dp))

        SummaryRow(label = "When this applies", value = understanding.sessionTimeLabel)

        understanding.appliesToLabel?.let { appliesTo ->
            Spacer(modifier = Modifier.height(12.dp))
            SummaryRow(label = "Applies to", value = appliesTo)
        }

        Spacer(modifier = Modifier.height(12.dp))
        SummaryRow(label = "I will allow", value = understanding.allowedLabel)
        Spacer(modifier = Modifier.height(12.dp))
        SummaryRow(label = "I will block", value = understanding.blockedLabel)

        // Always-on Life Rules render in their own row. Merging them into "I will
        // block" (old behavior) lied by suggesting the current promise blocks them
        // when in fact a separate permanent guardrail does — see reliability
        // design 2026-09-25 §Fix 3.
        if (understanding.alwaysOnLifeRules.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            SummaryRow(
                label = "Always on",
                value = understanding.alwaysOnLifeRules.joinToString(", ")
            )
        }

        understanding.conditionalLabel?.let { conditional ->
            Spacer(modifier = Modifier.height(12.dp))
            SummaryRow(label = "Also", value = conditional)
        }

        if (understanding.clarificationOptions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            understanding.clarificationQuestion?.let { question ->
                SummaryRow(label = "Need clarification", value = question)
                Spacer(modifier = Modifier.height(12.dp))
            }
            ClarificationOptionList(
                options = understanding.clarificationOptions,
                selectedId = understanding.selectedClarificationOptionId,
                onSelect = onSelectClarificationOption
            )
        } else if (!understanding.clarificationQuestion.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            SummaryRow(
                label = "Need clarification",
                value = understanding.clarificationQuestion
            )
        }

        understanding.localAppTargetSelectionCandidate?.let { candidate ->
            Spacer(modifier = Modifier.height(18.dp))
            LocalAppTargetSelectionSection(
                label = candidate.label,
                matches = localAppTargetMatches,
                lookupRequested = localAppTargetLookupRequested,
                loading = localAppTargetLookupLoading,
                error = localAppTargetLookupError,
                onFind = onFindLocalAppTarget,
                onRefresh = onRefreshLocalAppTarget,
                onSelect = onSelectLocalAppTarget
            )
        }

        if (understanding.cautions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            SummaryRow(
                label = if (isOffline) "Offline note" else "Check this",
                value = understanding.cautions.joinToString(" · ")
            )
        }

        if (understanding.requiresStrongerConfirmation) {
            Spacer(modifier = Modifier.height(16.dp))
            StrongerConfirmRow(
                label = understanding.strongerConfirmLabel.orEmpty(),
                checked = understanding.strongerConfirmAcknowledged,
                onCheckedChange = onStrongerConfirmChange
            )
        }

        if (!needsClarification && !understanding.requiresStrongerConfirmation) {
            Spacer(modifier = Modifier.height(12.dp))
            SummaryRow(label = "Strictness", value = understanding.strictnessLabel)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = understanding.driftLabel,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }

        extraSection?.let { section ->
            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            section()
        }

        Spacer(modifier = Modifier.height(22.dp))
        if (understanding.canStart) {
            PrimaryAction(
                text = "Start Commitment",
                onClick = onStartCommitment,
                modifier = Modifier.testTag("start_commitment")
            )
        } else {
            Text(
                text = startDisabledMessage(understanding),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
        }

        startBlockedMessage?.let { message ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        QuietAction(
            text = if (needsClarification) "Edit promise" else "Change my promise",
            onClick = onEditPromise
        )
    }
}

@Composable
private fun LocalAppTargetSelectionSection(
    label: String,
    matches: List<InstalledApp>,
    lookupRequested: Boolean,
    loading: Boolean,
    error: String?,
    onFind: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (InstalledApp) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Choose which launchable app “$label” means",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Text(
            text = "Only apps with a launcher icon appear. This lookup stays on your phone. Nothing is blocked until you review the updated preview and tap Start Commitment.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        when {
            loading -> Text("Checking launchable apps on this phone…", color = colors.onSurfaceVariant)
            error != null -> {
                Text(error, color = colors.error)
                OutlinedButton(onClick = onFind, modifier = Modifier.testTag("local_app_target_retry")) {
                    Text("Try again")
                }
            }
            !lookupRequested -> OutlinedButton(
                onClick = onFind,
                modifier = Modifier.testTag("local_app_target_find")
            ) {
                Text("Find exact app on this phone")
            }
            matches.isEmpty() -> {
                Text(
                    text = "No launchable app has the exact name “$label”. Refresh the list or edit the promise to use the name shown on your phone.",
                    color = colors.error
                )
                OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("local_app_target_refresh")) {
                    Text("Refresh app list")
                }
            }
            matches.size > 1 -> {
                Text(
                    text = "More than one launchable app has this exact name. Strlix won’t guess; edit the promise to make the target clear, or refresh the list.",
                    color = colors.error
                )
                OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("local_app_target_refresh")) {
                    Text("Refresh app list")
                }
            }
            !LocalAppTargetSelectionLaw.canSelect(matches.single()) -> Text(
                text = "This is a phone-safety app and remains available; choose a different target.",
                color = colors.error
            )
            else -> Button(
                onClick = { onSelect(matches.single()) },
                modifier = Modifier.testTag("local_app_target_select")
            ) {
                Text("Use ${matches.single().label}")
            }
        }
    }
}

@Composable
private fun ClarificationOptionList(
    options: List<ClarificationOption>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            val isSelected = option.id.equals(selectedId, ignoreCase = true)
            Surface(
                onClick = { onSelect(option.id) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) colors.primaryContainer else colors.surface,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) colors.primary else colors.outlineVariant
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) colors.primary else colors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (option.recommended) {
                            StatusPill(text = "Recommended", tone = PillTone.POSITIVE)
                        }
                    }
                    if (option.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    option.policyPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StrongerConfirmRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("stronger_confirm_checkbox")
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp)
        )
    }
}

