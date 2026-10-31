package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences
import com.phonecodex.app.domain.classifier.ClassificationBackendMeta
import java.util.WeakHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
    val lastDurationSource: String? = null,
    val lastDetectedPackageName: String? = null,
    val lastDetectedScreenTextPreview: String? = null,
    val lastDetectionUpdatedMillis: Long = 0L,
    val lastSessionId: String? = null
)

class DebugStateStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    init {
        migrateDecisionContextIfNeeded()
        prefs.edit()
            .remove(KEY_LAST_SCREEN_TEXT_PREVIEW)
            .remove(KEY_LAST_DETECTED_SCREEN_TEXT_PREVIEW)
            .remove(KEY_LAST_REASON)
            .apply()
    }

    fun recordPackageDetected(packageName: String) {
        prefs.edit()
            .putString(KEY_LAST_DETECTED_PACKAGE_NAME, packageName)
            .remove(KEY_LAST_DETECTED_SCREEN_TEXT_PREVIEW)
            .putLong(KEY_LAST_DETECTION_UPDATED_MILLIS, System.currentTimeMillis())
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
        durationSource: String? = null,
        sessionId: String? = null
    ) {
        val nowMillis = System.currentTimeMillis()
        DecisionPrivateContextCache.record(prefs, preview(screenText), reason)
        prefs.edit()
            .putString(KEY_LAST_PACKAGE_NAME, packageName)
            .putString(KEY_LAST_DETECTED_PACKAGE_NAME, packageName)
            .remove(KEY_LAST_SCREEN_TEXT_PREVIEW)
            .remove(KEY_LAST_DETECTED_SCREEN_TEXT_PREVIEW)
            .putLong(KEY_LAST_DETECTION_UPDATED_MILLIS, nowMillis)
            .putString(KEY_LAST_DECISION, decision)
            .remove(KEY_LAST_REASON)
            .putString(KEY_LAST_SOURCE, source)
            .putString(KEY_LAST_CONFIDENCE, confidence.toString())
            .putString(KEY_LAST_MATCHED_SIGNALS, encodeMatchedSignals(matchedSignals))
            .putLong(KEY_LAST_UPDATED_MILLIS, nowMillis)
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
                putOptionalString(KEY_LAST_SESSION_ID, sessionId)
            }
            .apply()
    }

    fun getDebugState(): DebugState {
        val privateContext = DecisionPrivateContextCache.get(prefs)
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
            lastScreenTextPreview = privateContext?.screenTextPreview,
            lastDecision = prefs.getString(KEY_LAST_DECISION, null),
            lastReason = privateContext?.reason,
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
            lastDurationSource = prefs.getString(KEY_LAST_DURATION_SOURCE, null),
            lastDetectedPackageName = prefs.getString(KEY_LAST_DETECTED_PACKAGE_NAME, null),
            lastDetectedScreenTextPreview = null,
            lastDetectionUpdatedMillis = prefs.getLong(KEY_LAST_DETECTION_UPDATED_MILLIS, 0L),
            lastSessionId = prefs.getString(KEY_LAST_SESSION_ID, null)
        )
    }

    private fun migrateDecisionContextIfNeeded() {
        val version = runCatching {
            prefs.getInt(KEY_DECISION_CONTEXT_VERSION, 0)
        }.getOrDefault(0)
        if (version >= DECISION_CONTEXT_VERSION) return

        prefs.edit()
            .remove(KEY_LAST_PACKAGE_NAME)
            .remove(KEY_LAST_SCREEN_TEXT_PREVIEW)
            .remove(KEY_LAST_DETECTED_SCREEN_TEXT_PREVIEW)
            .remove(KEY_LAST_DECISION)
            .remove(KEY_LAST_REASON)
            .remove(KEY_LAST_SOURCE)
            .remove(KEY_LAST_CONFIDENCE)
            .remove(KEY_LAST_MATCHED_SIGNALS)
            .remove(KEY_LAST_UPDATED_MILLIS)
            .remove(KEY_LAST_REASON_CATEGORY)
            .remove(KEY_LAST_WOULD_ESCALATE)
            .remove(KEY_LAST_BACKEND_PROVIDER)
            .remove(KEY_LAST_BACKEND_DEPLOYMENT)
            .remove(KEY_LAST_BACKEND_PROMPT_VERSION)
            .remove(KEY_LAST_BACKEND_LATENCY_MS)
            .remove(KEY_LAST_REASON_CODE)
            .remove(KEY_LAST_SURFACE)
            .remove(KEY_LAST_ACTIVITY)
            .remove(KEY_LAST_DURATION_SOURCE)
            .remove(KEY_LAST_SESSION_ID)
            .putInt(KEY_DECISION_CONTEXT_VERSION, DECISION_CONTEXT_VERSION)
            .apply()
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
        private const val DECISION_CONTEXT_VERSION = 2
        private const val KEY_DECISION_CONTEXT_VERSION = "decisionContextVersion"
        private const val KEY_LAST_PACKAGE_NAME = "lastPackageName"
        private const val KEY_LAST_SCREEN_TEXT_PREVIEW = "lastScreenTextPreview"
        private const val KEY_LAST_DETECTED_PACKAGE_NAME = "lastDetectedPackageName"
        private const val KEY_LAST_DETECTED_SCREEN_TEXT_PREVIEW = "lastDetectedScreenTextPreview"
        private const val KEY_LAST_DETECTION_UPDATED_MILLIS = "lastDetectionUpdatedMillis"
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
        private const val KEY_LAST_SESSION_ID = "lastSessionId"
        private const val MATCHED_SIGNAL_SEPARATOR = "\u001E"
    }
}

