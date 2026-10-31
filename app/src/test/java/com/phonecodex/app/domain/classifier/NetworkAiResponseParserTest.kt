package com.phonecodex.app.domain.classifier

import com.phonecodex.app.domain.model.DecisionType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAiResponseParserTest {

    @Test
    fun parse_fullBackendResponse_mapsAllFields() {
        val raw = """
            {
              "decision": "WARN",
              "confidence": 0.82,
              "reason": "Incomplete screen during focus session.",
              "reasonCategory": "ambiguous",
              "would_escalate": true,
              "meta": {
                "provider": "azure",
                "deployment": "pc-lab-cheap",
                "promptVersion": "v04",
                "latencyMs": 1842
              }
            }
        """.trimIndent()

        val result = NetworkAiResponseParser.parse(raw)

        assertNotNull(result)
        assertEquals(DecisionType.WARN, result!!.decision)
        assertEquals(0.82, result.confidence, 0.001)
        assertEquals("ambiguous", result.reasonCategory)
        assertTrue(result.wouldEscalate)
        assertEquals("azure", result.backendMeta?.provider)
        assertEquals("pc-lab-cheap", result.backendMeta?.deployment)
        assertEquals("v04", result.backendMeta?.promptVersion)
        assertEquals(1842L, result.backendMeta?.latencyMs)
        assertEquals(AiConfidenceGate.NETWORK_AI_SOURCE, result.source)
        assertEquals(false, result.usedImage)
    }

    @Test
    fun parse_usedImage_isMapped() {
        val raw = """{"decision":"WARN","confidence":0.9,"reason":"blank ott","reasonCategory":"likely_movie","usedImage":true}"""
        val result = NetworkAiResponseParser.parse(raw)
        assertEquals(true, result?.usedImage)
        assertEquals("likely_movie", result?.reasonCategory)
    }

    @Test
    fun parse_whatOnScreen_isMapped() {
        val raw = """{"decision":"WARN","confidence":0.8,"reason":"ott","reasonCategory":"likely_movie","usedImage":true,"whatOnScreen":"NetMirror movie player, no clock"}"""
        val result = NetworkAiResponseParser.parse(raw)
        assertEquals("NetMirror movie player, no clock", result?.whatOnScreen)
    }

    @Test
    fun parse_lockDecision_isSupported() {
        val raw = """{"decision":"LOCK","confidence":1.0,"reason":"Tamper","reasonCategory":"tamper_attempt"}"""

        val result = NetworkAiResponseParser.parse(raw)

        assertEquals(DecisionType.LOCK, result?.decision)
    }

    @Test
    fun parse_invalidDecision_returnsNull() {
        val raw = """{"decision":"MAYBE","confidence":0.5,"reason":"nope"}"""

        assertNull(NetworkAiResponseParser.parse(raw))
    }

    @Test
    fun parse_invalidConfidence_returnsNullBeforeItCanAuthorizeABlock() {
        for (confidence in listOf("2", "-0.01", "\"0.9\"", "null")) {
            val raw = """{"decision":"BLOCK","confidence":$confidence,"reason":"model guess"}"""
            assertNull(NetworkAiResponseParser.parse(raw))
        }
    }

    @Test
    fun parse_verificationIncomplete_isRetainedAsAuthoritativeGateMetadata() {
        val parsed = NetworkAiResponseParser.parse(
            """{"decision":"WARN","confidence":0.2,"verificationIncomplete":true}"""
        )

        assertNotNull(parsed)
        assertTrue(parsed!!.verificationIncomplete)
    }

    @Test
    fun parse_andGate_incompleteBackendVerificationCannotBecomeAllowOrBlock() {
        val cases = listOf(
            """{"decision":"WARN","confidence":0.4,"verificationIncomplete":true}""",
            """{"decision":"BLOCK","confidence":0.95,"verificationIncomplete":true}"""
        )

        cases.forEach { raw ->
            val parsed = NetworkAiResponseParser.parse(raw)
            assertNotNull(parsed)

            val gated = AiConfidenceGate().apply(parsed!!)

            assertEquals(DecisionType.WARN, gated.decision)
            assertEquals("Could not verify this screen safely", gated.reason)
        }
    }

    @Test
    fun parse_unknownTextCategoryCannotPreserveConfidentBlock() {
        val parsed = NetworkAiResponseParser.parse(
            """{"decision":"BLOCK","confidence":0.99,"reason":"model guess","reasonCategory":"shortformdisallowed"}"""
        )

        assertNotNull(parsed)
        assertEquals(DecisionType.WARN, parsed!!.decision)
        assertEquals("ambiguous", parsed.reasonCategory)
        assertTrue(parsed.verificationIncomplete)
        assertTrue(parsed.wouldEscalate)
    }

    @Test
    fun parse_unknownVisionCategoryCannotPreserveConfidentBlock() {
        val parsed = NetworkAiResponseParser.parse(
            """{"decision":"BLOCK","confidence":0.99,"reason":"model guess","reasonCategory":"adult_content","usedImage":true}"""
        )

        assertNotNull(parsed)
        assertEquals(DecisionType.WARN, parsed!!.decision)
        assertEquals("unknown", parsed.reasonCategory)
        assertTrue(parsed.verificationIncomplete)
        assertTrue(parsed.wouldEscalate)
    }

    @Test
    fun parse_missingCategoryCannotPreserveConfidentBlock() {
        val parsed = NetworkAiResponseParser.parse(
            """{"decision":"BLOCK","confidence":0.99,"reason":"model guess"}"""
        )

        assertNotNull(parsed)
        assertEquals(DecisionType.WARN, parsed!!.decision)
        assertEquals("ambiguous", parsed.reasonCategory)
        assertTrue(parsed.verificationIncomplete)
    }

    @Test
    fun buildRequestBody_includesOptionalFields() {
        val classifier = NetworkAiContentClassifier()
        val request = ClassificationRequest(
            packageName = "com.google.android.youtube",
            appLabel = "YouTube",
            screenText = "Shorts",
            goal = "Monk mode",
            strictnessLevel = "STRICT",
            activeGuardrails = listOf("no_adult_content"),
            commitmentType = "monk_mode",
            sessionCounters = mapOf("attemptCount" to 2),
            limitState = "n/a"
        )

        val json = JSONObject(classifier.buildRequestBody(request))

        assertEquals("com.google.android.youtube", json.getString("packageName"))
        assertEquals("YouTube", json.getString("appLabel"))
        assertEquals("STRICT", json.getString("strictnessLevel"))
        assertEquals("monk_mode", json.getString("commitmentType"))
        assertEquals(2, json.getJSONObject("sessionCounters").getInt("attemptCount"))
        assertEquals(1, json.getJSONArray("activeGuardrails").length())
        assertFalse(json.has("imageJpegBase64"))
    }

    @Test
    fun buildRequestBody_includesImageWithoutLoggingRequirement() {
        val classifier = NetworkAiContentClassifier()
        val request = ClassificationRequest(
            packageName = "app.netmirror.newtv",
            appLabel = "NetMirror",
            screenText = "Play",
            goal = "no shorts",
            strictnessLevel = "STRICT",
            activeGuardrails = emptyList(),
            commitmentType = "focus_session",
            sessionCounters = null,
            limitState = "n/a",
            imageJpegBase64 = "dGVzdA=="
        )
        val json = JSONObject(classifier.buildRequestBody(request))
        assertEquals("dGVzdA==", json.getString("imageJpegBase64"))
    }
}
