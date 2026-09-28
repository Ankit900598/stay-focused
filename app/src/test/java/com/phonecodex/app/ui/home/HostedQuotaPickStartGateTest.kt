package com.phonecodex.app.ui.home

import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.promise.CompiledPromiseMapper
import com.phonecodex.app.domain.promise.CompiledPromiseResponseParser
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.EffectivePromisePolicy
import com.phonecodex.app.domain.promise.PromiseClarificationLaw
import com.phonecodex.app.domain.scope.ScopeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real hosted v09 response (staging 8ed37e4, 2026-09-28): "Strict: only 2 YouTube Shorts today",
 * option A (YouTube short videos only) picked. The server carries the daily count as short-form
 * rules on the YouTube Shorts surface. Phone 2026-09-28: the card invented "Should I block all of
 * YouTube, or only part of it?" and Start stayed off even after the confirm box.
 */
class HostedQuotaPickStartGateTest {

    private val yt = "com.google.android.youtube"

    private fun draft(): FocusPromise {
        val json = javaClass.classLoader!!
            .getResourceAsStream("hosted/v09_quota_two_shorts_pick_a_staging.json")!!
            .bufferedReader().use { it.readText() }
        val parsed = CompiledPromiseResponseParser.parse(json)
        assertNotNull(parsed)
        return PromiseClarificationLaw.apply(CompiledPromiseMapper.toFocusPromise(parsed!!), "A")
    }

    @Test
    fun shortsCountRules_areInsideTheShortsOnlyScope() {
        val d = draft()
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, d.enforcementScope?.kind)
        assertNull(EffectivePromisePolicy.conflictReason(d))
        assertFalse(d.appRuleScopeConflict)
        assertFalse(d.clarificationRequired)
    }

    @Test
    fun startOpensAfterStrongerConfirm_andCardAgrees() {
        val d = draft()
        assertTrue(d.needsClarification(strongerConfirmAcknowledged = false))
        assertFalse(d.needsClarification(strongerConfirmAcknowledged = true))
        val card = buildPromiseUnderstanding(
            d,
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = "A",
            strongerConfirmAcknowledged = true
        )
        assertTrue(card.canStart)
    }

    @Test
    fun boundPolicy_isATwoShortDailyCount_notAStandingBlock() {
        val bound = ConfirmedPromiseBinder.bind(draft())
        assertEquals(2, bound.shortFormDailyQuotaLimit)
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, bound.enforcementScope?.kind)
        assertTrue(bound.blockedSurfaces.isEmpty())
    }

    @Test
    fun nonShortFormRuleInTheSameApp_isStillOutsideTheShortsOnlyScope() {
        val d = draft()
        val longVideoCap = ContentRule(
            appLabel = "YouTube",
            packageName = yt,
            surface = "video",
            contentType = "long_form_video",
            operator = "gt",
            value = 20,
            unit = "minutes",
            action = ContentRuleAction.BLOCK
        )
        assertEquals(
            EffectivePromisePolicy.APP_SCOPE_MISSES_RULES,
            EffectivePromisePolicy.conflictReason(d.copy(contentRules = d.contentRules + longVideoCap))
        )
        val shortsMinutes = longVideoCap.copy(surface = "shorts", contentType = "short_form_video")
        assertEquals(
            EffectivePromisePolicy.APP_SCOPE_MISSES_RULES,
            EffectivePromisePolicy.conflictReason(d.copy(contentRules = d.contentRules + shortsMinutes))
        )
    }
}
