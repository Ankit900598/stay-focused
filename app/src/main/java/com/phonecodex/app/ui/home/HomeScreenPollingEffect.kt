package com.phonecodex.app.ui.home

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.phonecodex.app.accessibility.AccessibilityHealthChecker
import com.phonecodex.app.data.CommitmentTeardown
import com.phonecodex.app.data.ProtectionViolationStore
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.protection.ProtectionHeartbeatLogic
import com.phonecodex.app.domain.session.SessionExpiryPolicy
import com.phonecodex.app.protection.ProtectionHeartbeatController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Core home polling: accessibility + session only.
 * Debug / events poll is a separate effect gated on inspector visibility.
 */
@Composable
internal fun HomeScreenPollingEffect(state: HomeScreenState) {
    LaunchedEffect(Unit) {
        ProtectionHeartbeatController.ensureRunningIfNeeded(state.context)
    }

    LaunchedEffect(state.showAdvancedControls, state.showAdvancedEngineering) {
        if (state.showAdvancedControls && state.showAdvancedEngineering) {
            state.loadAdvancedSnapshotIfNeeded()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val started = SystemClock.elapsedRealtime()
            pollCoreHomeState(state)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed > 50L) {
                Log.d("PhoneCodexUIPerf", "corePoll=${elapsed}ms")
            }
            delay(if (state.hasStoredSession) CORE_POLL_ACTIVE_MS else CORE_POLL_IDLE_MS)
        }
    }
}

/**
 * Debug + event prefs poll. Runs only while the inspector region is on-screen.
 */
@Composable
internal fun HomeAdvancedInspectorPollingEffect(
    state: HomeScreenState,
    inspectorVisible: () -> Boolean
) {
    LaunchedEffect(state.showAdvancedControls, state.showAdvancedEngineering) {
        if (!state.showAdvancedControls || !state.showAdvancedEngineering) return@LaunchedEffect
        while (true) {
            if (!inspectorVisible()) {
                delay(ADVANCED_POLL_IDLE_CHECK_MS)
                continue
            }
            val started = SystemClock.elapsedRealtime()
            pollInspectorHomeState(state)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed > 40L) {
                Log.d("PhoneCodexUIPerf", "debugPoll=${elapsed}ms")
            }
            delay(ADVANCED_POLL_MS)
        }
    }
}

private suspend fun pollCoreHomeState(state: HomeScreenState) {
    val coreSnapshot = withContext(Dispatchers.IO) {
        Pair(
            AccessibilityHealthChecker.isPhoneCodexAccessibilityEnabled(state.context),
            state.runtimeDiagStore.getHeartbeat()
        )
    }
    val accessibilityEnabled = coreSnapshot.first
    val heartbeat = coreSnapshot.second
    if (accessibilityEnabled != state.isAccessibilityEnabled) {
        state.isAccessibilityEnabled = accessibilityEnabled
    }
    if (heartbeat.serviceAlive != state.isAccessibilityAlive) {
        state.isAccessibilityAlive = heartbeat.serviceAlive
    }
    if (heartbeat.lastEventMillis != state.lastHeartbeatMillis) {
        state.lastHeartbeatMillis = heartbeat.lastEventMillis
        state.lastHeartbeatPackage = heartbeat.packageName
        state.lastHeartbeatReason = heartbeat.reason
    }

    val nowMillis = System.currentTimeMillis()
    if (state.nowMillis != nowMillis) {
        state.nowMillis = nowMillis
    }
    val currentSession = withContext(Dispatchers.IO) {
        state.sessionStore.getStoredSession()
    }

    if (
        currentSession != null &&
        SessionExpiryPolicy.isExpired(
            currentSession,
            nowMillis,
            state.studyWorldSettingsStore.getSettings().shortFormDailyQuotaLimit
        )
    ) {
        withContext(Dispatchers.IO) {
            CommitmentTeardown(state.sessionStore, state.appRulesStore, state.eventLogStore::addEvent)
                .end(CommitmentTeardown.Kind.EXPIRED, expectedSessionId = currentSession.id)
        }
        state.appRules = withContext(Dispatchers.IO) { state.appRulesStore.getRules() }
        ProtectionHeartbeatController.stop(state.context)
        if (state.showAdvancedControls) {
            state.recentEvents = withContext(Dispatchers.IO) {
                state.eventLogStore.getRecentEvents()
            }
        }
        if (state.session != null) {
            state.session = null
        }
        return
    }

    if (currentSession != state.session) {
        state.session = currentSession
    }

    val hasActiveOrLockedSession = currentSession?.let { stored ->
        stored.status == SessionStatus.ACTIVE || stored.status == SessionStatus.LOCKED
    } == true

    if (hasActiveOrLockedSession && state.nowMillis != nowMillis) {
        state.nowMillis = nowMillis
    }

    if (hasActiveOrLockedSession) {
        val continuousOff = withContext(Dispatchers.IO) {
            state.protectionViolationStore.markAccessibilityObserved(
                accessibilityEnabled = accessibilityEnabled,
                nowMillis = nowMillis
            )
        }
        if (
            ProtectionHeartbeatLogic.shouldRecordAccessibilityViolation(
                session = currentSession,
                accessibilityEnabled = accessibilityEnabled,
                disabledContinuouslyMillis = continuousOff
            )
        ) {
            withContext(Dispatchers.IO) {
                state.protectionViolationStore.recordViolation(
                    ProtectionViolationStore.REASON_ACCESSIBILITY_DISABLED_DURING_COMMITMENT
                )
            }
        }
        if (state.showAdvancedControls) {
            val violationState = withContext(Dispatchers.IO) {
                state.protectionViolationStore.getState()
            }
            if (violationState != state.protectionViolationState) {
                state.protectionViolationState = violationState
            }
        }
    }

    if (hasActiveOrLockedSession || state.showAdvancedControls) {
        val debugState = withContext(Dispatchers.IO) {
            state.debugStateStore.getDebugState()
        }
        if (debugState != state.debugState) {
            state.debugState = debugState
        }
    }
}

private suspend fun pollInspectorHomeState(state: HomeScreenState) {
    val debugState = withContext(Dispatchers.IO) {
        state.debugStateStore.getDebugState()
    }
    if (debugState != state.debugState) {
        state.debugState = debugState
    }

    val recentEvents = withContext(Dispatchers.IO) {
        state.eventLogStore.getRecentEvents()
    }
    if (recentEvents != state.recentEvents) {
        state.recentEvents = recentEvents
    }

    val violationState = withContext(Dispatchers.IO) {
        state.protectionViolationStore.getState()
    }
    if (violationState != state.protectionViolationState) {
        state.protectionViolationState = violationState
    }

    val accessibilityAlive = withContext(Dispatchers.IO) {
        state.runtimeDiagStore.isAccessibilityServiceAlive()
    }
    if (accessibilityAlive != state.isAccessibilityAlive) {
        state.isAccessibilityAlive = accessibilityAlive
    }

    val overlayActive = withContext(Dispatchers.IO) {
        state.runtimeDiagStore.isOverlayActive()
    }
    if (overlayActive != state.overlayActive) {
        state.overlayActive = overlayActive
    }
}

private const val CORE_POLL_IDLE_MS = 3_000L
private const val CORE_POLL_ACTIVE_MS = 1_000L
private const val ADVANCED_POLL_MS = 2_500L
private const val ADVANCED_POLL_IDLE_CHECK_MS = 500L
