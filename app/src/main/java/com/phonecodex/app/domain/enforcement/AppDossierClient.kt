package com.phonecodex.app.domain.enforcement

import android.util.Log
import com.phonecodex.app.domain.diagnostics.BackendConfig
import com.phonecodex.app.domain.diagnostics.BackendHealthProbe
import com.phonecodex.app.domain.diagnostics.diagnosticErrorKind
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * POST /research-app. Counsel only. Caller merges with [AppDossierMergeLaw].
 */
class AppDossierClient(
    private val baseUrl: String = BackendHealthProbe.BACKEND_BASE_URL,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) {

    fun research(
        packageName: String,
        appLabel: String,
        playListingText: String,
        userClaimedClass: AppClass? = null
    ): AppDossier? {
        val connection = (URL("$baseUrl/research-app").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            BackendConfig.applyAuth(this)
        }
        return try {
            val body = JSONObject()
                .put("packageName", packageName)
                .put("appLabel", appLabel)
                .put("playListingText", playListingText)
            userClaimedClass?.let { body.put("userClaimedClass", it.name) }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "research-app HTTP ${connection.responseCode} for $packageName")
                return null
            }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use(
                BufferedReader::readText
            )
            parse(packageName, responseText)
        } catch (err: Exception) {
            Log.w(TAG, "research-app failed for $packageName: ${diagnosticErrorKind(err)}")
            null
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(packageName: String, responseText: String): AppDossier? {
        return try {
            val json = JSONObject(responseText)
            val counsel = json.optString("appClass").takeIf { it.isNotBlank() }?.let {
                AppClass.valueOf(it)
            } ?: return null
            AppDossier(
                packageName = packageName,
                localClass = AppClass.UNKNOWN,
                playClass = json.optString("playClass").takeIf { it.isNotBlank() }?.let {
                    runCatching { AppClass.valueOf(it) }.getOrNull()
                },
                counselClass = counsel,
                confidence = json.optDouble("confidence", 0.0),
                summary = json.optString("summary"),
                source = json.optString("source", AppDossier.SOURCE_AZURE_WEB),
                researchedAtMillis = json.optLong("researchedAtMillis", System.currentTimeMillis())
            )
        } catch (err: Exception) {
            Log.w(TAG, "research-app parse failed: ${diagnosticErrorKind(err)}")
            null
        }
    }

    companion object {
        private const val TAG = "PhoneCodexDossier"
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 8000
    }
}
