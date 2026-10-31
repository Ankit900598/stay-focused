package com.phonecodex.app.connector.gmail

import com.phonecodex.app.connector.AccessToken
import com.phonecodex.app.connector.ConnectorFailure
import com.phonecodex.app.connector.ConnectorResult
import com.phonecodex.app.connector.HttpRequest
import com.phonecodex.app.connector.HttpResponse
import com.phonecodex.app.connector.HttpTransport
import com.phonecodex.app.connector.TransportException
import com.phonecodex.app.domain.delegation.EmailAddress
import com.phonecodex.app.domain.delegation.OutgoingReply
import com.phonecodex.app.domain.delegation.SendOutcome
import com.phonecodex.app.domain.delegation.SendRecord
import com.phonecodex.app.domain.delegation.SendState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

class GmailRestConnectorTest {

    private val prof = EmailAddress.parseUserEntry("prof.sharma@iitd.ac.in")!!
    private val token = AccessToken("ya29.fake", setOf(GmailRestConnector.SCOPE_READONLY), "me@gmail.com")

    private class FakeTransport(val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun connector(t: HttpTransport) = GmailRestConnector(t, baseUrl = "https://gmail.test", io = Dispatchers.Unconfined)

    private fun meta(id: String, from: String, subject: String = "Assignment 3", auth: String? = null): String {
        val headers = JSONArray()
            .put(JSONObject().put("name", "From").put("value", from))
            .put(JSONObject().put("name", "Subject").put("value", subject))
            .put(JSONObject().put("name", "Message-ID").put("value", "<$id@mail.iitd.ac.in>"))
        auth?.let { headers.put(JSONObject().put("name", "Authentication-Results").put("value", it)) }
        return JSONObject()
            .put("id", id).put("threadId", "t-$id").put("internalDate", "1700000000000").put("snippet", "Please submit")
            .put("payload", JSONObject().put("headers", headers))
            .toString()
    }

    private fun full(id: String, from: String, body: String): String {
        val o = JSONObject(meta(id, from))
        o.getJSONObject("payload")
            .put("mimeType", "text/plain")
            .put("body", JSONObject().put("data", Base64.getUrlEncoder().encodeToString(body.toByteArray())))
        return o.toString()
    }

    private fun ok(body: String) = HttpResponse(200, body)
    private fun err(code: Int, reason: String = "") =
        HttpResponse(code, JSONObject().put("error", JSONObject().put("code", code).put("errors", JSONArray().put(JSONObject().put("reason", reason)))).toString())

    @Test
    fun list_keepsOnlyExactSender_andCountsLookalikes() = runBlocking {
        val t = FakeTransport { req ->
            when {
                "/messages?" in req.url -> ok("""{"messages":[{"id":"m1"},{"id":"m2"},{"id":"m3"},{"id":"m4"}]}""")
                "/messages/m1" in req.url -> ok(meta("m1", "Prof. Sharma <prof.sharma@iitd.ac.in>", auth = "mx.google.com; dkim=pass header.i=@iitd.ac.in; spf=pass smtp.mailfrom=iitd.ac.in"))
                "/messages/m2" in req.url -> ok(meta("m2", "prof.sharma@iitd.ac.in.evil.com"))
                "/messages/m3" in req.url -> ok(meta("m3", "\"prof.sharma@iitd.ac.in\" <attacker@evil.com>"))
                "/messages/m4" in req.url -> ok(meta("m4", "Prof <prof.sharma+hw@iitd.ac.in>"))
                else -> err(404)
            }
        }
        val r = connector(t).listFromExactSender(token, prof, 14, 10) as ConnectorResult.Ok
        assertEquals(listOf("m1"), r.value.items.map { it.id })
        assertEquals(3, r.value.droppedNotExactSender)
        assertEquals(true, r.value.items.single().senderAuthenticated)
        val q = URLDecoder.decode(t.requests.first().url.substringAfter("q="), "UTF-8")
        assertEquals("from:prof.sharma@iitd.ac.in newer_than:14d", q)
        assertTrue(t.requests.all { it.method == "GET" && it.headers["Authorization"] == "Bearer ya29.fake" })
    }

    @Test
    fun list_empty_isOkNotFailure() = runBlocking {
        val r = connector(FakeTransport { ok("{\"resultSizeEstimate\":0}") }).listFromExactSender(token, prof, 14, 10)
        assertEquals(0, (r as ConnectorResult.Ok).value.items.size)
    }

    @Test
    fun forgedAuthenticationResults_fromOtherServers_areIgnored() {
        assertNull(GmailMime.senderAuthenticated(listOf("evil.example; dmarc=pass"), "iitd.ac.in"))
        assertEquals(false, GmailMime.senderAuthenticated(listOf("mx.google.com; dkim=pass header.i=@evil.com; spf=fail"), "iitd.ac.in"))
        assertEquals(true, GmailMime.senderAuthenticated(listOf("mx.google.com; dmarc=pass (p=NONE) header.from=iitd.ac.in"), "iitd.ac.in"))
    }

    @Test
    fun failures_mapToTypedStates() = runBlocking {
        suspend fun kind(resp: () -> HttpResponse): ConnectorFailure =
            (connector(FakeTransport { resp() }).listFromExactSender(token, prof, 14, 10) as ConnectorResult.Failure).kind
        assertEquals(ConnectorFailure.AUTH_REVOKED, kind { err(401) })
        assertEquals(ConnectorFailure.INSUFFICIENT_SCOPE, kind { err(403, "insufficientPermissions") })
        assertEquals(ConnectorFailure.RATE_LIMITED, kind { err(403, "userRateLimitExceeded") })
        assertEquals(ConnectorFailure.RATE_LIMITED, kind { err(429) })
        assertEquals(ConnectorFailure.SERVER_ERROR, kind { err(503) })
        assertEquals(ConnectorFailure.MALFORMED, kind { ok("not json") })
        assertEquals(ConnectorFailure.OFFLINE, kind { throw TransportException(false, null) })
    }

    @Test
    fun verifyAccount_detectsWrongAccount() = runBlocking {
        val c = connector(FakeTransport { ok("""{"emailAddress":"Other@Gmail.com"}""") })
        assertEquals(ConnectorFailure.WRONG_ACCOUNT, (c.verifyAccount(token, "me@gmail.com") as ConnectorResult.Failure).kind)
        val c2 = connector(FakeTransport { ok("""{"emailAddress":"ME@gmail.com"}""") })
        assertTrue(c2.verifyAccount(token, "me@gmail.com") is ConnectorResult.Ok)
    }

    @Test
    fun readMessage_rechecksSender() = runBlocking {
        val c = connector(FakeTransport { ok(full("m9", "Someone <someone@else.com>", "hi")) })
        assertEquals(ConnectorFailure.SENDER_MISMATCH, (c.readMessage(token, "m9", prof) as ConnectorResult.Failure).kind)
        val c2 = connector(FakeTransport { ok(full("m1", "Prof <prof.sharma@iitd.ac.in>", "Submit by Friday")) })
        assertEquals("Submit by Friday", (c2.readMessage(token, "m1", prof) as ConnectorResult.Ok).value.bodyText)
    }

    @Test
    fun deeplyNestedMime_isTraversedWithoutRecursiveStackGrowth() {
        val body = JSONObject().put("data", Base64.getUrlEncoder().encodeToString("nested body".toByteArray()))
        var part = JSONObject().put("mimeType", "text/plain").put("body", body)
        repeat(2_000) {
            part = JSONObject().put("mimeType", "multipart/mixed").put("parts", JSONArray().put(part))
        }
        assertEquals("nested body", GmailMime.plainText(part))

        repeat(3_000) {
            part = JSONObject().put("mimeType", "multipart/mixed").put("parts", JSONArray().put(part))
        }
        assertEquals("", GmailMime.plainText(part))
    }

    private fun reply(subject: String = "Re: Assignment 3", draftId: String = "draft-1") =
        OutgoingReply(draftId, prof, subject, "Thanks, I will submit by Friday.", "t-m1", "<m1@mail.iitd.ac.in>", null)

    @Test
    fun send_outcomes_areHonest_andNeverRetried() = runBlocking {
        fun outcome(handler: (HttpRequest) -> HttpResponse): Pair<SendOutcome, Int> {
            val t = FakeTransport(handler)
            val o = runBlocking { connector(t).send(token, reply()) }
            return o to t.requests.size
        }
        assertEquals(SendOutcome.Sent("g1") to 1, outcome { ok("""{"id":"g1"}""") })
        assertEquals(SendOutcome.NotSent("offline") to 1, outcome { throw TransportException(false, null) })
        assertEquals(SendOutcome.Uncertain("no_response") to 1, outcome { throw TransportException(true, null) })
        assertEquals(SendOutcome.Uncertain("server_502") to 1, outcome { HttpResponse(502, "") })
        assertEquals(SendOutcome.NotSent("auth_revoked") to 1, outcome { err(401) })
        assertEquals(SendOutcome.NotSent("insufficient_scope") to 1, outcome { err(403, "insufficientPermissions") })
        assertEquals(SendOutcome.NotSent("rejected_400") to 1, outcome { err(400) })
    }

    @Test
    fun rawMessage_hasOnlyTheConfirmedRecipient_andNoHeaderInjection() = runBlocking {
        var sentBody: String? = null
        val t = FakeTransport { req -> sentBody = String(req.body!!); ok("""{"id":"g1"}""") }
        connector(t).send(token, reply(subject = "Re: Hi\r\nBcc: evil@attacker.com\r\nTo: evil@attacker.com", draftId = "draft-1\r\nBcc: x@y.z"))
        val raw = JSONObject(sentBody!!).getString("raw")
        val mime = String(Base64.getUrlDecoder().decode(raw))
        val headerBlock = mime.substringBefore("\r\n\r\n")
        val headerLines = headerBlock.split("\r\n")
        assertEquals(listOf("To: prof.sharma@iitd.ac.in"), headerLines.filter { it.startsWith("To:", ignoreCase = true) })
        assertFalse(headerLines.any { it.startsWith("Bcc:", ignoreCase = true) || it.startsWith("Cc:", ignoreCase = true) })
        assertTrue(headerLines.contains("X-Strlix-Draft-Id: draft-1Bccxyz"))
        assertTrue(headerLines.single { it.startsWith("Subject:") }.let { "\r" !in it && "\n" !in it })
        assertTrue(headerLines.contains("In-Reply-To: <m1@mail.iitd.ac.in>"))
        assertEquals("t-m1", JSONObject(sentBody!!).getString("threadId"))
    }

    @Test
    fun findSentCopy_matchesOnlyTheDraftIdHeader() = runBlocking {
        val record = SendRecord("draft-7", prof.value, 1_700_000_000_000L, SendState.UNCERTAIN)
        val t = FakeTransport { req ->
            when {
                "/messages?" in req.url -> ok("""{"messages":[{"id":"s1"},{"id":"s2"}]}""")
                "/messages/s1" in req.url -> ok("""{"payload":{"headers":[{"name":"X-Strlix-Draft-Id","value":"draft-6"}]}}""")
                "/messages/s2" in req.url -> ok("""{"payload":{"headers":[{"name":"X-Strlix-Draft-Id","value":"draft-7"}]}}""")
                else -> err(404)
            }
        }
        assertEquals("s2", (connector(t).findSentCopy(token, record) as ConnectorResult.Ok).value)
        val q = URLDecoder.decode(t.requests.first().url.substringAfter("q="), "UTF-8")
        assertTrue(q, q.startsWith("in:sent to:prof.sharma@iitd.ac.in after:"))
    }
}
