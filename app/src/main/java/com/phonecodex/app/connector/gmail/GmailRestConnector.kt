package com.phonecodex.app.connector.gmail

import com.phonecodex.app.connector.AccessToken
import com.phonecodex.app.connector.ConnectorFailure
import com.phonecodex.app.connector.ConnectorResult
import com.phonecodex.app.connector.HttpRequest
import com.phonecodex.app.connector.HttpResponse
import com.phonecodex.app.connector.HttpTransport
import com.phonecodex.app.connector.MailConnector
import com.phonecodex.app.connector.MailHeader
import com.phonecodex.app.connector.MailList
import com.phonecodex.app.connector.MailMessage
import com.phonecodex.app.connector.TransportException
import com.phonecodex.app.domain.delegation.EmailAddress
import com.phonecodex.app.domain.delegation.OutgoingReply
import com.phonecodex.app.domain.delegation.SendOutcome
import com.phonecodex.app.domain.delegation.SendRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Gmail REST v1 with two scopes only: gmail.readonly (list/read/reconcile) and gmail.send
 * (send). The access token stays on the phone; nothing here talks to Strlix's backend.
 */
class GmailRestConnector(
    private val transport: HttpTransport,
    private val baseUrl: String = "https://gmail.googleapis.com",
    private val io: CoroutineDispatcher = Dispatchers.IO
) : MailConnector {

    override suspend fun verifyAccount(token: AccessToken, expectedAccount: String): ConnectorResult<Unit> =
        withContext(io) {
            when (val r = get(token, "/gmail/v1/users/me/profile")) {
                is Http.Ok -> {
                    val email = r.json.optString("emailAddress").trim().lowercase()
                    if (email.isNotEmpty() && email == expectedAccount.trim().lowercase()) ConnectorResult.Ok(Unit)
                    else ConnectorResult.Failure(ConnectorFailure.WRONG_ACCOUNT)
                }
                is Http.Err -> ConnectorResult.Failure(r.kind)
            }
        }

    override suspend fun listFromExactSender(
        token: AccessToken,
        sender: EmailAddress,
        newerThanDays: Int,
        max: Int
    ): ConnectorResult<MailList> = withContext(io) {
        val q = "from:${sender.value} newer_than:${newerThanDays.coerceIn(1, 60)}d"
        val list = when (val r = get(token, "/gmail/v1/users/me/messages?maxResults=${max.coerceIn(1, 25)}&q=${enc(q)}")) {
            is Http.Ok -> r.json
            is Http.Err -> return@withContext ConnectorResult.Failure(r.kind)
        }
        val ids = list.optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        }.orEmpty()
        val items = mutableListOf<MailHeader>()
        var dropped = 0
        for (id in ids) {
            val path = "/gmail/v1/users/me/messages/${enc(id)}?format=metadata" +
                METADATA_HEADERS.joinToString("") { "&metadataHeaders=${enc(it)}" }
            when (val r = get(token, path)) {
                is Http.Ok -> {
                    val header = parseHeader(r.json)
                    if (header != null && header.from.matchesExactly(sender)) items += header else dropped++
                }
                is Http.Err -> if (r.kind == ConnectorFailure.NOT_FOUND) dropped++ else {
                    return@withContext ConnectorResult.Failure(r.kind)
                }
            }
        }
        ConnectorResult.Ok(MailList(items.sortedByDescending { it.internalDateMillis }, dropped))
    }

    override suspend fun readMessage(
        token: AccessToken,
        messageId: String,
        expectedSender: EmailAddress
    ): ConnectorResult<MailMessage> = withContext(io) {
        when (val r = get(token, "/gmail/v1/users/me/messages/${enc(messageId)}?format=full")) {
            is Http.Err -> ConnectorResult.Failure(r.kind)
            is Http.Ok -> {
                val header = parseHeader(r.json) ?: return@withContext ConnectorResult.Failure(ConnectorFailure.SENDER_MISMATCH)
                if (!header.from.matchesExactly(expectedSender)) {
                    return@withContext ConnectorResult.Failure(ConnectorFailure.SENDER_MISMATCH)
                }
                val text = GmailMime.plainText(r.json.optJSONObject("payload"))
                ConnectorResult.Ok(
                    MailMessage(
                        header = header,
                        bodyText = text.take(GmailMime.MAX_BODY_CHARS),
                        truncated = text.length > GmailMime.MAX_BODY_CHARS
                    )
                )
            }
        }
    }

    override suspend fun send(token: AccessToken, reply: OutgoingReply): SendOutcome = withContext(io) {
        val body = JSONObject().put("raw", GmailMime.buildRawReply(reply))
        reply.threadId?.takeIf { it.isNotBlank() }?.let { body.put("threadId", it) }
        val response = try {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = "$baseUrl/gmail/v1/users/me/messages/send",
                    headers = authHeaders(token) + ("Content-Type" to "application/json; charset=UTF-8"),
                    body = body.toString().toByteArray(Charsets.UTF_8)
                )
            )
        } catch (e: TransportException) {
            return@withContext if (e.requestMayHaveBeenSent) SendOutcome.Uncertain("no_response")
            else SendOutcome.NotSent("offline")
        }
        when {
            response.code in 200..299 -> SendOutcome.Sent(
                runCatching { JSONObject(response.body).optString("id").ifBlank { null } }.getOrNull()
            )
            response.code == 401 -> SendOutcome.NotSent("auth_revoked")
            response.code == 403 -> SendOutcome.NotSent(
                if (failureFor(response) == ConnectorFailure.RATE_LIMITED) "rate_limited" else "insufficient_scope"
            )
            response.code == 429 -> SendOutcome.NotSent("rate_limited")
            response.code in 400..499 -> SendOutcome.NotSent("rejected_${response.code}")
            else -> SendOutcome.Uncertain("server_${response.code}")
        }
    }

    override suspend fun findSentCopy(token: AccessToken, record: SendRecord): ConnectorResult<String?> =
        withContext(io) {
            val afterSec = (record.attemptedAtMillis / 1000) - 120
            val q = "in:sent to:${record.recipient} after:$afterSec"
            val list = when (val r = get(token, "/gmail/v1/users/me/messages?maxResults=10&q=${enc(q)}")) {
                is Http.Ok -> r.json
                is Http.Err -> return@withContext ConnectorResult.Failure(r.kind)
            }
            val ids = list.optJSONArray("messages")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            }.orEmpty()
            for (id in ids) {
                val path = "/gmail/v1/users/me/messages/${enc(id)}?format=metadata&metadataHeaders=${enc(GmailMime.DRAFT_ID_HEADER)}"
                when (val r = get(token, path)) {
                    is Http.Ok -> {
                        val draft = GmailMime.first(GmailMime.headerValues(r.json.optJSONObject("payload")), GmailMime.DRAFT_ID_HEADER)
                        if (draft?.trim() == record.draftId) return@withContext ConnectorResult.Ok(id)
                    }
                    is Http.Err -> if (r.kind != ConnectorFailure.NOT_FOUND) return@withContext ConnectorResult.Failure(r.kind)
                }
            }
            ConnectorResult.Ok(null)
        }

    private fun parseHeader(json: JSONObject): MailHeader? {
        val payload = json.optJSONObject("payload")
        val headers = GmailMime.headerValues(payload)
        val fromRaw = GmailMime.first(headers, "from")
        val from = EmailAddress.fromHeader(fromRaw) ?: return null
        return MailHeader(
            id = json.optString("id"),
            threadId = json.optString("threadId").ifBlank { null },
            from = from,
            fromDisplayName = GmailMime.displayName(fromRaw),
            subject = GmailMime.headerSafe(GmailMime.first(headers, "subject").orEmpty()).take(300),
            internalDateMillis = json.optString("internalDate").toLongOrNull() ?: 0L,
            snippet = GmailMime.decodeEntities(json.optString("snippet")).take(300),
            senderAuthenticated = GmailMime.senderAuthenticated(headers["authentication-results"].orEmpty(), from.domain),
            rfcMessageId = GmailMime.messageIdOrNull(GmailMime.first(headers, "message-id")),
            references = GmailMime.first(headers, "references")
        )
    }

    private sealed class Http {
        class Ok(val json: JSONObject) : Http()
        class Err(val kind: ConnectorFailure) : Http()
    }

    private fun get(token: AccessToken, path: String): Http {
        val response = try {
            transport.execute(HttpRequest(method = "GET", url = baseUrl + path, headers = authHeaders(token)))
        } catch (_: TransportException) {
            return Http.Err(ConnectorFailure.OFFLINE)
        }
        if (response.code in 200..299) {
            return runCatching { Http.Ok(JSONObject(response.body)) }.getOrElse { Http.Err(ConnectorFailure.MALFORMED) }
        }
        return Http.Err(failureFor(response))
    }

    private fun failureFor(response: HttpResponse): ConnectorFailure {
        val reason = runCatching {
            val err = JSONObject(response.body).optJSONObject("error")
            val first = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
            val status = err?.optString("status").orEmpty()
            val detail = err?.optJSONArray("details")?.optJSONObject(0)?.optString("reason").orEmpty()
            "$first $status $detail".lowercase()
        }.getOrDefault("")
        return when {
            response.code == 401 -> ConnectorFailure.AUTH_REVOKED
            response.code == 429 || "ratelimit" in reason || "rate_limit" in reason -> ConnectorFailure.RATE_LIMITED
            response.code == 403 -> ConnectorFailure.INSUFFICIENT_SCOPE
            response.code == 404 -> ConnectorFailure.NOT_FOUND
            response.code >= 500 -> ConnectorFailure.SERVER_ERROR
            else -> ConnectorFailure.MALFORMED
        }
    }

    private fun authHeaders(token: AccessToken) = mapOf(
        "Authorization" to "Bearer ${token.value}",
        "Accept" to "application/json"
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val SCOPE_SEND = "https://www.googleapis.com/auth/gmail.send"
        private val METADATA_HEADERS = listOf("From", "Subject", "Date", "Message-ID", "References", "Authentication-Results")
    }
}
