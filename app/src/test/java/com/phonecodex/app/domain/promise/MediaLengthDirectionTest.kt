package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StrictnessLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone P0 2026-09-25: "do not allow videos shorter than 30 minutes" saved
 * min=30 AND max=30, which blocked a 4-hour study video. Saved clocks must
 * keep one direction unless the user says "exactly N minutes".
 */
class MediaLengthDirectionTest {

    private fun lengthRule(action: ContentRuleAction, operator: String, value: Int) = ContentRule(
        appLabel = null,
        packageName = null,
        surface = "video",
        contentType = "long_form_video",
        operator = operator,
        value = value,
        unit = "minutes",
        action = action
    )

    private fun draft(text: String, rules: List<ContentRule>) = FocusPromise(
        rawText = text,
        sessionDurationMinutes = 60,
        allowedKeywords = emptyList(),
        blockedKeywords = emptyList(),
        strictness = StrictnessLevel.STRICT,
        suggestedAppRules = emptyList(),
        contentRules = rules
    )

    @Test
    fun doNotAllowShorterThan30_savesMinOnly_evenWhenCompilerAlsoSentMax() {
        val stored = ConfirmedPromiseBinder.bind(
            draft(
                "do not allow videos shorter than 30 minutes for next 1 hour",
                listOf(
                    lengthRule(ContentRuleAction.BLOCK, "lt", 30),
                    lengthRule(ContentRuleAction.BLOCK, "gt", 30)
                )
            )
        )
        assertEquals(30, stored.minVideoLengthBlockMinutes)
        assertNull(stored.maxVideoLengthBlockMinutes)
    }

    @Test
    fun doNotAllowShorterThan30_rawTextOnly_savesMinOnly() {
        val stored = ConfirmedPromiseBinder.bind(
            draft("do not allow videos shorter than 30 minutes for next 1 hour", emptyList())
        )
        assertEquals(30, stored.minVideoLengthBlockMinutes)
        assertNull(stored.maxVideoLengthBlockMinutes)
    }

    @Test
    fun allowOnlyLongerThan30_savesMinOnly_evenWhenCompilerSentBlockGt() {
        val stored = ConfirmedPromiseBinder.bind(
            draft(
                "allow only videos longer than 30 minutes",
                listOf(lengthRule(ContentRuleAction.BLOCK, "gt", 30))
            )
        )
        assertEquals(30, stored.minVideoLengthBlockMinutes)
        assertNull(stored.maxVideoLengthBlockMinutes)
    }

    @Test
    fun blockLongerThan30_savesMaxOnly_evenWhenCompilerSentBlockLt() {
        val stored = ConfirmedPromiseBinder.bind(
            draft(
                "block videos longer than 30 minutes",
                listOf(
                    lengthRule(ContentRuleAction.BLOCK, "gt", 30),
                    lengthRule(ContentRuleAction.BLOCK, "lt", 30)
                )
            )
        )
        assertEquals(30, stored.maxVideoLengthBlockMinutes)
        assertNull(stored.minVideoLengthBlockMinutes)
    }

    @Test
    fun allowOnlyShorterThan20_isACeilingNotAFloor() {
        val intent = PromiseIntentRules.mediaLengthIntent("allow only videos shorter than 20 min")
        assertEquals(20, intent.maxBlockMinutes)
        assertNull(intent.minBlockMinutes)
    }

    @Test
    fun collisionWithoutLengthWords_neverSavesMinEqualsMax() {
        val stored = ConfirmedPromiseBinder.bind(
            draft(
                "focus for 1 hour",
                listOf(
                    lengthRule(ContentRuleAction.BLOCK, "lt", 30),
                    lengthRule(ContentRuleAction.BLOCK, "gt", 30)
                )
            )
        )
        assertEquals(30, stored.minVideoLengthBlockMinutes)
        assertNull(stored.maxVideoLengthBlockMinutes)
    }

    @Test
    fun exactlyNMinutes_isTheOnlyWayToSaveMinEqualsMax() {
        val intent = PromiseIntentRules.mediaLengthIntent("only watch videos exactly 30 minutes long")
        assertTrue(intent.exact)
        assertEquals(30, intent.minBlockMinutes)
        assertEquals(30, intent.maxBlockMinutes)
    }

    @Test
    fun rangeKeepsBothSides() {
        val stored = ConfirmedPromiseBinder.bind(
            draft("block videos shorter than 10 minutes and block videos longer than 60 minutes", emptyList())
        )
        assertEquals(10, stored.minVideoLengthBlockMinutes)
        assertEquals(60, stored.maxVideoLengthBlockMinutes)
    }
}
