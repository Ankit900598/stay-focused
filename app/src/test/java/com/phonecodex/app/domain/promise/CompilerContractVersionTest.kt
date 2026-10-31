package com.phonecodex.app.domain.promise

import com.phonecodex.app.data.InMemorySharedPreferences
import com.phonecodex.app.data.StudyWorldSettingsStore
import com.phonecodex.app.domain.enforcement.CommitmentPolicyEvaluator
import com.phonecodex.app.domain.enforcement.CommitmentSnapshot
import com.phonecodex.app.domain.enforcement.LawVerdict
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.ui.home.LEGACY_CONTRACT_STATUS_LABEL
import com.phonecodex.app.ui.home.UnderstandingSource
import com.phonecodex.app.ui.home.buildPromiseUnderstanding
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2: the version that compiled a promise travels with it, a legacy (hosted v07)
 * response never starts a promise that needs typed scope, and what the card previews
 * is what gets stored and enforced.
 */
class CompilerContractVersionTest {

    private val v07 = "promise_compiler_v07"

    private fun legacyResponse(
        rawText: String,
        appRules: List<Triple<String, String, String>> = emptyList(),
        contentRules: List<JSONObject> = emptyList(),
        promptVersion: String? = v07,
        minutes: Int = 60
    ): String = JSONObject()
        .put("rawText", rawText)
        .put("sessionDurationMinutes", minutes)
        .put("strictness", "STRICT")
        .put("source", "azure_promise_compiler")
        .put("confidence", 0.9)
        .put("canStartCommitment", true)
        .put(
            "suggestedAppRules",
            JSONArray(appRules.map { (pkg, behavior, scope) ->
                JSONObject().put("packageName", pkg).put("label", pkg).put("behavior", behavior).put("scope", scope)
            })
        )
        .put("contentRules", JSONArray(contentRules))
        .apply {
            if (promptVersion != null) {
                put("meta", JSONObject().put("provider", "test").put("promptVersion", promptVersion))
            }
        }
        .toString()

    private fun draft(body: String): FocusPromise =
        CompiledPromiseMapper.toFocusPromise(CompiledPromiseResponseParser.parse(body)!!)

    private fun understanding(d: FocusPromise) =
        buildPromiseUnderstanding(d, source = UnderstandingSource.PROMISE_COMPILER)

    private fun law(settings: StudyWorldSettings, goal: String, pkg: String, text: String) =
        CommitmentPolicyEvaluator.evaluate(
            CommitmentSnapshot(
                packageName = pkg,
                screenText = text,
                sessionStatus = SessionStatus.ACTIVE,
                goal = goal,
                settings = settings,
                storedAppRule = null,
                isSafePackage = false
            ),
            ShortFormQuotaGate()
        )

    @Test
    fun metaPromptVersion_travelsFromResponseToStoredSettingsAndAudit() {
        val d = draft(legacyResponse("Block YouTube Shorts for 1 hour", listOf(Triple(VideoPlatformRegistry.YOUTUBE, "BLOCK", "shorts"))))
        assertEquals(v07, d.compilerPromptVersion)

        val store = StudyWorldSettingsStore(InMemorySharedPreferences())
        val bound = ConfirmedPromiseBinder.bind(d)
        store.replaceSettings(bound)
        val stored = store.getSettings()
        assertEquals(v07, stored.compilerPromptVersion)

        val audit = ConfirmedPromiseBinder.audit(d, stored).format()
        assertTrue(audit, "compiler=$v07 scopeContract=legacy" in audit)
        assertTrue(CommitmentPolicyEvaluator.policyVersion(stored).endsWith("scope=legacy compiler=$v07"))
    }

    @Test
    fun missingMeta_isRecordedAsUnknown_neverGuessed() {
        val d = draft(legacyResponse("Block YouTube Shorts for 1 hour", promptVersion = null))
        assertNull(d.compilerPromptVersion)
        val audit = ConfirmedPromiseBinder.audit(d, ConfirmedPromiseBinder.bind(d)).format()
        assertTrue(audit, "compiler=unknown" in audit)
    }

