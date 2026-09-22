package com.phonecodex.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import com.phonecodex.app.data.AppDossierStore
import com.phonecodex.app.data.ShortFormQuotaStore
import com.phonecodex.app.data.AppRulesStore
import com.phonecodex.app.data.DebugStateStore
import com.phonecodex.app.data.EventLogStore
import com.phonecodex.app.data.FeedbackStore
import com.phonecodex.app.data.PermanentGuardrailsStore
import com.phonecodex.app.data.ProtectionViolationStore
import com.phonecodex.app.data.RuntimeDiagStore
import com.phonecodex.app.data.SessionStore
import com.phonecodex.app.data.SafeAppsStore
import com.phonecodex.app.data.StudyWorldSettingsStore
import com.phonecodex.app.domain.classifier.ClassifyRequestBuilder
import com.phonecodex.app.domain.classifier.ContentClassification
import com.phonecodex.app.domain.classifier.ContentClassifier
import com.phonecodex.app.domain.classifier.AiConfidenceGate
import com.phonecodex.app.domain.classifier.NetworkAiContentClassifier
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContextSnapshot
import com.phonecodex.app.domain.model.DecisionExplanation
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.FocusWorld
import com.phonecodex.app.domain.model.PolicyDecision
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.model.WorldMode
import com.phonecodex.app.domain.policy.PolicyEngine
import com.phonecodex.app.domain.enforcement.BlankVideoTreeAction
import com.phonecodex.app.domain.enforcement.BlankVideoTreeGate
import com.phonecodex.app.domain.enforcement.DurationSource
import com.phonecodex.app.domain.enforcement.EnforcementContext
import com.phonecodex.app.domain.enforcement.EnforcementDecisionLog
import com.phonecodex.app.domain.enforcement.EnforcementEventGate
import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.AppClass
import com.phonecodex.app.domain.enforcement.AppClassResolver
import com.phonecodex.app.domain.enforcement.AppDossierClient
import com.phonecodex.app.domain.enforcement.AppDossierMergeLaw
import com.phonecodex.app.domain.enforcement.EntertainmentBanAction
import com.phonecodex.app.domain.enforcement.EntertainmentClassDetector
import com.phonecodex.app.domain.enforcement.EntertainmentBanGate
import com.phonecodex.app.domain.enforcement.PlayListingCategoryParser
import com.phonecodex.app.domain.enforcement.MediaLengthEnforcement
import com.phonecodex.app.domain.enforcement.MediaLengthLocalDecision
import com.phonecodex.app.domain.enforcement.OverlayLifecycleGate
import com.phonecodex.app.domain.enforcement.PromiseEnforcementCoordinator
import com.phonecodex.app.domain.enforcement.PromiseEvalInput
import com.phonecodex.app.domain.enforcement.SessionEnforcementCopy
import com.phonecodex.app.domain.enforcement.SessionLockLaw
import com.phonecodex.app.domain.enforcement.ShortFormQuotaAction
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.enforcement.SurfaceDetectionResult
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.SurfaceEnforcementGate
import com.phonecodex.app.domain.enforcement.VideoDurationParser
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.enforcement.VisionCaptureGate
import com.phonecodex.app.domain.protection.AccessibilityHeartbeatLaw
import com.phonecodex.app.domain.protection.ProtectionHeartbeatLogic
import com.phonecodex.app.domain.enforcement.VisionCaptureInput
import com.phonecodex.app.domain.enforcement.VisionCounselMerge
import com.phonecodex.app.domain.feedback.FeedbackMemory
import com.phonecodex.app.domain.guardrail.PermanentGuardrailEvaluator
import com.phonecodex.app.domain.promise.PromiseIntentRules
import com.phonecodex.app.domain.session.SessionExpiryPolicy
import com.phonecodex.app.domain.signals.ContentSignalDetector
import com.phonecodex.app.domain.tamper.AccessibilityTamperGuard

class PhoneCodexAccessibilityService : AccessibilityService() {

