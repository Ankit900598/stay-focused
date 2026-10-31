package com.phonecodex.app.connector

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null
)

class HttpResponse(val code: Int, val body: String)

/**
 * [requestMayHaveBeenSent] is false only when the connection never opened, so the server
 * cannot have acted on the request. Anything later is treated as possibly delivered.
 */
class TransportException(val requestMayHaveBeenSent: Boolean, cause: Throwable?) : IOException(cause)

fun interface HttpTransport {
    @Throws(TransportException::class)
    fun execute(request: HttpRequest): HttpResponse
}

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000
) : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        val connection = try {
            (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                doOutput = request.body != null
                connect()
            }
        } catch (e: IOException) {
            throw TransportException(requestMayHaveBeenSent = false, cause = e)
        }
        try {
            request.body?.let { bytes -> connection.outputStream.use { it.write(bytes) } }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { input ->
                val bytes = input.readNBytesCompat(MAX_BODY_BYTES)
                String(bytes, Charsets.UTF_8)
            }.orEmpty()
            return HttpResponse(code, text)
        } catch (e: IOException) {
            throw TransportException(requestMayHaveBeenSent = true, cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}
