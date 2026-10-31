package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.ui.home.UnderstandingSource
import com.phonecodex.app.ui.home.buildPromiseUnderstanding
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Block the YouTube app for 30 minutes. YouTube Music is allowed." and the "YouTube app only"
 * pick for "Block YouTube for 30 minutes." (real v09 output, 2026-09-29) used to dead-end on
 * "What should Strlix block in YouTube?" with Start closed. Fixtures are written by
 * `node backend/smoke_v09_clarification_scope.js --write-fixtures`.
 */
class YouTubeAppBlockRoundTripTest {

    private val youtube = "com.google.android.youtube"
    private val youtubeMusic = "com.google.android.apps.youtube.music"

    private fun draft(name: String): Pair<String, FocusPromise> {
        val stream = javaClass.classLoader!!.getResourceAsStream("scope-contract/$name.json")
        assertNotNull("missing fixture $name", stream)
        val root = JSONObject(stream!!.bufferedReader().use { it.readText() })
        val parsed = CompiledPromiseResponseParser.parse(root.getJSONObject("response").toString())
        assertNotNull("$name: parse failed", parsed)
        return root.getString("promise") to CompiledPromiseMapper.toFocusPromise(parsed!!)
    }

    private fun assertYouTubeAppBlockStartsOnlyAfterConfirm(name: String, selectedId: String?) {
        val (promise, draft) = draft(name)
        val lawDraft = PromiseClarificationLaw.apply(draft, selectedId)
        assertTrue("$name: v1 contract", lawDraft.scopeContractV1)
        assertTrue("$name: no unrepresentable scope", lawDraft.unrepresentableAppScopes.isEmpty())
        assertNull("$name: WHERE and WHAT agree", EffectivePromisePolicy.conflictReason(lawDraft))

        fun card(ack: Boolean) = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = selectedId,
            strongerConfirmAcknowledged = ack
        )
        val unconfirmed = card(ack = false)
        assertNull("$name: no redundant question", unconfirmed.clarificationQuestion)
        assertTrue("$name: no option list left to pick", unconfirmed.clarificationOptions.isEmpty())
        if (unconfirmed.requiresStrongerConfirmation) {
            assertFalse("$name: Start waits for the explicit confirm", unconfirmed.canStart)
        }
        assertTrue("$name: Start opens after the explicit confirm", card(ack = true).canStart)
        assertTrue("$name: card names the YouTube block", unconfirmed.blockedLabel.contains("YouTube"))

        val bound = ConfirmedPromiseBinder.bind(lawDraft)
        assertEquals("$name: saved scope", ScopeKind.APP_ONLY, bound.enforcementScope?.kind)
        val rules = lawDraft.suggestedAppRules.associateBy { it.packageName }
        assertEquals("$name ($promise): YouTube app blocked", AppRuleBehavior.BLOCK, rules[youtube]?.behavior)
        assertTrue(
            "$name: YouTube Music never blocked",
            rules[youtubeMusic] == null || rules[youtubeMusic]?.behavior != AppRuleBehavior.BLOCK
        )
    }

    @Test
    fun exactPromise_blocksYouTubeApp_keepsYouTubeMusic_noRedundantQuestion() {
        assertYouTubeAppBlockStartsOnlyAfterConfirm("v09_yt_app_music_exact", selectedId = null)
    }

    @Test
    fun youTubeAppOnlyPick_roundTrips_toTheSavedAppBlock() {
        assertYouTubeAppBlockStartsOnlyAfterConfirm("v09_yt_pick_a_app_only", selectedId = "A")
    }

    @Test
    fun freeTextWholeAppScopes_areWholeApp_onlyWithoutNarrowing() {
        for (scope in listOf("Entire native app only", "Entire native app", "Entire app", "the entire YouTube app")) {
            assertEquals(scope, EffectivePromisePolicy.AppScope.WHOLE_APP, EffectivePromisePolicy.classifyAppScope(scope))
        }
        for (scope in listOf(
            "Entire app except search",
            "not the entire app",
            "entire app but keep music",
            "whole app home feed",
            "all of the app, including Shorts"
        )) {
            assertEquals(scope, EffectivePromisePolicy.AppScope.UNKNOWN, EffectivePromisePolicy.classifyAppScope(scope))
        }
    }
}
