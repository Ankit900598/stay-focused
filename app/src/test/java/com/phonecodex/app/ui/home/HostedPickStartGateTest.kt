package com.phonecodex.app.ui.home

import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.promise.CompiledPromiseMapper
import com.phonecodex.app.domain.promise.CompiledPromiseResponseParser
import com.phonecodex.app.domain.promise.PromiseClarificationLaw
import com.phonecodex.app.domain.scope.ScopeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real hosted v09 response (staging 37ac85c, 2026-09-27): "Block YouTube Shorts for 1 hour",
 * option A picked. The server echoes the options and keeps canStart=false because the model
 * asked for a stronger confirm. Phone 2026-09-28: the card showed Start after the checkbox,
 * but the Start tap refused with "I still need a clearer promise".
 */
class HostedPickStartGateTest {

    private fun draft(): FocusPromise {
        val json = javaClass.classLoader!!
            .getResourceAsStream("hosted/v09_block_shorts_pick_a_staging.json")!!
            .bufferedReader().use { it.readText() }
        val parsed = CompiledPromiseResponseParser.parse(json)
        assertNotNull(parsed)
        return PromiseClarificationLaw.apply(CompiledPromiseMapper.toFocusPromise(parsed!!), "A")
    }

    @Test
    fun hostedPick_isResolvedButNeedsStrongerConfirm() {
        val d = draft()
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, d.enforcementScope?.kind)
        assertFalse(d.clarificationRequired)
        assertFalse(d.typedScopeBlocksStart)
        assertFalse(d.appRuleScopeConflict)
        assertTrue(d.requiresStrongerConfirmation)
        assertTrue(d.clarificationOptions.isNotEmpty())
    }

    @Test
    fun startGate_agreesWithCard_beforeAndAfterStrongerConfirm() {
        val d = draft()
        val before = buildPromiseUnderstanding(d, source = UnderstandingSource.PROMISE_COMPILER, selectedClarificationOptionId = "A")
        assertFalse("card: Start closed until the box is ticked", before.canStart)
        assertTrue("gate: closed until the box is ticked", d.needsClarification(strongerConfirmAcknowledged = false))

        val after = buildPromiseUnderstanding(
            d,
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = "A",
            strongerConfirmAcknowledged = true
        )
        assertTrue("card: Start open after the box", after.canStart)
        assertFalse("gate: must agree with the card", d.needsClarification(strongerConfirmAcknowledged = true))
    }

    @Test
    fun strongerConfirm_neverUnlocksUnresolvedScopeOrUnansweredQuestion() {
        val d = draft()
        assertTrue(d.copy(clarificationRequired = true).needsClarification(strongerConfirmAcknowledged = true))
        assertTrue(d.copy(enforcementScope = null).needsClarification(strongerConfirmAcknowledged = true))
        assertTrue(d.copy(choiceVerificationFailed = true).needsClarification(strongerConfirmAcknowledged = true))
        assertTrue(d.copy(requiresStrongerConfirmation = false).needsClarification(strongerConfirmAcknowledged = true))
    }
}
