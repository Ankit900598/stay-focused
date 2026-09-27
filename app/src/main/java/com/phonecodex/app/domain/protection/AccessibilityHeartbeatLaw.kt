package com.phonecodex.app.domain.protection

/**
 * When the AccessibilityService may persist a heartbeat, and how Home reads age.
 * Prefs writes stay off the hot path — throttle plus package/reason change.
 */
object AccessibilityHeartbeatLaw {

    const val WRITE_MIN_INTERVAL_MS = 2_000L
    const val FRESH_EVENT_MAX_AGE_MS = 45_000L
    const val SERVICE_LEASE_REFRESH_INTERVAL_MS = 10_000L
    const val SERVICE_LEASE_MAX_AGE_MS = 30_000L

    const val REASON_SERVICE_CONNECTED = "service_connected"
    const val REASON_WINDOW_CHANGED = "window_changed"
    const val REASON_CONTENT_CHANGED = "content_changed"

    fun shouldWrite(
        nowMillis: Long,
        lastWriteMillis: Long,
        packageName: String,
        lastPackageName: String,
        reason: String,
        lastReason: String,
        minIntervalMs: Long = WRITE_MIN_INTERVAL_MS
    ): Boolean {
        if (lastWriteMillis <= 0L) return true
        if (packageName != lastPackageName) return true
        if (reason != lastReason) return true
        return nowMillis - lastWriteMillis >= minIntervalMs
    }

    fun ageMs(nowMillis: Long, lastEventMillis: Long): Long? {
        if (lastEventMillis <= 0L) return null
        return (nowMillis - lastEventMillis).coerceAtLeast(0L)
    }

    fun isFresh(ageMs: Long?, maxAgeMs: Long = FRESH_EVENT_MAX_AGE_MS): Boolean =
        ageMs != null && ageMs <= maxAgeMs

    fun shouldRefreshServiceLease(
        nowElapsedMillis: Long,
        lastLeaseElapsedMillis: Long,
        minIntervalMs: Long = SERVICE_LEASE_REFRESH_INTERVAL_MS
    ): Boolean {
        if (lastLeaseElapsedMillis <= 0L || nowElapsedMillis < lastLeaseElapsedMillis) return true
        return nowElapsedMillis - lastLeaseElapsedMillis >= minIntervalMs
    }

    fun isServiceLeaseFresh(
        nowElapsedMillis: Long,
        lastLeaseElapsedMillis: Long,
        maxAgeMs: Long = SERVICE_LEASE_MAX_AGE_MS
    ): Boolean =
        lastLeaseElapsedMillis > 0L &&
            lastLeaseElapsedMillis <= nowElapsedMillis &&
            nowElapsedMillis - lastLeaseElapsedMillis <= maxAgeMs

    fun formatAge(ageMs: Long?): String {
        if (ageMs == null) return "no phone event yet"
        val seconds = ageMs / 1_000L
        return when {
            seconds < 1L -> "last phone event just now"
            seconds < 60L -> "last phone event ${seconds}s ago"
            else -> {
                val minutes = seconds / 60L
                if (minutes < 60L) {
                    "last phone event ${minutes}m ago"
                } else {
                    "last phone event over an hour ago"
                }
            }
        }
    }
}
