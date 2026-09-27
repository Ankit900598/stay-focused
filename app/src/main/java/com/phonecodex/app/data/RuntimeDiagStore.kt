package com.phonecodex.app.data

import android.content.Context
import android.os.SystemClock
import com.phonecodex.app.domain.protection.AccessibilityHeartbeatLaw

data class AccessibilityHeartbeat(
    val lastEventMillis: Long = 0L,
    val packageName: String = "",
    val reason: String = "",
    val serviceAlive: Boolean = false
)

/**
 * Cross-process diagnostics written by the accessibility service and read by the UI.
 * Liveness is a short monotonic lease, not the persisted boolean by itself.
 */
class RuntimeDiagStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun setAccessibilityServiceAlive(alive: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_A11Y_ALIVE, alive)
        if (alive) {
            editor.putLong(KEY_A11Y_LEASE_ELAPSED_MS, SystemClock.elapsedRealtime())
        } else {
            editor.remove(KEY_A11Y_LEASE_ELAPSED_MS)
        }
        editor.apply()
    }

    fun isAccessibilityServiceAlive(): Boolean =
        getHeartbeat().serviceAlive

    fun recordServiceHeartbeat(nowElapsedMillis: Long = SystemClock.elapsedRealtime()) {
        if (!prefs.getBoolean(KEY_A11Y_ALIVE, false)) return
        val lastLeaseElapsedMillis = prefs.getLong(KEY_A11Y_LEASE_ELAPSED_MS, 0L)
        if (
            !AccessibilityHeartbeatLaw.shouldRefreshServiceLease(
                nowElapsedMillis = nowElapsedMillis,
                lastLeaseElapsedMillis = lastLeaseElapsedMillis
            )
        ) {
            return
        }
        prefs.edit()
            .putLong(KEY_A11Y_LEASE_ELAPSED_MS, nowElapsedMillis)
            .apply()
    }

    fun recordHeartbeat(
        nowMillis: Long,
        packageName: String?,
        reason: String
    ) {
        recordServiceHeartbeat()
        val pkg = packageName?.trim().orEmpty()
        val last = getHeartbeat()
        if (
            !AccessibilityHeartbeatLaw.shouldWrite(
                nowMillis = nowMillis,
                lastWriteMillis = last.lastEventMillis,
                packageName = pkg,
                lastPackageName = last.packageName,
                reason = reason,
                lastReason = last.reason
            )
        ) {
            return
        }
        prefs.edit()
            .putBoolean(KEY_A11Y_ALIVE, true)
            .putLong(KEY_HEARTBEAT_MS, nowMillis)
            .putString(KEY_HEARTBEAT_PKG, pkg)
            .putString(KEY_HEARTBEAT_REASON, reason)
            .apply()
    }

    fun getHeartbeat(): AccessibilityHeartbeat {
        val serviceAlive = prefs.getBoolean(KEY_A11Y_ALIVE, false) &&
            AccessibilityHeartbeatLaw.isServiceLeaseFresh(
                nowElapsedMillis = SystemClock.elapsedRealtime(),
                lastLeaseElapsedMillis = prefs.getLong(KEY_A11Y_LEASE_ELAPSED_MS, 0L)
            )
        return AccessibilityHeartbeat(
            lastEventMillis = prefs.getLong(KEY_HEARTBEAT_MS, 0L),
            packageName = prefs.getString(KEY_HEARTBEAT_PKG, "") ?: "",
            reason = prefs.getString(KEY_HEARTBEAT_REASON, "") ?: "",
            serviceAlive = serviceAlive
        )
    }

    fun setOverlayActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_ACTIVE, active).apply()
    }

    fun isOverlayActive(): Boolean =
        prefs.getBoolean(KEY_OVERLAY_ACTIVE, false)

    companion object {
        private const val PREFS_NAME = "phonecodex_runtime_diag"
        private const val KEY_A11Y_ALIVE = "accessibilityServiceAlive"
        private const val KEY_A11Y_LEASE_ELAPSED_MS = "accessibilityServiceLeaseElapsedMillis"
        private const val KEY_OVERLAY_ACTIVE = "overlayActive"
        private const val KEY_HEARTBEAT_MS = "accessibilityLastEventMillis"
        private const val KEY_HEARTBEAT_PKG = "accessibilityLastEventPackage"
        private const val KEY_HEARTBEAT_REASON = "accessibilityLastEventReason"
    }
}
