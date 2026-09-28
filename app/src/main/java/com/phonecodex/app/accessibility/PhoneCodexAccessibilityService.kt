package com.phonecodex.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import com.phonecodex.app.data.AppDossierStore
import com.phonecodex.app.data.ShortFormQuotaStore
import com.phonecodex.app.data.AppRulesStore
import com.phonecodex.app.data.CommitmentTeardown
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
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog
import com.phonecodex.app.domain.enforcement.BlankVideoTreeAction
import com.phonecodex.app.domain.enforcement.DurationSource
import com.phonecodex.app.domain.enforcement.EnforcementContext
import com.phonecodex.app.domain.enforcement.EnforcementDecisionLog
import com.phonecodex.app.domain.enforcement.EnforcementEventGate
import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.AppClass
import com.phonecodex.app.domain.enforcement.AppClassResolver
import com.phonecodex.app.domain.enforcement.AppDossierClient
import com.phonecodex.app.domain.enforcement.AppDossierMergeLaw
import com.phonecodex.app.domain.enforcement.EntertainmentClassDetector
import com.phonecodex.app.domain.enforcement.PlayListingCategoryParser
import com.phonecodex.app.domain.enforcement.MediaLengthBlockBasis
import com.phonecodex.app.domain.enforcement.MediaLengthLocalDecision
import com.phonecodex.app.domain.enforcement.BlockOverlayWatch
import com.phonecodex.app.domain.enforcement.OverlayLifecycleGate
import com.phonecodex.app.domain.enforcement.OverlayWatchAction
import com.phonecodex.app.domain.enforcement.OverlayWatchVerdict
import com.phonecodex.app.domain.enforcement.PictureInPictureEscapeLaw
import com.phonecodex.app.domain.enforcement.PromiseEnforcementCoordinator
import com.phonecodex.app.domain.enforcement.PromiseEvalInput
import com.phonecodex.app.domain.enforcement.SessionEnforcementCopy
import com.phonecodex.app.domain.enforcement.ShortFormQuotaAction
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.enforcement.SurfaceDetectionResult
import com.phonecodex.app.domain.enforcement.BlankVideoTreeResult
import com.phonecodex.app.domain.enforcement.CommitmentDecision
import com.phonecodex.app.domain.enforcement.CommitmentPolicyEvaluator
import com.phonecodex.app.domain.enforcement.CommitmentSnapshot
import com.phonecodex.app.domain.enforcement.LawStage
import com.phonecodex.app.domain.enforcement.LawVerdict
import com.phonecodex.app.domain.enforcement.MediaLengthDecisionDetail
import com.phonecodex.app.domain.enforcement.ShortFormQuotaDecision
import com.phonecodex.app.domain.enforcement.SurfaceDetection
import com.phonecodex.app.domain.model.GuardrailMatch
import com.phonecodex.app.domain.enforcement.StructuredSurfaceAction
import com.phonecodex.app.domain.enforcement.StructuredSurfaceGate
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
import com.phonecodex.app.domain.promise.PromiseIntentRules
import com.phonecodex.app.domain.scope.ScopeMatch
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
    private val serviceLeaseHeartbeatRunnable = object : Runnable {
        override fun run() {
            runtimeDiagStore.recordServiceHeartbeat()
            mainHandler.postDelayed(this, AccessibilityHeartbeatLaw.SERVICE_LEASE_REFRESH_INTERVAL_MS)
        }
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
    private var blockOverlayScreenText: String? = null
    private var blockOverlayWatch: BlockOverlayWatch? = null
    private var lastOverlayLifecycleLogKey: String? = null
    private var lastOverlayLifecycleLogAtMillis: Long = 0L
    /** A fresh BLOCK right after a page-change re-evaluation is not a new user attempt. */
    private var suppressAttemptCountUntilMillis: Long = 0L
    private val blockOverlayWatchdog = object : Runnable {
        override fun run() {
            if (currentOverlay == null || blockOverlayWatch == null) return
            tickBlockOverlayWatchdog()
            if (currentOverlay != null) {
                mainHandler.postDelayed(this, BLOCK_OVERLAY_WATCH_INTERVAL_MS)
            }
        }
    }
    /** Last external package we blocked (survives hide, for diagnostics). */
    private var lastBlockedPackage: String? = null
    private var pipRecentBlock: PictureInPictureEscapeLaw.RecentBlock? = null
    private val pipGuardRunnable = Runnable { enforcePictureInPictureGuard() }
    private var latestPackageName: String? = null
    private var latestScreenTextSignature: String? = null
    private var visibleWarningPackageName: String? = null
    private var visibleWarningTextSignature: String? = null
    private var dismissedWarningPackageName: String? = null
    private var dismissedWarningTextSignature: String? = null
    private var dismissedWarningTimeMillis: Long = 0L
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
    private var lastScopeCorruptLoggedPackage: String? = null
    private var lastScopeCorruptLoggedMillis: Long = 0L
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
        mainHandler.removeCallbacks(serviceLeaseHeartbeatRunnable)
        mainHandler.postDelayed(
            serviceLeaseHeartbeatRunnable,
            AccessibilityHeartbeatLaw.SERVICE_LEASE_REFRESH_INTERVAL_MS
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

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> enforcePictureInPictureGuard()

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
        mainHandler.removeCallbacks(serviceLeaseHeartbeatRunnable)
        mainHandler.removeCallbacks(pipGuardRunnable)
        hideWarningOverlay()
        hideBlockOverlay()
        runtimeDiagStore.setAccessibilityServiceAlive(false)
        runtimeDiagStore.setOverlayActive(false)
        super.onDestroy()
    }

    /**
     * Reliability design 2026-09-25 §Fix 4: overlay was disappearing on rotation
     * and only reappearing 15–20s later once the passive away-debounce hit.
     *
     * WindowManager does not re-measure our TYPE_ACCESSIBILITY_OVERLAY window on
     * orientation change — sized MATCH_PARENT, sitting on top of a rotated app,
     * it either fails silently or renders cropped. Fix: tear it down and re-add
     * against the new configuration in the same main-thread tick, so the block
     * stays continuous. Uses the last known explanation for that target package.
     *
     * We intentionally do NOT shorten AWAY_DEBOUNCE_MS: that would mask a
     * legitimate own-package event race. Active re-show > passive shorter wait.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Only rebuild when we actually own an overlay right now — otherwise
        // rotation on a normal app must never spawn a spurious overlay.
        val blockTarget = activeOverlayTargetPackage
        val blockView = currentOverlay
        val explanation = latestExplanation
        if (blockView != null && blockTarget != null && explanation != null) {
            Log.d("PhoneCodexOverlay", "Rotation rebuild BLOCK target=$blockTarget")
            hideBlockOverlay()
            showBlockOverlay(blockTarget, explanation)
        }
        val warnTarget = visibleWarningPackageName
        val warnSig = visibleWarningTextSignature
        val warnView = currentWarningOverlay
        if (warnView != null && warnTarget != null && warnSig != null && explanation != null) {
            Log.d("PhoneCodexOverlay", "Rotation rebuild WARN target=$warnTarget")
            hideWarningOverlay()
            showWarningOverlay(warnTarget, warnSig, explanation)
        }
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

        CommitmentTeardown(sessionStore, appRulesStore, eventLogStore::addEvent)
            .end(CommitmentTeardown.Kind.EXPIRED, expectedSessionId = session.id)
        hideWarningOverlay()
        hideBlockOverlay()
        Log.d("PhoneCodexDecision", SessionExpiryPolicy.EXPIRED_EVENT_MESSAGE)
        return null
    }

    private fun inspectAndEvaluate(packageName: String) {
        latestPackageName = packageName

        val screenText = collectScreenText(packageName)
        latestScreenTextSignature = textSignature(screenText)
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
        if (handleActiveOverlayForeground(packageName, screenText)) {
            return
        }

        val settings = studyWorldSettingsStore.getSettings()
        val lawSession = storedSession?.status?.takeIf {
            it == SessionStatus.ACTIVE || it == SessionStatus.LOCKED
        }
        val lawGoal = storedSession?.goal.orEmpty()
        val law = CommitmentPolicyEvaluator.evaluate(
            CommitmentSnapshot(
                packageName = packageName,
                screenText = screenText,
                sessionStatus = lawSession,
                goal = lawGoal,
                settings = settings,
                storedAppRule = appRulesStore.getBehaviorForPackage(packageName),
                isSafePackage = safeAppsStore.isSafePackage(packageName),
                isSystemOverlayNoise = isSystemOverlayNoise(packageName),
                guardrails = permanentGuardrailsStore.getEnabledGuardrails(),
                storedDossier = if (CommitmentPolicyEvaluator.readsStoredDossier(packageName, lawGoal, lawSession)) {
                    appDossierStore.get(packageName)
                } else {
                    null
                }
            ),
            shortFormQuotaGate
        )
        if (safeRootInActiveWindow()?.packageName?.toString() == packageName) {
            pipRecentBlock = PictureInPictureEscapeLaw.afterForegroundVerdict(pipRecentBlock, packageName, law.verdict)
        }
        if (applyLawDecision(law, packageName, screenText, storedSession)) return
        if (storedSession == null) return
        runCounselTail(packageName, screenText, storedSession)
    }

    /** Effects for a scope short-circuit decided by [CommitmentPolicyEvaluator]. */
    private fun applyScopeDecision(
        law: CommitmentDecision,
        packageName: String,
        screenText: String,
        surface: SurfaceDetection
    ): Boolean {
        val scopeOutcome = law.scope ?: return false
        run {
            hideWarningOverlay()
            when (val m = scopeOutcome.match) {
                is ScopeMatch.Unresolved -> {
                    hideBlockOverlay()
                    Log.d(
                        "PhoneCodexDecision",
                        EnforcementDecisionLog.format(
                            packageName = packageName,
                            surface = surface.surface.name,
                            rule = "Scope",
                            decision = "ALLOW",
                            reasonCode = EnforcementReasonCodes.SCOPE_UNRESOLVED_NO_ENFORCE,
                            reason = "Scope unresolved -- not enforcing this promise",
                            activity = surface.activity.name.lowercase(),
                            scopeKind = scopeOutcome.scopeKind.name
                        )
                    )
                    return true
                }
                is ScopeMatch.PersistenceCorrupt -> {
                    hideBlockOverlay()
                    val now = System.currentTimeMillis()
                    if (packageName != lastScopeCorruptLoggedPackage ||
                        now - lastScopeCorruptLoggedMillis >= SCOPE_CORRUPT_LOG_THROTTLE_MS
                    ) {
                        Log.w(
                            "PhoneCodexDecision",
                            EnforcementDecisionLog.format(
                                packageName = packageName,
                                surface = surface.surface.name,
                                rule = "Scope",
                                decision = "ALLOW",
                                reasonCode = EnforcementReasonCodes.SCOPE_PERSISTENCE_CORRUPT,
                                reason = "Saved commitment scope unreadable (${m.detail}) -- " +
                                    "commitment unverified, not enforcing; legacy scope not substituted",
                                activity = surface.activity.name.lowercase(),
                                scopeKind = scopeOutcome.scopeKind.name
                            )
                        )
                        lastScopeCorruptLoggedPackage = packageName
                        lastScopeCorruptLoggedMillis = now
                    }
                    return true
                }
                is ScopeMatch.Exempt -> {
                    hideBlockOverlay()
                    Log.d(
                        "PhoneCodexDecision",
                        EnforcementDecisionLog.format(
                            packageName = packageName,
                            surface = surface.surface.name,
                            rule = "Scope",
                            decision = "ALLOW",
                            reasonCode = EnforcementReasonCodes.SCOPE_EXEMPT_ALLOW,
                            reason = "In-scope exemption matched",
                            activity = surface.activity.name.lowercase(),
                            scopeKind = scopeOutcome.scopeKind.name,
                            scopeClause = m.clauseId
                        )
                    )
                    return true
                }
                is ScopeMatch.Indeterminate -> {
                    hideBlockOverlay()
                    Log.d(
                        "PhoneCodexDecision",
                        EnforcementDecisionLog.format(
                            packageName = packageName,
                            surface = surface.surface.name,
                            rule = "Scope",
                            decision = "WAIT",
                            reasonCode = EnforcementReasonCodes.SCOPE_EVIDENCE_INDETERMINATE,
                            reason = "Scope evidence uncertain (${m.reason.name.lowercase()}) -- waiting",
                            activity = surface.activity.name.lowercase(),
                            scopeKind = scopeOutcome.scopeKind.name,
                            scopeClause = m.clauseId
                        )
                    )
                    return true
                }
                ScopeMatch.OutOfScope -> {
                    if (law.stage == LawStage.SCOPE_APP_RULE) {
                        return applyAppRuleBehavior(packageName, screenText, law.effectiveAppRule)
                    }
                    hideBlockOverlay()
                    Log.d(
                        "PhoneCodexDecision",
                        EnforcementDecisionLog.format(
                            packageName = packageName,
                            surface = surface.surface.name,
                            rule = "Scope",
                            decision = "ALLOW",
                            reasonCode = EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW,
                            reason = "Outside this promise's package/content scope",
                            activity = surface.activity.name.lowercase(),
                            scopeKind = scopeOutcome.scopeKind.name
                        )
                    )
                    return true
                }
                is ScopeMatch.InScope -> return false
            }
        }
    }

    /**
     * Advisory tail after the law returned CONTINUE: feedback memory, unknown-clone WARN,
     * then the backend classifier. None of these can override a law decision.
     */
    private fun runCounselTail(packageName: String, screenText: String, storedSession: FocusSession) {
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
                    if (!isCurrentPolicyEvaluation(packageName, screenText, storedSession)) {
                        Log.d("PhoneCodexNetAI", "Discarded stale classifier result for $packageName")
                        return@post
                    }

                    lastClassifiedPackage = packageName
                    lastClassifiedTextSignature = signature
                    lastClassification = classification
                    lastClassificationTimeMillis = System.currentTimeMillis()

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

    private fun isCurrentPolicyEvaluation(
        packageName: String,
        screenText: String,
        storedSession: FocusSession
    ): Boolean {
        val currentSession = sessionStore.getStoredSession() ?: return false
        return latestPackageName == packageName &&
            latestScreenTextSignature == textSignature(screenText) &&
            currentSession.id == storedSession.id &&
            currentSession.goal == storedSession.goal &&
            currentSession.status == storedSession.status
    }

    private fun textSignature(text: String): String {
        return text
            .lowercase()
            .take(300)
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Android effects (overlays, logs, vision counsel) for one [CommitmentPolicyEvaluator]
     * decision. The law decides; this only renders. Returns false when the advisory tail
     * (feedback memory / classifier) may run.
     */
    private fun applyLawDecision(
        law: CommitmentDecision,
        packageName: String,
        screenText: String,
        storedSession: FocusSession?
    ): Boolean {
        when (law.stage) {
            LawStage.SAFE_APP -> {
                applySafeAppAllow(packageName, screenText)
                return true
            }
            LawStage.UNRELATED_PRODUCTIVITY -> {
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
                    ),
                    reasonCode = law.reasonCode
                )
                return true
            }
            else -> Unit
        }
        logScreenTextIfChanged(packageName, screenText)
        when (law.stage) {
            LawStage.SYSTEM_NOISE -> return true
            LawStage.PERMANENT_GUARDRAIL -> {
                val match = law.guardrail ?: return true
                return applyPermanentGuardrail(packageName, screenText, match)
            }
            LawStage.NO_SESSION -> {
                applyNoSessionAllow(packageName)
                return true
            }
            else -> Unit
        }
        val session = storedSession ?: return true
        lastNoSessionLoggedPackage = null
        val detection = law.detection ?: SurfaceDetector.detect(packageName, screenText)
        if (law.stage == LawStage.SESSION_LOCK_UNRELATED) {
            hideWarningOverlay()
            hideBlockOverlay()
            Log.d(
                "PhoneCodexDecision",
                EnforcementDecisionLog.format(
                    packageName = packageName,
                    surface = detection.surface.name,
                    rule = "SessionLock",
                    decision = "ALLOW",
                    reasonCode = EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED,
                    reason = SessionEnforcementCopy.SESSION_LOCK_ALLOW_UNRELATED
                )
            )
            return true
        }

        logInstallSurfaceIfPresent(packageName, screenText)
        logEvalPrelude(packageName, session.goal.orEmpty(), screenText)
        maybeResearchApp(packageName, screenText, session.goal.orEmpty())
        Log.d(
            "PhoneCodexDecision",
            "law verdict=${law.verdict} stage=${law.stage} code=${law.reasonCode} ${law.policyVersion}"
        )

        val context = EnforcementContext.fromParts(
            packageName = packageName,
            screenText = screenText,
            detection = detection,
            parsed = VideoDurationParser.parse(screenText)
        )
        return when (law.stage) {
            LawStage.SCOPE, LawStage.SCOPE_APP_RULE ->
                applyScopeDecision(law, packageName, screenText, detection)
            LawStage.STRUCTURED_SURFACE ->
                applyStructuredSurfaceBlock(packageName, screenText, detection)
            LawStage.BLANK_VIDEO_TREE -> {
                val result = law.blankTree ?: return true
                applyBlankVideoTree(packageName, screenText, session, result, detection, context)
            }
            LawStage.APP_RULE_BLOCK, LawStage.APP_RULE ->
                applyAppRuleBehavior(packageName, screenText, law.effectiveAppRule)
            LawStage.BLANK_TEXT -> {
                Log.d(
                    "PhoneCodexA11yText",
                    "Blank/stale screen text for $packageName — skip content/AI, keep last decision"
                )
                true
            }
            LawStage.SHORT_FORM_QUOTA -> {
                val quota = law.quota ?: return true
                applyShortFormQuota(packageName, screenText, quota, context)
            }
            LawStage.MEDIA_LENGTH -> {
                val media = law.media ?: return true
                applyMediaLength(packageName, screenText, law, media, detection, context)
            }
            LawStage.STRUCTURED_SURFACE_SCOPE ->
                applyStructuredSurfaceScope(packageName, law.verdict, detection)
            LawStage.ENTERTAINMENT_BAN,
            LawStage.SHORT_FORM_BAN,
            LawStage.YOUTUBE_SHORTS_SIGNAL,
            LawStage.YOUTUBE_STUDY_SIGNAL,
            LawStage.CHROME_ADULT_SIGNAL,
            LawStage.CHROME_BROAD_USE,
            LawStage.SURFACE_GATE ->
                applyContentLawDecision(packageName, screenText, law, detection, context)
            LawStage.SAFE_APP,
            LawStage.UNRELATED_PRODUCTIVITY,
            LawStage.SYSTEM_NOISE,
            LawStage.PERMANENT_GUARDRAIL,
            LawStage.NO_SESSION,
            LawStage.SESSION_LOCK_UNRELATED -> true
        }
    }

    private fun applySafeAppAllow(packageName: String, screenText: String) {
        hideWarningOverlay()
        // No active overlay target here — normal PhoneCodex / safe-app open.
        val now = System.currentTimeMillis()
        val shouldLogSafeApp = packageName != lastSafeAppLoggedPackage ||
            now - lastSafeAppLoggedTimeMillis >= SAFE_APP_LOG_THROTTLE_MS
        if (!shouldLogSafeApp) return
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
            "Safe app allowed: $packageName code=${EnforcementReasonCodes.EMERGENCY_ALLOW}"
        )
        lastSafeAppLoggedPackage = packageName
        lastSafeAppLoggedTimeMillis = now
    }

    private fun applyNoSessionAllow(packageName: String) {
        hideWarningOverlay()
        hideBlockOverlay()
        if (appRulesStore.hasCommitmentLedger()) {
            appRulesStore.restoreStaleCommitmentRules(hasActiveSession = false)
                .eventMessage?.let(eventLogStore::addEvent)
        }
        if (packageName == lastNoSessionLoggedPackage) return
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

    private fun applyStructuredSurfaceBlock(
        packageName: String,
        screenText: String,
        detection: SurfaceDetection
    ): Boolean {
        return applySignalDecision(
            packageName = packageName,
            screenText = screenText,
            decision = DecisionType.BLOCK,
            reason = "This part of the app is blocked by your promise",
            source = "Surface Rule",
            matchedSignals = listOf("structured_surface_block") + detection.evidence,
            block = true,
            reasonCode = EnforcementReasonCodes.STRUCTURED_SURFACE_BLOCK,
            surface = detection.surface,
            context = EnforcementContext.fromParts(
                packageName = packageName,
                screenText = screenText,
                detection = detection,
                parsed = VideoDurationParser.parse(screenText)
            )
        )
    }

    private fun applyStructuredSurfaceScope(
        packageName: String,
        verdict: LawVerdict,
        detection: SurfaceDetection
    ): Boolean {
        return when (verdict) {
            LawVerdict.ALLOW -> {
                hideWarningOverlay()
                hideBlockOverlay()
                Log.d(
                    "PhoneCodexDecision",
                    EnforcementDecisionLog.format(
                        packageName = packageName,
                        surface = detection.surface.name,
                        rule = "Structured Surface Scope",
                        decision = "ALLOW",
                        reasonCode = EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW,
                        reason = "Current screen is outside the promise's blocked in-app surface",
                        activity = detection.activity.name.lowercase()
                    )
                )
                true
            }
            LawVerdict.WAIT -> {
                hideWarningOverlay()
                hideBlockOverlay()
                Log.d(
                    "PhoneCodexDecision",
                    EnforcementDecisionLog.format(
                        packageName = packageName,
                        surface = detection.surface.name,
                        rule = "Structured Surface Scope",
                        decision = "WAIT",
                        reasonCode = EnforcementReasonCodes.SCOPE_EVIDENCE_INDETERMINATE,
                        reason = "Cannot identify whether this is the blocked in-app surface; waiting for clearer evidence",
                        activity = detection.activity.name.lowercase()
                    )
                )
                true
            }
            else -> false
        }
    }

    private fun applyBlankVideoTree(
        packageName: String,
        screenText: String,
        storedSession: FocusSession,
        result: BlankVideoTreeResult,
        surface: SurfaceDetection,
        enforcementContext: EnforcementContext
    ): Boolean {
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

    private fun applyPermanentGuardrail(
        packageName: String,
        screenText: String,
        match: GuardrailMatch
    ): Boolean {
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

    private fun applyAppRuleBehavior(
        packageName: String,
        screenText: String,
        behaviorOverride: AppRuleBehavior? = null
    ): Boolean {
        val rule = appRulesStore.getRuleForPackage(packageName)
        val behavior = behaviorOverride ?: rule?.behavior ?: return false
        val label = rule?.label ?: SafeAppsCatalog.defaultLabel(packageName)

        return when (behavior) {
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
                        reason = "Allowed by app rule for $label",
                        source = "App Rule",
                        confidence = 1.0
                    )
                    Log.d(
                        "PhoneCodexDecision",
                        "App rule ALLOW for $packageName ($label)"
                    )
                    true
                }
            }
            AppRuleBehavior.WARN -> {
                hideBlockOverlay()
                val signature = textSignature(screenText)
                val reason = "App rule warns for $label"
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
                    "App rule WARN for $packageName ($label)"
                )
                true
            }
            AppRuleBehavior.BLOCK -> {
                hideWarningOverlay()
                val reason = "Blocked by app rule for $label"
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
                    "App rule BLOCK for $packageName ($label)"
                )
                true
            }
            AppRuleBehavior.AI_DECIDE -> false
        }
    }

    private fun applyContentLawDecision(
        packageName: String,
        screenText: String,
        law: CommitmentDecision,
        surfaceDetection: SurfaceDetection,
        enforcementContext: EnforcementContext
    ): Boolean {
        val surface = surfaceDetection.surface
        val signals = law.signals ?: contentSignalDetector.detect(packageName, screenText)
        val block = law.verdict == LawVerdict.BLOCK
        val decision = if (block) DecisionType.BLOCK else DecisionType.ALLOW
        return when (law.stage) {
            LawStage.ENTERTAINMENT_BAN -> applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = decision,
                reason = if (block) {
                    SessionEnforcementCopy.MONK_ENTERTAINMENT_BLOCK
                } else {
                    "Active study lecture allowed during monk / study-only"
                },
                source = "Entertainment Ban",
                matchedSignals = if (block) {
                    listOf("monk_entertainment_ban") + surfaceDetection.evidence
                } else {
                    signals.matchedSignals
                },
                block = block,
                reasonCode = law.reasonCode,
                surface = surface,
                context = enforcementContext
            )
            LawStage.SHORT_FORM_BAN, LawStage.YOUTUBE_SHORTS_SIGNAL -> applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.BLOCK,
                reason = "Short-form video conflicts with this promise",
                source = if (law.stage == LawStage.SHORT_FORM_BAN) "Short-form Rule" else "Content Signal",
                matchedSignals = signals.matchedSignals + surfaceDetection.evidence,
                block = true,
                reasonCode = law.reasonCode,
                surface = surface,
                context = enforcementContext
            )
            LawStage.YOUTUBE_STUDY_SIGNAL -> applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.ALLOW,
                reason = "Study-like YouTube content detected",
                source = "Content Signal",
                matchedSignals = signals.matchedSignals,
                block = false,
                reasonCode = law.reasonCode,
                surface = surface,
                context = enforcementContext
            )
            LawStage.CHROME_ADULT_SIGNAL -> applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.BLOCK,
                reason = "Adult or porn content detected in Chrome during Study World",
                source = "Content Signal",
                matchedSignals = signals.matchedSignals,
                block = true,
                reasonCode = law.reasonCode,
                surface = surface,
                context = enforcementContext
            )
            LawStage.CHROME_BROAD_USE -> applySignalDecision(
                packageName = packageName,
                screenText = screenText,
                decision = DecisionType.ALLOW,
                reason = "Chrome use is allowed by this promise",
                source = "Content Signal",
                matchedSignals = signals.matchedSignals,
                block = false,
                reasonCode = law.reasonCode,
                surface = surface,
                context = enforcementContext
            )
            LawStage.SURFACE_GATE -> {
                val gate = law.surfaceGate ?: return true
                applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.ALLOW,
                    reason = gate.reason,
                    source = "Surface Gate",
                    matchedSignals = listOf("surface_gate_pass") + gate.detection.evidence,
                    block = false,
                    reasonCode = law.reasonCode,
                    surface = gate.detection.surface,
                    context = enforcementContext
                )
            }
            else -> false
        }
    }

    private fun applyMediaLength(
        packageName: String,
        screenText: String,
        law: CommitmentDecision,
        mediaLength: MediaLengthDecisionDetail,
        surfaceDetection: SurfaceDetection,
        enforcementContext: EnforcementContext
    ): Boolean {
        val surface = surfaceDetection.surface
        when (mediaLength.decision) {
            MediaLengthLocalDecision.BLOCK -> {
                val blockCode = mediaLength.blockReasonCode
                    ?: EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.BLOCK,
                    reason = if (mediaLength.blockBasis == MediaLengthBlockBasis.SHORT_FORM_SURFACE_UNDER_MIN) {
                        "Short-form video conflicts with this promise"
                    } else {
                        "Video length conflicts with the promise"
                    },
                    source = "Duration Rule",
                    matchedSignals = mediaLength.blockEvidence(
                        enforcementContext.durationSource.name.lowercase()
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
                val waitCode = law.reasonCode
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
                    matchedSignals = mediaLength.allowEvidence(
                        enforcementContext.durationSource.name.lowercase()
                    ) + surfaceDetection.evidence,
                    block = false,
                    reasonCode = EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT,
                    surface = surface,
                    context = enforcementContext
                )
            }
            MediaLengthLocalDecision.NONE -> return false
        }
    }

    /**
     * Deterministic short-form quota. Returns true when a hard decision was applied
     * (AI must not run afterward for that event).
     */
    private fun applyShortFormQuota(
        packageName: String,
        screenText: String,
        decision: ShortFormQuotaDecision,
        context: EnforcementContext? = null
    ): Boolean {
        Log.d("PhoneCodexDecision", ShortFormQuotaGate.formatLog(decision, packageName))

        when (decision.action) {
            ShortFormQuotaAction.ALLOW_AND_COUNT,
            ShortFormQuotaAction.ALLOW_DUPLICATE -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.WARN,
                    reason = decision.count?.let { SessionEnforcementCopy.shortsRemaining(it, decision.limit) }
                        ?: decision.reason,
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
            ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE -> {
                return applySignalDecision(
                    packageName = packageName,
                    screenText = screenText,
                    decision = DecisionType.BLOCK,
                    reason = SessionEnforcementCopy.QUOTA_STATE_UNREADABLE_BLOCK,
                    source = "ShortQuota",
                    matchedSignals = listOf("short_form_quota_unreadable"),
                    block = true,
                    reasonCode = EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE,
                    surface = decision.surface,
                    count = null,
                    limit = decision.limit,
                    context = context
                )
            }
            ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY,
            ShortFormQuotaAction.NONE -> return false
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
        if (!isCurrentPolicyEvaluation(packageName, screenText, storedSession)) {
            Log.d("PhoneCodexNetAI", "Discarded stale classifier decision for $packageName")
            return
        }

        val settings = studyWorldSettingsStore.getSettings()
        val structuredDetection = SurfaceDetector.detect(packageName, screenText)
        if (StructuredSurfaceGate.blocks(packageName, settings, structuredDetection)) {
            applyStructuredSurfaceBlock(packageName, screenText, structuredDetection)
            return
        }
        when (StructuredSurfaceGate.evaluate(packageName, settings, structuredDetection)) {
            StructuredSurfaceAction.ALLOW_OUTSIDE_SCOPE -> {
                applyStructuredSurfaceScope(packageName, LawVerdict.ALLOW, structuredDetection)
                return
            }
            StructuredSurfaceAction.WAIT_FOR_EVIDENCE -> {
                applyStructuredSurfaceScope(packageName, LawVerdict.WAIT, structuredDetection)
                return
            }
            StructuredSurfaceAction.BLOCK, StructuredSurfaceAction.NONE -> Unit
        }

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
            strictness = session?.confirmedStrictness,
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
        blockOverlayScreenText = screenText
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
        val reevaluationBlock = System.currentTimeMillis() < suppressAttemptCountUntilMillis
        suppressAttemptCountUntilMillis = 0L
        val shouldCountAttempt =
            !reevaluationBlock && shouldCountBlockedAttempt(packageName, screenText)
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
    private fun handleActiveOverlayForeground(packageName: String, screenText: String): Boolean {
        val blockTarget = activeOverlayTargetPackage
        if (currentOverlay != null && blockTarget != null) {
            return handleActiveBlockOverlayForeground(packageName, blockTarget, screenText)
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
        target: String,
        screenText: String
    ): Boolean {
        val now = System.currentTimeMillis()
        val watch = blockOverlayWatch
            ?: BlockOverlayWatch(target, null, now).also { blockOverlayWatch = it }
        val root = safeRootInActiveWindow()
        val rootPackage = root?.packageName?.toString()
        val effective = rootPackage?.takeIf { it.isNotBlank() } ?: packageName
        val freshText = if (effective == packageName) screenText else ""
        val page = if (effective == target) currentPageIdentity(root, effective, freshText) else null
        val verdict = watch.observe(packageName, rootPackage, page, now)
        return applyOverlayWatchVerdict(
            verdict = verdict,
            source = "event",
            canContinueInline = effective == packageName
        )
    }

    private fun tickBlockOverlayWatchdog() {
        val watch = blockOverlayWatch ?: return
        val root = safeRootInActiveWindow() ?: return
        val rootPackage = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val page = if (rootPackage == watch.targetPackage) {
            currentPageIdentity(root, rootPackage, "")
        } else {
            null
        }
        val verdict = watch.observe(rootPackage, rootPackage, page, System.currentTimeMillis())
        applyOverlayWatchVerdict(verdict = verdict, source = "watchdog", canContinueInline = false)
    }

    /**
     * @param canContinueInline true when the caller already holds fresh screen text for the
     * effective package and will keep evaluating if this returns false.
     * @return true if the event is consumed.
     */
    private fun applyOverlayWatchVerdict(
        verdict: OverlayWatchVerdict,
        source: String,
        canContinueInline: Boolean
    ): Boolean {
        logOverlayLifecycle(verdict, source)
        return when (verdict.action) {
            OverlayWatchAction.RETAIN, OverlayWatchAction.AWAY_PENDING -> true
            OverlayWatchAction.CLEAR_LEFT_TARGET -> {
                val now = verdict.effectivePackage
                if (safeAppsStore.isSafePackage(now)) {
                    hideBlockOverlayBecauseAllowed(now)
                } else {
                    hideBlockOverlay()
                    Log.d(
                        "PhoneCodexOverlay",
                        "Overlay hidden because target left foreground, now=$now"
                    )
                }
                if (canContinueInline) {
                    false
                } else {
                    mainHandler.post { inspectAndEvaluate(now) }
                    true
                }
            }
            OverlayWatchAction.REEVALUATE_PAGE_CHANGED -> {
                hideBlockOverlay()
                suppressAttemptCountUntilMillis =
                    System.currentTimeMillis() + REEVALUATION_ATTEMPT_SUPPRESS_MS
                if (canContinueInline) {
                    false
                } else {
                    mainHandler.post { inspectAndEvaluate(verdict.targetPackage) }
                    true
                }
            }
        }
    }

    private fun logOverlayLifecycle(verdict: OverlayWatchVerdict, source: String) {
        val line = verdict.logLine(source)
        if (verdict.action == OverlayWatchAction.RETAIN) {
            val key = "${verdict.reason}|${verdict.effectivePackage}|${verdict.currentPage}"
            val now = System.currentTimeMillis()
            if (
                key == lastOverlayLifecycleLogKey &&
                now - lastOverlayLifecycleLogAtMillis < OVERLAY_RETAIN_LOG_THROTTLE_MS
            ) {
                return
            }
            lastOverlayLifecycleLogKey = key
            lastOverlayLifecycleLogAtMillis = now
            Log.d("PhoneCodexOverlay", line)
            return
        }
        lastOverlayLifecycleLogKey = null
        if (verdict.action == OverlayWatchAction.AWAY_PENDING) {
            Log.d("PhoneCodexOverlay", line)
        } else {
            Log.i("PhoneCodexOverlay", line)
        }
    }

    private fun safeRootInActiveWindow(): AccessibilityNodeInfo? =
        try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        }

    private fun currentPageIdentity(
        root: AccessibilityNodeInfo?,
        packageName: String,
        freshScreenText: String
    ): String? {
        val urlBar = root?.let { readBrowserUrlBar(it, packageName) }
        return OverlayLifecycleGate.pageIdentity(urlBar, freshScreenText)
    }

    private fun readBrowserUrlBar(root: AccessibilityNodeInfo, packageName: String): String? {
        val viewId = BROWSER_URL_BAR_IDS[packageName] ?: return null
        return try {
            root.findAccessibilityNodeInfosByViewId(viewId)
                .firstNotNullOfOrNull { node ->
                    node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun isSystemUiPackage(packageName: String): Boolean {
        return OverlayLifecycleGate.isSystemUi(packageName)
    }

    private fun pictureInPicturePackages(): Set<String> =
        try {
            windows.filter { it.isInPictureInPictureMode }
                .mapNotNull { it.root?.packageName?.toString() }
                .toSet()
        } catch (_: RuntimeException) {
            emptySet()
        }

    /** Keeps a PiP window paused while it continues a block; rechecks so play inside PiP is paused again. */
    private fun enforcePictureInPictureGuard() {
        mainHandler.removeCallbacks(pipGuardRunnable)
        val pipPackages = pictureInPicturePackages()
        if (pipPackages.isEmpty()) return
        val session = sessionStore.getStoredSession()
        val settings = studyWorldSettingsStore.getSettings()
        val now = System.currentTimeMillis()
        val guarded = pipPackages.firstOrNull { pkg ->
            PictureInPictureEscapeLaw.mustPause(
                pipPackage = pkg,
                sessionStatus = session?.status,
                isSafePackage = safeAppsStore.isSafePackage(pkg),
                goal = session?.goal.orEmpty(),
                enforcementScopePackages = settings.enforcementScopePackages,
                contentBrands = settings.enforcementContentBrands,
                recentBlock = pipRecentBlock,
                nowMillis = now
            )
        } ?: return
        if (pipRecentBlock?.packageName == guarded) pipRecentBlock = pipRecentBlock?.copy(atMillis = now)
        pauseMediaPlayback("pip_guard pkg=$guarded")
        mainHandler.postDelayed(pipGuardRunnable, PIP_GUARD_RECHECK_MS)
    }

    private fun pauseMediaPlayback(reason: String) {
        val audio = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        if (!audio.isMusicActive) return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        Log.d("PhoneCodexDecision", "Media paused reason=$reason")
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
        val structuredSurfaceBlock =
            model.reasonCode == EnforcementReasonCodes.STRUCTURED_SURFACE_BLOCK
        val layout = CommitmentOverlayBuilder(this).build(
            model = model,
            actions = CommitmentOverlayActions(
                onBackToSafe = {
                    hideBlockOverlay()
                    performGlobalAction(
                        if (structuredSurfaceBlock) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME
                    )
                },
                onGoBack = if (structuredSurfaceBlock) null else {
                    {
                        hideBlockOverlay()
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
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
            pipRecentBlock = PictureInPictureEscapeLaw.RecentBlock(packageName, System.currentTimeMillis())
            // Home would otherwise shrink a still-playing video into a PiP window.
            if (VideoPlatformRegistry.isVideoOrStreamingPackage(packageName)) {
                pauseMediaPlayback("block_overlay target=$packageName")
            }
            val root = safeRootInActiveWindow()
            val rootPackage = root?.packageName?.toString()
            val blockedPage = currentPageIdentity(
                root = root.takeIf { rootPackage == packageName },
                packageName = packageName,
                freshScreenText = blockOverlayScreenText.orEmpty()
            )
            blockOverlayWatch = BlockOverlayWatch(
                targetPackage = packageName,
                blockedPage = blockedPage,
                shownAtMillis = System.currentTimeMillis()
            )
            lastOverlayLifecycleLogKey = null
            mainHandler.removeCallbacks(blockOverlayWatchdog)
            mainHandler.postDelayed(blockOverlayWatchdog, BLOCK_OVERLAY_WATCH_INTERVAL_MS)
            runtimeDiagStore.setOverlayActive(true)
            Log.d(
                "PhoneCodexOverlay",
                "Overlay shown kind=${model.kind} target=$packageName " +
                    "root=${rootPackage ?: "none"} blockedPage=${blockedPage ?: "unknown"}"
            )
        } catch (err: RuntimeException) {
            Log.e("PhoneCodexOverlay", "Failed to show block overlay", err)
            currentOverlay = null
            activeOverlayTargetPackage = null
            blockOverlayScreenText = null
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
        blockOverlayScreenText = null
        blockOverlayWatch = null
        mainHandler.removeCallbacks(blockOverlayWatchdog)
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
        // Chrome puts the URL bar last in DFS order, so long watch pages lose the
        // watch URL to the text cap and read like a comments page.
        readBrowserUrlBar(root, packageName)?.let { appendText(builder, it) }
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
        private const val SCOPE_CORRUPT_LOG_THROTTLE_MS = 60_000L
        private const val FOREGROUND_LOG_THROTTLE_MS = 3_000L
        private const val BLOCK_OVERLAY_WATCH_INTERVAL_MS = 750L
        private const val PIP_GUARD_RECHECK_MS = 2_000L
        private const val OVERLAY_RETAIN_LOG_THROTTLE_MS = 5_000L
        private const val REEVALUATION_ATTEMPT_SUPPRESS_MS = 3_000L
        private val BROWSER_URL_BAR_IDS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar"
        )
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
