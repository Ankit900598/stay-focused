package com.phonecodex.app.data

import android.content.Context
import com.phonecodex.app.domain.classifier.ClassificationBackendMeta

data class DebugState(
    val lastPackageName: String?,
    val lastScreenTextPreview: String?,
    val lastDecision: String?,
    val lastReason: String?,
    val lastSource: String?,
    val lastConfidence: Double?,
    val lastMatchedSignals: List<String>,
    val lastUpdatedMillis: Long,
    val lastReasonCategory: String?,
    val lastWouldEscalate: Boolean?,
    val lastBackendProvider: String?,
    val lastBackendDeployment: String?,
    val lastBackendPromptVersion: String?,
    val lastBackendLatencyMs: Long?,
    val lastReasonCode: String? = null,
    val lastSurface: String? = null,
    val lastActivity: String? = null,
    val lastDurationSource: String? = null
)

class DebugStateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun recordPackageDetected(packageName: String, screenText: String) {
        prefs.edit()
            .putString(KEY_LAST_PACKAGE_NAME, packageName)
            .putString(KEY_LAST_SCREEN_TEXT_PREVIEW, preview(screenText))
            .putLong(KEY_LAST_UPDATED_MILLIS, System.currentTimeMillis())
            .apply()
    }

    fun recordDecision(
        packageName: String,
        screenText: String,
        decision: String,
        reason: String,
        source: String,
        confidence: Double,
        matchedSignals: List<String> = emptyList(),
        reasonCategory: String? = null,
        wouldEscalate: Boolean? = null,
        backendMeta: ClassificationBackendMeta? = null,
        reasonCode: String? = null,
        surface: String? = null,
        activity: String? = null,
        durationSource: String? = null
    ) {
        prefs.edit()
            .putString(KEY_LAST_PACKAGE_NAME, packageName)
            .putString(KEY_LAST_SCREEN_TEXT_PREVIEW, preview(screenText))
            .putString(KEY_LAST_DECISION, decision)
            .putString(KEY_LAST_REASON, reason)
            .putString(KEY_LAST_SOURCE, source)
            .putString(KEY_LAST_CONFIDENCE, confidence.toString())
            .putString(KEY_LAST_MATCHED_SIGNALS, encodeMatchedSignals(matchedSignals))
            .putLong(KEY_LAST_UPDATED_MILLIS, System.currentTimeMillis())
            .apply {
                if (reasonCategory != null) {
                    putString(KEY_LAST_REASON_CATEGORY, reasonCategory)
                } else {
                    remove(KEY_LAST_REASON_CATEGORY)
                }
                if (wouldEscalate != null) {
                    putBoolean(KEY_LAST_WOULD_ESCALATE, wouldEscalate)
                } else {
                    remove(KEY_LAST_WOULD_ESCALATE)
                }
                if (backendMeta != null) {
                    putString(KEY_LAST_BACKEND_PROVIDER, backendMeta.provider)
                    putString(KEY_LAST_BACKEND_DEPLOYMENT, backendMeta.deployment)
                    putString(KEY_LAST_BACKEND_PROMPT_VERSION, backendMeta.promptVersion)
                    if (backendMeta.latencyMs != null) {
                        putLong(KEY_LAST_BACKEND_LATENCY_MS, backendMeta.latencyMs)
                    } else {
                        remove(KEY_LAST_BACKEND_LATENCY_MS)
                    }
                } else {
                    remove(KEY_LAST_BACKEND_PROVIDER)
                    remove(KEY_LAST_BACKEND_DEPLOYMENT)
                    remove(KEY_LAST_BACKEND_PROMPT_VERSION)
                    remove(KEY_LAST_BACKEND_LATENCY_MS)
                }
                putOptionalString(KEY_LAST_REASON_CODE, reasonCode)
                putOptionalString(KEY_LAST_SURFACE, surface)
                putOptionalString(KEY_LAST_ACTIVITY, activity)
                putOptionalString(KEY_LAST_DURATION_SOURCE, durationSource)
            }
            .apply()
    }

    fun getDebugState(): DebugState {
        val confidenceRaw = prefs.getString(KEY_LAST_CONFIDENCE, null)
        val confidence = confidenceRaw?.toDoubleOrNull()
        val wouldEscalate = if (prefs.contains(KEY_LAST_WOULD_ESCALATE)) {
            prefs.getBoolean(KEY_LAST_WOULD_ESCALATE, false)
        } else {
            null
        }
        val backendLatency = if (prefs.contains(KEY_LAST_BACKEND_LATENCY_MS)) {
            prefs.getLong(KEY_LAST_BACKEND_LATENCY_MS, 0L)
        } else {
            null
        }

        return DebugState(
            lastPackageName = prefs.getString(KEY_LAST_PACKAGE_NAME, null),
            lastScreenTextPreview = prefs.getString(KEY_LAST_SCREEN_TEXT_PREVIEW, null),
            lastDecision = prefs.getString(KEY_LAST_DECISION, null),
            lastReason = prefs.getString(KEY_LAST_REASON, null),
            lastSource = prefs.getString(KEY_LAST_SOURCE, null),
            lastConfidence = confidence,
            lastMatchedSignals = decodeMatchedSignals(
                prefs.getString(KEY_LAST_MATCHED_SIGNALS, null)
            ),
            lastUpdatedMillis = prefs.getLong(KEY_LAST_UPDATED_MILLIS, 0L),
            lastReasonCategory = prefs.getString(KEY_LAST_REASON_CATEGORY, null),
            lastWouldEscalate = wouldEscalate,
            lastBackendProvider = prefs.getString(KEY_LAST_BACKEND_PROVIDER, null),
            lastBackendDeployment = prefs.getString(KEY_LAST_BACKEND_DEPLOYMENT, null),
            lastBackendPromptVersion = prefs.getString(KEY_LAST_BACKEND_PROMPT_VERSION, null),
            lastBackendLatencyMs = backendLatency,
            lastReasonCode = prefs.getString(KEY_LAST_REASON_CODE, null),
            lastSurface = prefs.getString(KEY_LAST_SURFACE, null),
            lastActivity = prefs.getString(KEY_LAST_ACTIVITY, null),
            lastDurationSource = prefs.getString(KEY_LAST_DURATION_SOURCE, null)
        )
    }

    private fun android.content.SharedPreferences.Editor.putOptionalString(
        key: String,
        value: String?
    ) {
        if (value.isNullOrBlank()) remove(key) else putString(key, value)
    }

    private fun encodeMatchedSignals(matchedSignals: List<String>): String {
        return matchedSignals.joinToString(MATCHED_SIGNAL_SEPARATOR)
    }

    private fun decodeMatchedSignals(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(MATCHED_SIGNAL_SEPARATOR).filter { it.isNotBlank() }
    }

    private fun preview(screenText: String): String {
        return screenText.take(PREVIEW_LENGTH)
    }

    companion object {
        private const val PREFS_NAME = "phonecodex_debug_state"
        private const val PREVIEW_LENGTH = 300
        private const val KEY_LAST_PACKAGE_NAME = "lastPackageName"
        private const val KEY_LAST_SCREEN_TEXT_PREVIEW = "lastScreenTextPreview"
        private const val KEY_LAST_DECISION = "lastDecision"
        private const val KEY_LAST_REASON = "lastReason"
        private const val KEY_LAST_SOURCE = "lastSource"
        private const val KEY_LAST_CONFIDENCE = "lastConfidence"
        private const val KEY_LAST_MATCHED_SIGNALS = "lastMatchedSignals"
        private const val KEY_LAST_UPDATED_MILLIS = "lastUpdatedMillis"
        private const val KEY_LAST_REASON_CATEGORY = "lastReasonCategory"
        private const val KEY_LAST_WOULD_ESCALATE = "lastWouldEscalate"
        private const val KEY_LAST_BACKEND_PROVIDER = "lastBackendProvider"
        private const val KEY_LAST_BACKEND_DEPLOYMENT = "lastBackendDeployment"
        private const val KEY_LAST_BACKEND_PROMPT_VERSION = "lastBackendPromptVersion"
        private const val KEY_LAST_BACKEND_LATENCY_MS = "lastBackendLatencyMs"
        private const val KEY_LAST_REASON_CODE = "lastReasonCode"
        private const val KEY_LAST_SURFACE = "lastSurface"
        private const val KEY_LAST_ACTIVITY = "lastActivity"
        private const val KEY_LAST_DURATION_SOURCE = "lastDurationSource"
        private const val MATCHED_SIGNAL_SEPARATOR = "\u001E"
    }
}
