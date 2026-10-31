package com.phonecodex.app.ui.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.phonecodex.app.accessibility.AccessibilityHealthChecker
import com.phonecodex.app.data.AccessibilityHeartbeat
import com.phonecodex.app.data.AppRulesStore
import com.phonecodex.app.data.AccessibilityDisclosureConsentStore
import com.phonecodex.app.data.CommitmentTeardown
import com.phonecodex.app.data.EmergencyExitStore
import com.phonecodex.app.data.DebugState
import com.phonecodex.app.data.DebugStateStore
import com.phonecodex.app.data.EventLogStore
import com.phonecodex.app.data.FeedbackStore
import com.phonecodex.app.data.InstalledAppsReader
import com.phonecodex.app.data.PermanentGuardrailsStore
import com.phonecodex.app.data.ProtectionViolationState
import com.phonecodex.app.data.ProtectionViolationStore
import com.phonecodex.app.data.RuntimeDiagStore
import com.phonecodex.app.data.SafeAppsStore
import com.phonecodex.app.data.SessionStore
import com.phonecodex.app.data.StudyWorldSettingsStore
import com.phonecodex.app.domain.delegation.MailDelegationIntent
import com.phonecodex.app.domain.delegation.MailDelegationOfferLaw
import com.phonecodex.app.domain.delegation.MailDelegationRequest
import com.phonecodex.app.domain.diagnostics.BackendHealthProbe
import com.phonecodex.app.domain.diagnostics.BackendProbeKind
import com.phonecodex.app.domain.diagnostics.BackendProbeResult
import com.phonecodex.app.domain.diagnostics.diagnosticErrorKind
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.ConfirmedSessionPolicy
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FeedbackEntry
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.FocusWorld
import com.phonecodex.app.domain.model.InstalledApp
import com.phonecodex.app.domain.model.PermanentGuardrail
import com.phonecodex.app.domain.model.RecoveryPolicy
import com.phonecodex.app.domain.recovery.RecoveryPolicyDefaults
import com.phonecodex.app.domain.model.SafeApp
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.policy.PolicyEngine
import com.phonecodex.app.domain.protection.ProtectionReliabilityGate
import com.phonecodex.app.domain.protection.ProtectionReliabilityInput
import com.phonecodex.app.domain.protection.ProtectionReliabilityUiModel
import com.phonecodex.app.domain.promise.ClarificationChoiceCarry
import com.phonecodex.app.domain.promise.ClarificationChoiceSnapshot
import com.phonecodex.app.domain.promise.CompiledPromiseMapper
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.EffectivePromisePolicy
import com.phonecodex.app.domain.promise.FocusPromiseParser
import com.phonecodex.app.domain.promise.LegacyScopeContractGuard
import com.phonecodex.app.domain.promise.PromiseCompilerClient
import com.phonecodex.app.domain.promise.PromiseTextCleanup
import com.phonecodex.app.domain.promise.PromiseUnderstandingRequestGate
import com.phonecodex.app.domain.promise.LocalAppTargetSelectionLaw
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.promise.PromiseUnderstandingResolver
import com.phonecodex.app.domain.scope.TypedScopeRecord
import com.phonecodex.app.domain.session.CommitmentExitGate
import com.phonecodex.app.domain.session.ConfirmedSessionPolicyCodec
import com.phonecodex.app.domain.session.EmergencyExitGate
import com.phonecodex.app.domain.session.ExitFriction
import com.phonecodex.app.domain.session.SessionManager
import com.phonecodex.app.protection.ProtectionHeartbeatController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Home screen state holder. Store I/O lives here (and in polling effects) — never inside
 * composable bodies. Marked [Stable] so unrelated snapshot fields do not invalidate siblings.
 */