    @Test
    fun legacyResponse_forATypedOnlyPromise_keepsStartClosedWithReconnectCopy() {
        val cases = mapOf(
            "Block Instagram feed but allow DMs for 1 hour" to LegacyScopeContractGuard.SOCIAL_MESSAGING_SURFACE,
            "insta pe sirf feed band karo 1 ghante" to LegacyScopeContractGuard.SOCIAL_FEED_SURFACE,
            "Block YouTube only in Chrome for 2 hours" to LegacyScopeContractGuard.BROWSER_ONLY_HOST,
            "Block reddit.com for 1 hour" to LegacyScopeContractGuard.WEBSITE_SCOPE
        )
        for ((promise, reason) in cases) {
            val d = draft(legacyResponse(promise, listOf(Triple(VideoPlatformRegistry.INSTAGRAM, "BLOCK", ""))))
            assertEquals(promise, reason, d.legacyContractGap)
            assertTrue(promise, d.needsClarification)
            val u = understanding(d)
            assertFalse("$promise must not start (server said canStart=true)", u.canStart)
            assertEquals(LEGACY_CONTRACT_STATUS_LABEL, u.statusLabel)
            assertEquals(LegacyScopeContractGuard.START_BLOCKED_COPY, u.statusDetail)
        }
    }

    @Test
    fun legacyResponse_simpleRepresentablePromises_stillStart() {
        val simple = listOf(
            "Block YouTube Shorts for 1 hour" to listOf(Triple(VideoPlatformRegistry.YOUTUBE, "BLOCK", "shorts")),
            "Block Instagram for 2 hours" to listOf(Triple(VideoPlatformRegistry.INSTAGRAM, "BLOCK", "")),
            "Block Instagram reels for 1 hour" to listOf(Triple(VideoPlatformRegistry.INSTAGRAM, "BLOCK", "reels")),
            "Allow WhatsApp chats, block Instagram for 1 hour" to
                listOf(Triple(VideoPlatformRegistry.INSTAGRAM, "BLOCK", ""), Triple("com.whatsapp", "ALLOW", ""))
        )
        for ((promise, rules) in simple) {
            val d = draft(legacyResponse(promise, rules))
            assertNull(promise, d.legacyContractGap)
            assertTrue(promise, understanding(d).canStart)
        }
    }

    @Test
    fun typedV1Response_isNotSubjectToTheLegacyGuard() {
        val stream = javaClass.classLoader!!.getResourceAsStream("scope-contract/app_surface_ig_messages_only.json")!!
        val body = JSONObject(stream.bufferedReader().use { it.readText() }).getJSONObject("response").toString()
        val d = draft(body)
        assertTrue(d.scopeContractV1)
        assertNull(d.legacyContractGap)
        assertEquals("promise_compiler_v09", d.compilerPromptVersion)
        // Still closed, but for the v1 reason (messages surface unsupported), not a version gap.
        assertFalse(understanding(d).canStart)
        assertNotEquals(LEGACY_CONTRACT_STATUS_LABEL, understanding(d).statusLabel)
    }

    @Test
    fun pairedPreviewAndEnforcement_shortsOnlyLegacyPromise() {
        val goal = "Block YouTube Shorts for 1 hour"
        val d = draft(legacyResponse(goal, listOf(Triple(VideoPlatformRegistry.YOUTUBE, "BLOCK", "shorts"))))
        val u = understanding(d)
        assertTrue(u.blockedLabel, u.blockedLabel.contains("YouTube Shorts"))
        val stored = ConfirmedPromiseBinder.bind(d)

        val shorts = law(stored, goal, VideoPlatformRegistry.YOUTUBE, "Shorts Like Dislike Comment Share Remix Subscribe #shorts")
        assertEquals("preview says Shorts are blocked", LawVerdict.BLOCK, shorts.verdict)
        val home = law(stored, goal, VideoPlatformRegistry.YOUTUBE, "Home Subscriptions Library Explore Trending lecture 1:02:11")
        assertNotEquals("preview never says all of YouTube", LawVerdict.BLOCK, home.verdict)
    }

