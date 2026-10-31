package com.phonecodex.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.protection.ProtectionReliabilityLevel
import com.phonecodex.app.domain.protection.ProtectionReliabilityUiModel

/**
 * Idle / confirm home: greeting + one composer. Advanced and live sessions use the list.
 */
@Composable
internal fun ChatHomeScaffold(
    modifier: Modifier = Modifier,
    protection: ProtectionReliabilityUiModel,
    lastCheckSummary: String?,
    checkInProgress: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRunProtectionCheck: () -> Unit,
    understanding: PromiseUnderstanding?,
    startBlockedMessage: String?,
    onStartCommitment: () -> Unit,
    onEditPromise: () -> Unit,
    onSelectClarificationOption: (String) -> Unit,
    onStrongerConfirmChange: (Boolean) -> Unit,
    promiseText: String,
    onPromiseTextChange: (String) -> Unit,
    onUnderstandPromise: () -> Unit,
    onVoicePromise: () -> Unit,
    voiceInputStatus: String?,
    voiceRecording: Boolean = false,
    voiceBusy: Boolean = false,
    understandingInProgress: Boolean,
    onOpenAdvanced: () -> Unit,
    understandingExtra: (@Composable () -> Unit)? = null,
    todayInsight: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val reviewing = understanding != null
    val protectionMode = chatHomeProtectionMode(reviewing, protection.level)
    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        ChatHomeHeader(protectionLevel = protection.level)

        if (protectionMode != ChatHomeProtectionMode.HIDDEN) {
            Spacer(modifier = Modifier.height(16.dp))
            ProtectionReliabilityCard(
                model = protection,
                lastCheckSummary = lastCheckSummary,
                checkInProgress = checkInProgress,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onRunProtectionCheck = onRunProtectionCheck,
                compact = true,
                allowDetailsToggle = !reviewing
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (understanding == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 18.dp, bottom = 12.dp)
                ) {
                    IdleGreeting(
                        greeting = protection.idleGreeting,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    if (todayInsight != null) {
                        Spacer(modifier = Modifier.height(20.dp))
                        todayInsight()
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp, bottom = 12.dp)
                ) {
                    PromiseUnderstandingCard(
                        understanding = understanding,
                        startBlockedMessage = startBlockedMessage,
                        onStartCommitment = onStartCommitment,
                        onEditPromise = onEditPromise,
                        onSelectClarificationOption = onSelectClarificationOption,
                        onStrongerConfirmChange = onStrongerConfirmChange,
                        extraSection = understandingExtra
                    )
                }
            }
        }

        if (!confirmationReviewHidesComposer(reviewing)) {
            PromiseComposerSection(
                promiseText = promiseText,
                onPromiseTextChange = onPromiseTextChange,
                onExampleSelected = {},
                onUnderstandPromise = onUnderstandPromise,
                onVoicePromise = onVoicePromise,
                voiceInputStatus = voiceInputStatus,
                voiceRecording = voiceRecording,
                voiceBusy = voiceBusy,
                understandingInProgress = understandingInProgress,
                compact = true
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        QuietAction(text = "Settings & controls", onClick = onOpenAdvanced)
    }
}

@Composable
private fun ChatHomeHeader(protectionLevel: ProtectionReliabilityLevel) {
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
        Spacer(modifier = Modifier.weight(1f))
        StatusPill(
            text = protectionStatusPillText(protectionLevel),
            tone = protectionStatusPillTone(protectionLevel)
        )
    }
}

@Composable
private fun IdleGreeting(
    greeting: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.testTag("chat_home_greeting"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Hey, Ankit.",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = greeting,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("chat_home_greeting_status")
        )
    }
}