internal data class DecisionPrivateContext(
    val screenTextPreview: String?,
    val reason: String?,
    val recordedAtMillis: Long,
    val generation: Long
)

internal object DecisionPrivateContextCache {
    private val contextByPreferences = WeakHashMap<SharedPreferences, DecisionPrivateContext>()
    private val cleanupByPreferences = WeakHashMap<SharedPreferences, ScheduledFuture<*>>()
    private val cleanupExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "strlix-private-context-expiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var nextGeneration = 0L

    fun record(
        prefs: SharedPreferences,
        screenTextPreview: String,
        reason: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        synchronized(contextByPreferences) {
            cleanupByPreferences.remove(prefs)?.cancel(false)
            val generation = ++nextGeneration
            contextByPreferences[prefs] = DecisionPrivateContext(
                screenTextPreview = screenTextPreview.takeIf { it.isNotBlank() },
                reason = reason.take(MAX_PRIVATE_CONTEXT_LENGTH).takeIf { it.isNotBlank() },
                recordedAtMillis = nowMillis,
                generation = generation
            )
            cleanupByPreferences[prefs] = cleanupExecutor.schedule(
                {
                    synchronized(contextByPreferences) {
                        if (contextByPreferences[prefs]?.generation == generation) {
                            contextByPreferences.remove(prefs)
                            cleanupByPreferences.remove(prefs)
                        }
                    }
                },
                PRIVATE_CONTEXT_RETENTION_MILLIS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun get(
        prefs: SharedPreferences,
        nowMillis: Long = System.currentTimeMillis()
    ): DecisionPrivateContext? = synchronized(contextByPreferences) {
        val cached = contextByPreferences[prefs] ?: return@synchronized null
        val ageMillis = nowMillis - cached.recordedAtMillis
        if (ageMillis !in 0..PRIVATE_CONTEXT_RETENTION_MILLIS) {
            contextByPreferences.remove(prefs)
            cleanupByPreferences.remove(prefs)?.cancel(false)
            null
        } else {
            cached
        }
    }

    internal const val PRIVATE_CONTEXT_RETENTION_MILLIS = 10 * 60 * 1000L
    private const val MAX_PRIVATE_CONTEXT_LENGTH = 300
}
