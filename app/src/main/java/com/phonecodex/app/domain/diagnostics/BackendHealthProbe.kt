package com.phonecodex.app.domain.diagnostics

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Developer-only smoke probe for the local PhoneCodex backend.
 * Call from a background thread — never from the main thread or composable bodies.
 *
 * Status kinds (do not collapse into a single scary "offline"):
 * - [BackendProbeKind.REACHABLE] — /health succeeded
 * - [BackendProbeKind.UNREACHABLE] — host not responding as expected
 * - [BackendProbeKind.BRIDGE_MISSING] — loopback URL failed (ADB reverse likely missing)
 * - [BackendProbeKind.CHECKING] — probe in flight
 * - [BackendProbeKind.STALE_OK] — recent success; current failure/check must not flip UI to offline
 */
object BackendHealthProbe {

    val BACKEND_BASE_URL: String
        get() = BackendConfig.baseUrl
    val HEALTH_URL: String
        get() = "$BACKEND_BASE_URL/health"
    val CLASSIFY_URL: String
        get() = "$BACKEND_BASE_URL/classify"
    const val BACKEND_PORT = 8787

    const val STATUS_CONNECTED = "Backend connected"
    const val STATUS_UNREACHABLE = "Backend unreachable"
    const val STATUS_BRIDGE_MISSING = "ADB/local bridge likely missing"
    const val STATUS_CHECKING = "Checking backend…"
    const val STATUS_STALE = "Backend OK recently — status may be stale"
    const val STATUS_IDLE = "Not tested yet"
    const val STATUS_A11Y_OFF = "Accessibility off — protection cannot work"

    /** Kept for older call sites; prefer [STATUS_UNREACHABLE] / [STATUS_BRIDGE_MISSING]. */
    const val STATUS_OFFLINE = "Backend offline — using local fallback"

    const val HINT_ADB_REVERSE =
        "Backend not reachable from phone. If testing over USB, run adb reverse tcp:8787 tcp:8787."
    const val HINT_HOSTED =
        "Cannot reach Strlix. Check internet, then try Understand again."

    fun reachabilityHint(): String =
        if (isLoopbackBaseUrl(BACKEND_BASE_URL)) HINT_ADB_REVERSE else HINT_HOSTED

    /** Keep last success sticky so a flaky probe does not flash "offline". */
    const val STALE_OK_WINDOW_MS = 60_000L

    /** Debounce rapid Test Backend taps / auto probes. */
    const val MIN_PROBE_INTERVAL_MS = 2_500L

    private const val TAG = "PhoneCodexDiag"
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 4000