@Stable
internal class HomeScreenState(
    val context: Context,
    val sessionManager: SessionManager,
    val sessionStore: SessionStore,
    val eventLogStore: EventLogStore,
    val debugStateStore: DebugStateStore,
    val runtimeDiagStore: RuntimeDiagStore,
    val feedbackStore: FeedbackStore,
    val appRulesStore: AppRulesStore,
    val studyWorldSettingsStore: StudyWorldSettingsStore,
    val permanentGuardrailsStore: PermanentGuardrailsStore,
    val safeAppsStore: SafeAppsStore,
    val protectionViolationStore: ProtectionViolationStore,
    val installedAppsReader: InstalledAppsReader,
    val policyEngine: PolicyEngine,
    val focusPromiseParser: FocusPromiseParser,
    val promiseCompilerClient: PromiseCompilerClient = PromiseCompilerClient(),
    val studyWorld: FocusWorld,
    val createdAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    val emergencyExitStore: EmergencyExitStore = EmergencyExitStore(context),
    val recoveryPolicy: RecoveryPolicy = RecoveryPolicyDefaults.default()
) {
    private val commitmentTeardown = CommitmentTeardown(
        sessionStore,
        appRulesStore,
        eventLogStore::addEvent,
        studyWorldSettingsStore
    )
    private val voiceInputRequestGate = PromiseUnderstandingRequestGate()

    /** Delegated mail helper; attached by [HomeScreen]. Null in previews/tests. */
    var mailTask: MailTaskUiState? = null

    var promiseText by mutableStateOf("")
    var promiseUnderstanding by mutableStateOf<PromiseUnderstanding?>(null)
    var promiseDraft by mutableStateOf<FocusPromise?>(null)
    var selectedClarificationOptionId by mutableStateOf<String?>(null)

    /**
     * Clarification options exactly as last shown, bound to the promise text they
     * were generated for. On a pick we echo the snapshot + tapped option so the
     * backend resolves the choice from what the user saw, never from a fresh
     * model response that may reuse the same id for a different scope.
     */
    private var lastClarificationSnapshot: ClarificationChoiceSnapshot? = null
    var strongerConfirmAcknowledged by mutableStateOf(false)

    /** Non-null while the Strict/Locked end-commitment confirm step is showing. */
    var pendingExitFriction by mutableStateOf<ExitFriction?>(null)
        private set
    var exitPhraseError by mutableStateOf<String?>(null)
        private set
    /** True while the emergency-exit confirm step is showing. Never survives recreation. */
    var pendingEmergencyExit by mutableStateOf(false)
        private set
    var startBlockedMessage by mutableStateOf<String?>(null)
    var understandingInProgress by mutableStateOf(false)
    var voiceInputStatus by mutableStateOf<String?>(null)

    var goal by mutableStateOf("")
    var session by mutableStateOf<FocusSession?>(null)
    var nowMillis by mutableStateOf(System.currentTimeMillis())
    var lastDecisionText by mutableStateOf("No policy check yet")

    var recentEvents by mutableStateOf<List<String>>(emptyList())
    var debugState by mutableStateOf(EMPTY_DEBUG_STATE)
    var protectionViolationState by mutableStateOf(EMPTY_VIOLATION_STATE)
    var recentFeedback by mutableStateOf<List<FeedbackEntry>>(emptyList())
    private var advancedSnapshotLoaded: Boolean = false

    var appRules by mutableStateOf<List<AppRule>>(emptyList())
    var installedApps by mutableStateOf<List<InstalledApp>>(emptyList())
    var installedAppsLoading by mutableStateOf(false)
    var installedAppsLoaded by mutableStateOf(false)
    var installedAppsLoadError by mutableStateOf<String?>(null)
    var installedAppsSearch by mutableStateOf("")
    var localAppTargetLookupRequested by mutableStateOf(false)
    val localAppTargetMatches: List<InstalledApp>
        get() {
            val candidate = promiseDraft?.localAppTargetSelection ?: return emptyList()
            if (!localAppTargetLookupRequested || !installedAppsLoaded) return emptyList()
            return LocalAppTargetSelectionLaw.exactMatches(installedApps, candidate.label)
        }
    var studyWorldSettings by mutableStateOf(DEFAULT_STUDY_SETTINGS)
    var permanentGuardrails by mutableStateOf<List<PermanentGuardrail>>(emptyList())
    var safeApps by mutableStateOf<List<SafeApp>>(emptyList())
    var safeAppsSearch by mutableStateOf("")
    var isAccessibilityEnabled by mutableStateOf(false)
    var isAccessibilityAlive by mutableStateOf(false)
    var showAccessibilityDisclosure by mutableStateOf(false)
        private set
    var accessibilityDisclosureSaveFailed by mutableStateOf(false)
        private set
    var lastHeartbeatMillis by mutableStateOf(0L)
    var lastHeartbeatPackage by mutableStateOf("")
    var lastHeartbeatReason by mutableStateOf("")
    var lastProtectionCheckSummary by mutableStateOf<String?>(null)
    var protectionCheckInProgress by mutableStateOf(false)
    var overlayActive by mutableStateOf(false)
    var backendProbeResult by mutableStateOf(BackendProbeResult.idle())
    var backendTestInProgress by mutableStateOf(false)
    /** ElapsedRealtime of last successful /health probe — used for sticky status. */
    private var lastBackendSuccessElapsedMs: Long? = null
    private var lastProbeAttemptElapsedMs: Long = 0L
    private var backendAutoProbeStarted: Boolean = false
    private var lastProtectionStatusLogKey: String? = null
    var showAdvancedControls by mutableStateOf(false)
    var showAdvancedEngineering by mutableStateOf(false)
    var isBootstrapped by mutableStateOf(false)
    private var bootstrapStarted: Boolean = false
    private var diagnosticsScope: CoroutineScope? = null
    private val understandingRequestGate = PromiseUnderstandingRequestGate()

    val isSessionActive: Boolean
        get() = session?.status == SessionStatus.ACTIVE

    val isSessionLocked: Boolean
        get() = session?.status == SessionStatus.LOCKED

    val hasStoredSession: Boolean
        get() = isSessionActive || isSessionLocked

    val enabledGuardrailCount: Int
        get() = permanentGuardrails.count { it.enabled }

    val enabledGuardrails: List<PermanentGuardrail>
        get() = permanentGuardrails.filter { it.enabled }

    val protectionReliability: ProtectionReliabilityUiModel
        get() = ProtectionReliabilityGate.evaluate(protectionReliabilityInput())

    suspend fun refreshAccessibilityStatus(source: String = "unspecified") {
        val previousEnabled = isAccessibilityEnabled
        val previousAlive = isAccessibilityAlive
        val snapshot = withContext(Dispatchers.IO) {
            Pair(
                AccessibilityHealthChecker.isPhoneCodexAccessibilityEnabled(context),
                runtimeDiagStore.getHeartbeat()
            )
        }
        if (snapshot.first != isAccessibilityEnabled) {
            isAccessibilityEnabled = snapshot.first
        }
        if (snapshot.second.serviceAlive != isAccessibilityAlive) {
            isAccessibilityAlive = snapshot.second.serviceAlive
        }
        if (snapshot.second.lastEventMillis != lastHeartbeatMillis) {
            lastHeartbeatMillis = snapshot.second.lastEventMillis
            lastHeartbeatPackage = snapshot.second.packageName
            lastHeartbeatReason = snapshot.second.reason
        }
        val now = System.currentTimeMillis()
        if (now != nowMillis) nowMillis = now
        val model = protectionReliability
        val statusLogKey = listOf(
            model.level,
            isBootstrapped,
            isAccessibilityEnabled,
            isAccessibilityAlive,
            hasStoredSession,
            enabledGuardrailCount
        ).joinToString("|")
        if (statusLogKey != lastProtectionStatusLogKey) {
            lastProtectionStatusLogKey = statusLogKey
            Log.i(
                TAG_PROTECTION_STATUS,
                "refresh=$source previousEnabled=$previousEnabled previousAlive=$previousAlive " +
                    "enabled=$isAccessibilityEnabled alive=$isAccessibilityAlive " +
                    "session=$hasStoredSession guardrails=$enabledGuardrailCount " +
                    "initialized=$isBootstrapped level=${model.level} " +
                    runtimeDiagStore.serviceLeaseDiagnostics()
            )
        }
    }

    fun protectionReliabilityInput(): ProtectionReliabilityInput =
        ProtectionReliabilityInput(
            accessibilityEnabled = isAccessibilityEnabled,
            accessibilityAlive = isAccessibilityAlive,
            lastEventMillis = lastHeartbeatMillis,
            lastEventPackage = lastHeartbeatPackage.ifBlank { null },
            lastEventReason = lastHeartbeatReason.ifBlank { null },
            hasActiveCommitment = hasStoredSession,
            permanentGuardrailOn = enabledGuardrailCount > 0,
            backendKind = backendProbeResult.kind,
            backendLoopback = ProtectionReliabilityGate.backendLoopback(),
            nowMillis = nowMillis,
            commitmentScopeUnverified =
                studyWorldSettings.typedScopeRecord == TypedScopeRecord.CORRUPT,
            statusInitialized = isBootstrapped
        )

    val developerDiagnostics: DeveloperDiagnosticsUiModel
        get() = buildDeveloperDiagnosticsUiModel(
            isAccessibilityEnabled = isAccessibilityEnabled,
            isAccessibilityAlive = isAccessibilityAlive,
            session = session,
            overlayActive = overlayActive,
            debugState = debugState,
            probeResult = backendProbeResult,
            testInProgress = backendTestInProgress,
            visionExperimentEnabled = studyWorldSettings.visionExperimentEnabled,
            nowEpochMs = nowMillis
        )

    fun attachDiagnosticsScope(scope: CoroutineScope) {
        diagnosticsScope = scope
        if (isBootstrapped) {
            scheduleBackendAutoProbe()
        }
    }

    fun openPrivacyPolicy() {
        launchPrivacyPolicy(context)
    }

    fun openAccessibilitySettings() {
        accessibilityDisclosureSaveFailed = false
        showAccessibilityDisclosure = true
    }

    fun dismissAccessibilityDisclosure() {
        accessibilityDisclosureSaveFailed = false
        showAccessibilityDisclosure = false
    }

    fun continueToAccessibilitySettings() {
        if (!showAccessibilityDisclosure) return
        if (!AccessibilityDisclosureConsentStore(context).acceptCurrentDisclosure()) {
            accessibilityDisclosureSaveFailed = true
            return
        }
        accessibilityDisclosureSaveFailed = false
        showAccessibilityDisclosure = false
        launchAccessibilitySettings(context)
    }

    /**
     * First paint uses empty defaults. Essential prefs load off the main thread so the
     * LazyColumn can appear immediately.
     */
    suspend fun bootstrapIfNeeded() {
        if (bootstrapStarted) return
        bootstrapStarted = true
        val started = SystemClock.elapsedRealtime()
        val snapshot = withContext(Dispatchers.IO) {
            val heartbeat = runtimeDiagStore.getHeartbeat()
            val storedSession = sessionStore.getStoredSession()
            appRulesStore.restoreStaleCommitmentRules(hasActiveSession = storedSession != null)
                .eventMessage?.let(eventLogStore::addEvent)
            studyWorldSettingsStore
                .restoreStaleCommitmentTuningDefaults(hasActiveSession = storedSession != null)
                .eventMessage?.let(eventLogStore::addEvent)
            BootstrapSnapshot(
                session = storedSession,
                accessibilityEnabled =
                    AccessibilityHealthChecker.isPhoneCodexAccessibilityEnabled(context),
                studyWorldSettings = studyWorldSettingsStore.getSettings(),
                appRules = appRulesStore.getRules(),
                guardrails = permanentGuardrailsStore.getGuardrails(),
                safeApps = safeAppsStore.getSafeApps(),
                accessibilityAlive = heartbeat.serviceAlive,
                lastHeartbeatMillis = heartbeat.lastEventMillis,
                lastHeartbeatPackage = heartbeat.packageName,
                lastHeartbeatReason = heartbeat.reason
            )
        }
        session = snapshot.session
        isAccessibilityEnabled = snapshot.accessibilityEnabled
        studyWorldSettings = snapshot.studyWorldSettings
        appRules = snapshot.appRules
        permanentGuardrails = snapshot.guardrails
        safeApps = snapshot.safeApps
        isAccessibilityAlive = snapshot.accessibilityAlive
        lastHeartbeatMillis = snapshot.lastHeartbeatMillis
        lastHeartbeatPackage = snapshot.lastHeartbeatPackage
        lastHeartbeatReason = snapshot.lastHeartbeatReason
        isBootstrapped = true
        Log.d(
            "PhoneCodexUIPerf",
            "bootstrap=${SystemClock.elapsedRealtime() - started}ms"
        )
        scheduleBackendAutoProbe()
    }

    /**
     * One quiet health check after first paint so diagnostics are truthful without
     * requiring a manual tap. Debounced + sticky — never flashes offline on a blip.
     */
    private fun scheduleBackendAutoProbe() {
        if (backendAutoProbeStarted) return
        val scope = diagnosticsScope ?: return
        backendAutoProbeStarted = true
        scope.launch {
            delay(400)
            testBackend(force = false, allowRetry = true)
        }
    }

    fun loadInstalledApps(coroutineScope: CoroutineScope) {
        if (installedAppsLoading || (installedAppsLoaded && installedAppsLoadError == null)) return
        installedAppsLoading = true
        installedAppsLoadError = null
        coroutineScope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val apps = withContext(Dispatchers.Default) {
                    installedAppsReader.getLaunchableApps()
                }
                installedApps = apps
                installedAppsLoaded = true
                Log.d(
                    "PhoneCodexUIPerf",
                    "installedAppsLoad=${SystemClock.elapsedRealtime() - started}ms count=${apps.size}"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                installedAppsLoaded = false
                installedAppsLoadError = "Couldn't load apps with a launcher icon on this phone. Try again or edit the promise."
                Log.w("PhoneCodexAppPicker", "Installed-app lookup failed: ${error.javaClass.simpleName}")
            } finally {
                installedAppsLoading = false
            }
        }
    }

    fun requestLocalAppTargetLookup(coroutineScope: CoroutineScope) {
        refreshLocalAppTargetLookup(coroutineScope)
    }

    fun refreshLocalAppTargetLookup(coroutineScope: CoroutineScope) {
        if (promiseDraft?.localAppTargetSelection == null) return
        localAppTargetLookupRequested = true
        installedAppsLoaded = false
        installedAppsLoadError = null
        loadInstalledApps(coroutineScope)
    }

    fun selectLocalAppTarget(app: InstalledApp) {
        val draft = promiseDraft ?: return
        val uniqueMatch = localAppTargetMatches.singleOrNull()
        if (uniqueMatch?.packageName != app.packageName) {
            startBlockedMessage = "That app is not a unique exact match. Choose again or edit the promise."
            return
        }
        if (!LocalAppTargetSelectionLaw.canSelect(app)) {
            startBlockedMessage = "This phone-safety app stays available and can't be blocked by this promise."
            return
        }
        val bound = LocalAppTargetSelectionLaw.bindSelectedApp(draft, app)
        if (bound == null) {
            startBlockedMessage = "The app target no longer matches this preview. Edit the promise and understand it again."
            return
        }
        promiseDraft = bound
        selectedClarificationOptionId = null
        strongerConfirmAcknowledged = false
        localAppTargetLookupRequested = false
        startBlockedMessage = null
        promiseUnderstanding = buildPromiseUnderstanding(
            draft = bound,
            alwaysBlockedLabels = enabledGuardrails.map(::guardrailBlockedLabel),
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = null,
            strongerConfirmAcknowledged = false,
            existingAppRules = appRulesStore.getRules(),
            fallbackLockAttemptThreshold = studyWorldSettings.lockAttemptThreshold
                .takeIf { studyWorldSettings.lockAttemptThresholdCustomized }
        )
    }

    fun selectExamplePromise(example: ExamplePromise) {
        voiceInputRequestGate.invalidate()
        promiseText = example.promiseText
        clearUnderstanding()
    }

    fun updatePromiseText(text: String) {
        voiceInputRequestGate.invalidate()
        promiseText = PromiseTextCleanup.normalizeTyped(text)
        voiceInputStatus = null
        if (promiseUnderstanding != null || understandingInProgress) {
            clearUnderstanding()
        }
    }

    fun applyVoiceTranscript(transcript: String, requestVersion: Long) {
        if (!voiceInputRequestGate.isCurrent(requestVersion)) return
        val cleanTranscript = PromiseTextCleanup.normalizeTyped(transcript.trim())
        if (cleanTranscript.isEmpty()) {
            voiceInputStatus = "I did not hear a promise. Try again or type it."
            return
        }
        promiseText = cleanTranscript
        // Voice fills text only — never auto-starts commitment.
        voiceInputStatus = "Voice captured. Edit if needed, then tap the arrow to understand."
        clearUnderstanding()
    }

    fun beginVoiceCapture(status: String = "Listening…"): Long {
        val requestVersion = voiceInputRequestGate.begin()
        voiceInputStatus = status
        return requestVersion
    }

    fun isVoiceCaptureCurrent(requestVersion: Long): Boolean =
        voiceInputRequestGate.isCurrent(requestVersion)

    fun markVoiceInputUnavailable(message: String, requestVersion: Long) {
        if (voiceInputRequestGate.isCurrent(requestVersion)) voiceInputStatus = message
    }

    fun cancelVoiceCapture(requestVersion: Long, message: String) {
        if (!voiceInputRequestGate.isCurrent(requestVersion)) return
        voiceInputRequestGate.invalidate()
        voiceInputStatus = message
    }

    fun understandPromise(selectedOptionId: String? = null) {
        val text = PromiseTextCleanup.normalizeTyped(promiseText.trim())
        if (text.isEmpty()) return
        if (text != promiseText) {
            promiseText = text
        }
        // "…but let me ask Strlix to check mail from X" is a delegated-task request, decided
        // on-device. Only the enforcement part goes to the Promise Compiler.
        val mailRequest = MailDelegationIntent.detect(text)
        val commitmentText = mailRequest.commitmentText
        val fallbackDurationMinutes = studyWorldSettings.durationMinutes
        val fallbackLockAttemptThreshold = studyWorldSettings.lockAttemptThreshold
            .takeIf { studyWorldSettings.lockAttemptThresholdCustomized }

        val scope = diagnosticsScope
        if (scope == null) {
            Log.w(TAG_UNDERSTAND, "compile skipped — no coroutine scope; using local fallback")
            applyLocalUnderstanding(commitmentText, mailRequest)
            return
        }

        if (understandingInProgress) return
        val requestVersion = understandingRequestGate.begin()
        understandingInProgress = true
        promiseUnderstanding = null
        promiseDraft = null
        localAppTargetLookupRequested = false
        startBlockedMessage = null
        if (selectedOptionId == null) {
            selectedClarificationOptionId = null
            strongerConfirmAcknowledged = false
            // A fresh turn-1 compile invalidates any cached option snapshot.
            lastClarificationSnapshot = null
        }
        Log.i(
            TAG_UNDERSTAND,
            "compile request started v=$requestVersion len=${text.length} " +
                "selected=${selectedOptionId ?: "none"}"
        )

        // Read carry state on the main thread before launching.
        val choicePayload = selectedOptionId
            ?.takeIf { it.isNotBlank() }
            ?.let { ClarificationChoiceCarry.payloadFor(it, commitmentText, lastClarificationSnapshot) }

        scope.launch {
            try {
                val compiledResult = withContext(Dispatchers.IO) {
                    runCatching {
                        promiseCompilerClient.compile(
                            promise = commitmentText,
                            selectedClarificationOptionId = selectedOptionId,
                            selectedClarificationOptionFullTextJson = choicePayload?.selectedOptionJson,
                            priorClarificationOptionsSnapshotJson = choicePayload?.snapshotJson,
                            priorClarificationPromiseText = choicePayload?.snapshotPromiseText
                        )
                    }
                }
                if (!understandingRequestGate.isCurrent(requestVersion)) {
                    Log.i(TAG_UNDERSTAND, "compile result discarded — stale v=$requestVersion")
                    return@launch
                }

                compiledResult.fold(
                    onSuccess = {
                        lastClarificationSnapshot =
                            ClarificationChoiceCarry.snapshotFrom(commitmentText, it.clarificationOptionsRawJson)
                        Log.i(
                            TAG_UNDERSTAND,
                            "compile success v=$requestVersion clarificationRequired=${it.clarificationRequired} " +
                                "canStart=${it.canStartCommitment} options=${it.clarificationOptions.size} " +
                                "verification=${it.optionVerification ?: "none"} " +
                                "cachedOptionsBytes=${lastClarificationSnapshot?.optionsJson?.length ?: 0}"
                        )
                    },
                    onFailure = { err ->
                        Log.w(
                            TAG_UNDERSTAND,
                            "compile failure v=$requestVersion — fallback used: ${diagnosticErrorKind(err)}"
                        )
                    }
                )

                val resolved = PromiseUnderstandingResolver.resolve(
                    text = commitmentText,
                    compiledResult = compiledResult,
                    parser = focusPromiseParser,
                    selectedClarificationOptionId = selectedOptionId,
                    namedPackageHint = namedPackageHint(),
                    fallbackDurationMinutes = fallbackDurationMinutes
                )

                if (!understandingRequestGate.isCurrent(requestVersion)) {
                    Log.i(TAG_UNDERSTAND, "resolved result discarded — stale v=$requestVersion")
                    return@launch
                }

                val draft = MailDelegationOfferLaw.withDeterministicGmailBlock(resolved.draft)
                promiseDraft = draft
                mailTask?.onPreview(mailRequest, draft)
                selectedClarificationOptionId = selectedOptionId
                promiseUnderstanding = buildPromiseUnderstanding(
                    draft = draft,
                    alwaysBlockedLabels = enabledGuardrails.map(::guardrailBlockedLabel),
                    source = if (resolved.fromCompiler) {
                        UnderstandingSource.PROMISE_COMPILER
                    } else {
                        UnderstandingSource.LOCAL_PREVIEW
                    },
                    selectedClarificationOptionId = selectedOptionId,
                    strongerConfirmAcknowledged = strongerConfirmAcknowledged,
                    existingAppRules = appRulesStore.getRules(),
                    fallbackLockAttemptThreshold = fallbackLockAttemptThreshold
                )
                Log.i(
                    TAG_UNDERSTAND,
                    "preview ready source=${if (resolved.fromCompiler) "AI" else "local"} " +
                        "canStart=${promiseUnderstanding?.canStart} " +
                        "clarificationRequired=${draft.needsClarification}"
                )
            } finally {
                if (understandingRequestGate.isCurrent(requestVersion)) {
                    understandingInProgress = false
                }
            }
        }
    }

    fun selectClarificationOption(optionId: String) {
        if (optionId.isBlank()) return
        selectedClarificationOptionId = optionId
        strongerConfirmAcknowledged = false
        understandPromise(selectedOptionId = optionId)
    }

    fun updateStrongerConfirmAcknowledged(acknowledged: Boolean) {
        strongerConfirmAcknowledged = acknowledged
        val draft = promiseDraft ?: return
        promiseUnderstanding = buildPromiseUnderstanding(
            draft = draft,
            alwaysBlockedLabels = enabledGuardrails.map(::guardrailBlockedLabel),
            source = promiseUnderstanding?.source ?: UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = selectedClarificationOptionId,
            strongerConfirmAcknowledged = acknowledged,
            existingAppRules = appRulesStore.getRules(),
            fallbackLockAttemptThreshold = studyWorldSettings.lockAttemptThreshold
                .takeIf { studyWorldSettings.lockAttemptThresholdCustomized }
        )
    }

    private fun applyLocalUnderstanding(text: String, mailRequest: MailDelegationRequest) {
        val resolved = PromiseUnderstandingResolver.resolve(
            text = text,
            compiledResult = Result.failure(IllegalStateException("no coroutine scope")),
            parser = focusPromiseParser,
            namedPackageHint = namedPackageHint(),
            fallbackDurationMinutes = studyWorldSettings.durationMinutes
        )
        val draft = MailDelegationOfferLaw.withDeterministicGmailBlock(resolved.draft)
        promiseDraft = draft
        localAppTargetLookupRequested = false
        mailTask?.onPreview(mailRequest, draft)
        promiseUnderstanding = buildPromiseUnderstanding(
            draft = draft,
            alwaysBlockedLabels = enabledGuardrails.map(::guardrailBlockedLabel),
            source = UnderstandingSource.LOCAL_PREVIEW,
            selectedClarificationOptionId = null,
            strongerConfirmAcknowledged = false,
            existingAppRules = appRulesStore.getRules(),
            fallbackLockAttemptThreshold = studyWorldSettings.lockAttemptThreshold
                .takeIf { studyWorldSettings.lockAttemptThresholdCustomized }
        )
        startBlockedMessage = null
        selectedClarificationOptionId = null
        strongerConfirmAcknowledged = false
        lastClarificationSnapshot = null
        Log.i(
            TAG_UNDERSTAND,
            "local-only preview canStart=${!draft.needsClarification} " +
                "clarificationRequired=${draft.needsClarification}"
        )
    }

    private fun namedPackageHint(): String? {
        val last = debugState.lastPackageName?.trim().orEmpty()
        if (last.isEmpty()) return null
        return last.takeIf { VideoPlatformRegistry.isVideoOrStreamingPackage(it) }
    }

    fun clearUnderstanding() {
        understandingRequestGate.invalidate()
        promiseUnderstanding = null
        promiseDraft = null
        localAppTargetLookupRequested = false
        selectedClarificationOptionId = null
        strongerConfirmAcknowledged = false
        startBlockedMessage = null
        lastClarificationSnapshot = null
        understandingInProgress = false
        mailTask?.resetPreview()
    }

    fun startCommitment() {
        val draft = promiseDraft ?: return
        val understanding = promiseUnderstanding

        EffectivePromisePolicy.savedWholeAppRuleConflict(draft, appRulesStore.getRules())?.let {
            startBlockedMessage = it
            return
        }

        if (understanding != null && !understanding.canStart) {
            startBlockedMessage = when {
                understanding.startBlockReason != null -> understanding.startBlockReason
                draft.legacyContractGap != null -> LegacyScopeContractGuard.START_BLOCKED_COPY
                understanding.clarificationOptions.isNotEmpty() &&
                    understanding.selectedClarificationOptionId.isNullOrBlank() ->
                    "Pick one of the clarification options before starting."
                understanding.requiresStrongerConfirmation &&
                    !understanding.strongerConfirmAcknowledged ->
                    "Confirm how long this commitment lasts before starting."
                understanding.statusLabel == "Reconnect to verify this promise" ->
                    "Reconnect to verify this promise before starting."
                else ->
                    "I still need a clearer promise before I can protect it. Edit and tap Understand again."
            }
            return
        }

        if (draft.needsClarification(strongerConfirmAcknowledged)) {
            startBlockedMessage =
                "I still need a clearer promise before I can protect it. Edit and tap Understand again."
            return
        }

        if (!isAccessibilityEnabled) {
            startBlockedMessage =
                "Turn on Accessibility first. You are not protected yet."
            return
        }

        mailTask?.startBlockReason()?.let {
            startBlockedMessage = it
            return
        }

        val visibleAppLabels = ConfirmationUserCopy.userVisibleAppLabels(draft)
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        val confirmedPolicy = ConfirmedSessionPolicy(
            appRules = draft.suggestedAppRules.filter {
                it.label.trim().lowercase(Locale.ROOT) in visibleAppLabels
            }.toList(),
            contentRules = draft.contentRules.toList()
        )
        if (ConfirmedSessionPolicyCodec.encode(confirmedPolicy) == null) {
            startBlockedMessage =
                "This promise has too many or unsupported rules to save safely. Simplify it and review the preview again."
            return
        }

        startBlockedMessage = null

        val bound = applyFocusPromiseDraft(
            draft = draft,
            studyWorldSettingsStore = studyWorldSettingsStore
        ) ?: run {
            startBlockedMessage =
                "I couldn't safely preserve your saved session settings, so nothing was started. Reopen Strlix and try again."
            return
        }
        appRulesStore.applyCommitmentSuggestions(draft.suggestedAppRules)
            .eventMessage?.let(eventLogStore::addEvent)
        appRules = appRulesStore.getRules()
        studyWorldSettings = bound

        goal = draft.rawText.ifEmpty { "Focus" }
        val startedAt = System.currentTimeMillis()
        val started = sessionManager.startSession(
            world = studyWorld,
            goal = goal,
            durationMillis = ConfirmedPromiseBinder.sessionDurationMillis(draft, bound, startedAt),
            nowMillis = startedAt
        ).copy(
            promiseStrictness = draft.strictness,
            confirmedPolicy = confirmedPolicy
        )
        sessionStore.saveActiveSession(started)
        session = started
        mailTask?.bindToSession(started)
        ProtectionHeartbeatController.start(context)

        promiseUnderstanding = null
        promiseDraft = null
        localAppTargetLookupRequested = false
    }

    /** Tap on "End Commitment". Soft/expired ends now; Strict/Locked opens a confirm step. */
    fun requestEndCommitment() {
        val current = session ?: return
        val friction = CommitmentExitGate.frictionFor(current, System.currentTimeMillis())
        if (friction == ExitFriction.NONE) {
            performEndCommitment(friction)
            return
        }
        pendingExitFriction = friction
        exitPhraseError = null
    }

    fun confirmEndCommitment(typedPhrase: String?) {
        val friction = pendingExitFriction ?: return
        if (!CommitmentExitGate.mayEnd(friction, confirmed = true, typedPhrase = typedPhrase)) {
            exitPhraseError = "Type the sentence exactly to end this commitment."
            return
        }
        performEndCommitment(friction)
    }

    fun cancelEndCommitment() {
        pendingExitFriction = null
        exitPhraseError = null
    }

    private fun performEndCommitment(friction: ExitFriction) {
        pendingExitFriction = null
        exitPhraseError = null
        endCommitment(CommitmentTeardown.Kind.EARLY_END, "friction=${friction.name}")
    }

    fun emergencyExitAvailability(): EmergencyExitGate.Availability =
        EmergencyExitGate.availability(
            session = session,
            policy = recoveryPolicy,
            lastUsedAtMillis = emergencyExitStore.lastUsedAtMillis(),
            nowMillis = System.currentTimeMillis()
        )

    fun emergencyExitNextAvailableAtMillis(): Long? =
        EmergencyExitGate.nextAvailableAtMillis(emergencyExitStore.lastUsedAtMillis())

    fun requestEmergencyExit() {
        pendingEmergencyExit = emergencyExitAvailability() == EmergencyExitGate.Availability.AVAILABLE
    }

    fun cancelEmergencyExit() {
        pendingEmergencyExit = false
    }

    /** Duplicate taps are no-ops: the confirm step closes first and teardown ends a session once. */
    fun confirmEmergencyExit() {
        if (!pendingEmergencyExit) return
        pendingEmergencyExit = false
        val availability = emergencyExitAvailability()
        if (availability != EmergencyExitGate.Availability.AVAILABLE) {
            eventLogStore.addEvent("${CommitmentTeardown.EVENT_PREFIX} kind=EMERGENCY_EXIT outcome=REFUSED reason=${availability.name}")
            return
        }
        val result = endCommitment(CommitmentTeardown.Kind.EMERGENCY_EXIT, "")
        if (result.outcome == CommitmentTeardown.Outcome.ENDED) {
            emergencyExitStore.recordUse(System.currentTimeMillis())
        }
    }

    private fun endCommitment(kind: CommitmentTeardown.Kind, detail: String): CommitmentTeardown.Result {
        val result = commitmentTeardown.end(kind, expectedSessionId = session?.id, detail = detail)
        sessionManager.stopSession(System.currentTimeMillis())
        pendingExitFriction = null
        exitPhraseError = null
        pendingEmergencyExit = false
        appRules = appRulesStore.getRules()
        studyWorldSettings = studyWorldSettingsStore.getSettings()
        session = sessionStore.getStoredSession()
        if (session == null) {
            mailTask?.onCommitmentEnded()
            ProtectionHeartbeatController.stop(context)
            promiseText = ""
            clearUnderstanding()
        }
        if (showAdvancedControls) {
            recentEvents = eventLogStore.getRecentEvents()
        }
        return result
    }

    fun setDurationMinutes(minutes: Int) {
        studyWorldSettingsStore.setDurationMinutes(minutes)
        studyWorldSettings = studyWorldSettingsStore.getSettings()
    }

    fun setLockAttemptThreshold(threshold: Int?) {
        studyWorldSettingsStore.setLockAttemptThreshold(threshold)
        studyWorldSettings = studyWorldSettingsStore.getSettings()
    }

    fun setBlockAttemptCooldownMinutes(minutes: Int) {
        studyWorldSettingsStore.setBlockAttemptCooldownMinutes(minutes)
        studyWorldSettings = studyWorldSettingsStore.getSettings()
    }

    fun setVisionExperimentEnabled(enabled: Boolean) {
        studyWorldSettingsStore.setVisionExperimentEnabled(enabled)
        studyWorldSettings = studyWorldSettingsStore.getSettings()
    }

    fun toggleGuardrail(guardrail: PermanentGuardrail, enabled: Boolean) {
        permanentGuardrailsStore.setGuardrailEnabled(guardrail.id, enabled)
        permanentGuardrails = permanentGuardrailsStore.getGuardrails()
    }

    fun toggleAdvancedControls() {
        if (showAdvancedControls) {
            showAdvancedControls = false
            showAdvancedEngineering = false
            advancedSnapshotLoaded = false
            return
        }
        val started = SystemClock.elapsedRealtime()
        // Open shell immediately; snapshot fills on IO via loadAdvancedSnapshotAsync or poll.
        showAdvancedControls = true
        Log.d(
            "PhoneCodexUIPerf",
            "advancedToggle open shell=${SystemClock.elapsedRealtime() - started}ms"
        )
    }

    fun toggleAdvancedEngineering() {
        if (showAdvancedEngineering) {
            showAdvancedEngineering = false
            return
        }
        showAdvancedEngineering = true
        // Snapshot (debug/events) loads only when engineering opens.
        advancedSnapshotLoaded = false
    }

    suspend fun loadAdvancedSnapshotIfNeeded() {
        if (advancedSnapshotLoaded || !showAdvancedControls || !showAdvancedEngineering) return
        val started = SystemClock.elapsedRealtime()
        val snapshot = withContext(Dispatchers.IO) {
            AdvancedSnapshot(
                debugState = debugStateStore.getDebugState(),
                recentEvents = eventLogStore.getRecentEvents(),
                protectionViolationState = protectionViolationStore.getState(),
                recentFeedback = feedbackStore.getRecentFeedback(),
                accessibilityAlive = runtimeDiagStore.isAccessibilityServiceAlive(),
                overlayActive = runtimeDiagStore.isOverlayActive()
            )
        }
        debugState = snapshot.debugState
        recentEvents = snapshot.recentEvents
        protectionViolationState = snapshot.protectionViolationState
        recentFeedback = snapshot.recentFeedback
        isAccessibilityAlive = snapshot.accessibilityAlive
        overlayActive = snapshot.overlayActive
        advancedSnapshotLoaded = true
        Log.d(
            "PhoneCodexUIPerf",
            "advancedSnapshot=${SystemClock.elapsedRealtime() - started}ms"
        )
    }

    fun clearViolations() {
        protectionViolationStore.clearViolations()
        protectionViolationState = protectionViolationStore.getState()
    }

    fun refreshDebugState() {
        debugState = debugStateStore.getDebugState()
    }

    fun refreshDeveloperDiagnostics() {
        debugState = debugStateStore.getDebugState()
        val heartbeat = runtimeDiagStore.getHeartbeat()
        isAccessibilityAlive = heartbeat.serviceAlive
        lastHeartbeatMillis = heartbeat.lastEventMillis
        lastHeartbeatPackage = heartbeat.packageName
        lastHeartbeatReason = heartbeat.reason
        overlayActive = runtimeDiagStore.isOverlayActive()
        isAccessibilityEnabled =
            AccessibilityHealthChecker.isPhoneCodexAccessibilityEnabled(context)
        session = sessionStore.getStoredSession()
        nowMillis = System.currentTimeMillis()
    }

    /**
     * Proves the enforcement loop without starting a promise.
     * Refreshes Accessibility heartbeat, backend, session, and guardrail, then writes UI + log.
     */
    fun runProtectionCheck() {
        if (protectionCheckInProgress) return
        val scope = diagnosticsScope ?: return
        protectionCheckInProgress = true
        lastProtectionCheckSummary = "Checking protection…"
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    ProtectionCheckSnapshot(
                        accessibilityEnabled =
                            AccessibilityHealthChecker.isPhoneCodexAccessibilityEnabled(context),
                        heartbeat = runtimeDiagStore.getHeartbeat(),
                        session = sessionStore.getStoredSession(),
                        guardrails = permanentGuardrailsStore.getGuardrails(),
                        nowMillis = System.currentTimeMillis(),
                        probe = BackendHealthProbe.probe()
                    )
                }
                isAccessibilityEnabled = snapshot.accessibilityEnabled
                isAccessibilityAlive = snapshot.heartbeat.serviceAlive
                lastHeartbeatMillis = snapshot.heartbeat.lastEventMillis
                lastHeartbeatPackage = snapshot.heartbeat.packageName
                lastHeartbeatReason = snapshot.heartbeat.reason
                session = snapshot.session
                permanentGuardrails = snapshot.guardrails
                nowMillis = snapshot.nowMillis
                if (snapshot.probe.kind == BackendProbeKind.REACHABLE) {
                    lastBackendSuccessElapsedMs = snapshot.probe.probedAtElapsedMs
                        ?: SystemClock.elapsedRealtime()
                }
                lastProbeAttemptElapsedMs = SystemClock.elapsedRealtime()
                backendProbeResult = BackendHealthProbe.resolveDisplayResult(
                    raw = snapshot.probe,
                    checking = false,
                    lastSuccessElapsedMs = lastBackendSuccessElapsedMs
                )
                val model = protectionReliability
                lastProtectionCheckSummary = model.checkLogLine
                withContext(Dispatchers.IO) {
                    eventLogStore.addEvent("Protection check: ${model.checkLogLine}")
                }
                if (showAdvancedControls) {
                    recentEvents = eventLogStore.getRecentEvents()
                }
                Log.i(TAG_PROTECTION_CHECK, model.checkLogLine)
            } finally {
                protectionCheckInProgress = false
            }
        }
    }

    fun testBackend(force: Boolean = true, allowRetry: Boolean = true) {
        if (backendTestInProgress) return
        val scope = diagnosticsScope ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force &&
            lastProbeAttemptElapsedMs > 0L &&
            now - lastProbeAttemptElapsedMs < BackendHealthProbe.MIN_PROBE_INTERVAL_MS
        ) {
            return
        }

        backendTestInProgress = true
        backendProbeResult = BackendHealthProbe.resolveDisplayResult(
            raw = backendProbeResult,
            checking = true,
            lastSuccessElapsedMs = lastBackendSuccessElapsedMs,
            nowElapsedMs = now
        )
        lastProbeAttemptElapsedMs = now

        scope.launch {
            var raw = withContext(Dispatchers.IO) {
                BackendHealthProbe.probe()
            }
            // One quiet retry on first failure — USB reverse often races with app open.
            if (allowRetry &&
                raw.kind != BackendProbeKind.REACHABLE &&
                raw.kind != BackendProbeKind.IDLE
            ) {
                delay(700)
                raw = withContext(Dispatchers.IO) {
                    BackendHealthProbe.probe()
                }
            }
            if (raw.kind == BackendProbeKind.REACHABLE) {
                lastBackendSuccessElapsedMs = raw.probedAtElapsedMs
                    ?: SystemClock.elapsedRealtime()
            }
            backendProbeResult = BackendHealthProbe.resolveDisplayResult(
                raw = raw,
                checking = false,
                lastSuccessElapsedMs = lastBackendSuccessElapsedMs
            )
            backendTestInProgress = false
            refreshDeveloperDiagnostics()
        }
    }

    fun refreshEventsFromStore() {
        recentEvents = eventLogStore.getRecentEvents()
        session = sessionStore.getStoredSession()
    }

    fun isDefaultAppRule(packageName: String): Boolean =
        appRulesStore.isDefaultRule(packageName)

    fun applyAppRuleBehavior(rule: AppRule, behavior: AppRuleBehavior) {
        appRulesStore.applySuggestedRule(rule.copy(behavior = behavior))
        appRules = appRulesStore.getRules()
    }

    fun addAppRule(app: InstalledApp, behavior: AppRuleBehavior) {
        appRulesStore.applySuggestedRule(
            AppRule(
                packageName = app.packageName,
                label = app.label,
                behavior = behavior
            )
        )
        appRules = appRulesStore.getRules()
    }

    fun removeAppRule(packageName: String) {
        appRulesStore.removeCustomRule(packageName)
        appRules = appRulesStore.getRules()
    }

    fun addSafeApp(app: InstalledApp) {
        safeAppsStore.addSafeApp(app.packageName, app.label)
        safeApps = safeAppsStore.getSafeApps()
    }

    fun removeSafeApp(packageName: String) {
        safeAppsStore.removeSafeApp(packageName)
        safeApps = safeAppsStore.getSafeApps()
    }

    fun submitFeedback(
        correctedDecision: String,
        includePrivateContext: Boolean = false
    ) {
        saveFeedback(
            feedbackStore = feedbackStore,
            debugState = debugState,
            correctedDecision = correctedDecision,
            includePrivateContext = includePrivateContext
        )
        recentFeedback = feedbackStore.getRecentFeedback()
    }

    fun clearSavedFeedback() {
        feedbackStore.clearFeedback()
        recentFeedback = emptyList()
    }

    fun updateAppRules(rules: List<AppRule>) {
        appRules = rules
    }

    fun updateSafeApps(apps: List<SafeApp>) {
        safeApps = apps
    }

    fun updateRecentFeedback(entries: List<FeedbackEntry>) {
        recentFeedback = entries
    }
}

