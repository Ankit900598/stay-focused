package com.phonecodex.app.mailtask

import com.phonecodex.app.connector.HttpRequest
import com.phonecodex.app.connector.HttpTransport
import com.phonecodex.app.connector.MailMessage
import com.phonecodex.app.connector.TransportException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sends ONE user-selected message to Strlix's backend for a summary or a reply draft.
 * Identity: the user's Google ID token (per-user), plus the app key header. The Gmail access
 * token never leaves the phone. Nothing here logs message content.
 */
class BackendMailAssistant(
    private val baseUrl: String,
    private val appSecret: String,
    private val transport: HttpTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : MailAssistant {

    override suspend fun summarize(identityToken: String, message: MailMessage): AssistResult<MailSummary> =
        post("/mail/summarize", identityToken, body(message)) { json ->
            val summary = json.optString("summary").trim()
            if (summary.isEmpty()) null
            else MailSummary(
                summary = summary,
                keyPoints = json.optJSONArray("keyPoints").strings(),
                requestedActions = json.optJSONArray("requestedActions").strings(),
                deadlines = json.optJSONArray("deadlines").strings(),
                containsInstructionsToAssistant = json.optBoolean("containsInstructionsToAssistant", false)
            )
        }

    override suspend fun draftReply(identityToken: String, message: MailMessage, userNote: String): AssistResult<String> =
        post("/mail/draft-reply", identityToken, body(message).put("userNote", userNote.take(MAX_NOTE_CHARS))) { json ->
            json.optString("body").trim().ifEmpty { null }
        }

    private fun body(message: MailMessage) = JSONObject()
        .put("consent", CONSENT_VERSION)
        .put(
            "message",
            JSONObject()
                .put("fromAddress", message.header.from.value)
                .put("subject", message.header.subject.take(MAX_SUBJECT_CHARS))
                .put("bodyText", message.bodyText.take(MAX_BODY_CHARS))
        )

    private suspend fun <T> post(
        path: String,
        identityToken: String,
        payload: JSONObject,
        parse: (JSONObject) -> T?
    ): AssistResult<T> = withContext(io) {
        if (baseUrl.isBlank()) return@withContext AssistResult.Failure(AssistFailure.UNAVAILABLE)
        val headers = buildMap {
            put("Content-Type", "application/json; charset=utf-8")
            put("Accept", "application/json")
            put("Authorization", "Bearer $identityToken")
            if (appSecret.isNotBlank()) put(APP_SECRET_HEADER, appSecret)
        }
        val response = try {
            transport.execute(HttpRequest("POST", baseUrl.trimEnd('/') + path, headers, payload.toString().toByteArray(Charsets.UTF_8)))
        } catch (e: TransportException) {
            return@withContext AssistResult.Failure(if (e.requestMayHaveBeenSent) AssistFailure.FAILED else AssistFailure.OFFLINE)
        }
        when (response.code) {
            in 200..299 -> runCatching { parse(JSONObject(response.body)) }.getOrNull()
                ?.let { AssistResult.Ok(it) }
                ?: AssistResult.Failure(AssistFailure.FAILED)
            401 -> AssistResult.Failure(AssistFailure.NOT_SIGNED_IN)
            400 -> AssistResult.Failure(
                if (errorCode(response.body) == "consent_required") AssistFailure.CONSENT_REQUIRED else AssistFailure.FAILED
            )
            404, 503 -> AssistResult.Failure(AssistFailure.UNAVAILABLE)
            429 -> AssistResult.Failure(AssistFailure.RATE_LIMITED)
            else -> AssistResult.Failure(AssistFailure.FAILED)
        }
    }

    private fun errorCode(body: String): String = runCatching { JSONObject(body).optString("error") }.getOrDefault("")

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotEmpty) }.take(5)
    }

    companion object {
        const val CONSENT_VERSION = "mail_ai_v1"
        const val APP_SECRET_HEADER = "X-Strlix-App-Secret"
        const val MAX_BODY_CHARS = 12_000
        const val MAX_SUBJECT_CHARS = 300
        const val MAX_NOTE_CHARS = 600
    }
}
