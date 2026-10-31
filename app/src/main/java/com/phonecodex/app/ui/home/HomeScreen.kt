package com.phonecodex.app.ui.home

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import com.phonecodex.app.data.ScreenTimeUsageReader
import com.phonecodex.app.domain.promise.PromiseAudioRecorder
import com.phonecodex.app.domain.promise.PromiseSpeechClient
import com.phonecodex.app.domain.promise.PromiseSpeechException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home is a LazyColumn of section items — only on-screen sections compose.
 * Heavy Advanced cards are separate list items, not one mega-composable.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val state = rememberHomeScreenState()
    val protectionLevel by remember(state) {
        derivedStateOf { state.protectionReliability.level }
    }
    val mail = rememberMailTaskUiState(state)
    val screenTime = rememberScreenTimeInsightsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val cloudAudioRecorder = remember { PromiseAudioRecorder(context.applicationContext) }
    val speechClient = remember { PromiseSpeechClient() }
    var showVoiceChoice by remember { mutableStateOf(false) }
    var cloudRecording by remember { mutableStateOf(false) }
    var cloudTranscribing by remember { mutableStateOf(false) }
    var cloudVoiceJob by remember { mutableStateOf<Job?>(null) }
    var activeAzureVoiceVersion by remember { mutableStateOf<Long?>(null) }
    var activePhoneVoiceVersion by remember { mutableStateOf<Long?>(null) }
    var pendingAzureVoiceVersion by remember { mutableStateOf<Long?>(null) }
    state.pendingExitFriction?.takeIf { state.session != null }?.let { friction ->
        EndCommitmentDialog(
            friction = friction,
            phraseError = state.exitPhraseError,
            onConfirm = state::confirmEndCommitment,
            onKeep = state::cancelEndCommitment
        )
    }
    if (state.pendingEmergencyExit && state.session != null) {
        EmergencyExitDialog(
            onConfirm = state::confirmEmergencyExit,
            onKeep = state::cancelEmergencyExit
        )
    }
    val recoveryPolicy = state.recoveryPolicy
    val listState = rememberLazyListState()
    val bootstrapCompletionLogged = remember { booleanArrayOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            activePhoneVoiceVersion?.let { state.applyVoiceTranscript(transcript, it) }
            activePhoneVoiceVersion = null
        } else {
            activePhoneVoiceVersion?.let {
                state.markVoiceInputUnavailable("Voice cancelled. You can still type the promise.", it)
            }
            activePhoneVoiceVersion = null
        }
    }
    fun startAzureVoiceCapture(requestVersion: Long) {
        if (!state.isVoiceCaptureCurrent(requestVersion)) return
        if (cloudVoiceJob?.isActive == true) return
        cloudAudioRecorder.prepareForCapture()
        activeAzureVoiceVersion = requestVersion
        state.markVoiceInputUnavailable("Recording… tap the mic to finish (15 seconds max).", requestVersion)
        cloudRecording = true
        cloudVoiceJob = coroutineScope.launch {
            try {
                val wavAudio = cloudAudioRecorder.recordWav()
                cloudRecording = false
                cloudTranscribing = true
                state.markVoiceInputUnavailable("Transcribing with Azure Speech…", requestVersion)
                val transcript = withContext(Dispatchers.IO) {
                    speechClient.transcribe(wavAudio)
                }
                state.applyVoiceTranscript(transcript.transcript, requestVersion)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when {
                    error is SecurityException ->
                        "Microphone permission is unavailable. Use phone speech or type instead."
                    error is PromiseSpeechException && error.httpStatus == 503 ->
                        "Azure Speech is not configured. Use phone speech or type instead."
                    else ->
                        "Azure voice could not transcribe this clip. Use phone speech or type instead."
                }
                Log.w("PromiseVoice", "Azure transcription failed kind=${error.javaClass.simpleName}")
                state.markVoiceInputUnavailable(message, requestVersion)
            } finally {
                cloudRecording = false
                cloudTranscribing = false
                if (activeAzureVoiceVersion == requestVersion) activeAzureVoiceVersion = null
                cloudVoiceJob = null
            }
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val requestVersion = pendingAzureVoiceVersion
        pendingAzureVoiceVersion = null
        if (granted && requestVersion != null) {
            startAzureVoiceCapture(requestVersion)
        } else if (!granted && requestVersion != null) {
            state.markVoiceInputUnavailable(
                "Microphone permission is needed for Azure voice. You can use phone speech or type.",
                requestVersion
            )
        }
    }
    val usageAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        screenTime.refresh(coroutineScope, force = true)
    }

    fun launchPhoneSpeechInput() {
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
            activePhoneVoiceVersion = state.beginVoiceCapture()
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            activePhoneVoiceVersion?.let {
                state.markVoiceInputUnavailable("Voice input is not available on this phone.", it)
            }
            activePhoneVoiceVersion = null
        }
    }

    fun launchVoicePromiseInput() {
        if (cloudRecording) {
            cloudAudioRecorder.requestStop()
        } else if (!cloudTranscribing) {
            showVoiceChoice = true
        }
    }

    fun cancelCloudVoiceForEditing() {
        val wasRecording = cloudRecording
        if (cloudRecording || cloudTranscribing) cloudVoiceJob?.cancel()
        if (wasRecording) cloudAudioRecorder.requestStop()
    }

    fun cancelCloudVoiceWhenBackgrounded() {
        val wasRecording = cloudRecording
        if (!wasRecording && !cloudTranscribing) return
        val requestVersion = activeAzureVoiceVersion
        cloudVoiceJob?.cancel()
        if (wasRecording) cloudAudioRecorder.requestStop()
        if (requestVersion != null) {
            state.cancelVoiceCapture(
                requestVersion,
                "Voice stopped when Strlix left the foreground. Tap the mic to try again."
            )
            activeAzureVoiceVersion = null
        }
    }

    fun updatePromiseInput(text: String) {
        cancelCloudVoiceForEditing()
        state.updatePromiseText(text)
    }

    fun selectPromiseExample(example: ExamplePromise) {
        cancelCloudVoiceForEditing()
        state.selectExamplePromise(example)
    }

    fun selectAzureVoice() {
        showVoiceChoice = false
        val requestVersion = state.beginVoiceCapture("Waiting for microphone permission…")
        pendingAzureVoiceVersion = requestVersion
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingAzureVoiceVersion = null
            startAzureVoiceCapture(requestVersion)
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showVoiceChoice) {
        VoiceInputChoiceDialog(
            onDismiss = { showVoiceChoice = false },
            onPhoneRecognizer = {
                showVoiceChoice = false
                launchPhoneSpeechInput()
            },
            onAzureSpeech = ::selectAzureVoice
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            if (cloudRecording) cloudAudioRecorder.requestStop()
            cloudVoiceJob?.cancel()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = HomeScreenLifecycleObserver(
            onResume = {
                screenTime.refresh(coroutineScope, force = true)
                coroutineScope.launch { state.refreshAccessibilityStatus("resume") }
            },
            onStop = ::cancelCloudVoiceWhenBackgrounded
        )
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    HomeScreenPollingEffect(state)

    if (state.showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onDismiss = state::dismissAccessibilityDisclosure,
            onContinue = state::continueToAccessibilitySettings,
            onOpenPrivacyPolicy = state::openPrivacyPolicy,
            consentSaveFailed = state.accessibilityDisclosureSaveFailed
        )
    }

    LaunchedEffect(state.isBootstrapped) {
        if (state.isBootstrapped) screenTime.refresh(coroutineScope)
    }

    fun requestUsageAccess() {
        try {
            usageAccessLauncher.launch(
                Intent(ScreenTimeUsageReader.USAGE_ACCESS_SETTINGS_ACTION)
            )
        } catch (_: ActivityNotFoundException) {
            screenTime.markUnavailable()
        }
    }

    // Grant lives only as long as its own commitment: re-check whenever the session changes.
    LaunchedEffect(state.session?.id, state.session?.status, state.isBootstrapped) {
        if (state.isBootstrapped) mail.refresh()
    }

    LaunchedEffect(Unit) {
        state.bootstrapIfNeeded()
        if (!bootstrapCompletionLogged[0]) {
            bootstrapCompletionLogged[0] = true
            Log.d(
                "PhoneCodexUIPerf",
                "bootstrapReady bootstrapped=${state.isBootstrapped} " +
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
                showAdvancedEngineering = state.showAdvancedEngineering,
                hasMailTask = mail.thread != null
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
            onPromiseTextChange = ::updatePromiseInput,
            onUnderstandPromise = state::understandPromise,
            onVoicePromise = ::launchVoicePromiseInput,
            voiceInputStatus = state.voiceInputStatus,
            voiceRecording = cloudRecording,
            voiceBusy = cloudRecording || cloudTranscribing,
            understandingInProgress = state.understandingInProgress,
            onOpenAdvanced = state::toggleAdvancedControls,
            understandingExtra = mailHelperSectionOrNull(mail),
            todayInsight = {
                TodayScreenTimeCard(
                    result = screenTime.result,
                    loading = screenTime.loading,
                    onEnableAccess = ::requestUsageAccess,
                    onRefresh = { screenTime.refresh(coroutineScope, force = true) }
                )
            }
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
                        protectionLevel = protectionLevel,
                        permanentSafetyRuleCount = state.enabledGuardrailCount,
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

                HomeListItem.TodayInsight -> {
                    TodayScreenTimeCard(
                        result = screenTime.result,
                        loading = screenTime.loading,
                        onEnableAccess = ::requestUsageAccess,
                        onRefresh = { screenTime.refresh(coroutineScope, force = true) }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }

                HomeListItem.ActiveCommitment -> {
                    val session = state.session
                    if (session != null) {
                        ActiveCommitmentCard(
                            session = session,
                            isLocked = state.isSessionLocked,
                            nowMillis = state.nowMillis,
                            recoveryPolicy = recoveryPolicy,
                            onEndCommitment = state::requestEndCommitment,
                            emergencyExit = state.emergencyExitAvailability(),
                            emergencyExitNextAvailable = state.emergencyExitNextAvailableAtMillis()
                                ?.let(::formatClockTime),
                            onEmergencyExit = state::requestEmergencyExit
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }

                HomeListItem.LastDecision -> {
                    LastDecisionCard(
                        debugState = state.debugState,
                        currentSessionId = state.session?.id,
                        currentSessionGoal = state.session?.goal,
                        nowMillis = state.nowMillis
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }

                HomeListItem.PromiseComposer -> {
                    PromiseComposerSection(
                        promiseText = state.promiseText,
                        onPromiseTextChange = ::updatePromiseInput,
                        onExampleSelected = ::selectPromiseExample,
                        onUnderstandPromise = state::understandPromise,
                        onVoicePromise = ::launchVoicePromiseInput,
                        voiceInputStatus = state.voiceInputStatus,
                        voiceRecording = cloudRecording,
                        voiceBusy = cloudRecording || cloudTranscribing,
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
                            onStrongerConfirmChange = state::updateStrongerConfirmAcknowledged,
                            extraSection = mailHelperSectionOrNull(mail),
                            localAppTargetMatches = state.localAppTargetMatches,
                            localAppTargetLookupRequested = state.localAppTargetLookupRequested,
                            localAppTargetLookupLoading = state.installedAppsLoading,
                            localAppTargetLookupError = state.installedAppsLoadError,
                            onFindLocalAppTarget = { state.requestLocalAppTargetLookup(coroutineScope) },
                            onRefreshLocalAppTarget = { state.refreshLocalAppTargetLookup(coroutineScope) },
                            onSelectLocalAppTarget = state::selectLocalAppTarget
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }

                HomeListItem.MailTask -> {
                    MailTaskCard(mail)
                    Spacer(modifier = Modifier.height(24.dp))
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

                HomeListItem.AdvancedPrivacyPolicy -> {
                    AdvancedPrivacyPolicyCard(onOpenPrivacyPolicy = state::openPrivacyPolicy)
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

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var current: Context? = this
    while (current != null) {
        if (current is LifecycleOwner) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    PhoneCodexTheme {
        HomeScreen()
    }
}
