package com.phonecodex.app.domain.enforcement

/**
 * Pure hot-path helpers for Accessibility reliability.
 * Keep Android-free so unit tests can lock detect/decide behavior.
 */
object EnforcementEventGate {

    const val CONTENT_CHANGED_DEBOUNCE_MS = 400L
    const val DEBUG_PACKAGE_WRITE_THROTTLE_MS = 2_000L
    const val MIN_CLASSIFICATION_INTERVAL_MS = 2_500L
    const val CLASSIFICATION_CACHE_TTL_MS = 5_000L

    /**
     * Whether a CONTENT_CHANGED event should be coalesced (scheduled) rather than
     * evaluated immediately. STATE_CHANGED should never debounce.
     */
    fun shouldDebounceContentChanged(eventIsContentChanged: Boolean): Boolean =
        eventIsContentChanged

    /**
     * Blank or stale screen text must not drive AI / content decisions.
     * App-package rules and permanent package-based guardrails may still run.
     */
    fun shouldSkipContentAndAi(screenText: String): Boolean =
        screenText.isBlank()

    fun shouldWriteDebugPackageDetection(
        packageName: String,
        lastWrittenPackage: String?,
        lastWrittenAtMillis: Long,
        nowMillis: Long,
        throttleMs: Long = DEBUG_PACKAGE_WRITE_THROTTLE_MS
    ): Boolean {
        if (packageName != lastWrittenPackage) return true
        return nowMillis - lastWrittenAtMillis >= throttleMs
    }

    /**
     * Do not storm the backend on every CONTENT_CHANGED tick.
     */
    fun shouldThrottleClassificationRequest(
        packageName: String,
        lastRequestPackage: String?,
        lastRequestAtMillis: Long,
        nowMillis: Long,
        classificationInFlight: Boolean,
        minIntervalMs: Long = MIN_CLASSIFICATION_INTERVAL_MS
    ): Boolean {
        if (classificationInFlight) return true
        if (packageName != lastRequestPackage) return false
        if (lastRequestAtMillis <= 0L) return false
        val requestAgeMillis = nowMillis - lastRequestAtMillis
        return requestAgeMillis >= 0L && requestAgeMillis < minIntervalMs
    }

    fun shouldUseCachedClassification(
        packageName: String,
        textSignature: String,
        lastPackage: String?,
        lastSignature: String?,
        lastClassifiedAtMillis: Long,
        nowMillis: Long,
        cacheTtlMs: Long = CLASSIFICATION_CACHE_TTL_MS
    ): Boolean {
        if (packageName != lastPackage) return false
        if (textSignature != lastSignature) return false
        if (lastClassifiedAtMillis <= 0L) return false
        val classificationAgeMillis = nowMillis - lastClassifiedAtMillis
        return classificationAgeMillis >= 0L && classificationAgeMillis < cacheTtlMs
    }
}
