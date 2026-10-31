package com.phonecodex.app.mailtask

import com.phonecodex.app.connector.HttpRequest
import com.phonecodex.app.connector.HttpResponse
import com.phonecodex.app.connector.HttpTransport
import com.phonecodex.app.connector.MailHeader
import com.phonecodex.app.connector.MailMessage
import com.phonecodex.app.connector.TransportException
import com.phonecodex.app.domain.delegation.EmailAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendMailAssistantTest {

    private val message = MailMessage(
        MailHeader("m1", "t1", EmailAddress.parseUserEntry("prof.sharma@iitd.ac.in")!!, "Prof", "Assignment 3", 0L, "", true, null, null),
        "Please submit by Friday.",
        false
    )

    private fun assistant(handler: (HttpRequest) -> HttpResponse): Pair<BackendMailAssistant, MutableList<HttpRequest>> {
        val seen = mutableListOf<HttpRequest>()
        val t = HttpTransport { req -> seen += req; handler(req) }
        return BackendMailAssistant("https://api.test/", "app-key", t, Dispatchers.Unconfined) to seen
    }

    @Test
    fun summarize_sendsOneMessageWithIdentityAndConsent_parsesWhitelistedFields() = runBlocking {
        val (a, seen) = assistant {
            HttpResponse(200, """{"summary":"Submit by Friday.","keyPoints":["Due Friday"],"requestedActions":["Submit"],"deadlines":["Friday"],"containsInstructionsToAssistant":true,"send":true}""")
        }
        val r = a.summarize("google-id-token", message) as AssistResult.Ok
        assertEquals("Submit by Friday.", r.value.summary)
        assertTrue(r.value.containsInstructionsToAssistant)
        val req = seen.single()
        assertEquals("https://api.test/mail/summarize", req.url)
        assertEquals("Bearer google-id-token", req.headers["Authorization"])
        assertEquals("app-key", req.headers["X-Strlix-App-Secret"])
        val body = JSONObject(String(req.body!!))
        assertEquals("mail_ai_v1", body.getString("consent"))
        assertEquals("Please submit by Friday.", body.getJSONObject("message").getString("bodyText"))
        assertFalse("no Gmail token ever goes to Strlix", String(req.body!!).contains("ya29"))
    }

    @Test
    fun statusCodes_mapToHonestFailures() = runBlocking {
        fun kind(code: Int, body: String = "{}"): AssistFailure =
            (runBlocking { assistant { HttpResponse(code, body) }.first.summarize("t", message) } as AssistResult.Failure).kind
        assertEquals(AssistFailure.NOT_SIGNED_IN, kind(401))
        assertEquals(AssistFailure.CONSENT_REQUIRED, kind(400, """{"error":"consent_required"}"""))
        assertEquals(AssistFailure.FAILED, kind(400, """{"error":"message_too_long"}"""))
        assertEquals(AssistFailure.UNAVAILABLE, kind(404))
        assertEquals(AssistFailure.UNAVAILABLE, kind(503))
        assertEquals(AssistFailure.RATE_LIMITED, kind(429))
        assertEquals(AssistFailure.FAILED, kind(500))
        assertEquals(AssistFailure.FAILED, kind(200, """{"summary":""}"""))
        val offline = assistant { throw TransportException(false, null) }.first.summarize("t", message)
        assertEquals(AssistFailure.OFFLINE, (offline as AssistResult.Failure).kind)
    }

    @Test
    fun draft_returnsBodyOnly() = runBlocking {
        val (a, seen) = assistant { HttpResponse(200, """{"body":"Thanks, will do.","to":"attacker@evil.com"}""") }
        assertEquals("Thanks, will do.", (a.draftReply("t", message, "say yes") as AssistResult.Ok).value)
        assertEquals("say yes", JSONObject(String(seen.single().body!!)).getString("userNote"))
    }
}