    fun probe(): BackendProbeResult {
        val started = SystemClock.elapsedRealtime()
        return try {
            val health = fetchHealth()
            if (health == null) {
                val latency = SystemClock.elapsedRealtime() - started
                Log.w(TAG, "Test Backend: health unreachable latencyMs=$latency")
                BackendProbeResult.failure(
                    kind = BackendProbeKind.UNREACHABLE,
                    latencyMs = latency,
                    detail = "/health returned non-OK",
                    probedAtElapsedMs = SystemClock.elapsedRealtime()
                )
            } else {
                val classify = smokeClassify()
                val latency = SystemClock.elapsedRealtime() - started
                Log.i(
                    TAG,
                    "Test Backend: health=ok provider=${health.provider} " +
                        "deployment=${health.deployment} prompt=${health.promptVersion} " +
                        "classifyOk=${classify.ok} decision=${classify.decision} " +
                        "latencyMs=$latency"
                )
                BackendProbeResult(
                    kind = BackendProbeKind.REACHABLE,
                    reachable = true,
                    statusLine = STATUS_CONNECTED,
                    provider = health.provider,
                    deployment = health.deployment,
                    promptVersion = health.promptVersion,
                    classifyOk = classify.ok,
                    classifyDecision = classify.decision,
                    classifyReasonCategory = classify.reasonCategory,
                    latencyMs = latency,
                    detail = classify.error,
                    probedAtElapsedMs = SystemClock.elapsedRealtime()
                )
            }
        } catch (err: Exception) {
            val latency = SystemClock.elapsedRealtime() - started
            val kind = classifyFailure(BACKEND_BASE_URL, err)
            Log.w(
                TAG,
                "Test Backend: failed kind=$kind error=${diagnosticErrorKind(err)}"
            )
            BackendProbeResult.failure(
                kind = kind,
                latencyMs = latency,
                detail = err.message,
                probedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        }
    }

    /**
     * Maps a raw probe (or in-flight check) into a UI-safe result.
     * Recent success stays sticky instead of jumping to scary offline.
     */
    fun resolveDisplayResult(
        raw: BackendProbeResult?,
        checking: Boolean,
        lastSuccessElapsedMs: Long?,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        staleWindowMs: Long = STALE_OK_WINDOW_MS
    ): BackendProbeResult {
        if (checking) {
            val recentOk = isRecentSuccess(lastSuccessElapsedMs, nowElapsedMs, staleWindowMs)
            return if (recentOk) {
                (raw?.takeIf { it.kind == BackendProbeKind.REACHABLE } ?: BackendProbeResult.idle())
                    .copy(
                        kind = BackendProbeKind.CHECKING,
                        reachable = true,
                        statusLine = STATUS_CHECKING,
                        detail = "Re-checking… last success was recent"
                    )
            } else {
                BackendProbeResult.checking()
            }
        }

        val result = raw ?: BackendProbeResult.idle()
        if (result.kind == BackendProbeKind.REACHABLE || result.kind == BackendProbeKind.CHECKING) {
            return result
        }
        if (result.kind == BackendProbeKind.IDLE) {
            return result
        }

        // Failed probe: keep sticky OK if last success was recent.
        if (isRecentSuccess(lastSuccessElapsedMs, nowElapsedMs, staleWindowMs)) {
            return result.copy(
                kind = BackendProbeKind.STALE_OK,
                reachable = true,
                statusLine = STATUS_STALE,
                detail = buildStaleDetail(result)
            )
        }

        return result.withBridgeHintIfNeeded()
    }

    fun classifyFailure(baseUrl: String, error: Throwable?): BackendProbeKind {
        if (!isLoopbackBaseUrl(baseUrl)) {
            return BackendProbeKind.UNREACHABLE
        }
        return if (looksLikeBridgeOrLocalConnectFailure(error)) {
            BackendProbeKind.BRIDGE_MISSING
        } else {
            BackendProbeKind.UNREACHABLE
        }
    }

    fun isLoopbackBaseUrl(baseUrl: String): Boolean {
        val lower = baseUrl.lowercase()
        return lower.contains("127.0.0.1") || lower.contains("localhost")
    }

    fun looksLikeBridgeOrLocalConnectFailure(error: Throwable?): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            when (cur) {
                is ConnectException,
                is SocketTimeoutException,
                is UnknownHostException,
                is NoRouteToHostException -> return true
            }
            val msg = cur.message?.lowercase().orEmpty()
            if (
                msg.contains("failed to connect") ||
                msg.contains("connection refused") ||
                msg.contains("econnrefused") ||
                msg.contains("network is unreachable") ||
                msg.contains("cleartext") ||
                msg.contains("timeout")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    fun isRecentSuccess(
        lastSuccessElapsedMs: Long?,
        nowElapsedMs: Long,
        staleWindowMs: Long = STALE_OK_WINDOW_MS
    ): Boolean {
        if (lastSuccessElapsedMs == null) return false
        val age = nowElapsedMs - lastSuccessElapsedMs
        return age in 0 until staleWindowMs
    }

    fun statusLineFor(kind: BackendProbeKind): String = when (kind) {
        BackendProbeKind.REACHABLE -> STATUS_CONNECTED
        BackendProbeKind.UNREACHABLE -> STATUS_UNREACHABLE
        BackendProbeKind.BRIDGE_MISSING -> STATUS_BRIDGE_MISSING
        BackendProbeKind.CHECKING -> STATUS_CHECKING
        BackendProbeKind.STALE_OK -> STATUS_STALE
        BackendProbeKind.IDLE -> STATUS_IDLE
    }

    private fun buildStaleDetail(failed: BackendProbeResult): String {
        val hint = when (failed.kind) {
            BackendProbeKind.BRIDGE_MISSING -> reachabilityHint()
            else -> failed.detail?.takeIf { it.isNotBlank() }
                ?: "Latest probe failed; keeping last good status briefly."
        }
        return hint
    }

    private fun fetchHealth(): HealthMeta? {
        val connection = (URL(HEALTH_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Test Backend: /health HTTP $code")
                return null
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use(
                BufferedReader::readText
            )
            return parseHealth(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseHealth(body: String): HealthMeta? {
        return try {
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return null
            val classifier = json.optJSONObject("classifier")
            HealthMeta(
                provider = classifier?.optString("provider")?.ifBlank { null },
                deployment = classifier?.optString("deployment")?.ifBlank { null },
                promptVersion = classifier?.optString("promptVersion")?.ifBlank { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun smokeClassify(): ClassifySmoke {
        val connection = (URL(CLASSIFY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            BackendConfig.applyAuth(this)
        }
        try {
            val requestBody = JSONObject()
                .put("packageName", "com.phonecodex.diag.smoke")
                .put("screenText", "PhoneCodex developer diagnostics smoke test")
                .put("goal", "diagnostics")
                .put("strictnessLevel", "NORMAL")
                .put("commitmentType", "focus")
                .put("activeGuardrails", org.json.JSONArray())
                .toString()

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Test Backend: /classify smoke HTTP $code")
                return ClassifySmoke(ok = false, error = "HTTP $code")
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use(
                BufferedReader::readText
            )
            return parseClassifySmoke(body)
        } catch (err: Exception) {
            return ClassifySmoke(ok = false, error = err.message)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseClassifySmoke(body: String): ClassifySmoke {
        return try {
            val json = JSONObject(body)
            val decision = json.optString("decision").ifBlank { null }
            if (decision == null) {
                ClassifySmoke(ok = false, error = "missing decision")
            } else {
                ClassifySmoke(
                    ok = true,
                    decision = decision,
                    reasonCategory = json.optString("reasonCategory").ifBlank { null }
                )
            }
        } catch (err: Exception) {
            ClassifySmoke(ok = false, error = err.message)
        }
    }

    data class HealthMeta(
        val provider: String?,
        val deployment: String?,
        val promptVersion: String?
    )

    data class ClassifySmoke(
        val ok: Boolean,
        val decision: String? = null,
        val reasonCategory: String? = null,
        val error: String? = null
    )
}

enum class BackendProbeKind {
    IDLE,
    CHECKING,
    REACHABLE,
    UNREACHABLE,
    BRIDGE_MISSING,
    STALE_OK
}

data class BackendProbeResult(
    val kind: BackendProbeKind,
    val reachable: Boolean,
    val statusLine: String,
    val provider: String?,
    val deployment: String?,
    val promptVersion: String?,
    val classifyOk: Boolean?,
    val classifyDecision: String?,
    val classifyReasonCategory: String?,
    val latencyMs: Long?,
    val detail: String?,
    val probedAtElapsedMs: Long? = null
) {
    fun withBridgeHintIfNeeded(): BackendProbeResult {
        if (kind != BackendProbeKind.BRIDGE_MISSING) return this
        val hint = BackendHealthProbe.reachabilityHint()
        val detailText = detail?.takeIf { it.isNotBlank() && it != hint }
        return copy(
            statusLine = BackendHealthProbe.STATUS_BRIDGE_MISSING,
            detail = if (detailText == null) {
                hint
            } else {
                "$hint ($detailText)"
            }
        )
    }

    companion object {
        fun failure(
            kind: BackendProbeKind,
            latencyMs: Long?,
            detail: String? = null,
            probedAtElapsedMs: Long? = null
        ): BackendProbeResult =
            BackendProbeResult(
                kind = kind,
                reachable = false,
                statusLine = BackendHealthProbe.statusLineFor(kind),
                provider = null,
                deployment = null,
                promptVersion = null,
                classifyOk = null,
                classifyDecision = null,
                classifyReasonCategory = null,
                latencyMs = latencyMs,
                detail = detail,
                probedAtElapsedMs = probedAtElapsedMs
            ).withBridgeHintIfNeeded()

        /** @deprecated Prefer [failure] with an explicit kind. */
        fun offline(latencyMs: Long?, detail: String? = null): BackendProbeResult =
            failure(
                kind = BackendHealthProbe.classifyFailure(
                    BackendHealthProbe.BACKEND_BASE_URL,
                    detail?.let { RuntimeException(it) }
                ),
                latencyMs = latencyMs,
                detail = detail
            )

        fun idle(): BackendProbeResult =
            BackendProbeResult(
                kind = BackendProbeKind.IDLE,
                reachable = false,
                statusLine = BackendHealthProbe.STATUS_IDLE,
                provider = null,
                deployment = null,
                promptVersion = null,
                classifyOk = null,
                classifyDecision = null,
                classifyReasonCategory = null,
                latencyMs = null,
                detail = null,
                probedAtElapsedMs = null
            )

        fun checking(): BackendProbeResult =
            BackendProbeResult(
                kind = BackendProbeKind.CHECKING,
                reachable = false,
                statusLine = BackendHealthProbe.STATUS_CHECKING,
                provider = null,
                deployment = null,
                promptVersion = null,
                classifyOk = null,
                classifyDecision = null,
                classifyReasonCategory = null,
                latencyMs = null,
                detail = null,
                probedAtElapsedMs = null
            )
    }
}
