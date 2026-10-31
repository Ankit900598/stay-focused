package com.phonecodex.app.domain.promise

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
 * Calls backend POST /compile-promise. Call from a background thread / IO dispatcher.
 * On any failure throws [PromiseCompilerException] — caller must fall back to local parser.
 */
class PromiseCompilerClient(
    private val baseUrl: String = BackendHealthProbe.BACKEND_BASE_URL,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) {

    fun compile(
        promise: String,
        selectedClarificationOptionId: String? = null,
        /**
         * Reliability design 2026-09-25 §Fix 1: verbatim JSON string of the
         * option the user just tapped (from the previous compile response),
         * including its `internalPolicyPreview`. Lets the stateless backend
         * verify the pick deterministically instead of re-guessing.
         */
        selectedClarificationOptionFullTextJson: String? = null,
        /**
         * Reliability design 2026-09-25 §Fix 1: verbatim JSON string of the
         * full `clarificationOptions` array shown to the user on turn 1.
         * Used as a snapshot fallback if the fresh compile doesn't re-emit
         * the same options list.
         */
        priorClarificationOptionsSnapshotJson: String? = null,
        /** Promise text the snapshot was shown for; the backend fails closed on mismatch. */
        priorClarificationPromiseText: String? = null
    ): CompiledPromiseResponse {
        val trimmed = promise.trim()
        require(trimmed.isNotEmpty()) { "promise must be non-empty" }

        Log.i(
            TAG,
            "compile request started len=${trimmed.length} selected=${selectedClarificationOptionId ?: "none"} " +
                "carry=${selectedClarificationOptionFullTextJson?.length ?: 0} " +
                "snapshot=${priorClarificationOptionsSnapshotJson?.length ?: 0}"
        )
        val url = URL("$baseUrl/compile-promise")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            BackendConfig.applyAuth(this)
        }

        try {
            val bodyJson = JSONObject().put("promise", trimmed)
            selectedClarificationOptionId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { bodyJson.put("selectedClarificationOptionId", it) }
            // Fix 1 carry-through: attach the exact option object + full options
            // snapshot verbatim so the backend can verify the pick without
            // re-inferring scope from the prompt alone.
            selectedClarificationOptionFullTextJson
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { JSONObject(raw) }
                        .getOrNull()
                        ?.let { bodyJson.put("selectedClarificationOptionFullText", it) }
                }
            priorClarificationOptionsSnapshotJson
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { JSONArray(raw) }
                        .getOrNull()
                        ?.let { bodyJson.put("priorClarificationOptionsSnapshot", it) }
                }
            priorClarificationPromiseText
                ?.takeIf { it.isNotBlank() }
                ?.let { bodyJson.put("priorClarificationPromiseText", it) }
            val body = bodyJson.toString()
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }

            val code = connection.responseCode
            val responseText = (
                if (code in 200..299) connection.inputStream else connection.errorStream
            )?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                val errMsg = try {
                    JSONObject(responseText).optString("error").ifBlank { "HTTP $code" }
                } catch (_: Exception) {
                    "HTTP $code"
                }
                Log.w(TAG, "compile failure HTTP $code")
                throw PromiseCompilerException(errMsg, code)
            }

            val parsed = CompiledPromiseResponseParser.parse(responseText)
                ?: throw PromiseCompilerException("Invalid compile-promise response", code)
            Log.i(
                TAG,
                "compile success clarificationRequired=${parsed.clarificationRequired} " +
                    "canStart=${parsed.canStartCommitment} options=${parsed.clarificationOptions.size}"
            )
            return parsed
        } catch (err: PromiseCompilerException) {
            throw err
        } catch (err: Exception) {
            Log.w(TAG, "compile failure network: ${diagnosticErrorKind(err)}")
            throw PromiseCompilerException(err.message ?: "network error", cause = err)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "PromiseCompiler"
        const val CONNECT_TIMEOUT_MS = 3000
        const val READ_TIMEOUT_MS = 45_000
    }
}

class PromiseCompilerException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
