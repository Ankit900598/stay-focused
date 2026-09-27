package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.ui.home.buildPromiseUnderstanding
import com.phonecodex.app.ui.home.UnderstandingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite: four clocks must never collapse.
 * A session, B media length, C usage quota, D lock — plus Shorts only when stated.
 */
class PromiseClockSemanticsTest {

    private val parser = FocusPromiseParser()

    @Test
    fun keyExample_sessionOneHour_mediaLengthForty_noShortsBan() {
        val text =
            "for 1 hour do not allow to watch any video whose length is greater than 40 min"
        val draft = parser.parse(text)

        assertEquals(60, draft.sessionDurationMinutes)
        assertTrue(
            draft.contentRules.any {
                it.action == ContentRuleAction.BLOCK &&
                    it.operator == "gt" &&
                    it.value == 40
            }
        )
        assertEquals(40, PromiseContentRuleSupport.maxVideoLengthBlockMinutes(draft))
        assertFalse(PromiseIntentRules.blocksShortForm(text))
        assertTrue(PromiseIntentRules.hasMediaLengthLimit(text))
        assertFalse(draft.needsClarification)
    }

    @Test
    fun mediaLengthPromise_doesNotTreatAsEntertainmentBudgetInUnderstanding() {
        val draft = FocusPromise(
            rawText = "for 1 hour block videos longer than 40 min",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            contentRules = listOf(
                ContentRule(
                    appLabel = null,
                    packageName = null,
                    surface = "video",
                    contentType = "long_form_video",
                    operator = "gt",
                    value = 40,
                    unit = "minutes",
                    action = ContentRuleAction.BLOCK
                )
            ),
            blockedSummaries = listOf("videos longer than 40 min")
        )
        val understanding = buildPromiseUnderstanding(
            draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )
        assertTrue(understanding.canStart)
        assertFalse(understanding.blockedLabel.contains("budget", ignoreCase = true))
        assertTrue(understanding.sessionTimeLabel.contains("1 hour") || understanding.durationMinutes == 60)
    }

    @Test
    fun chromeNormalPage_notDeferredByMediaLengthAlone_withoutYouTubeSurface() {
        val goal =
            "for 1 hour do not allow to watch any video whose length is greater than 40 min"
        assertTrue(PromiseIntentRules.hasMediaLengthLimit(goal))
        assertFalse(
            PromiseIntentRules.isYouTubeSurfaceInChrome("Open Gmail Inbox Compose Search mail")
        )
        assertFalse(
            PromiseIntentRules.needsMoreChromeYouTubeDurationText(
                goal = goal,
                screenText = "Open Gmail Inbox Compose Search mail"
            )
        )
        assertFalse(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = goal,
                screenText = "Open Gmail Inbox Compose Search mail"
            )
        )
    }

    @Test
    fun chromeYouTube_overThreshold_blocks_withStructuredOrGoal() {
        val goal =
            "for 1 hour do not allow to watch any video whose length is greater than 40 min"
        val longPlayer =
            "Hide player controls Time duration 1 hour 25 minutes YouTube"

        assertTrue(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = goal,
                screenText = longPlayer
            )
        )
        assertTrue(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = "focus session",
                screenText = longPlayer,
                structuredMaxBlockMinutes = 40
            )
        )
        assertFalse(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = goal,
                screenText = "Hide player controls Time duration 12 minutes YouTube"
            )
        )
    }

    @Test
    fun chromeYouTube_withoutDuration_waits() {
        val goal =
            "for 1 hour do not allow to watch any video whose length is greater than 40 min"
        assertTrue(
            PromiseIntentRules.needsMoreChromeYouTubeDurationText(
                goal = goal,
                screenText = "Hide player controls Search YouTube"
            )
        )
    }

    @Test
    fun shortsBan_onlyWhenPromiseSaysSo() {
        assertFalse(
            PromiseIntentRules.blocksShortForm(
                "for 1 hour block videos longer than 40 min"
            )
        )
        assertTrue(
            PromiseIntentRules.blocksShortForm(
                "allow YouTube lectures but no Shorts for 2 hours"
            )
        )
        assertTrue(PromiseIntentRules.allowsShortForm("allow 40 Shorts then stop"))
    }

    @Test
    fun ambiguousFortyMinYouTube_asksFollowUp() {
        val draft = parser.parse("40 min youtube")
        assertTrue(draft.needsClarification)
        assertNotNull(draft.clarificationQuestion)
    }

    @Test
    fun instagramMessagesNoReels_parsesSurfaces() {
        val draft = parser.parse("instagram only messages no reels for 7 days")
        assertEquals(7 * 24 * 60, draft.sessionDurationMinutes)
        assertTrue(
            draft.contentRules.any {
                it.surface == "messages" || it.contentType.contains("dm", ignoreCase = true) ||
                    it.describe().contains("message", ignoreCase = true)
            } || draft.allowedSummaries.any { it.contains("message", ignoreCase = true) } ||
                draft.suggestedAppRules.any { it.packageName.contains("instagram") }
        )
        assertTrue(
            draft.blockedSummaries.any { it.contains("reel", ignoreCase = true) } ||
                draft.contentRules.any {
                    it.surface == "reels" || it.contentType == "short_form_video"
                } ||
                PromiseIntentRules.blocksShortForm(draft.rawText)
        )
    }

    @Test
    fun noPornOneYear_longSessionNotConfusedWithMediaLength() {
        val draft = parser.parse("no porn for 1 year rest normal")
        assertEquals(365 * 24 * 60, draft.sessionDurationMinutes)
        assertNull(PromiseContentRuleSupport.maxVideoLengthBlockMinutes(draft))
        assertFalse(PromiseIntentRules.hasMediaLengthLimit(draft.rawText))
    }

    @Test
    fun hinglishNeso_sessionThreeHours() {
        val draft = parser.parse("kal OS exam hai only Neso playlist 3 hours")
        assertEquals(180, draft.sessionDurationMinutes)
    }

    @Test
    fun tenShotsSpokenQuota_parsesAsTen() {
        val text =
            "allow me to watch only up to Max 10 shots and remind me how much Sort is left " +
                "to watch for next 30 minutes"
        val draft = parser.parse(text)
        val stored = ConfirmedPromiseBinder.bind(draft)
        assertEquals(10, stored.shortFormDailyQuotaLimit)
        assertTrue(PromiseIntentRules.allowsShortForm(text))
    }

    @Test
    fun tenShortsRemindLeft_isCalendarDayQuota() {
        val text =
            "allow only 10 shorts video and remind me how much shorts video is left which I can watch"
        val draft = parser.parse(text)
        val stored = ConfirmedPromiseBinder.bind(draft)
        assertEquals(10, stored.shortFormDailyQuotaLimit)
        assertEquals("calendar_day", stored.timeWindowKind)
        assertTrue(stored.durationMinutes >= 24 * 60)
        assertFalse(PromiseIntentRules.hasExplicitSessionDuration(text))
        assertEquals(
            "8 shorts left of 10.",
            com.phonecodex.app.domain.enforcement.SessionEnforcementCopy.shortsRemaining(2, 10)
        )
    }
}