    @Test
    fun pairedPreviewAndEnforcement_minimumLengthKeepsItsMeaning() {
        val goal = "Only watch YouTube videos longer than 40 minutes for 2 hours"
        val rule = JSONObject()
            .put("appLabel", "YouTube").put("packageName", VideoPlatformRegistry.YOUTUBE)
            .put("surface", "any").put("contentType", "video")
            .put("operator", "lt").put("value", 40).put("unit", "minutes").put("action", "BLOCK")
        val d = draft(legacyResponse(goal, contentRules = listOf(rule), minutes = 120))
        val stored = ConfirmedPromiseBinder.bind(d)
        assertEquals("session clock is the session, not the media rule", 120, stored.durationMinutes)
        assertEquals("media rule is the media rule, not the session", 40, stored.minVideoLengthBlockMinutes)
        assertNull(stored.shortFormDailyQuotaLimit)

        val short = law(stored, goal, VideoPlatformRegistry.YOUTUBE, "Hide player controls Time duration 12 minutes Subscribe")
        assertEquals(LawVerdict.BLOCK, short.verdict)
        val long = law(stored, goal, VideoPlatformRegistry.YOUTUBE, "Hide player controls Time duration 1 hour 5 minutes Subscribe")
        assertNotEquals(LawVerdict.BLOCK, long.verdict)
    }

    @Test
    fun strictness_confirmedValueDrivesLockThreshold_andIsAudited() {
        val soft = draft(legacyResponse("Block Instagram for 1 hour", listOf(Triple(VideoPlatformRegistry.INSTAGRAM, "BLOCK", ""))))
            .copy(strictness = com.phonecodex.app.domain.model.StrictnessLevel.SOFT)
        val stored = ConfirmedPromiseBinder.bind(soft)
        assertEquals(understanding(soft).lockAttemptThreshold, stored.lockAttemptThreshold)
        val audit = ConfirmedPromiseBinder.audit(soft, stored).format()
        assertTrue(audit, "strictness=SOFT lockThreshold=${stored.lockAttemptThreshold}" in audit)
        assertNotNull(stored)
    }

    @Test
    fun explicitCompilerLockThreshold_isShownAndBoundInsteadOfStrictnessDefault() {
        val response = JSONObject(legacyResponse("Block Instagram and lock after 6 attempts", listOf(
            Triple(VideoPlatformRegistry.INSTAGRAM, "BLOCK", "")
        )))
            .put("lockAttemptThreshold", 6)
            .toString()
        val draft = draft(response)

        assertEquals(6, draft.lockAttemptThreshold)
        assertEquals(6, understanding(draft).lockAttemptThreshold)
        assertEquals(6, ConfirmedPromiseBinder.bind(draft).lockAttemptThreshold)
    }

    @Test
    fun invalidCompilerLockThreshold_keepsStartClosedWithActionableCopy() {
        val response = JSONObject(legacyResponse("Block Instagram and lock after 101 attempts", listOf(
            Triple(VideoPlatformRegistry.INSTAGRAM, "BLOCK", "")
        )))
            .put("lockAttemptThresholdInvalid", true)
            .put("clarificationRequired", true)
            .put("canStartCommitment", false)
            .toString()
        val draft = draft(response)
        val ui = understanding(draft)

        assertTrue(draft.lockAttemptThresholdInvalid)
        assertFalse(ui.canStart)
        assertTrue(ui.clarificationQuestion.orEmpty().contains("1 to 100"))
    }

    @Test
    fun customSavedLockThresholdAndCooldown_overrideAutomaticStrictnessFallback() {
        val saved = StudyWorldSettings(
            durationMinutes = 45,
            lockAttemptThreshold = 5,
            blockAttemptCooldownMinutes = 5,
            lockAttemptThresholdCustomized = true
        )
        val draft = draft(legacyResponse("Block Instagram for 1 hour", listOf(
            Triple(VideoPlatformRegistry.INSTAGRAM, "BLOCK", "")
        )))
        val ui = buildPromiseUnderstanding(
            draft,
            source = UnderstandingSource.PROMISE_COMPILER,
            fallbackLockAttemptThreshold = saved.lockAttemptThreshold
        )
        val bound = ConfirmedPromiseBinder.bind(draft, saved)

        assertEquals(5, ui.lockAttemptThreshold)
        assertEquals(5, bound.lockAttemptThreshold)
        assertEquals(5, bound.blockAttemptCooldownMinutes)
    }
}
