package com.phonecodex.app.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDurationParserTest {

    private val minLengthPromise =
        "do not allow videos shorter than 30 minutes for next 1 hour"

    @Test
    fun recShelfClockPairs_afterMoreVideos_areNotCurrentPlayer() {
        val screen =
            "youtube.com/watch?v=chip Chip design Show player controls " +
                "More videos Can This Vibe Coder 8 minutes 55 seconds " +
                "Andrew Tate 4 minutes 48 seconds 2:14 8:10"
        val parsed = VideoDurationParser.parse(screen)
        assertNull(parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.RECOMMENDATION_IGNORED, parsed.source)
        assertNotEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.CHROME,
                goal = minLengthPromise,
                screenText = screen,
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 40
            )
        )
    }

    @Test
    fun liveChromeWatch_recommendationsOnly_ignoresRecDurations() {
        val screen =
            "youtube.com/watch?v=chip " +
                "Chip design from the bottom up – Reiner Pope " +
                "Show player controls " +
                "More videos " +
                "Can This Vibe Coder... by SomeChannel 1.2M views 2 days ago 8 minutes 55 seconds " +
                "Andrew Tate... by Other 4 minutes 48 seconds " +
                "Introducing GPT-6 Astra... by Lab 2 minutes 43 seconds"

        val parsed = VideoDurationParser.parse(screen)
        assertNull(parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.RECOMMENDATION_IGNORED, parsed.source)
        assertTrue(parsed.recommendationDurationsSeconds.isNotEmpty())
        assertTrue(parsed.recommendationDurationsSeconds.any { it in 500..540 }) // ~8m55s

        val decision = MediaLengthEnforcement.decideDetailed(
            packageName = VideoPlatformRegistry.CHROME,
            goal = minLengthPromise,
            screenText = screen,
            structuredMaxBlockMinutes = null,
            structuredMinBlockMinutes = 30
        )
        assertNotEquals(MediaLengthLocalDecision.BLOCK, decision.decision)
        assertEquals(MediaLengthLocalDecision.WAIT, decision.decision)
        assertEquals(DurationSource.RECOMMENDATION_IGNORED, decision.durationSource)
    }

    @Test
    fun currentPlayer_shortClock_blocksMin30() {
        val screen = "Show player controls Play Pause 00:15 / 00:59 Related"
        val parsed = VideoDurationParser.parse(screen)
        assertEquals(59, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)

        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.NEWPIPE,
                goal = minLengthPromise,
                screenText = screen,
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
    }

    @Test
    fun currentPlayer_longClock_allowsMin30() {
        val screen = "Show player controls Play Pause 00:10 / 52:00 Related"
        val parsed = VideoDurationParser.parse(screen)
        assertEquals(52 * 60, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)

        assertEquals(
            MediaLengthLocalDecision.ALLOW,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.NEWPIPE,
                goal = minLengthPromise,
                screenText = screen,
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
    }

    @Test
    fun timeDurationPrefix_isCurrentPlayer_notRecommendationMax() {
        val screen =
            "Hide player controls Time duration 1 hour 25 minutes YouTube " +
                "More videos Can This Vibe Coder 8 minutes 55 seconds Andrew 4 minutes 48 seconds"
        val parsed = VideoDurationParser.parse(screen)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)
        assertEquals(85 * 60, parsed.currentPlayerDurationSeconds)
        assertTrue(parsed.recommendationDurationsSeconds.any { it in 500..540 })
    }

    @Test
    fun recommendationTimeDuration_afterMoreVideos_neverDrivesMediaLengthBlock() {
        val shortRecommendation =
            "Hide player controls Playback Settings More videos Time duration 12 minutes"
        val longRecommendation =
            "Hide player controls Playback Settings More videos Time duration 1 hour 25 minutes"
        val maxLengthPromise =
            "for 1 hour do not allow to watch any video whose length is greater than 40 min"

        assertEquals(
            MediaLengthLocalDecision.WAIT,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.YOUTUBE,
                goal = minLengthPromise,
                screenText = shortRecommendation,
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
        assertEquals(
            MediaLengthLocalDecision.WAIT,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.YOUTUBE,
                goal = maxLengthPromise,
                screenText = shortRecommendation,
                structuredMaxBlockMinutes = 40
            )
        )
        assertEquals(
            MediaLengthLocalDecision.WAIT,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.YOUTUBE,
                goal = maxLengthPromise,
                screenText = longRecommendation,
                structuredMaxBlockMinutes = 40
            )
        )
        assertEquals(
            MediaLengthLocalDecision.WAIT,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.YOUTUBE,
                goal = minLengthPromise,
                screenText = longRecommendation,
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
        assertNull(VideoDurationParser.parse(shortRecommendation).currentPlayerDurationSeconds)
        assertNull(VideoDurationParser.parse(longRecommendation).currentPlayerDurationSeconds)
    }

    @Test
    fun extractPrimary_neverReturnsRecommendationOnlyDuration() {
        val screen =
            "Chip design from the bottom up – Reiner Pope Show player controls " +
                "Can This Vibe Coder 8 minutes 55 seconds"
        assertNull(VideoDurationParser.extractPrimaryDurationSeconds(screen))
        assertNull(VideoDurationParser.extractPrimaryDurationMinutes(screen))
    }

    @Test
    fun clockB_shortTotal_isShortFormItemLength() {
        val parsed = VideoDurationParser.parse("Play Pause 00:15 / 00:59")
        assertEquals(59, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)
        assertTrue(
            VideoDurationParser.isShortFormItemLength(
                parsed,
                SurfaceDetector.SHORT_FORM_MAX_SECONDS
            )
        )
    }

    @Test
    fun clockB_lectureTotal_38_25_isNotShortForm() {
        val parsed = VideoDurationParser.parse("Play Pause 00:15 / 38:25 lecture")
        assertEquals(2305, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)
        assertFalse(
            VideoDurationParser.isShortFormItemLength(
                parsed,
                SurfaceDetector.SHORT_FORM_MAX_SECONDS
            )
        )
    }

    @Test
    fun clockC_watchTime2305_isNotPlayerTotal() {
        val parsed = VideoDurationParser.parse(
            "Hide player controls Playback Settings watched 2305 seconds"
        )
        assertNull(parsed.currentPlayerDurationSeconds)
        assertFalse(
            VideoDurationParser.isShortFormItemLength(
                parsed,
                SurfaceDetector.SHORT_FORM_MAX_SECONDS
            )
        )
    }

    @Test
    fun chromeSpokenSeekbar_gluedElapsedAndDuration_isCurrentPlayer() {
        val screen = "Time elapsed 1 secondTime duration 31 minutes, 33 seconds"
        val parsed = VideoDurationParser.parse(screen)
        assertEquals(31 * 60 + 33, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)
    }

    @Test
    fun spokenTimeDuration_hoursMinutesSeconds_withCommas() {
        val parsed = VideoDurationParser.parse(
            "Time duration 1 hour, 2 minutes, 3 seconds"
        )
        assertEquals(1 * 3600 + 2 * 60 + 3, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)
    }

    @Test
    fun clockPair_0_01_over_31_33_isCurrentPlayerTotal() {
        val parsed = VideoDurationParser.parse("Play Pause 0:01 / 31:33")
        assertEquals(31 * 60 + 33, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)
    }

    @Test
    fun spokenCurrentPositionOfTotal_isCurrentPlayerTotal() {
        val parsed = VideoDurationParser.parse(
            "2 minutes 52 seconds of 17 minutes 4 seconds"
        )
        assertEquals(17 * 60 + 4, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)
    }

    @Test
    fun spokenProgressBeyondTotal_isNotCurrentPlayerDuration() {
        val parsed = VideoDurationParser.parse(
            "18 minutes 5 seconds of 17 minutes 4 seconds"
        )
        assertNull(parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.RECOMMENDATION_IGNORED, parsed.source)
    }

    @Test
    fun spokenProgressAfterRecommendationHeader_isRecommendationOnly() {
        val parsed = VideoDurationParser.parse(
            "More videos 2 minutes 52 seconds of 17 minutes 4 seconds"
        )
        assertNull(parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.RECOMMENDATION_IGNORED, parsed.source)
    }

    @Test
    fun spokenProgressAfterCommentsHeader_isNotCurrentPlayerDuration() {
        val parsed = VideoDurationParser.parse(
            "The essence of calculus 3 lakh likes 1.1 crore views Subscribe " +
                "Comments Add a comment Top comments Someone wrote " +
                "2 minutes 52 seconds of 17 minutes 4 seconds"
        )
        assertNull(parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.RECOMMENDATION_IGNORED, parsed.source)
    }

    @Test
    fun timeElapsedAlone_isNotPlayerTotal() {
        val parsed = VideoDurationParser.parse("Time elapsed 1 second Hide player controls")
        assertNull(parsed.currentPlayerDurationSeconds)
        assertNotEquals(DurationSource.CURRENT_PLAYER, parsed.source)
    }

    @Test
    fun recommendationClock_isNotShortFormItemLength() {
        val parsed = VideoDurationParser.parse(
            "Show player controls More videos 8 minutes 55 seconds"
        )
        assertEquals(DurationSource.RECOMMENDATION_IGNORED, parsed.source)
        assertFalse(
            VideoDurationParser.isShortFormItemLength(
                parsed,
                SurfaceDetector.SHORT_FORM_MAX_SECONDS
            )
        )
    }
}