private data class BootstrapSnapshot(
    val session: FocusSession?,
    val accessibilityEnabled: Boolean,
    val studyWorldSettings: StudyWorldSettings,
    val appRules: List<AppRule>,
    val guardrails: List<PermanentGuardrail>,
    val safeApps: List<SafeApp>,
    val accessibilityAlive: Boolean,
    val lastHeartbeatMillis: Long,
    val lastHeartbeatPackage: String,
    val lastHeartbeatReason: String
)

private data class ProtectionCheckSnapshot(
    val accessibilityEnabled: Boolean,
    val heartbeat: AccessibilityHeartbeat,
    val session: FocusSession?,
    val guardrails: List<PermanentGuardrail>,
    val nowMillis: Long,
    val probe: BackendProbeResult
)

private data class AdvancedSnapshot(
    val debugState: DebugState,
    val recentEvents: List<String>,
    val protectionViolationState: ProtectionViolationState,
    val recentFeedback: List<FeedbackEntry>,
    val accessibilityAlive: Boolean,
    val overlayActive: Boolean
)

private val EMPTY_DEBUG_STATE = DebugState(
    lastPackageName = null,
    lastScreenTextPreview = null,
    lastDecision = null,
    lastReason = null,
    lastSource = null,
    lastConfidence = null,
    lastMatchedSignals = emptyList(),
    lastUpdatedMillis = 0L,
    lastReasonCategory = null,
    lastWouldEscalate = null,
    lastBackendProvider = null,
    lastBackendDeployment = null,
    lastBackendPromptVersion = null,
    lastBackendLatencyMs = null
)

