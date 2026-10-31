package com.phonecodex.app.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.phonecodex.app.domain.promise.PromiseIntentRules

class MediaLengthEnforcementTest {

    private val lengthPromise =
        "for 1 hour do not allow to watch any video whose length is greater than 40 min"

    private val minLengthPromise =
        "do not allow videos shorter than 30 minutes for next 1 hour"

    @Test
    fun normalChromePage_passes_underLengthOnlyPromise() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = "Wikipedia Android (operating system) Edit Search",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.PASS, decision)
    }

    @Test
    fun chromeGoogleResults_withVideosTab_doesNotBlock() {
        val screenText =
            "Google Search Videos Short videos Images Shopping All filters " +
                "About 1,200,000 results YouTube 1 hour 25 minutes some channel"
        assertFalse(PromiseIntentRules.isActiveVideoPlayerSurface(screenText))
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = screenText,
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.PASS, decision)
        assertFalse(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = lengthPromise,
                screenText = screenText,
                structuredMaxBlockMinutes = 40
            )
        )
    }

    @Test
    fun chromeHomepage_incognitoProfileTiles_doesNotBlock() {
        val screenText =
            "Google New incognito tab Profile Other bookmarks Chrome tips " +
                "Art and photos Short videos"
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = screenText,
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.PASS, decision)
    }

    @Test
    fun chromeYouTube_overLimit_blocks() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = "Hide player controls Time duration 1 hour 25 minutes YouTube",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.BLOCK, decision)
    }

    @Test
    fun chromeYouTube_underLimit_allows() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = "Hide player controls Time duration 12 minutes YouTube",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.ALLOW, decision)
    }

    @Test
    fun maxLengthUsesExactSecondsAtMinuteBoundary() {
        val overByOneSecond = "Hide player controls youtube.com Time duration 40 minutes, 1 second"
        val exactlyAtLimit = "Hide player controls youtube.com Time duration 40 minutes"

        assertTrue(
            PromiseIntentRules.videoViolatesMediaLengthLimit(
                goal = lengthPromise,
                screenText = overByOneSecond,
                structuredMaxBlockMinutes = 40
            )
        )
        assertFalse(
            PromiseIntentRules.videoViolatesMediaLengthLimit(
                goal = lengthPromise,
                screenText = exactlyAtLimit,
                structuredMaxBlockMinutes = 40
            )
        )

        val over = MediaLengthEnforcement.decideDetailed(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = overByOneSecond,
            structuredMaxBlockMinutes = 40
        )
        assertEquals(40 * 60 + 1, over.currentPlayerDurationSeconds)
        assertEquals(MediaLengthLocalDecision.BLOCK, over.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX, over.blockReasonCode)

        val exact = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = exactlyAtLimit,
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.ALLOW, exact)
    }

    @Test
    fun chromeYouTube_withoutDuration_waits() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = "Hide player controls Search YouTube Playback Settings",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.WAIT, decision)
    }

    @Test
    fun chromeWatchUrl_withLongDuration_blocks() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = lengthPromise,
            screenText = "youtube.com/watch?v=abc123 Time duration 1 hour 10 minutes",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.BLOCK, decision)
    }

    @Test
    fun nativeYouTube_overLimit_blocks() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.YOUTUBE,
            goal = lengthPromise,
            screenText = "Time duration 2 hours 5 minutes Subscribe Share",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.BLOCK, decision)
    }

    @Test
    fun nativeYouTube_withoutDuration_waits_notAi() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.YOUTUBE,
            goal = lengthPromise,
            screenText = "Hide player controls Search Playback Settings",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.WAIT, decision)
    }

    @Test
    fun nativeYouTubeHome_returnsPass_notWarn() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.YOUTUBE,
            goal = lengthPromise,
            screenText = "Home Shorts Subscriptions Library",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.PASS, decision)
    }

    @Test
    fun nativeYouTubeSearch_withThumbnailDurations_doesNotBlock() {
        val screenText =
            "Search results 1:25:30 45 minutes 12:04 Home Shorts Library"
        assertFalse(PromiseIntentRules.isActiveVideoPlayerSurface(screenText))
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.YOUTUBE,
            goal = lengthPromise,
            screenText = screenText,
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.PASS, decision)
    }

    @Test
    fun bareYouTubeWord_isNotActivePlayer() {
        assertFalse(
            PromiseIntentRules.isActiveVideoPlayerSurface(
                "Google Search Videos Short videos youtube.com results"
            )
        )
        assertTrue(
            PromiseIntentRules.isActiveVideoPlayerSurface(
                "Hide player controls YouTube"
            )
        )
    }

    @Test
    fun newPipe_overLimit_blocks() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.NEWPIPE,
            goal = lengthPromise,
            screenText = "Play Pause 00:10 / 55:00 Related",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.BLOCK, decision)
    }

    @Test
    fun newPipe_underLimit_allows() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.NEWPIPE,
            goal = lengthPromise,
            screenText = "Play Pause 00:10 / 12:00 Related",
            structuredMaxBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.ALLOW, decision)
    }

    @Test
    fun noLengthPromise_returnsNone() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.CHROME,
            goal = "study mode for 1 hour no shorts",
            screenText = "Wikipedia Android",
            structuredMaxBlockMinutes = null
        )
        assertEquals(MediaLengthLocalDecision.NONE, decision)
    }

    @Test
    fun minLengthFloor_activeShortWithoutClock_blocks() {
        val decision = MediaLengthEnforcement.decide(
            packageName = VideoPlatformRegistry.YOUTUBE,
            goal = "allow only videos longer than 40 minutes for 1 hrs",
            screenText = "Home Shorts Subscriptions Remix this short Pause",
            structuredMaxBlockMinutes = null,
            structuredMinBlockMinutes = 40
        )
        assertEquals(MediaLengthLocalDecision.BLOCK, decision)
    }

    @Test
    fun shorterThanPromise_isMediaLengthLimit() {
        assertTrue(PromiseIntentRules.hasMediaLengthLimit(minLengthPromise))
        assertEquals(30, PromiseIntentRules.extractGoalMinVideoLimitMinutes(minLengthPromise))
    }

    @Test
    fun chromeYouTubeHome_shorterThanPromise_passes() {
        val screen =
            "YouTube Home Search Shorts Subscriptions You Explore Podcasts " +
                "Intelligence Zscaler’s Jay Chaudhry… 52 minutes"
        assertEquals(
            MediaLengthLocalDecision.PASS,
            MediaLengthEnforcement.decide(
                VideoPlatformRegistry.CHROME,
                minLengthPromise,
                screen,
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = null
            )
        )
    }

    @Test
    fun chromeWatch_activeTitle_withOnlyRecommendationDurations_waitsNotBlocks() {
        val screen =
            "m.youtube.com/watch?v=abc " +
                "Chip design from the bottom up – Reiner Pope " +
                "Show player controls " +
                "Can This Vibe Coder... 8 minutes 55 seconds " +
                "Andrew Tate... 4 minutes 48 seconds " +
                "Introducing GPT-6 Astra... 2 minutes 43 seconds"
        val detailed = MediaLengthEnforcement.decideDetailed(
            packageName = VideoPlatformRegistry.CHROME,
            goal = minLengthPromise,
            screenText = screen,
            structuredMaxBlockMinutes = null,
            structuredMinBlockMinutes = 30
        )
        assertEquals(MediaLengthLocalDecision.WAIT, detailed.decision)
        assertEquals(DurationSource.RECOMMENDATION_IGNORED, detailed.durationSource)
        assertNull(detailed.currentPlayerDurationSeconds)
    }

    @Test
    fun activePlayer_clockUnderMin_blocks() {
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.NEWPIPE,
                goal = minLengthPromise,
                screenText = "Play Pause 00:15 / 00:59",
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
    }

    @Test
    fun activePlayer_clockOverMin_allows() {
        assertEquals(
            MediaLengthLocalDecision.ALLOW,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.NEWPIPE,
                goal = minLengthPromise,
                screenText = "Play Pause 00:10 / 52:00",
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
    }

    /**
     * Phase 2A: scope preflight has moved to
     * [com.phonecodex.app.domain.scope.ScopeGate], which runs in the coordinator
     * AND the AccessibilityService BEFORE MediaLengthEnforcement is called.
     * The gate itself no longer filters by scope, so it BLOCKs on any registered
     * media package whose player clock violates the length rule; the two legacy
     * `enforcementScopePackages` / `contentBrands` parameters are inert here.
     * Trace-level equivalence (out-of-scope traces reach ALLOW with
     * `ENFORCEMENT_SCOPE_ALLOW`, not `MEDIA_BLOCK_*`) is asserted through the
     * coordinator by `PhoneSurfaceTraceReplayTest` and by
     * `ScopeGateEquivalenceTest` in `domain/scope/`.
     */
    @Test
    fun mediaLengthGate_noLongerFiltersByScope_bothPackagesBlockOverLimit() {
        val player = "Hide player controls Time duration 1 hour 25 minutes YouTube"
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.YOUTUBE,
                goal = lengthPromise,
                screenText = player,
                structuredMaxBlockMinutes = 40
            )
        )
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.CHROME,
                goal = lengthPromise,
                screenText = player,
                structuredMaxBlockMinutes = 40
            )
        )
        // Inert legacy params must not change the verdict.
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.YOUTUBE,
                goal = lengthPromise,
                screenText = player,
                structuredMaxBlockMinutes = 40,
                enforcementScopePackages = listOf(VideoPlatformRegistry.CHROME)
            )
        )
    }

    @Test
    fun youtubeContentBrand_chromeYoutubeHost_underMin_blocks() {
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.CHROME,
                goal = "allow YouTube video longer than 40 min only",
                screenText =
                    "https://m.youtube.com/watch?v=abc Hide player controls Play Pause 00:10 / 12:00",
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 40,
                enforcementScopePackages = emptyList(),
                contentBrands = listOf("youtube")
            )
        )
    }

    /**
     * Phase 2A: with the legacy scope check gone from the gate, Chrome on a Gmail
     * SERP is no longer short-circuited to `NONE` by the gate itself -- it lands
     * on the passive-surface arm and returns `PASS`. Scope-level filtering
     * (Gmail is out of a YouTube-content promise) is asserted upstream by
     * `ScopeGateEquivalenceTest`; downstream, `PASS` is mapped to `ALLOW`
     * with `SURFACE_PASS_PASSIVE` by the coordinator (still a no-op).
     */
    @Test
    fun youtubeContentBrand_chromeGmail_isPassivePass() {
        assertEquals(
            MediaLengthLocalDecision.PASS,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.CHROME,
                goal = "allow YouTube video longer than 40 min only",
                screenText = "https://mail.google.com/mail Inbox Primary",
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 40
            )
        )
    }

    @Test
    fun youtubeContentBrand_newPipeShort_blocks() {
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.NEWPIPE,
                goal = "allow YouTube video longer than 40 min only",
                screenText = "Play Pause 00:15 / 00:59 Hide player controls",
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 40,
                enforcementScopePackages = emptyList(),
                contentBrands = listOf("youtube")
            )
        )
    }

    private val youtubeMin40 = "allow only YouTube videos longer than 40 minutes"
    private val chromeSpoken31m33 =
        "Time elapsed 1 secondTime duration 31 minutes, 33 seconds"

    @Test
    fun chromeSpokenSeekbar_exactPhoneLog_31m33_blocksUnderMin40() {
        val parsed = VideoDurationParser.parse(chromeSpoken31m33)
        assertEquals(31 * 60 + 33, parsed.currentPlayerDurationSeconds)
        assertEquals(DurationSource.CURRENT_PLAYER, parsed.source)

        val detailed = MediaLengthEnforcement.decideDetailed(
            packageName = VideoPlatformRegistry.CHROME,
            goal = youtubeMin40,
            screenText = chromeSpoken31m33,
            structuredMaxBlockMinutes = null,
            structuredMinBlockMinutes = 40,
            contentBrands = listOf("youtube")
        )
        assertEquals(MediaLengthLocalDecision.BLOCK, detailed.decision)
        assertEquals(DurationSource.CURRENT_PLAYER, detailed.durationSource)
        assertEquals(31 * 60 + 33, detailed.currentPlayerDurationSeconds)
        assertNotEquals(MediaLengthLocalDecision.WAIT, detailed.decision)
    }

    @Test
    fun chromeSpokenSeekbar_41m_allowsMin40() {
        val screen = "Time elapsed 1 secondTime duration 41 minutes"
        val detailed = MediaLengthEnforcement.decideDetailed(
            packageName = VideoPlatformRegistry.CHROME,
            goal = youtubeMin40,
            screenText = screen,
            structuredMaxBlockMinutes = null,
            structuredMinBlockMinutes = 40,
            contentBrands = listOf("youtube")
        )
        assertEquals(41 * 60, detailed.currentPlayerDurationSeconds)
        assertEquals(MediaLengthLocalDecision.ALLOW, detailed.decision)
        assertEquals(DurationSource.CURRENT_PLAYER, detailed.durationSource)
    }

    @Test
    fun allowOnlyLongerThan40_blocksExactly40Minutes() {
        val detailed = MediaLengthEnforcement.decideDetailed(
            packageName = VideoPlatformRegistry.CHROME,
            goal = youtubeMin40,
            screenText = "Hide player controls YouTube Time duration 40 minutes",
            structuredMaxBlockMinutes = null,
            structuredMinBlockMinutes = 40,
            contentBrands = listOf("youtube")
        )
        assertEquals(40 * 60, detailed.currentPlayerDurationSeconds)
        assertEquals(MediaLengthLocalDecision.BLOCK, detailed.decision)
        assertEquals(DurationSource.CURRENT_PLAYER, detailed.durationSource)
    }

    @Test
    fun chromeSpokenSeekbar_hourCommaForm_isNotWait() {
        val screen = "Time elapsed 8 secondsTime duration 1 hour, 2 minutes, 3 seconds"
        val detailed = MediaLengthEnforcement.decideDetailed(
            packageName = VideoPlatformRegistry.CHROME,
            goal = youtubeMin40,
            screenText = screen,
            structuredMaxBlockMinutes = null,
            structuredMinBlockMinutes = 40,
            contentBrands = listOf("youtube")
        )
        assertEquals(1 * 3600 + 2 * 60 + 3, detailed.currentPlayerDurationSeconds)
        assertEquals(MediaLengthLocalDecision.ALLOW, detailed.decision)
        assertNotEquals(MediaLengthLocalDecision.WAIT, detailed.decision)
    }

    @Test
    fun chromeClockPair_0_01_over_31_33_blocksMin40() {
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.CHROME,
                goal = youtubeMin40,
                screenText = "Hide player controls YouTube 0:01 / 31:33",
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 40,
                contentBrands = listOf("youtube")
            )
        )
    }
}
