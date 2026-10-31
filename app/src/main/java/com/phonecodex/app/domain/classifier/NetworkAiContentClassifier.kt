package com.phonecodex.app.domain.classifier

import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import com.phonecodex.app.domain.diagnostics.BackendConfig
import com.phonecodex.app.domain.diagnostics.BackendHealthProbe
import com.phonecodex.app.domain.diagnostics.diagnosticErrorKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud classifier via local backend POST /classify (Azure OpenAI v04).
 * On failure returns null — [PhoneCodexAccessibilityService] runs PolicyEngine then safe WARN.
 */
class NetworkAiContentClassifier : ContentClassifier {

    override fun classify(request: ClassificationRequest): ContentClassification? {
        val previousPolicy = StrictMode.getThreadPolicy()
        return try {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder(previousPolicy).permitNetwork().build()
            )
            classifyFromBackend(request)
        } catch (err: Exception) {
            Log.w(TAG, "Backend classify failed for ${request.packageName}: ${diagnosticErrorKind(err)}")
            null
        } finally {
            StrictMode.setThreadPolicy(previousPolicy)
        }
    }

    private fun classifyFromBackend(request: ClassificationRequest): ContentClassification? {
        val startedElapsed = SystemClock.elapsedRealtime()
        val connection = (URL(BackendHealthProbe.CLASSIFY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = if (request.imageJpegBase64.isNullOrBlank()) {
                READ_TIMEOUT_MS
            } else {
                VISION_READ_TIMEOUT_MS
            }
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            BackendConfig.applyAuth(this)
        }

        try {
            Log.d(
                TAG,
                "POST /classify pkg=${request.packageName} strictness=${request.strictnessLevel} " +
                    "commitment=${request.commitmentType} guardrails=${request.activeGuardrails.size} " +
                    "hasImage=${!request.imageJpegBase64.isNullOrBlank()}"
            )

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(buildRequestBody(request))
                writer.flush()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Backend HTTP $code for ${request.packageName}")
                return null
            }

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use(
                BufferedReader::readText
            )
            val clientLatencyMs = SystemClock.elapsedRealtime() - startedElapsed

            val result = NetworkAiResponseParser.parse(responseText)
            if (result == null) {
                Log.w(TAG, "Backend response parse failed for ${request.packageName}")
                return null
            }

            Log.d(
                TAG,
                "Backend ok pkg=${request.packageName} decision=${result.decision} " +
                    "confidence=${result.confidence} reasonCategory=${result.reasonCategory} " +
                    "wouldEscalate=${result.wouldEscalate} deployment=${result.backendMeta?.deployment} " +
                    "prompt=${result.backendMeta?.promptVersion} " +
                    "latencyMs=${result.backendMeta?.latencyMs ?: clientLatencyMs}"
            )
            return result
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequestBody(request: ClassificationRequest): String {
        val body = JSONObject()
            .put("packageName", request.packageName)
            .put("screenText", request.screenText)
            .put("goal", request.goal)
            .put("strictnessLevel", request.strictnessLevel)
            .put("commitmentType", request.commitmentType)
            .put("limitState", request.limitState ?: JSONObject.NULL)

        request.appLabel?.takeIf { it.isNotBlank() }?.let { label ->
            body.put("appLabel", label)
        }

        body.put("activeGuardrails", JSONArray(request.activeGuardrails))

        request.sessionCounters?.let { counters ->
            val counterJson = JSONObject()
            counters.forEach { (key, value) -> counterJson.put(key, value) }
            body.put("sessionCounters", counterJson)
        }

        request.imageJpegBase64?.takeIf { it.isNotBlank() }?.let { image ->
            body.put("imageJpegBase64", image)
        }

        return body.toString()
    }

    companion object {
        private const val TAG = "PhoneCodexNetAI"
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 4000
        private const val VISION_READ_TIMEOUT_MS = 8000
    }
}
