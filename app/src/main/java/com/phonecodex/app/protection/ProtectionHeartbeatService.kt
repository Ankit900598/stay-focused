package com.phonecodex.app.protection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.phonecodex.app.R
import com.phonecodex.app.accessibility.AccessibilityHealthChecker
import com.phonecodex.app.data.AppRulesStore
import com.phonecodex.app.data.CommitmentTeardown
import com.phonecodex.app.data.EventLogStore
import com.phonecodex.app.data.ProtectionViolationStore
import com.phonecodex.app.data.SessionStore
import com.phonecodex.app.data.StudyWorldSettingsStore
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.protection.ProtectionHeartbeatLogic
import com.phonecodex.app.domain.session.SessionExpiryPolicy

class ProtectionHeartbeatService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var sessionStore: SessionStore
    private lateinit var protectionViolationStore: ProtectionViolationStore
    private lateinit var eventLogStore: EventLogStore
    private lateinit var studyWorldSettingsStore: StudyWorldSettingsStore

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!runHeartbeatTick()) {
                stopSelf()
                return
            }
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        protectionViolationStore = ProtectionViolationStore(this)
        eventLogStore = EventLogStore(this)
        studyWorldSettingsStore = StudyWorldSettingsStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = sessionStore.getStoredSession()
        if (isSessionExpired(session)) {
            clearExpiredSession(session)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ProtectionHeartbeatLogic.shouldKeepRunning(session)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.post(heartbeatRunnable)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runHeartbeatTick(): Boolean {
        val session = sessionStore.getStoredSession()
        if (isSessionExpired(session)) {
            clearExpiredSession(session)
            return false
        }
        if (!ProtectionHeartbeatLogic.shouldKeepRunning(session)) {
            return false
        }

        val accessibilityEnabled =
            AccessibilityHealthChecker.isPhoneCodexAccessibilityEnabled(this)
        val continuousOff = protectionViolationStore.markAccessibilityObserved(
            accessibilityEnabled = accessibilityEnabled
        )
        if (
            ProtectionHeartbeatLogic.shouldRecordAccessibilityViolation(
                session = session,
                accessibilityEnabled = accessibilityEnabled,
                disabledContinuouslyMillis = continuousOff
            )
        ) {
            protectionViolationStore.recordViolation(
                ProtectionViolationStore.REASON_ACCESSIBILITY_DISABLED_DURING_COMMITMENT
            )
        }
        return true
    }

    private fun isSessionExpired(session: FocusSession?): Boolean {
        return session != null &&
            SessionExpiryPolicy.isExpired(
                session,
                System.currentTimeMillis(),
                studyWorldSettingsStore.getSettings().shortFormDailyQuotaLimit
            )
    }

    private fun clearExpiredSession(session: FocusSession?) {
        CommitmentTeardown(sessionStore, AppRulesStore(this), eventLogStore::addEvent)
            .end(CommitmentTeardown.Kind.EXPIRED, expectedSessionId = session?.id)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.protection_heartbeat_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.protection_heartbeat_notification_title))
            .setContentText(getString(R.string.protection_heartbeat_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "phonecodex_protection_heartbeat"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
    }
}
