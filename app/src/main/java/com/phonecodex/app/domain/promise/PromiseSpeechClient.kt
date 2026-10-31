package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.diagnostics.BackendConfig
import com.phonecodex.app.domain.diagnostics.BackendHealthProbe
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Locale

internal data class PromiseSpeechTranscript(
    val transcript: String,
    val confidence: Double?,
    val language: String,
    val provider: String
)

internal class PromiseSpeechClient(
    private val baseUrl: String = BackendHealthProbe.BACKEND_BASE_URL,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) {
    fun transcribe(wavBytes: ByteArray, language: String = Locale.getDefault().toLanguageTag()): PromiseSpeechTranscript {
        require(wavBytes.isNotEmpty()) { "Audio is empty" }
        require(wavBytes.size <= MAX_WAV_BYTES) { "Audio exceeds the voice clip limit" }

        val url = URL("${baseUrl.trimEnd('/')}/transcribe-promise")
        val connection = connectionFactory(url).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            BackendConfig.applyAuth(this)
        }

        try {
            val body = JSONObject()
                .put("audioBase64", Base64.getEncoder().encodeToString(wavBytes))
                .put("audioFormat", "wav")
                .put("language", normalizeLanguage(language))
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val statusCode = connection.responseCode
            val responseText = (
                if (statusCode in 200..299) connection.inputStream else connection.errorStream
            )?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()

            if (statusCode !in 200..299) {
                throw PromiseSpeechException("Speech service returned HTTP $statusCode", statusCode)
            }

            return parseTranscriptResponse(responseText)
        } catch (error: PromiseSpeechException) {
            throw error
        } catch (error: Exception) {
            throw PromiseSpeechException("Speech request failed", cause = error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_RECORDING_SECONDS = 15
        const val MAX_WAV_BYTES = SAMPLE_RATE_HZ * 2 * MAX_RECORDING_SECONDS + 44
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 20_000
        const val DEFAULT_LANGUAGE = "en-IN"

        private fun normalizeLanguage(language: String): String =
            language.trim().takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) }
                ?: DEFAULT_LANGUAGE

        internal fun parseTranscriptResponse(responseText: String): PromiseSpeechTranscript {
            val json = try {
                JSONObject(responseText)
            } catch (error: Exception) {
                throw PromiseSpeechException("Speech service returned an invalid response", cause = error)
            }
            val transcript = json.optString("transcript").trim()
            if (json.optBoolean("ok", false).not() || transcript.isEmpty()) {
                throw PromiseSpeechException("Speech service returned no transcript")
            }
            val confidence = json.optDouble("confidence").takeIf { it.isFinite() && it in 0.0..1.0 }
            return PromiseSpeechTranscript(
                transcript = transcript,
                confidence = confidence,
                language = json.optString("language").ifBlank { DEFAULT_LANGUAGE },
                provider = json.optString("provider").ifBlank { "unknown" }
            )
        }
    }
}

internal class PromiseSpeechException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : IOException(message, cause)