private val EMPTY_VIOLATION_STATE = ProtectionViolationState(
    violationCount = 0,
    lastViolationMillis = 0L,
    lastReason = null
)

private val DEFAULT_STUDY_SETTINGS = StudyWorldSettings(
    durationMinutes = StudyWorldSettingsStore.DEFAULT_DURATION_MINUTES,
    lockAttemptThreshold = StudyWorldSettingsStore.DEFAULT_LOCK_ATTEMPT_THRESHOLD,
    blockAttemptCooldownMinutes = StudyWorldSettingsStore.DEFAULT_BLOCK_ATTEMPT_COOLDOWN_MINUTES
)

private const val TAG_UNDERSTAND = "PromiseUnderstand"
private const val TAG_PROTECTION_CHECK = "PhoneCodexProtect"
private const val TAG_PROTECTION_STATUS = "PhoneCodexHealthUi"

@Composable
internal fun rememberHomeScreenState(): HomeScreenState {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()

    val state = remember {
        val started = SystemClock.elapsedRealtime()
        val created = HomeScreenState(
            context = context,
            sessionManager = SessionManager(),
            sessionStore = SessionStore(appContext),
            eventLogStore = EventLogStore(appContext),
            debugStateStore = DebugStateStore(appContext),
            runtimeDiagStore = RuntimeDiagStore(appContext),
            feedbackStore = FeedbackStore(appContext),
            appRulesStore = AppRulesStore(appContext),
            studyWorldSettingsStore = StudyWorldSettingsStore(appContext),
            permanentGuardrailsStore = PermanentGuardrailsStore(appContext),
            safeAppsStore = SafeAppsStore(appContext),
            protectionViolationStore = ProtectionViolationStore(appContext),
            installedAppsReader = InstalledAppsReader(appContext),
            policyEngine = PolicyEngine(),
            focusPromiseParser = FocusPromiseParser(),
            studyWorld = defaultStudyWorld(),
            createdAtElapsedMs = started
        )
        Log.d(
            "PhoneCodexUIPerf",
            "rememberHomeScreenState construct=${SystemClock.elapsedRealtime() - started}ms"
        )
        created
    }
    state.attachDiagnosticsScope(coroutineScope)
    return state
}