    private val policyEngine = PolicyEngine()
    private val contentSignalDetector = ContentSignalDetector()
    private val shortFormQuotaStore by lazy { ShortFormQuotaStore(this) }
    private val shortFormQuotaGate by lazy { ShortFormQuotaGate(ledger = shortFormQuotaStore) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingContentPackage: String? = null
    private val contentChangeDebounceRunnable = Runnable {
        val packageName = pendingContentPackage ?: return@Runnable
        pendingContentPackage = null
        inspectAndEvaluate(packageName)
    }
    private var lastDebugPackageWritten: String? = null
    private var lastDebugPackageWrittenAtMillis: Long = 0L
    private val contentClassifier: ContentClassifier = NetworkAiContentClassifier()
    private val aiConfidenceGate = AiConfidenceGate()
    private val sessionStore by lazy { SessionStore(this) }
    private val eventLogStore by lazy { EventLogStore(this) }
    private val debugStateStore by lazy { DebugStateStore(this) }
    private val runtimeDiagStore by lazy { RuntimeDiagStore(this) }
    private val appRulesStore by lazy { AppRulesStore(this) }
    private val studyWorldSettingsStore by lazy { StudyWorldSettingsStore(this) }
    private val feedbackStore by lazy { FeedbackStore(this) }
    private val feedbackMemory by lazy { FeedbackMemory(feedbackStore) }
    private val permanentGuardrailEvaluator = PermanentGuardrailEvaluator()
    private val permanentGuardrailsStore by lazy { PermanentGuardrailsStore(this) }
    private val safeAppsStore by lazy { SafeAppsStore(this) }
    private val appDossierStore by lazy { AppDossierStore(this) }
    private val appDossierClient = AppDossierClient()
    private val accessibilityTamperGuard = AccessibilityTamperGuard()
    private val protectionViolationStore by lazy { ProtectionViolationStore(this) }
    private var currentOverlay: View? = null
    private var currentWarningOverlay: View? = null
    /**
     * External package the block overlay is currently protecting against.
     * When non-null, foreground events from [OWN_PACKAGE_NAME] are caused by our overlay
     * and must not trigger hide.
     */
    private var activeOverlayTargetPackage: String? = null
    /** Last external package we blocked (survives hide, for diagnostics). */
    private var lastBlockedPackage: String? = null
    private var latestPackageName: String? = null
    private var visibleWarningPackageName: String? = null
    private var visibleWarningTextSignature: String? = null
    private var dismissedWarningPackageName: String? = null
    private var dismissedWarningTextSignature: String? = null
    private var dismissedWarningTimeMillis: Long = 0L
    private var overlayAwayCandidatePackage: String? = null
    private var overlayAwaySinceMillis: Long = 0L
    private var lastOverlayDecisionPackage: String? = null
    private var lastOverlayDecisionName: String? = null
    private var lastOverlayDecisionAtMillis: Long = 0L
    private val classifierExecutor = Executors.newSingleThreadExecutor()
    private val quotaReminderAutoDismiss = Runnable {
        dismissedWarningPackageName = visibleWarningPackageName
        dismissedWarningTextSignature = visibleWarningTextSignature
        dismissedWarningTimeMillis = System.currentTimeMillis()
        hideWarningOverlay()
    }

    private var lastClassifiedPackage: String? = null
    private var lastClassifiedTextSignature: String? = null
    private var lastClassification: ContentClassification? = null
    private var lastClassificationTimeMillis: Long = 0L
    private var lastClassificationRequestTimeMillis: Long = 0L
    private var lastClassificationRequestPackage: String? = null
    private var classificationInFlight: Boolean = false
    private var lastNoSessionLoggedPackage: String? = null
    private var lastEvalPreludePackage: String? = null
    private var lastEvalPreludeSignature: String? = null
    private var lastLoggedA11yTextPackage: String? = null
    private var lastLoggedA11yTextSignature: String? = null
    private var lastCountedBlockPackage: String? = null
    private var lastCountedBlockSignature: String? = null
    private var lastCountedBlockTimeMillis: Long = 0L
    private var lastSafeAppLoggedPackage: String? = null
    private var lastSafeAppLoggedTimeMillis: Long = 0L
    private var lastForegroundLoggedPackage: String? = null
    private var lastForegroundLoggedTimeMillis: Long = 0L
    private var latestExplanation: DecisionExplanation? = null
    private var lastVisionSkipPackage: String? = null
    private var lastVisionSkipAtMillis: Long = 0L

    private val debugWorld = FocusWorld(
        id = "study",
        name = "Study World",
        mode = WorldMode.STUDY,
        defaultStrictness = StrictnessLevel.STRICT,
        allowedPackages = setOf(
            "com.phonecodex.app",
            "com.android.systemui",
            "miui.systemui.plugin",
            "com.miui.home",
            "com.android.settings",
            "com.xiaomi.misettings",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.google.android.apps.docs",
            "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.docs.editors.slides",
            "com.google.android.apps.drive",
            "com.google.android.gm"
        ),
        blockedPackages = emptySet(),
        conditionalPackages = emptySet()
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        runtimeDiagStore.setAccessibilityServiceAlive(true)
        runtimeDiagStore.recordHeartbeat(
            nowMillis = System.currentTimeMillis(),
            packageName = packageName,
            reason = AccessibilityHeartbeatLaw.REASON_SERVICE_CONNECTED
        )
        handleProtectionViolationConsequences()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        recordProtectionHeartbeat(event)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                logForegroundPackageThrottled(packageName)
                // Immediate evaluate on window switches; cancel pending content debounce.
                mainHandler.removeCallbacks(contentChangeDebounceRunnable)
                pendingContentPackage = null
                inspectAndEvaluate(packageName)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val packageName = event.packageName?.toString()
                    ?: rootInActiveWindow?.packageName?.toString()
                    ?: return
                if (!EnforcementEventGate.shouldDebounceContentChanged(true)) {
                    inspectAndEvaluate(packageName)
                    return
                }
                pendingContentPackage = packageName
                mainHandler.removeCallbacks(contentChangeDebounceRunnable)
                mainHandler.postDelayed(
                    contentChangeDebounceRunnable,
                    EnforcementEventGate.CONTENT_CHANGED_DEBOUNCE_MS
                )
            }

            else -> return
        }
    }

    override fun onInterrupt() {
    }

    private fun recordProtectionHeartbeat(event: AccessibilityEvent) {
        val reason = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                AccessibilityHeartbeatLaw.REASON_WINDOW_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                AccessibilityHeartbeatLaw.REASON_CONTENT_CHANGED
            else -> return
        }
        runtimeDiagStore.recordHeartbeat(
            nowMillis = System.currentTimeMillis(),
            packageName = event.packageName?.toString()
                ?: rootInActiveWindow?.packageName?.toString(),
            reason = reason
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(contentChangeDebounceRunnable)
        hideWarningOverlay()
        hideBlockOverlay()
        runtimeDiagStore.setAccessibilityServiceAlive(false)
        runtimeDiagStore.setOverlayActive(false)
        super.onDestroy()
    }

    private fun handleProtectionViolationConsequences() {
        if (!protectionViolationStore.consumePendingConsequences()) return

        val violationState = protectionViolationStore.getState()
        if (violationState.violationCount <= 0) return

        val storedSession = sessionStore.getStoredSession() ?: return
        if (
            storedSession.status != SessionStatus.ACTIVE &&
            storedSession.status != SessionStatus.LOCKED
        ) {
            return
        }

        eventLogStore.addEvent("Protection violation detected during active Study World")

        val disableMs = protectionViolationStore.disabledContinuouslyMillis()
        val shouldLock = ProtectionHeartbeatLogic.shouldLockForAccessibilityOutage(disableMs)
        if (storedSession.status == SessionStatus.ACTIVE && shouldLock) {
            sessionStore.lockSession()
            eventLogStore.addEvent("Study World locked because protection was disabled.")
            Log.d(
                "PhoneCodexDecision",
                "Study World locked because protection was disabled. " +
                    "offMs=$disableMs"
            )
        } else if (storedSession.status == SessionStatus.ACTIVE) {
            eventLogStore.addEvent(
                "Accessibility blip ignored — service came back before lock grace."
            )
            Log.d(
                "PhoneCodexDecision",
                EnforcementDecisionLog.format(
                    packageName = packageName.orEmpty().ifBlank { OWN_PACKAGE_NAME },
                    surface = "system",
                    rule = "Tamper",
                    decision = "ALLOW",
                    reasonCode = EnforcementReasonCodes.A11Y_BLIP_NO_LOCK,
                    reason = "Accessibility bounce under ${ProtectionHeartbeatLogic.A11Y_DISABLE_GRACE_MS}ms"
                )
            )
        }
    }

    private fun endExpiredStudyWorldIfNeeded(storedSession: FocusSession?): FocusSession? {
        val session = storedSession ?: return null
        if (!SessionExpiryPolicy.isExpired(
                session,
                System.currentTimeMillis(),
                studyWorldSettingsStore.getSettings().shortFormDailyQuotaLimit
            )
        ) {
            return session
        }

        sessionStore.clearSession()
        hideWarningOverlay()
        hideBlockOverlay()
        eventLogStore.addEvent(SessionExpiryPolicy.EXPIRED_EVENT_MESSAGE)
        Log.d("PhoneCodexDecision", SessionExpiryPolicy.EXPIRED_EVENT_MESSAGE)
        return null
    }

    private fun inspectAndEvaluate(packageName: String) {
        latestPackageName = packageName

        val screenText = collectScreenText(packageName)
        val nowDebug = System.currentTimeMillis()
        if (
            EnforcementEventGate.shouldWriteDebugPackageDetection(
                packageName = packageName,
                lastWrittenPackage = lastDebugPackageWritten,
                lastWrittenAtMillis = lastDebugPackageWrittenAtMillis,
                nowMillis = nowDebug
            )
        ) {
            debugStateStore.recordPackageDetected(packageName, screenText)
            lastDebugPackageWritten = packageName
            lastDebugPackageWrittenAtMillis = nowDebug
        }

        var storedSession = endExpiredStudyWorldIfNeeded(sessionStore.getStoredSession())
        if (
            storedSession != null &&
            (storedSession.status == SessionStatus.ACTIVE ||
                storedSession.status == SessionStatus.LOCKED)
        ) {
            if (applyAccessibilityTamperGuard(packageName, screenText)) {
                return
            }
        }

        // Overlay self-hide guard: must run BEFORE safe-app handling.
        // Showing TYPE_ACCESSIBILITY_OVERLAY makes the foreground package look like
        // com.phonecodex.app; treating that as "safe" used to hide the overlay in a loop.
        if (handleActiveOverlayForeground(packageName)) {
            return
        }

        if (safeAppsStore.isSafePackage(packageName)) {
            hideWarningOverlay()
            // No active overlay target here — normal PhoneCodex / safe-app open.
            val now = System.currentTimeMillis()
            val shouldLogSafeApp = packageName != lastSafeAppLoggedPackage ||
                now - lastSafeAppLoggedTimeMillis >= SAFE_APP_LOG_THROTTLE_MS
            if (shouldLogSafeApp) {
                recordExplanation(
                    packageName = packageName,
                    screenText = screenText,
                    explanation = DecisionExplanation(
                        decision = DecisionType.ALLOW.name,
                        reason = "Always allowed safe app",
                        source = "Safe App",
                        confidence = 1.0,
                        matchedSignals = listOf(EnforcementReasonCodes.EMERGENCY_ALLOW)
                    ),
                    reasonCode = EnforcementReasonCodes.EMERGENCY_ALLOW
                )
                Log.d(
                    "PhoneCodexDecision",
                    "Safe app allowed: $packageName " +
                        "code=${EnforcementReasonCodes.EMERGENCY_ALLOW}"
                )
                lastSafeAppLoggedPackage = packageName
                lastSafeAppLoggedTimeMillis = now
            }
            return
        }

        val sessionGoalEarly = storedSession?.goal.orEmpty()
        if (
            storedSession != null &&
            PromiseIntentRules.isUnrelatedProductivityPackage(packageName, sessionGoalEarly)
        ) {
            hideWarningOverlay()
            hideBlockOverlay()
            recordExplanation(
                packageName = packageName,
                screenText = screenText,
                explanation = DecisionExplanation(
                    decision = DecisionType.ALLOW.name,
                    reason = "Productivity app unrelated to this promise",
                    source = "Safe App",
                    confidence = 1.0
                )
            )
            return
        }

        logScreenTextIfChanged(packageName, screenText)

        if (isSystemOverlayNoise(packageName)) return

        if (applyPermanentGuardrails(packageName, screenText)) {
            return
        }

        if (storedSession == null) {
            hideWarningOverlay()
            hideBlockOverlay()
            if (packageName != lastNoSessionLoggedPackage) {
                Log.d(
                    "PhoneCodexDecision",
                    EnforcementDecisionLog.format(
                        packageName = packageName,
                        surface = "none",
                        rule = "Session",
                        decision = "ALLOW",
                        reasonCode = EnforcementReasonCodes.NO_ACTIVE_SESSION_ALLOW,
                        reason = SessionEnforcementCopy.NO_ACTIVE_COMMITMENT
                    )
                )
                lastNoSessionLoggedPackage = packageName
            }
            return
        }

        lastNoSessionLoggedPackage = null

        val lockScopeSettings = studyWorldSettingsStore.getSettings()
        if (storedSession.status == SessionStatus.LOCKED &&
            SessionLockLaw.mayUseAppWhileLocked(
                packageName = packageName,
                screenText = screenText,
                goal = storedSession.goal.orEmpty(),
                enforcementScopePackages = lockScopeSettings.enforcementScopePackages,
                contentBrands = lockScopeSettings.enforcementContentBrands
            )
        ) {
            hideWarningOverlay()
            hideBlockOverlay()
            Log.d(
                "PhoneCodexDecision",
                EnforcementDecisionLog.format(
                    packageName = packageName,
                    surface = SurfaceDetector.detect(packageName, screenText).surface.name,
                    rule = "SessionLock",
                    decision = "ALLOW",
                    reasonCode = EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED,
                    reason = SessionEnforcementCopy.SESSION_LOCK_ALLOW_UNRELATED
                )
            )
            return
        }

        logInstallSurfaceIfPresent(packageName, screenText)

        if (isSystemOverlayNoise(packageName)) return

        logEvalPrelude(packageName, storedSession.goal.orEmpty(), screenText)
        maybeResearchApp(packageName, screenText, storedSession.goal.orEmpty())

        val scopedSettings = lockScopeSettings
        val scopedAppRule = appRulesStore.getBehaviorForPackage(packageName)
        if (
            !EntertainmentBanGate.mustEvaluate(
                packageName = packageName,
                screenText = screenText,
                goal = storedSession.goal.orEmpty(),
                enforcementScopePackages = scopedSettings.enforcementScopePackages,
                contentBrands = scopedSettings.enforcementContentBrands
            ) &&
            scopedAppRule != AppRuleBehavior.BLOCK
        ) {
            hideWarningOverlay()
            hideBlockOverlay()
            val surface = SurfaceDetector.detect(packageName, screenText)
            Log.d(
                "PhoneCodexDecision",
                EnforcementDecisionLog.format(
                    packageName = packageName,
                    surface = surface.surface.name,
                    rule = "Scope",
                    decision = "ALLOW",
                    reasonCode = EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW,
                    reason = "Outside this promise's package/content scope",
                    activity = surface.activity.name.lowercase()
                )
            )
            return
        }

        if (applyBlankVideoTreeRules(packageName, screenText, storedSession)) {
            return
        }

        // Blank/stale text: do not run content signals or AI (prevents wrong blocks).
        // Video/movie packages with blank trees are handled above — never silent allow.
        if (EnforcementEventGate.shouldSkipContentAndAi(screenText)) {
            Log.d(
                "PhoneCodexA11yText",
                "Blank/stale screen text for $packageName — skip content/AI, keep last decision"
            )
            return
        }

        if (applyContentSignalRules(packageName, screenText)) {
            return
        }

        if (applyAppRuleBehavior(packageName, screenText)) {
            return
        }

        if (applyFeedbackMemoryRules(packageName, screenText)) {
            return
        }

        if (applyUnknownVideoCloneWarn(packageName, screenText)) {
            return
        }

        val signature = textSignature(screenText)
        val now = System.currentTimeMillis()

        if (
            EnforcementEventGate.shouldThrottleClassificationRequest(
                packageName = packageName,
                lastRequestPackage = lastClassificationRequestPackage,
                lastRequestAtMillis = lastClassificationRequestTimeMillis,
                nowMillis = now,
                classificationInFlight = classificationInFlight,
                minIntervalMs = EnforcementEventGate.MIN_CLASSIFICATION_INTERVAL_MS
            )
        ) {
            // Do not storm the backend — re-apply last decision for this package if we have one.
            if (
                packageName == lastClassifiedPackage &&
                lastClassification != null
            ) {
                applyClassificationOrFallback(
                    packageName = packageName,
                    screenText = screenText,
                    storedSession = storedSession,
                    classification = lastClassification
                )
            } else {
                Log.d(
                    "PhoneCodexNetAI",
                    "Classification throttled for $packageName — waiting for in-flight/interval"
                )
            }
            return
        }

        if (
            EnforcementEventGate.shouldUseCachedClassification(
                packageName = packageName,
                textSignature = signature,
                lastPackage = lastClassifiedPackage,
                lastSignature = lastClassifiedTextSignature,
                lastClassifiedAtMillis = lastClassificationTimeMillis,
                nowMillis = now,
                cacheTtlMs = EnforcementEventGate.CLASSIFICATION_CACHE_TTL_MS
            )
        ) {
            Log.d("PhoneCodexNetAI", "Using cached classification for $packageName")
            applyClassificationOrFallback(
                packageName = packageName,
                screenText = screenText,
                storedSession = storedSession,
                classification = lastClassification
            )
            return
        }

        classificationInFlight = true
        lastClassificationRequestPackage = packageName
        lastClassificationRequestTimeMillis = now
        Log.d("PhoneCodexNetAI", "Classification request started for $packageName")

        classifierExecutor.execute {
            val classifyRequest = ClassifyRequestBuilder.build(
                packageName = packageName,
                appLabel = resolveAppLabel(packageName),
                screenText = screenText,
                session = storedSession,
                enabledGuardrailIds = permanentGuardrailsStore.getEnabledGuardrails().map { it.id }
            )
            val classification = try {
                contentClassifier.classify(classifyRequest)
            } catch (err: Exception) {
                Log.w("PhoneCodexNetAI", "Classifier failed for $packageName: ${err.message}")
                null
            }

            mainHandler.post {
                try {
                    lastClassifiedPackage = packageName
                    lastClassifiedTextSignature = signature
                    lastClassification = classification
                    lastClassificationTimeMillis = System.currentTimeMillis()

                    if (latestPackageName != packageName) return@post

                    applyClassificationOrFallback(
                        packageName = packageName,
                        screenText = screenText,
                        storedSession = storedSession,
                        classification = classification
                    )
                } finally {
                    classificationInFlight = false
                }
            }
        }
    }

    private fun textSignature(text: String): String {
        return text
            .lowercase()
            .take(300)
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun logScreenTextIfChanged(packageName: String, screenText: String) {
        if (safeAppsStore.isSafePackage(packageName)) return

        val signature = textSignature(screenText)
        if (
            packageName == lastLoggedA11yTextPackage &&
            signature == lastLoggedA11yTextSignature
        ) {
            return
        }

        Log.d(
            "PhoneCodexA11yText",
            "Screen text for $packageName (${screenText.length} chars): ${screenText.take(180)}"
        )
        lastLoggedA11yTextPackage = packageName
        lastLoggedA11yTextSignature = signature
    }

    private fun logEvalPrelude(packageName: String, goal: String, screenText: String) {
        val signature = textSignature(screenText)
        if (
            packageName == lastEvalPreludePackage &&
            signature == lastEvalPreludeSignature
        ) {
            return
        }
        lastEvalPreludePackage = packageName
        lastEvalPreludeSignature = signature
        val settings = studyWorldSettingsStore.getSettings()
        val prelude = PromiseEnforcementCoordinator.evalPrelude(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = goal,
                settings = settings,
                appRule = appRulesStore.getBehaviorForPackage(packageName),
                packageName = packageName,
                screenText = screenText
            )
        )
        Log.d("PhoneCodexDecision", prelude)
    }

    private fun applyBlankVideoTreeRules(
        packageName: String,
        screenText: String,
        storedSession: FocusSession
    ): Boolean {
        val result = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = packageName,
            screenText = screenText,
            goal = storedSession.goal.orEmpty(),
            enforcementScopePackages = studyWorldSettingsStore.getSettings().enforcementScopePackages,
            contentBrands = studyWorldSettingsStore.getSettings().enforcementContentBrands
        )
        if (!result.isHardDecision) return false

        val surface = SurfaceDetector.detect(packageName, screenText)
        val decisionType = if (result.action == BlankVideoTreeAction.BLOCK) {
            DecisionType.BLOCK
        } else {
            DecisionType.WARN
        }
        val explanation = DecisionExplanation(
            decision = decisionType.name,
            reason = SessionEnforcementCopy.overlayEvidence(
                result.reasonCode,
                result.reason,
                hardBlock = result.action == BlankVideoTreeAction.BLOCK
            ),
            source = "BlankVideoTree",
            confidence = 1.0,
            matchedSignals = listOf(result.reasonCode)
        )
        val enforcementContext = EnforcementContext.fromParts(
            packageName = packageName,
            screenText = screenText,
            detection = surface,
            parsed = VideoDurationParser.parse(screenText)
        )
        recordExplanation(
            packageName = packageName,
            screenText = screenText,
            explanation = explanation,
            reasonCode = result.reasonCode,
            context = enforcementContext,
            surface = surface.surface.name
        )
        when (result.action) {
            BlankVideoTreeAction.BLOCK -> {
                hideWarningOverlay()
                handleBlockedApp(
                    packageName = packageName,
                    screenText = screenText,
                    explanation = explanation
                )
            }
            BlankVideoTreeAction.WAIT -> {
                hideBlockOverlay()
                val signature = textSignature(screenText)
                if (!isRecentlyDismissedWarning(packageName, signature)) {
                    showWarningOverlay(packageName, signature, explanation)
                }
            }
            BlankVideoTreeAction.NONE -> return false
        }
        Log.d(
            "PhoneCodexDecision",
            EnforcementDecisionLog.format(
                packageName = packageName,
                surface = surface.surface.name,
                rule = "BlankVideoTree",
                decision = decisionType.name,
                reasonCode = result.reasonCode,
                reason = result.reason,
                evidence = listOf("blank_or_near_blank_tree"),
                activity = surface.activity.name.lowercase(),
                durationSource = "none"
            )
        )
        if (result.action == BlankVideoTreeAction.WAIT) {
            maybeRequestVisionCounsel(
                packageName = packageName,
                screenText = screenText,
                storedSession = storedSession,
                localReasonCode = result.reasonCode,
                localDecision = DecisionType.WARN,
                surfaceIsPass = false
            )
        }
        return true
    }

    private fun applyAccessibilityTamperGuard(packageName: String, screenText: String): Boolean {
        val match = accessibilityTamperGuard.evaluatePhoneCodexAccessibilityControls(
            packageName = packageName,
            screenText = screenText
        ) ?: return false

        hideBlockOverlay()
        hideWarningOverlay()
        val explanation = DecisionExplanation(
            decision = DecisionType.WARN.name,
            reason = match.reason,
            source = "Tamper Guard",
            confidence = 1.0,
            matchedSignals = match.matchedSignals
        )
        recordExplanation(packageName, screenText, explanation)
        eventLogStore.addEvent(
            "Tamper guard sent user home from PhoneCodex accessibility controls"
        )
        performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(
            "PhoneCodexDecision",
            "Tamper guard forced HOME for $packageName signals=${match.matchedSignals}"
        )
        return true
    }

    private fun applyPermanentGuardrails(packageName: String, screenText: String): Boolean {
        val match = permanentGuardrailEvaluator.evaluate(
            packageName = packageName,
            screenText = screenText,
            guardrails = permanentGuardrailsStore.getEnabledGuardrails()
        ) ?: return false

        val matchedSignals = listOf(EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL) +
            match.matchedStrongSignals + match.matchedWeakSignals
        val explanation = DecisionExplanation(
            decision = match.decision.name,
            reason = SessionEnforcementCopy.overlayEvidence(
                EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
                match.reason
            ),
            source = "Permanent Guardrail",
            confidence = 1.0,
            matchedSignals = matchedSignals
        )
        recordExplanation(packageName, screenText, explanation)

        when (match.decision) {
            DecisionType.BLOCK -> {
                eventLogStore.addEvent(
                    "Permanent guardrail blocked $packageName: ${match.guardrailName}"
                )
                hideWarningOverlay()
                showBlockOverlay(packageName, explanation)
            }
            DecisionType.WARN -> {
                hideBlockOverlay()
                val signature = textSignature(screenText)
                if (!isRecentlyDismissedWarning(packageName, signature)) {
                    showWarningOverlay(packageName, signature, explanation)
                }
            }
            else -> return false
        }

        Log.d(
            "PhoneCodexDecision",
            "Permanent guardrail ${match.decision.name} for $packageName " +
                "(${match.guardrailName}) reason=${match.reason}"
        )
        return true
    }

    private fun applyAppRuleBehavior(packageName: String, screenText: String): Boolean {
        val rule = appRulesStore.getRuleForPackage(packageName) ?: return false

        return when (rule.behavior) {
            AppRuleBehavior.ALLOW -> {
                if (VideoPlatformRegistry.enforcesMediaSurfaces(packageName) ||
                    VideoPlatformRegistry.looksLikeUnknownStreamingPackage(packageName)
                ) {
                    Log.d(
                        "PhoneCodexDecision",
                        EnforcementDecisionLog.format(
                            packageName = packageName,
                            surface = "video_shell",
                            rule = "App Rule",
                            decision = "CONTINUE",
                            reasonCode = EnforcementReasonCodes.APP_RULE_ALLOW_DEFERRED,
                            reason = "App rule ALLOW cannot skip promise content gates"
                        )
                    )
                    false
                } else {
                    hideWarningOverlay()
                    hideBlockOverlay()
                    debugStateStore.recordDecision(
                        packageName = packageName,
                        screenText = screenText,
                        decision = DecisionType.ALLOW.name,
                        reason = "Allowed by app rule for ${rule.label}",
                        source = "App Rule",
                        confidence = 1.0
                    )
                    Log.d(
                        "PhoneCodexDecision",
                        "App rule ALLOW for $packageName (${rule.label})"
                    )
                    true
                }
            }
            AppRuleBehavior.WARN -> {
                hideBlockOverlay()
                val signature = textSignature(screenText)
                val reason = "App rule warns for ${rule.label}"
                val explanation = DecisionExplanation(
                    decision = DecisionType.WARN.name,
                    reason = reason,
                    source = "App Rule",
                    confidence = 1.0
                )
                recordExplanation(packageName, screenText, explanation)
                if (!isRecentlyDismissedWarning(packageName, signature)) {
                    showWarningOverlay(packageName, signature, explanation)
                }
                Log.d(
                    "PhoneCodexDecision",
                    "App rule WARN for $packageName (${rule.label})"
                )
                true
            }
            AppRuleBehavior.BLOCK -> {
                hideWarningOverlay()
                val reason = "Blocked by app rule for ${rule.label}"
                val explanation = DecisionExplanation(
                    decision = DecisionType.BLOCK.name,
                    reason = reason,
                    source = "App Rule",
                    confidence = 1.0
                )
                recordExplanation(packageName, screenText, explanation)
                handleBlockedApp(
                    packageName = packageName,
                    screenText = screenText,
                    explanation = explanation
                )
                Log.d(
                    "PhoneCodexDecision",
                    "App rule BLOCK for $packageName (${rule.label})"
                )
                true
            }
            AppRuleBehavior.AI_DECIDE -> false
        }
    }

    private fun applyContentSignalRules(packageName: String, screenText: String): Boolean {
        val sessionGoal = sessionStore.getActiveSession()?.goal.orEmpty()
        val settings = studyWorldSettingsStore.getSettings()
        val maxVideoBlockMinutes = settings.maxVideoLengthBlockMinutes
        val minVideoBlockMinutes = settings.minVideoLengthBlockMinutes
        val surfaceDetection = SurfaceDetector.detect(packageName, screenText)
        val surface = surfaceDetection.surface
        // One unified context per snapshot — feeds activity/durationSource into all logs.
        val enforcementContext = EnforcementContext.fromParts(
            packageName = packageName,
            screenText = screenText,
            detection = surfaceDetection,
            parsed = VideoDurationParser.parse(screenText)
        )
        val signals = contentSignalDetector.detect(packageName, screenText)

        // Cross-app daily short-form quota (deterministic, before media-length / AI).
        // Only counts active short-form players — never home/shelf/recs.
        if (
            applyShortFormQuotaRules(
                packageName = packageName,
                screenText = screenText,
                sessionGoal = sessionGoal,
                settings = settings,
                surfaceFallback = surface,
                context = enforcementContext
            )
        ) {
            return true
        }

        when (
            EntertainmentBanGate.evaluate(
                packageName = packageName,
                screenText = screenText,
                goal = sessionGoal,
                dossier = appDossierStore.merged(packageName, screenText)
            )
        ) {
            EntertainmentBanAction.BLOCK -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.BLOCK,
                    reason = SessionEnforcementCopy.MONK_ENTERTAINMENT_BLOCK,
                    source = "Entertainment Ban",
                    matchedSignals = listOf("monk_entertainment_ban") + surfaceDetection.evidence,
                    block = true,
                    reasonCode = EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK,
                    surface = surface,
                    context = enforcementContext
                )
            }
            EntertainmentBanAction.ALLOW_STUDY -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.ALLOW,
                    reason = "Active study lecture allowed during monk / study-only",
                    source = "Entertainment Ban",
                    matchedSignals = signals.matchedSignals,
                    block = false,
                    reasonCode = EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW,
                    surface = surface,
                    context = enforcementContext
                )
            }
            EntertainmentBanAction.NONE -> Unit
        }

        // Clock B local law — active player only; passive → PASS (no AI nudge).
        // Duration must be current_player only — recommendation cards never BLOCK.
        val mediaLength = MediaLengthEnforcement.decideDetailed(
            packageName = packageName,
            goal = sessionGoal,
            screenText = screenText,
            structuredMaxBlockMinutes = maxVideoBlockMinutes,
            structuredMinBlockMinutes = minVideoBlockMinutes,
            enforcementScopePackages = settings.enforcementScopePackages,
            contentBrands = settings.enforcementContentBrands
        )
        when (mediaLength.decision) {
            MediaLengthLocalDecision.BLOCK -> {
                val durationSeconds = mediaLength.currentPlayerDurationSeconds ?: 0
                val blockCode = if (
                    minVideoBlockMinutes != null &&
                    durationSeconds < minVideoBlockMinutes * 60
                ) {
                    EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN
                } else {
                    EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
                }
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.BLOCK,
                    reason = "Video length conflicts with the promise",
                    source = "Duration Rule",
                    matchedSignals = listOf(
                        "media_length_over_limit",
                        "duration_source=current_player"
                    ) + surfaceDetection.evidence,
                    block = true,
                    reasonCode = blockCode,
                    surface = surface,
                    context = enforcementContext
                )
            }
            MediaLengthLocalDecision.WAIT -> {
                val sourceLabel = when (mediaLength.durationSource) {
                    DurationSource.CURRENT_PLAYER -> "current_player"
                    DurationSource.RECOMMENDATION_IGNORED -> "recommendation_ignored"
                    DurationSource.NONE -> "none"
                }
                val waitCode =
                    if (BlankVideoTreeGate.isNearBlankAccessibilityTree(screenText) &&
                        VideoPlatformRegistry.isVideoOrStreamingPackage(packageName)
                    ) {
                        EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE
                    } else {
                        EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK
                    }
                Log.d(
                    "PhoneCodexDecision",
                    EnforcementDecisionLog.format(
                        packageName = packageName,
                        surface = surface.name,
                        rule = "Duration Rule",
                        decision = "WAIT",
                        reasonCode = waitCode,
                        reason = "Waiting for current player duration " +
                            "(duration_source=$sourceLabel)",
                        evidence = surfaceDetection.evidence +
                            listOf("duration_source=$sourceLabel"),
                        activity = surfaceDetection.activity.name.lowercase(),
                        durationSource = sourceLabel
                    )
                )
                recordExplanation(
                    packageName = packageName,
                    screenText = screenText,
                    explanation = DecisionExplanation(
                        decision = "WAIT",
                        reason = SessionEnforcementCopy.overlayEvidence(
                            waitCode,
                            "Waiting for the player clock",
                            hardBlock = false
                        ),
                        source = "Duration Rule",
                        confidence = 1.0,
                        matchedSignals = listOf(waitCode, "duration_source=$sourceLabel")
                    ),
                    reasonCode = waitCode,
                    context = enforcementContext,
                    surface = surface.name
                )
                val waitSession = sessionStore.getActiveSession()
                if (waitSession != null) {
                    maybeRequestVisionCounsel(
                        packageName = packageName,
                        screenText = screenText,
                        storedSession = waitSession,
                        localReasonCode = waitCode,
                        localDecision = DecisionType.ALLOW,
                        surfaceIsPass = false
                    )
                }
                return true
            }
            MediaLengthLocalDecision.PASS -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.ALLOW,
                    reason = SurfaceEnforcementGate.PASS_REASON,
                    source = "Surface Gate",
                    matchedSignals = listOf("passive_video_surface") + surfaceDetection.evidence,
                    block = false,
                    reasonCode = EnforcementReasonCodes.SURFACE_PASS_PASSIVE,
                    surface = surface,
                    context = enforcementContext
                )
            }
            MediaLengthLocalDecision.ALLOW -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.ALLOW,
                    reason = "Video length is within the promise limit",
                    source = "Duration Rule",
                    matchedSignals = listOf(
                        "media_length_local_allow",
                        "duration_source=current_player"
                    ) + surfaceDetection.evidence,
                    block = false,
                    reasonCode = EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT,
                    surface = surface,
                    context = enforcementContext
                )
            }
            MediaLengthLocalDecision.NONE -> Unit
        }

        val isYouTube = VideoPlatformRegistry.isOfficialYouTube(packageName)
        val isChrome = VideoPlatformRegistry.isChrome(packageName)

        // Shorts ban: only on active short-form player evidence, never shelf/home.
        if (
            isYouTube &&
            surfaceDetection.isShortFormPlay &&
            signals.isYouTubeShorts &&
            PromiseIntentRules.blocksShortForm(sessionGoal) &&
            !PromiseIntentRules.allowsShortForm(sessionGoal) &&
            settings.shortFormDailyQuotaLimit == null
        ) {
            return applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.BLOCK,
                reason = "Short-form video conflicts with this promise",
                source = "Content Signal",
                matchedSignals = signals.matchedSignals,
                block = true,
                reasonCode = EnforcementReasonCodes.CONTENT_SIGNAL_BLOCK,
                surface = surface,
                context = enforcementContext
            )
        }

        if (isYouTube && signals.isLikelySearchOrLecture && surfaceDetection.isActivePlayer) {
            return applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.ALLOW,
                reason = "Study-like YouTube content detected",
                source = "Content Signal",
                matchedSignals = signals.matchedSignals,
                block = false,
                reasonCode = EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW,
                surface = surface,
                context = enforcementContext
            )
        }

        // Adult: still enforce on Chrome pages; keywords tightened to avoid news FPs.
        if (isChrome && signals.isChromeAdultOrPorn) {
            return applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.BLOCK,
                reason = "Adult or porn content detected in Chrome during Study World",
                source = "Content Signal",
                matchedSignals = signals.matchedSignals,
                block = true,
                reasonCode = EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
                surface = surface,
                context = enforcementContext
            )
        }

        val hasMediaLaw = minVideoBlockMinutes != null ||
            maxVideoBlockMinutes != null ||
            PromiseIntentRules.hasMediaLengthLimit(sessionGoal)
        val entertainmentBan = PromiseIntentRules.blocksEntertainmentOrMovies(sessionGoal)
        if (
            isChrome &&
            !hasMediaLaw &&
            !entertainmentBan &&
            (signals.isChromeStudyLike || PromiseIntentRules.allowsBroadChromeUse(sessionGoal))
        ) {
            return applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.ALLOW,
                reason = "Chrome use is allowed by this promise",
                source = "Content Signal",
                matchedSignals = signals.matchedSignals,
                block = false,
                reasonCode = EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW,
                surface = surface,
                context = enforcementContext
            )
        }

        // Hard Surface Gate: passive video surfaces never reach backend AI WARN/BLOCK.
        val gate = SurfaceEnforcementGate.evaluate(
            packageName = packageName,
            screenText = screenText,
            goal = sessionGoal,
            structuredMaxBlockMinutes = maxVideoBlockMinutes,
            structuredMinBlockMinutes = minVideoBlockMinutes,
            shortFormDailyQuotaLimit = settings.shortFormDailyQuotaLimit
        )
        if (gate.isPass) {
            return applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.ALLOW,
                reason = gate.reason,
                source = "Surface Gate",
                matchedSignals = listOf("surface_gate_pass") + gate.detection.evidence,
                block = false,
                reasonCode = EnforcementReasonCodes.SURFACE_PASS_PASSIVE,
                surface = gate.detection.surface,
                context = enforcementContext
            )
        }

        return false
    }

    /**
     * Deterministic short-form quota. Returns true when a hard decision was applied
     * (AI must not run afterward for that event).
     */
    private fun applyShortFormQuotaRules(
        packageName: String,
        screenText: String,
        sessionGoal: String,
        settings: com.phonecodex.app.domain.model.StudyWorldSettings,
        surfaceFallback: SurfaceDetectionResult,
        context: EnforcementContext? = null
    ): Boolean {
        val limit = settings.shortFormDailyQuotaLimit
            ?: ShortFormQuotaGate.parseDailyShortFormLimit(sessionGoal)
            ?: return false

        val decision = shortFormQuotaGate.evaluate(
            packageName = packageName,
            screenText = screenText,
            limit = limit,
            allowLongEducational = settings.allowLongEducationalVideos,
            enforcementScopePackages = settings.enforcementScopePackages,
            contentBrands = settings.enforcementContentBrands
        )
        Log.d("PhoneCodexDecision", ShortFormQuotaGate.formatLog(decision, packageName))

        when (decision.action) {
            ShortFormQuotaAction.ALLOW_AND_COUNT,
            ShortFormQuotaAction.ALLOW_DUPLICATE -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.WARN,
                    reason = SessionEnforcementCopy.shortsRemaining(decision.count, decision.limit),
                    source = "ShortQuota",
                    matchedSignals = listOf("short_form_quota"),
                    block = false,
                    reasonCode = EnforcementReasonCodes.QUOTA_ALLOW_COUNT,
                    surface = decision.surface,
                    count = decision.count,
                    limit = decision.limit,
                    context = context
                )
            }
            ShortFormQuotaAction.ALLOW_LONG_EDUCATIONAL -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.ALLOW,
                    reason = decision.reason,
                    source = "ShortQuota",
                    matchedSignals = listOf("long_educational_exception"),
                    block = false,
                    reasonCode = EnforcementReasonCodes.QUOTA_SKIP_NOT_PLAY,
                    surface = decision.surface,
                    count = decision.count,
                    limit = decision.limit,
                    context = context
                )
            }
            ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED,
            ShortFormQuotaAction.BLOCK_ADULT -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.BLOCK,
                    reason = decision.reason,
                    source = "ShortQuota",
                    matchedSignals = listOf(
                        if (decision.action == ShortFormQuotaAction.BLOCK_ADULT) {
                            "adult_short_form"
                        } else {
                            "short_form_quota_exceeded"
                        }
                    ),
                    block = true,
                    reasonCode = if (decision.action == ShortFormQuotaAction.BLOCK_ADULT) {
                        EnforcementReasonCodes.QUOTA_BLOCK_ADULT
                    } else {
                        EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED
                    },
                    surface = decision.surface,
                    count = decision.count,
                    limit = decision.limit,
                    context = context
                )
            }
            ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY,
            ShortFormQuotaAction.NONE -> {
                // Non-player / out of scope — continue other rules.
                if (decision.surface != surfaceFallback) {
                    // keep log already emitted
                }
                return false
            }
        }
    }

    private fun applyFeedbackMemoryRules(packageName: String, screenText: String): Boolean {
        val matchedDecision = feedbackMemory.findMatchingDecision(packageName, screenText)
            ?: return false

        val reason = "Matched previous feedback"
        return when (matchedDecision) {
            DecisionType.ALLOW.name -> applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.ALLOW,
                reason = reason,
                source = "Feedback Memory",
                matchedSignals = emptyList(),
                block = false,
                reasonCode = EnforcementReasonCodes.FEEDBACK_MEMORY_ALLOW
            )
            DecisionType.BLOCK.name -> applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.BLOCK,
                reason = reason,
                source = "Feedback Memory",
                matchedSignals = emptyList(),
                block = true,
                reasonCode = EnforcementReasonCodes.FEEDBACK_MEMORY_BLOCK
            )
            else -> false
        }
    }

    private fun maybeResearchApp(packageName: String, screenText: String, goal: String) {
        if (EntertainmentClassDetector.isMonkExemptPackage(packageName)) return
        if (VideoPlatformRegistry.isRegistered(packageName)) return
        if (!appDossierStore.shouldResearch(packageName, System.currentTimeMillis())) return
        val local = AppClassResolver.resolve(packageName, screenText)
        val needsResearch =
            PlayListingCategoryParser.isPlayStorePackage(packageName) ||
                local == AppClass.UNKNOWN ||
                local == AppClass.COMMUNICATION ||
                local == AppClass.DATING
        if (!needsResearch) return
        val label = resolveAppLabel(packageName) ?: packageName
        val claimed = appDossierStore.get(packageName)?.userClaimedClass
        classifierExecutor.execute {
            val researched = appDossierClient.research(
                packageName = packageName,
                appLabel = label,
                playListingText = screenText,
                userClaimedClass = claimed
            ) ?: return@execute
            val merged = appDossierStore.merged(packageName, screenText).withCounsel(
                counselClass = researched.counselClass ?: AppClass.UNKNOWN,
                confidence = researched.confidence,
                summary = researched.summary,
                source = researched.source,
                nowMillis = researched.researchedAtMillis
            )
            appDossierStore.put(merged)
            Log.d(
                "PhoneCodexDossier",
                "researched pkg=$packageName counsel=${merged.counselClass} " +
                    "effective=${AppDossierMergeLaw.effectiveClass(merged)} " +
                    "goal=$goal"
            )
        }
    }

    /**
     * Unknown entertainment video clones must WARN, never silent ALLOW-as-harmless.
     */
    private fun applyUnknownVideoCloneWarn(packageName: String, screenText: String): Boolean {
        if (!VideoPlatformRegistry.isUnknownVideoCloneCandidate(packageName, screenText)) {
            return false
        }
        val explanation = DecisionExplanation(
            decision = DecisionType.WARN.name,
            reason = "Unknown video app — treat carefully; not in the short-form platform registry",
            source = "Surface Gate",
            confidence = 0.7,
            matchedSignals = listOf("unknown_video_clone")
        )
        recordExplanation(packageName, screenText, explanation)
        hideBlockOverlay()
        val signature = textSignature(screenText)
        if (!isRecentlyDismissedWarning(packageName, signature)) {
            showWarningOverlay(packageName, signature, explanation)
        }
        Log.d(
            "PhoneCodexDecision",
            EnforcementDecisionLog.format(
                packageName = packageName,
                surface = SurfaceDetector.detect(packageName, screenText).surface.name,
                rule = "UnknownClone",
                decision = "WARN",
                reasonCode = EnforcementReasonCodes.UNKNOWN_CLONE_WARN,
                reason = explanation.reason
            )
        )
        return true
    }

    private fun applySignalDecision(
        packageName: String,
        screenText: String,
        decision: DecisionType,
        reason: String,
        source: String,
        matchedSignals: List<String>,
        block: Boolean,
        reasonCode: String,
        surface: SurfaceDetectionResult = SurfaceDetectionResult.UNKNOWN,
        count: Int? = null,
        limit: Int? = null,
        context: EnforcementContext? = null
    ): Boolean {
        val now = System.currentTimeMillis()
        if (
            (decision == DecisionType.BLOCK || decision == DecisionType.WARN) &&
            OverlayLifecycleGate.shouldHoldSameDecision(
                packageName = packageName,
                decision = decision.name,
                lastPackage = lastOverlayDecisionPackage,
                lastDecision = lastOverlayDecisionName,
                lastAppliedAtMillis = lastOverlayDecisionAtMillis,
                nowMillis = now
            )
        ) {
            Log.d(
                "PhoneCodexOverlay",
                "Decision hold pkg=$packageName decision=${decision.name} — skip overlay churn"
            )
            return true
        }

        val explanation = DecisionExplanation(
            decision = decision.name,
            reason = reason,
            source = source,
            confidence = 1.0,
            matchedSignals = listOf(reasonCode) + matchedSignals.filter { it != reasonCode }
        )
        recordExplanation(
            packageName = packageName,
            screenText = screenText,
            explanation = explanation,
            reasonCode = reasonCode,
            context = context,
            surface = surface.name
        )

        if (block) {
            hideWarningOverlay()
            handleBlockedApp(
                packageName = packageName,
                screenText = screenText,
                explanation = explanation
            )
        } else if (decision == DecisionType.WARN) {
            hideBlockOverlay()
            val signature = textSignature(screenText)
            if (!isRecentlyDismissedWarning(packageName, signature)) {
                showWarningOverlay(packageName, signature, explanation)
            }
        } else {
            if (OverlayLifecycleGate.shouldClearWarningOnAllow()) {
                hideWarningOverlay()
            }
            hideBlockOverlay()
        }

        lastOverlayDecisionPackage = packageName
        lastOverlayDecisionName = decision.name
        lastOverlayDecisionAtMillis = now

        Log.d(
            "PhoneCodexDecision",
            EnforcementDecisionLog.format(
                packageName = packageName,
                surface = surface.name,
                rule = source,
                decision = decision.name,
                reasonCode = reasonCode,
                reason = reason,
                count = count,
                limit = limit,
                evidence = matchedSignals,
                activity = context?.activityState?.name?.lowercase()
                    ?: if (surface.isActiveBehaviorSurface) "active" else "passive",
                durationSource = context?.durationSource?.name?.lowercase()
            )
        )
        return true
    }

    private fun maybeRequestVisionCounsel(
        packageName: String,
        screenText: String,
        storedSession: FocusSession,
        localReasonCode: String,
        localDecision: DecisionType?,
        surfaceIsPass: Boolean
    ) {
        val settings = studyWorldSettingsStore.getSettings()
        val parsed = VideoDurationParser.parse(screenText)
        val verdict = VisionCaptureGate.evaluate(
            VisionCaptureInput(
                nowEpochMs = System.currentTimeMillis(),
                visionExperimentEnabled = settings.visionExperimentEnabled,
                hasActiveSession = storedSession.status == SessionStatus.ACTIVE ||
                    storedSession.status == SessionStatus.LOCKED,
                packageName = packageName,
                screenText = screenText,
                localReasonCode = localReasonCode,
                localDecision = localDecision,
                surfaceIsPass = surfaceIsPass,
                hasCurrentPlayerClock = parsed.currentPlayerDurationSeconds != null
            )
        )
        if (!verdict.shouldCapture) {
            logVisionSkipThrottled(packageName, verdict.detail, localReasonCode)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            logVisionSkipThrottled(packageName, "api_too_low", localReasonCode)
            return
        }
        val now = System.currentTimeMillis()
        if (
            EnforcementEventGate.shouldThrottleClassificationRequest(
                packageName = packageName,
                lastRequestPackage = lastClassificationRequestPackage,
                lastRequestAtMillis = lastClassificationRequestTimeMillis,
                nowMillis = now,
                classificationInFlight = classificationInFlight
            )
        ) {
            logVisionSkipThrottled(packageName, "throttled", localReasonCode)
            return
        }
        classificationInFlight = true
        lastClassificationRequestPackage = packageName
        lastClassificationRequestTimeMillis = now
        requestVisionScreenshotThenClassify(
            packageName = packageName,
            screenText = screenText,
            storedSession = storedSession,
            localReasonCode = localReasonCode,
            localDecision = localDecision,
            surfaceIsPass = surfaceIsPass
        )
    }

    private fun requestVisionScreenshotThenClassify(
        packageName: String,
        screenText: String,
        storedSession: FocusSession,
        localReasonCode: String,
        localDecision: DecisionType?,
        surfaceIsPass: Boolean
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            classificationInFlight = false
            logVisionSkipThrottled(packageName, "api_too_low", localReasonCode)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                classifierExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var jpeg: String? = null
                        try {
                            jpeg = VisionScreenshotEncoder.encodeDownscaledJpeg(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                        } catch (err: Exception) {
                            Log.d(
                                "PhoneCodexDecision",
                                EnforcementDecisionLog.format(
                                    packageName = packageName,
                                    surface = "VISION",
                                    rule = "VisionExperiment",
                                    decision = "WAIT",
                                    reasonCode = EnforcementReasonCodes.VISION_EXPERIMENT_SKIPPED,
                                    reason = "capture_failed"
                                )
                            )
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                        if (jpeg.isNullOrBlank()) {
                            mainHandler.post { classificationInFlight = false }
                            return
                        }
                        runVisionClassify(
                            packageName = packageName,
                            screenText = screenText,
                            storedSession = storedSession,
                            imageJpegBase64 = jpeg,
                            localReasonCode = localReasonCode,
                            localDecision = localDecision,
                            surfaceIsPass = surfaceIsPass
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        mainHandler.post { classificationInFlight = false }
                        logVisionSkipThrottled(packageName, "capture_failed", localReasonCode)
                    }
                }
            )
        } catch (err: Exception) {
            classificationInFlight = false
            logVisionSkipThrottled(packageName, "capture_failed", localReasonCode)
        }
    }

    private fun runVisionClassify(
        packageName: String,
        screenText: String,
        storedSession: FocusSession,
        imageJpegBase64: String,
        localReasonCode: String,
        localDecision: DecisionType?,
        surfaceIsPass: Boolean
    ) {
        val request = ClassifyRequestBuilder.build(
            packageName = packageName,
            appLabel = resolveAppLabel(packageName),
            screenText = screenText,
            session = storedSession,
            enabledGuardrailIds = permanentGuardrailsStore.getEnabledGuardrails().map { it.id },
            imageJpegBase64 = imageJpegBase64
        )
        val classification = try {
            contentClassifier.classify(request)
        } catch (err: Exception) {
            Log.w("PhoneCodexNetAI", "Vision classify failed for $packageName: ${err.message}")
            null
        }
        mainHandler.post {
            try {
                applyVisionCounselResult(
                    packageName = packageName,
                    screenText = screenText,
                    storedSession = storedSession,
                    classification = classification,
                    localReasonCode = localReasonCode,
                    localDecision = localDecision,
                    surfaceIsPass = surfaceIsPass
                )
            } finally {
                classificationInFlight = false
            }
        }
    }

    private fun applyVisionCounselResult(
        packageName: String,
        screenText: String,
        storedSession: FocusSession,
        classification: ContentClassification?,
        localReasonCode: String,
        localDecision: DecisionType?,
        surfaceIsPass: Boolean
    ) {
        if (classification == null) {
            logVisionSkipThrottled(packageName, "classify_null", localReasonCode)
            return
        }
        val merge = VisionCounselMerge.merge(
            localDecision = localDecision,
            localReasonCode = localReasonCode,
            surfaceIsPass = surfaceIsPass,
            vision = classification,
            backendUsedImage = classification.usedImage
        )
        Log.d(
            "PhoneCodexDecision",
            EnforcementDecisionLog.format(
                packageName = packageName,
                surface = SurfaceDetector.detect(packageName, screenText).surface.name,
                rule = "VisionExperiment",
                decision = if (merge.applyToOverlay) {
                    merge.classification?.decision?.name ?: "WAIT"
                } else {
                    "WAIT"
                },
                reasonCode = merge.reasonCode,
                reason = listOfNotNull(
                    merge.detail,
                    classification.whatOnScreen?.takeIf { it.isNotBlank() }?.let { "seen=$it" }
                ).joinToString(" ")
            )
        )
        if (!merge.applyToOverlay) return
        val gated = merge.classification ?: return
        // Re-check surface PASS on the latest tree so a Home swipe cannot be blocked by counsel.
        val settings = studyWorldSettingsStore.getSettings()
        val surfaceGate = SurfaceEnforcementGate.evaluate(
            packageName = packageName,
            screenText = screenText,
            goal = storedSession.goal,
            structuredMaxBlockMinutes = settings.maxVideoLengthBlockMinutes,
            structuredMinBlockMinutes = settings.minVideoLengthBlockMinutes,
            shortFormDailyQuotaLimit = settings.shortFormDailyQuotaLimit
        )
        if (surfaceGate.isPass) {
            logVisionSkipThrottled(packageName, "surface_pass", localReasonCode)
            return
        }
        when (gated.decision) {
            DecisionType.BLOCK, DecisionType.LOCK -> {
                hideWarningOverlay()
                handleBlockedApp(
                    packageName = packageName,
                    screenText = screenText,
                    explanation = DecisionExplanation(
                        decision = gated.decision.name,
                        reason = gated.reason,
                        source = gated.source,
                        confidence = gated.confidence,
                        matchedSignals = listOf(EnforcementReasonCodes.VISION_COUNSEL)
                    )
                )
            }
            DecisionType.WARN, DecisionType.ASK -> {
                hideBlockOverlay()
                val signature = textSignature(screenText)
                if (!isRecentlyDismissedWarning(packageName, signature)) {
                    showWarningOverlay(
                        packageName,
                        signature,
                        DecisionExplanation(
                            decision = gated.decision.name,
                            reason = gated.reason,
                            source = gated.source,
                            confidence = gated.confidence,
                            matchedSignals = listOf(EnforcementReasonCodes.VISION_COUNSEL)
                        )
                    )
                }
            }
            else -> Unit
        }
    }

    private fun logVisionSkipThrottled(
        packageName: String,
        detail: String,
        localReasonCode: String
    ) {
        val now = System.currentTimeMillis()
        if (
            packageName == lastVisionSkipPackage &&
            now - lastVisionSkipAtMillis < EnforcementEventGate.MIN_CLASSIFICATION_INTERVAL_MS
        ) {
            return
        }
        lastVisionSkipPackage = packageName
        lastVisionSkipAtMillis = now
        Log.d(
            "PhoneCodexDecision",
            EnforcementDecisionLog.format(
                packageName = packageName,
                surface = "VISION",
                rule = "VisionExperiment",
                decision = "WAIT",
                reasonCode = EnforcementReasonCodes.VISION_EXPERIMENT_SKIPPED,
                reason = "$detail local=$localReasonCode"
            )
        )
    }

    private fun isInstallSurface(packageName: String, screenText: String): Boolean {
        if (
            packageName == "com.android.vending" ||
            packageName.contains("packageinstaller", ignoreCase = true)
        ) {
            return true
        }

        val text = screenText.lowercase()
        return INSTALL_SURFACE_KEYWORDS.any { keyword -> text.contains(keyword) }
    }

    private fun logInstallSurfaceIfPresent(packageName: String, screenText: String) {
        if (isInstallSurface(packageName, screenText)) {
            Log.d("PhoneCodexInstallGate", "Install surface detected for $packageName")
        }
    }

    private fun applyClassificationOrFallback(
        packageName: String,
        screenText: String,
        storedSession: FocusSession,
        classification: ContentClassification?
    ) {
        val settings = studyWorldSettingsStore.getSettings()
        val surfaceGate = SurfaceEnforcementGate.evaluate(
            packageName = packageName,
            screenText = screenText,
            goal = storedSession.goal,
            structuredMaxBlockMinutes = settings.maxVideoLengthBlockMinutes,
            structuredMinBlockMinutes = settings.minVideoLengthBlockMinutes,
            shortFormDailyQuotaLimit = settings.shortFormDailyQuotaLimit
        )
        // Backend AI must not WARN/BLOCK after Surface Gate PASS (active→passive clear).
        if (surfaceGate.isPass) {
            if (OverlayLifecycleGate.shouldClearWarningOnAllow()) {
                hideWarningOverlay()
            }
            hideBlockOverlay()
            Log.d(
                "PhoneCodexDecision",
                EnforcementDecisionLog.format(
                    packageName = packageName,
                    surface = surfaceGate.detection.surface.name,
                    rule = "Surface Gate",
                    decision = "ALLOW",
                    reasonCode = EnforcementReasonCodes.AI_SUPPRESSED_BY_SURFACE_GATE,
                    reason = surfaceGate.reason,
                    evidence = surfaceGate.detection.evidence,
                    activity = "passive"
                )
            )
            return
        }

        val gatedClassification = classification?.let { aiConfidenceGate.apply(it) }

        if (gatedClassification != null) {
            recordDebugClassification(packageName, screenText, gatedClassification)
        }

        when (gatedClassification?.decision) {
            DecisionType.ALLOW -> {
                if (OverlayLifecycleGate.shouldClearWarningOnAllow()) {
                    hideWarningOverlay()
                }
                hideBlockOverlay()
                Log.d(
                    "PhoneCodexDecision",
                    EnforcementDecisionLog.format(
                        packageName = packageName,
                        surface = SurfaceDetector.detect(packageName, screenText).surface.name,
                        rule = gatedClassification.source,
                        decision = "ALLOW",
                        reasonCode = EnforcementReasonCodes.AI_ALLOW,
                        reason = gatedClassification.reason,
                        activity = "active"
                    )
                )
            }
            DecisionType.BLOCK, DecisionType.LOCK -> {
                hideWarningOverlay()
                val explanation = DecisionExplanation(
                    decision = gatedClassification.decision.name,
                    reason = gatedClassification.reason,
                    source = gatedClassification.source,
                    confidence = gatedClassification.confidence
                )
                Log.d(
                    "PhoneCodexDecision",
                    "final ${gatedClassification.decision} pkg=$packageName " +
                        "source=${gatedClassification.source} " +
                        "confidence=${gatedClassification.confidence} " +
                        "reasonCategory=${gatedClassification.reasonCategory ?: "—"} " +
                        "wouldEscalate=${gatedClassification.wouldEscalate}"
                )
                handleBlockedApp(
                    packageName = packageName,
                    screenText = screenText,
                    explanation = explanation
                )
            }
            DecisionType.WARN, DecisionType.ASK -> {
                hideBlockOverlay()
                val signature = textSignature(screenText)
                val explanation = DecisionExplanation(
                    decision = gatedClassification.decision.name,
                    reason = gatedClassification.reason,
                    source = gatedClassification.source,
                    confidence = gatedClassification.confidence
                )
                if (!isRecentlyDismissedWarning(packageName, signature)) {
                    showWarningOverlay(packageName, signature, explanation)
                }
                Log.d(
                    "PhoneCodexDecision",
                    "final WARN pkg=$packageName source=${gatedClassification.source} " +
                        "confidence=${gatedClassification.confidence} " +
                        "reasonCategory=${gatedClassification.reasonCategory ?: "—"} " +
                        "wouldEscalate=${gatedClassification.wouldEscalate}"
                )
            }
            else -> evaluatePolicyFallback(
                packageName = packageName,
                screenText = screenText,
                storedSession = storedSession,
                backendUnavailable = classification == null
            )
        }
    }

    private fun evaluatePolicyFallback(
        packageName: String,
        screenText: String,
        storedSession: FocusSession,
        backendUnavailable: Boolean = false
    ) {
        val context = ContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            packageName = packageName,
            appLabel = null,
            screenText = screenText,
            url = null
        )
        val decision = policyEngine.evaluate(debugWorld, storedSession, context)
        val quotaOwnsVideoSurface =
            ShortFormQuotaGate.parseDailyShortFormLimit(storedSession.goal.orEmpty()) != null &&
                !PromiseIntentRules.isLifestyleEntertainmentBan(storedSession.goal.orEmpty()) &&
                VideoPlatformRegistry.participatesInShortFormQuota(packageName)
        if (
            quotaOwnsVideoSurface &&
            (decision.decision == DecisionType.WARN || decision.decision == DecisionType.ASK)
        ) {
            hideWarningOverlay()
            hideBlockOverlay()
            Log.d(
                "PhoneCodexDecision",
                EnforcementDecisionLog.format(
                    packageName = packageName,
                    surface = SurfaceDetector.detect(packageName, screenText).surface.name,
                    rule = "Policy",
                    decision = "ALLOW",
                    reasonCode = EnforcementReasonCodes.QUOTA_SKIP_NOT_PLAY,
                    reason = "Shorts quota owns this surface. Study World unknown-app is not the law."
                )
            )
            return
        }
        recordDebugPolicy(packageName, screenText, decision)
        Log.d(
            "PhoneCodexDecision",
            "package=$packageName decision=${decision.decision} " +
                "reason=${decision.reason} riskLevel=${decision.riskLevel}"
        )

        when (decision.decision) {
            DecisionType.BLOCK, DecisionType.LOCK -> {
                hideWarningOverlay()
                val explanation = DecisionExplanation(
                    decision = decision.decision.name,
                    reason = decision.reason,
                    source = "Policy",
                    confidence = decision.confidence
                )
                handleBlockedApp(
                    packageName = packageName,
                    screenText = screenText,
                    explanation = explanation
                )
            }
            DecisionType.WARN, DecisionType.ASK -> {
                hideBlockOverlay()
                val signature = textSignature(screenText)
                val explanation = DecisionExplanation(
                    decision = decision.decision.name,
                    reason = decision.reason,
                    source = "Policy",
                    confidence = decision.confidence
                )
                if (!isRecentlyDismissedWarning(packageName, signature)) {
                    showWarningOverlay(packageName, signature, explanation)
                }
                Log.d(
                    "PhoneCodexDecision",
                    "Policy warning for $packageName (${decision.decision}): ${decision.reason}"
                )
            }
            else -> {
                if (backendUnavailable) {
                    applySafeWarnFallback(packageName, screenText)
                } else {
                    hideWarningOverlayUnlessVisibleFor(packageName)
                    hideBlockOverlay()
                }
            }
        }
    }

    private fun applySafeWarnFallback(packageName: String, screenText: String) {
        hideBlockOverlay()
        val signature = textSignature(screenText)
        val explanation = DecisionExplanation(
            decision = DecisionType.WARN.name,
            reason = "Focus advisor unavailable. Stay aligned with your session goal.",
            source = "Safe Fallback",
            confidence = 0.5
        )
        recordExplanation(packageName, screenText, explanation)
        if (!isRecentlyDismissedWarning(packageName, signature)) {
            showWarningOverlay(packageName, signature, explanation)
        }
        Log.d(
            "PhoneCodexDecision",
            "Safe WARN fallback for $packageName (backend unavailable)"
        )
    }

    private fun resolveAppLabel(packageName: String): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun recordExplanation(
        packageName: String,
        screenText: String,
        explanation: DecisionExplanation,
        reasonCode: String? = null,
        context: EnforcementContext? = null,
        surface: String? = null
    ) {
        latestExplanation = explanation
        val code = reasonCode
            ?: explanation.matchedSignals.firstOrNull { signal ->
                signal.contains('_') && signal == signal.uppercase()
            }
        debugStateStore.recordDecision(
            packageName = packageName,
            screenText = screenText,
            decision = explanation.decision,
            reason = explanation.reason,
            source = explanation.source,
            confidence = explanation.confidence ?: 1.0,
            matchedSignals = explanation.matchedSignals,
            reasonCode = code,
            surface = surface ?: context?.surfaceFamily?.name,
            activity = context?.activityState?.name,
            durationSource = context?.durationSource?.name
        )
    }

    private fun buildOverlayModel(explanation: DecisionExplanation): CommitmentOverlayModel {
        val session = sessionStore.getActiveSession()
        val kind = CommitmentOverlayCopy.overlayKind(explanation.decision)
        val remaining = session?.let { it.deadlineMillis - System.currentTimeMillis() }
        val reasonCode = explanation.matchedSignals.firstOrNull { signal ->
            SessionEnforcementCopy.hasDedicatedOverlayCopy(signal)
        } ?: if (explanation.source.contains("Guardrail", ignoreCase = true)) {
            EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL
        } else {
            null
        }
        val hardBlock = kind == OverlayKind.BLOCK || kind == OverlayKind.LOCK
        val evidence = SessionEnforcementCopy.overlayEvidence(
            reasonCode,
            CommitmentOverlayCopy.evidenceLine(explanation.decision, explanation.reason),
            hardBlock = hardBlock
        )
        return CommitmentOverlayModel(
            kind = kind,
            reason = evidence,
            promiseGoal = session?.goal?.takeIf { it.isNotBlank() },
            attemptCount = session?.attemptCount,
            remainingMillis = remaining,
            strictness = session?.strictness,
            reasonCode = reasonCode,
            hidePromiseLine = SessionEnforcementCopy.hidePromiseLine(reasonCode, hardBlock),
            forceContinue = SessionEnforcementCopy.forceContinue(reasonCode, hardBlock)
        )
    }

    private fun openPhoneCodexApp() {
        val launch = packageManager.getLaunchIntentForPackage(OWN_PACKAGE_NAME) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(launch)
        } catch (err: RuntimeException) {
            Log.e("PhoneCodexOverlay", "Failed to open PhoneCodex", err)
        }
    }

    private fun recordDebugClassification(
        packageName: String,
        screenText: String,
        classification: ContentClassification
    ) {
        latestExplanation = DecisionExplanation(
            decision = classification.decision.name,
            reason = classification.reason,
            source = classification.source,
            confidence = classification.confidence
        )
        val context = EnforcementContext.from(packageName, screenText)
        debugStateStore.recordDecision(
            packageName = packageName,
            screenText = screenText,
            decision = classification.decision.name,
            reason = classification.reason,
            source = classification.source,
            confidence = classification.confidence,
            reasonCategory = classification.reasonCategory,
            wouldEscalate = classification.wouldEscalate,
            backendMeta = classification.backendMeta,
            surface = context.surfaceFamily.name,
            activity = context.activityState.name,
            durationSource = context.durationSource.name
        )
    }

    private fun recordDebugPolicy(
        packageName: String,
        screenText: String,
        decision: PolicyDecision
    ) {
        recordExplanation(
            packageName = packageName,
            screenText = screenText,
            explanation = DecisionExplanation(
                decision = decision.decision.name,
                reason = decision.reason,
                source = "Policy",
                confidence = decision.confidence
            ),
            context = EnforcementContext.from(packageName, screenText)
        )
    }

    private fun handleBlockedApp(
        packageName: String,
        screenText: String,
        explanation: DecisionExplanation
    ) {
        if (currentOverlay != null) return

        hideWarningOverlay()
        latestExplanation = explanation
        val alreadyLocked =
            sessionStore.getStoredSession()?.status == SessionStatus.LOCKED
        if (alreadyLocked) {
            showBlockOverlay(
                packageName,
                explanation.copy(
                    decision = DecisionType.LOCK.name,
                    reason = LOCKED_SESSION_REASON
                )
            )
            return
        }
        val shouldCountAttempt = shouldCountBlockedAttempt(packageName, screenText)
        val updated = if (shouldCountAttempt) {
            sessionStore.incrementAttempt()
        } else {
            sessionStore.getActiveSession()
        }
        eventLogStore.addEvent("Blocked $packageName because ${explanation.reason}")
        val lockAttemptThreshold = studyWorldSettingsStore.getSettings().lockAttemptThreshold
        if (updated != null && updated.attemptCount >= lockAttemptThreshold) {
            sessionStore.lockSession()
            eventLogStore.addEvent("Session locked after $lockAttemptThreshold attempts")
            val lockedExplanation = explanation.copy(
                decision = DecisionType.LOCK.name,
                reason = LOCKED_SESSION_REASON
            )
            latestExplanation = lockedExplanation
            showBlockOverlay(packageName, lockedExplanation)
        } else {
            showBlockOverlay(packageName, explanation)
        }
    }

    private fun shouldCountBlockedAttempt(packageName: String, screenText: String): Boolean {
        val signature = textSignature(screenText)
        val now = System.currentTimeMillis()
        val cooldownMs = studyWorldSettingsStore.getSettings()
            .blockAttemptCooldownMinutes * 60_000L
        val isDuplicate = packageName == lastCountedBlockPackage &&
            signature == lastCountedBlockSignature &&
            now - lastCountedBlockTimeMillis < cooldownMs

        if (isDuplicate) {
            Log.d("PhoneCodexDecision", "Duplicate block ignored for attempt count: $packageName")
            return false
        }

        lastCountedBlockPackage = packageName
        lastCountedBlockSignature = signature
        lastCountedBlockTimeMillis = now
        return true
    }

    /**
     * Handles foreground events while a block (or warning) overlay is already showing.
     *
     * @return true if the event is fully consumed and [inspectAndEvaluate] should return.
     */
    private fun handleActiveOverlayForeground(packageName: String): Boolean {
        val blockTarget = activeOverlayTargetPackage
        if (currentOverlay != null && blockTarget != null) {
            return handleActiveBlockOverlayForeground(packageName, blockTarget)
        }

        val warningTarget = visibleWarningPackageName
        if (currentWarningOverlay != null && warningTarget != null) {
            if (packageName == OWN_PACKAGE_NAME) {
                Log.d(
                    "PhoneCodexOverlay",
                    "Overlay retained for target=$warningTarget despite own-package event"
                )
                return true
            }
            if (isSystemUiPackage(packageName)) {
                return true
            }
            if (packageName == warningTarget) {
                return true
            }
            if (safeAppsStore.isSafePackage(packageName)) {
                hideWarningOverlay()
                Log.d(
                    "PhoneCodexOverlay",
                    "Overlay hidden because allowed package=$packageName"
                )
                return false
            }
        }

        return false
    }

    private fun handleActiveBlockOverlayForeground(
        packageName: String,
        target: String
    ): Boolean {
        if (OverlayLifecycleGate.shouldRetainForNoise(packageName, target)) {
            if (packageName == target) {
                clearOverlayAwayTracking()
            } else {
                clearOverlayAwayTracking()
                Log.d(
                    "PhoneCodexOverlay",
                    "Overlay retained for target=$target despite noise package=$packageName"
                )
            }
            return true
        }

        // Truly allowed / safe app under the overlay → debounce, do not instant-hide
        // (Settings/WhatsApp focus blips used to clear YouTube blocks).
        if (safeAppsStore.isSafePackage(packageName)) {
            val now = System.currentTimeMillis()
            if (overlayAwayCandidatePackage != packageName) {
                overlayAwayCandidatePackage = packageName
                overlayAwaySinceMillis = now
                Log.d(
                    "PhoneCodexOverlay",
                    "Overlay safe-app away debounce candidate=$packageName target=$target"
                )
                return true
            }
            if (
                !OverlayLifecycleGate.shouldClearAfterAway(
                    candidatePackage = packageName,
                    trackedCandidate = overlayAwayCandidatePackage,
                    awaySinceMillis = overlayAwaySinceMillis,
                    nowMillis = now,
                    debounceMs = OverlayLifecycleGate.AWAY_DEBOUNCE_MS
                )
            ) {
                return true
            }
            clearOverlayAwayTracking()
            hideBlockOverlayBecauseAllowed(packageName)
            return false
        }

        // Another external package: wait a short debounce so transient focus blips
        // (recents, share sheets) do not flap the overlay.
        val now = System.currentTimeMillis()
        if (overlayAwayCandidatePackage != packageName) {
            overlayAwayCandidatePackage = packageName
            overlayAwaySinceMillis = now
            Log.d(
                "PhoneCodexOverlay",
                "Overlay away debounce started candidate=$packageName target=$target"
            )
            return true
        }
        if (
            !OverlayLifecycleGate.shouldClearAfterAway(
                candidatePackage = packageName,
                trackedCandidate = overlayAwayCandidatePackage,
                awaySinceMillis = overlayAwaySinceMillis,
                nowMillis = now,
                debounceMs = OverlayLifecycleGate.AWAY_DEBOUNCE_MS
            )
        ) {
            return true
        }

        clearOverlayAwayTracking()
        hideBlockOverlay()
        Log.d(
            "PhoneCodexOverlay",
            "Overlay hidden because target left foreground, now=$packageName"
        )
        return false
    }

    private fun clearOverlayAwayTracking() {
        overlayAwayCandidatePackage = null
        overlayAwaySinceMillis = 0L
    }

    private fun isSystemUiPackage(packageName: String): Boolean {
        return OverlayLifecycleGate.isSystemUi(packageName)
    }

    private fun isSystemOverlayNoise(packageName: String): Boolean {
        return isSystemUiPackage(packageName) ||
            ((currentOverlay != null || currentWarningOverlay != null) &&
                OverlayLifecycleGate.isOwnPackage(packageName))
    }

    private fun showBlockOverlay(packageName: String, explanation: DecisionExplanation) {
        if (currentOverlay != null) return

        hideWarningOverlay()
        latestExplanation = explanation

        val model = buildOverlayModel(explanation)
        val layout = CommitmentOverlayBuilder(this).build(
            model = model,
            actions = CommitmentOverlayActions(
                onBackToSafe = {
                    hideBlockOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                },
                onGoBack = {
                    hideBlockOverlay()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                },
                onOpenPhoneCodex = {
                    hideBlockOverlay()
                    openPhoneCodexApp()
                }
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE prevents the overlay from stealing foreground package
            // identity (own-package flicker). Touches still reach overlay buttons.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            windowManager.addView(layout, params)
            currentOverlay = layout
            activeOverlayTargetPackage = packageName
            lastBlockedPackage = packageName
            clearOverlayAwayTracking()
            runtimeDiagStore.setOverlayActive(true)
            Log.d(
                "PhoneCodexOverlay",
                "Overlay shown kind=${model.kind} target=$packageName"
            )
        } catch (err: RuntimeException) {
            Log.e("PhoneCodexOverlay", "Failed to show block overlay", err)
            currentOverlay = null
            activeOverlayTargetPackage = null
            syncOverlayActiveFlag()
        }
    }

    private fun hideBlockOverlayBecauseAllowed(allowedPackage: String) {
        if (currentOverlay == null) return
        removeBlockOverlayView()
        Log.d(
            "PhoneCodexOverlay",
            "Overlay hidden because allowed package=$allowedPackage"
        )
    }

    private fun hideBlockOverlay() {
        if (currentOverlay == null) return
        removeBlockOverlayView()
        Log.d("PhoneCodexOverlay", "Overlay hidden")
    }

    private fun removeBlockOverlayView() {
        val overlay = currentOverlay ?: return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(overlay)
        } catch (_: IllegalArgumentException) {
        }
        currentOverlay = null
        activeOverlayTargetPackage = null
        clearOverlayAwayTracking()
        syncOverlayActiveFlag()
    }

    private fun showWarningOverlay(
        packageName: String,
        signature: String,
        explanation: DecisionExplanation
    ) {
        if (currentOverlay != null) return
        if (currentWarningOverlay != null && visibleWarningPackageName == packageName) {
            latestExplanation = explanation
            visibleWarningTextSignature = signature
            Log.d("PhoneCodexOverlay", "Warning overlay kept for $packageName")
            return
        }
        hideWarningOverlay()
        latestExplanation = explanation

        val model = buildOverlayModel(explanation).copy(kind = OverlayKind.WARN)
        val layout = CommitmentOverlayBuilder(this).build(
            model = model,
            actions = CommitmentOverlayActions(
                onBackToSafe = {
                    hideWarningOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                },
                onGoBack = {
                    hideWarningOverlay()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                },
                onContinue = {
                    dismissedWarningPackageName = visibleWarningPackageName
                    dismissedWarningTextSignature = visibleWarningTextSignature
                    dismissedWarningTimeMillis = System.currentTimeMillis()
                    hideWarningOverlay()
                }
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE prevents the overlay from stealing foreground package
            // identity (own-package flicker). Touches still reach overlay buttons.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            windowManager.addView(layout, params)
            currentWarningOverlay = layout
            visibleWarningPackageName = packageName
            visibleWarningTextSignature = signature
            runtimeDiagStore.setOverlayActive(true)
            if (SessionEnforcementCopy.isQuotaReminder(model.reasonCode)) {
                mainHandler.removeCallbacks(quotaReminderAutoDismiss)
                mainHandler.postDelayed(quotaReminderAutoDismiss, QUOTA_REMINDER_DISMISS_MS)
            }
            Log.d("PhoneCodexOverlay", "Warning overlay shown kind=WARN target=$packageName")
        } catch (err: RuntimeException) {
            Log.e("PhoneCodexOverlay", "Failed to show warning overlay", err)
            currentWarningOverlay = null
            visibleWarningPackageName = null
            visibleWarningTextSignature = null
            syncOverlayActiveFlag()
        }
    }

    private fun hideWarningOverlay() {
        mainHandler.removeCallbacks(quotaReminderAutoDismiss)
        val overlay = currentWarningOverlay ?: return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(overlay)
        } catch (_: IllegalArgumentException) {
        }
        currentWarningOverlay = null
        visibleWarningPackageName = null
        visibleWarningTextSignature = null
        syncOverlayActiveFlag()
        Log.d("PhoneCodexOverlay", "Warning overlay hidden")
    }

    private fun syncOverlayActiveFlag() {
        runtimeDiagStore.setOverlayActive(
            currentOverlay != null || currentWarningOverlay != null
        )
    }

    private fun hideWarningOverlayUnlessVisibleFor(packageName: String) {
        // Always clear on ALLOW — same-package home/search used to leave WARN stuck.
        hideWarningOverlay()
    }

    private fun isRecentlyDismissedWarning(packageName: String, signature: String): Boolean {
        val dismissedAt = dismissedWarningTimeMillis
        if (dismissedAt == 0L) return false
        return packageName == dismissedWarningPackageName &&
            signature == dismissedWarningTextSignature &&
            System.currentTimeMillis() - dismissedAt < WARNING_DISMISS_TTL_MS
    }

    private fun collectScreenText(packageName: String): String {
        val root = rootInActiveWindow ?: return ""
        val rootPackageName = root.packageName?.toString()
        if (rootPackageName != null && rootPackageName != packageName) {
            Log.d(
                "PhoneCodexA11yText",
                "Skipping stale screen text: event=$packageName root=$rootPackageName"
            )
            return ""
        }
        val builder = StringBuilder()
        collectVisibleText(root, builder, depth = 0)
        return builder.toString().take(MAX_TEXT_LENGTH)
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        if (out.length >= MAX_TEXT_LENGTH) return
        if (node.packageName?.toString() == OWN_PACKAGE_NAME) return

        if (node.isVisibleToUser) {
            appendText(out, node.text?.toString())
            appendText(out, node.contentDescription?.toString())
        }

        for (i in 0 until node.childCount) {
            if (out.length >= MAX_TEXT_LENGTH) return
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, out, depth + 1)
        }
    }

    private fun appendText(out: StringBuilder, value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        if (out.isNotEmpty()) out.append(' ')
        out.append(trimmed)
    }

    private fun logForegroundPackageThrottled(packageName: String) {
        val now = System.currentTimeMillis()
        if (
            packageName == lastForegroundLoggedPackage &&
            now - lastForegroundLoggedTimeMillis < FOREGROUND_LOG_THROTTLE_MS
        ) {
            return
        }
        lastForegroundLoggedPackage = packageName
        lastForegroundLoggedTimeMillis = now
        Log.d("PhoneCodexA11y", "Foreground package: $packageName")
    }

    companion object {
        private const val OWN_PACKAGE_NAME = OverlayLifecycleGate.OWN_PACKAGE
        private const val MAX_TEXT_LENGTH = 3000
        private const val MAX_DEPTH = 50
        private const val WARNING_DISMISS_TTL_MS = 60000L
        private const val QUOTA_REMINDER_DISMISS_MS = 4_000L
        private const val SAFE_APP_LOG_THROTTLE_MS = 10_000L
        private const val FOREGROUND_LOG_THROTTLE_MS = 3_000L
        private const val LOCKED_SESSION_REASON =
            "Lock is active because of repeated attempts to leave your commitment."

        private val INSTALL_SURFACE_KEYWORDS = listOf(
            "install",
            "uninstall",
            "in-app purchases",
            "rated for",
            "contains ads"
        )
    }
}
