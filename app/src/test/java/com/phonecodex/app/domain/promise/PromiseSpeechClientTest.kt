package com.phonecodex.app.domain.promise

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class PromiseSpeechClientTest {

    @Test
    fun sendsWavToTranscriptionRouteAndParsesTranscript() {
        val wav = byteArrayOf(1, 2, 3, 4)
        lateinit var connection: FakeHttpURLConnection
        val client = PromiseSpeechClient("https://backend.example") { url ->
            FakeHttpURLConnection(url, 200, successBody()).also { connection = it }
        }

        val result = client.transcribe(wav, "ta-IN")

        assertEquals("https://backend.example/transcribe-promise", connection.url.toString())
        val request = JSONObject(connection.requestBody.toString(Charsets.UTF_8.name()))
        assertEquals("wav", request.getString("audioFormat"))
        assertEquals("ta-IN", request.getString("language"))
        assertTrue(wav.contentEquals(Base64.getDecoder().decode(request.getString("audioBase64"))))
        assertEquals("Allow only my study playlist", result.transcript)
        assertEquals(0.94, result.confidence!!, 0.0001)
        assertEquals("ta-IN", result.language)
        assertEquals("azure_speech_fast", result.provider)
        assertTrue(connection.disconnected)
    }

    @Test
    fun rejectsEmptyOrOversizedAudioBeforeOpeningConnection() {
        var connectionOpened = false
        val client = PromiseSpeechClient("https://backend.example") { url ->
            connectionOpened = true
            FakeHttpURLConnection(url, 200, successBody())
        }

        assertThrows(IllegalArgumentException::class.java) { client.transcribe(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            client.transcribe(ByteArray(PromiseSpeechClient.MAX_WAV_BYTES + 1))
        }
        assertFalse(connectionOpened)
    }

    @Test
    fun rejectsServerErrorsAndEmptyTranscriptResponses() {
        val unavailable = PromiseSpeechClient("https://backend.example") { url ->
            FakeHttpURLConnection(url, 503, "{}")
        }
        val unavailableError = assertThrows(PromiseSpeechException::class.java) {
            unavailable.transcribe(byteArrayOf(1))
        }
        assertEquals(503, unavailableError.httpStatus)

        assertThrows(PromiseSpeechException::class.java) {
            PromiseSpeechClient.parseTranscriptResponse("{\"ok\":true,\"transcript\":\" \"}")
        }
        assertThrows(PromiseSpeechException::class.java) {
            PromiseSpeechClient.parseTranscriptResponse("not-json")
        }
    }

    private fun successBody(): String =
        """{"ok":true,"transcript":"Allow only my study playlist","confidence":0.94,"language":"ta-IN","provider":"azure_speech_fast"}"""

    private class FakeHttpURLConnection(
        url: URL,
        private val status: Int,
        private val response: String
    ) : HttpURLConnection(url) {
        val requestBody = ByteArrayOutputStream()
        var disconnected = false
            private set

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): OutputStream = requestBody

        override fun getInputStream(): InputStream = ByteArrayInputStream(response.toByteArray())

        override fun getErrorStream(): InputStream? = ByteArrayInputStream(response.toByteArray())

        override fun getResponseCode(): Int = status
    }
}
