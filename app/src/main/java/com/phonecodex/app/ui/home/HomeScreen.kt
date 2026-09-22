package com.phonecodex.app.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.recovery.RecoveryPolicyDefaults
import com.phonecodex.app.ui.theme.PhoneCodexTheme

/**
 * Home is a LazyColumn of section items — only on-screen sections compose.
 * Heavy Advanced cards are separate list items, not one mega-composable.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val state = rememberHomeScreenState()
    val coroutineScope = rememberCoroutineScope()
    val recoveryPolicy = remember { RecoveryPolicyDefaults.default() }
    val listState = rememberLazyListState()
    val firstRenderLogged = remember { booleanArrayOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            state.applyVoiceTranscript(transcript)
        } else {
            state.markVoiceInputUnavailable("Voice cancelled. You can still type the promise.")
        }
    }

    fun launchVoicePromiseInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                java.util.Locale.getDefault().toLanguageTag()
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the promise you want Strlix to protect")
        }
        try {
            state.beginVoiceCapture()
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            state.markVoiceInputUnavailable("Voice input is not available on this phone.")
        }
    }

    HomeScreenPollingEffect(state)

    LaunchedEffect(Unit) {
        state.bootstrapIfNeeded()
        if (!firstRenderLogged[0]) {
            firstRenderLogged[0] = true
            Log.d(
                "PhoneCodexUIPerf",
                "firstRender ready bootstrapped=${state.isBootstrapped} " +
                    "elapsed=${SystemClock.elapsedRealtime() - state.createdAtElapsedMs}ms"
            )
        }
    }

    val chatHome by remember {
        derivedStateOf {
            usesChatHome(
                hasStoredSession = state.hasStoredSession,
                showAdvancedControls = state.showAdvancedControls
            )
        }
    }
    val listItems by remember {
        derivedStateOf {
            buildHomeListItems(
                hasStoredSession = state.hasStoredSession,
                hasPromiseUnderstanding = state.promiseUnderstanding != null,
                showAdvancedControls = state.showAdvancedControls,
                showAdvancedEngineering = state.showAdvancedEngineering
            )
        }
    }

    // Inspector poll only while engineering debug rows are actually on-screen.
    val inspectorVisible by remember {
        derivedStateOf {
            if (!state.showAdvancedControls || !state.showAdvancedEngineering) {
                return@derivedStateOf false
            }
            listState.layoutInfo.visibleItemsInfo.any { info ->
                val key = info.key as? String
                key == HomeListItem.AdvancedDebug.id ||
                    key == HomeListItem.AdvancedEvents.id ||
                    key == HomeListItem.AdvancedDiagnostics.id ||
                    key == HomeListItem.AdvancedFeedback.id
            }
        }
    }
    val inspectorVisibleState = rememberUpdatedState(inspectorVisible)
    HomeAdvancedInspectorPollingEffect(
        state = state,
        inspectorVisible = { inspectorVisibleState.value }
    )

    if (chatHome) {
        ChatHomeScaffold(
            modifier = modifier,
            protection = state.protectionReliability,
            lastCheckSummary = state.lastProtectionCheckSummary,
            checkInProgress = state.protectionCheckInProgress,
            onOpenAccessibilitySettings = state::openAccessibilitySettings,
            onRunProtectionCheck = state::runProtectionCheck,
            understanding = state.promiseUnderstanding,
            startBlockedMessage = state.startBlockedMessage,
            onStartCommitment = state::startCommitment,
            onEditPromise = state::clearUnderstanding,
            onSelectClarificationOption = state::selectClarificationOption,
            onStrongerConfirmChange = state::updateStrongerConfirmAcknowledged,
            promiseText = state.promiseText,
            onPromiseTextChange = state::updatePromiseText,
            onUnderstandPromise = state::understandPromise,
            onVoicePromise = ::launchVoicePromiseInput,
            voiceInputStatus = state.voiceInputStatus,
            understandingInProgress = state.understandingInProgress,
            onOpenAdvanced = state::toggleAdvancedControls
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)
    ) {
        items(
            items = listItems,
            key = { item -> item.id },
            contentType = { item -> item.id }
        ) { item ->
            when (item) {
                HomeListItem.Header -> {
                    HomeScreenHeader(
                        protectionLevel = state.protectionReliability.level,
                        permanentCommitmentCount = state.enabledGuardrailCount,
                        hasActiveCommitment = state.hasStoredSession
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                HomeListItem.ProtectionStatus -> {
                    ProtectionReliabilityCard(
                        model = state.protectionReliability,
                        lastCheckSummary = state.lastProtectionCheckSummary,
                        checkInProgress = state.protectionCheckInProgress,
                        onOpenAccessibilitySettings = state::openAccessibilitySettings,
                        onRunProtectionCheck = state::runProtectionCheck
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                HomeListItem.ProtectionSetup -> {
                    ProtectionReliabilityCard(
                        model = state.protectionReliability,
                        lastCheckSummary = state.lastProtectionCheckSummary,
                        checkInProgress = state.protectionCheckInProgress,
                        onOpenAccessibilitySettings = state::openAccessibilitySettings,
                        onRunProtectionCheck = state::runProtectionCheck
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                HomeListItem.ActiveCommitment -> {
                    val session = state.session
                    if (session != null) {
                        ActiveCommitmentCard(
                            session = session,
                            isLocked = state.isSessionLocked,
                            nowMillis = state.nowMillis,
                            recoveryPolicy = recoveryPolicy,
                            onEndCommitment = state::endCommitment,
                            onEmergencyExit = state::logEmergencyExit,
                            onCooldownUnlock = state::logCooldownUnlock
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }

                HomeListItem.LastDecision -> {
                    LastDecisionCard(
                        debugState = state.debugState,
                        promiseText = state.session?.goal ?: state.goal.ifBlank { null },
                        nowMillis = state.nowMillis
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }

                HomeListItem.PromiseComposer -> {
                    PromiseComposerSection(
                        promiseText = state.promiseText,
                        onPromiseTextChange = state::updatePromiseText,
                        onExampleSelected = state::selectExamplePromise,
                        onUnderstandPromise = state::understandPromise,
                        onVoicePromise = ::launchVoicePromiseInput,
                        voiceInputStatus = state.voiceInputStatus,
                        understandingInProgress = state.understandingInProgress
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                HomeListItem.PromiseUnderstanding -> {
                    state.promiseUnderstanding?.let { understanding ->
                        PromiseUnderstandingCard(
                            understanding = understanding,
                            startBlockedMessage = state.startBlockedMessage,
                            onStartCommitment = state::startCommitment,
                            onEditPromise = state::clearUnderstanding,
                            onSelectClarificationOption = state::selectClarificationOption,
                            onStrongerConfirmChange = state::updateStrongerConfirmAcknowledged
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }

                HomeListItem.AdvancedToggle -> {
                    if (state.promiseUnderstanding == null && !state.hasStoredSession) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    AdvancedControlsToggle(
                        showAdvancedControls = state.showAdvancedControls,
                        onToggle = {
                            val openedAt = SystemClock.elapsedRealtime()
                            state.toggleAdvancedControls()
                            if (state.showAdvancedControls) {
                                Log.d(
                                    "PhoneCodexUIPerf",
                                    "advancedOpen requested after=" +
                                        "${SystemClock.elapsedRealtime() - openedAt}ms"
                                )
                            }
                        }
                    )
                    if (state.showAdvancedControls) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                HomeListItem.AdvancedIntro -> {
                    AdvancedIntroCard()
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedSessionTuning -> {
                    AdvancedSessionTuningCard(state = state)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedPermanentCommitments -> {
                    AdvancedPermanentCommitmentsCard(state = state)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedProtectionHealth -> {
                    AdvancedProtectionHealthCard(state = state)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedActiveSession -> {
                    AdvancedActiveSessionCard(state = state)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedRecoveryPolicy -> {
                    AdvancedRecoveryPolicyCard(recoveryPolicy = recoveryPolicy)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedEngineeringToggle -> {
                    AdvancedEngineeringToggle(
                        showEngineering = state.showAdvancedEngineering,
                        onToggle = state::toggleAdvancedEngineering
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedAppRules -> {
                    AdvancedAppRulesCard(
                        state = state,
                        onLoadInstalledApps = { state.loadInstalledApps(coroutineScope) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedSafeApps -> {
                    AdvancedSafeAppsCard(
                        state = state,
                        onLoadInstalledApps = { state.loadInstalledApps(coroutineScope) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedEvents -> {
                    AdvancedEventsCard(state = state)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedDebug -> {
                    AdvancedDebugCard(state = state)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedDiagnostics -> {
                    AdvancedDeveloperDiagnosticsCard(state = state)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.AdvancedFeedback -> {
                    AdvancedFeedbackCard(state = state)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                HomeListItem.BottomSpacer -> {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    PhoneCodexTheme {
        HomeScreen()
    }
}
