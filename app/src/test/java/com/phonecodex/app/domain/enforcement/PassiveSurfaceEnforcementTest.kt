package com.phonecodex.app.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.phonecodex.app.domain.promise.PromiseIntentRules

/**
 * P0 bug-family: passive surfaces must never trigger active-behavior enforcement.
 * ≥10 passive PASS + ≥10 active enforce cases.
 */
class PassiveSurfaceEnforcementTest {

    private val min30Promise =
        "do not allow videos shorter than 30 minutes for next 1 hour"

    private val max40Promise =
        "for 1 hour do not allow to watch any video whose length is greater than 40 min"

    private val chromeYtHomeMustPass =
        "YouTube Home Search Shorts Subscriptions You Explore Podcasts " +
            "Intelligence Zscaler’s Jay Chaudhry… 52 minutes"

    // --- Must-pass live phone evidence ---

    @Test
    fun chromeYouTubeHome_minLengthPromise_surfacePassive_mediaLengthPass() {
        val detection = SurfaceDetector.detect(VideoPlatformRegistry.CHROME, chromeYtHomeMustPass)
        assertTrue(detection.isPassive)
        assertFalse(detection.isActivePlayer)
        assertTrue(PromiseIntentRules.hasMediaLengthLimit(min30Promise))
        assertEquals(
            MediaLengthLocalDecision.PASS,
            MediaLengthEnforcement.decide(
                VideoPlatformRegistry.CHROME,
                min30Promise,
                chromeYtHomeMustPass,
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
        val gate = SurfaceEnforcementGate.evaluate(
            VideoPlatformRegistry.CHROME,
            chromeYtHomeMustPass,
            min30Promise,
            structuredMinBlockMinutes = 30
        )
        assertTrue(gate.isPass)
        assertEquals(SurfaceEnforcementGate.PASS_REASON, gate.reason)
        assertFalse(SurfaceEnforcementGate.aiMayWarnOrBlock(gate))
    }

    @Test
    fun chromeYouTubeHome_thumbnail52Minutes_doesNotCountAsActivePlayer() {
        val detection = SurfaceDetector.detect(VideoPlatformRegistry.CHROME, chromeYtHomeMustPass)
        assertFalse(detection.isActivePlayer)
        assertFalse(
            PromiseIntentRules.videoViolatesMediaLengthLimit(
                goal = min30Promise,
                screenText = chromeYtHomeMustPass,
                structuredMinBlockMinutes = 30,
                packageName = VideoPlatformRegistry.CHROME
            )
        )
    }

    // --- 10 passive PASS cases ---

    @Test
    fun passive_youtubeHome_pass() {
        assertPassivePass(
            VideoPlatformRegistry.YOUTUBE,
            "Home Shorts Subscriptions Library recommended for you",
            max40Promise
        )
    }

    @Test
    fun passive_youtubeSearch_pass() {
        assertPassivePass(
            VideoPlatformRegistry.YOUTUBE,
            "Search results About 1,200 results 12:04 45 minutes Home Shorts",
            max40Promise
        )
    }

    @Test
    fun passive_youtubeChannel_pass() {
        assertPassivePass(
            VideoPlatformRegistry.YOUTUBE,
            "Channel Neso Academy 1.2M subscribers Videos Home Playlists About",
            max40Promise
        )
    }

    @Test
    fun passive_youtubeSubscriptions_pass() {
        assertPassivePass(
            VideoPlatformRegistry.YOUTUBE,
            "Subscriptions All Today Latest videos Home",
            max40Promise
        )
    }

    @Test
    fun passive_youtubeComments_pass() {
        assertPassivePass(
            VideoPlatformRegistry.YOUTUBE,
            "Comments Add a comment Top comments View replies",
            max40Promise
        )
    }

    @Test
    fun youtubeWatchPage_commentsAndSpokenPlayerClock_isActivePlayer() {
        val screenText =
            "2 minutes 52 seconds of 17 minutes 4 seconds The essence of calculus " +
                "@3blue1brown 3 lakh likes 1.1 crore views Subscribe " +
                "Comments Add a comment Top comments"
        val detection = SurfaceDetector.detect(VideoPlatformRegistry.YOUTUBE, screenText)
        assertEquals(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER, detection.surface)
        assertTrue(detection.isActivePlayer)
        assertEquals(17 * 60 + 4, detection.durationSeconds)
        assertEquals(
            MediaLengthLocalDecision.ALLOW,
            MediaLengthEnforcement.decide(
                packageName = VideoPlatformRegistry.YOUTUBE,
                goal = max40Promise,
                screenText = screenText,
                structuredMaxBlockMinutes = 40
            )
        )
    }

    @Test
    fun commentsWithSpokenProgressButNoWatchMetadata_remainPassive() {
        val screenText =
            "Comments Add a comment Top comments " +
                "Someone wrote 2 minutes 52 seconds of 17 minutes 4 seconds"
        val detection = SurfaceDetector.detect(VideoPlatformRegistry.YOUTUBE, screenText)
        assertEquals(SurfaceDetectionResult.COMMENTS, detection.surface)
        assertFalse(detection.isActivePlayer)
    }

    @Test
    fun commentClockWithWatchMetadata_remainsPassive() {
        val screenText =
            "The essence of calculus 3 lakh likes 1.1 crore views Subscribe " +
                "Comments Add a comment Top comments Someone wrote " +
                "2 minutes 52 seconds of 17 minutes 4 seconds"
        val detection = SurfaceDetector.detect(VideoPlatformRegistry.YOUTUBE, screenText)
        assertEquals(SurfaceDetectionResult.COMMENTS, detection.surface)
        assertFalse(detection.isActivePlayer)
        assertNull(detection.durationSeconds)
    }

    @Test
    fun passive_chromeGoogleSearch_pass() {
        assertPassivePass(
            VideoPlatformRegistry.CHROME,
            "Google Search Videos Short videos Images About 900,000 results filters",
            max40Promise
        )
    }

    @Test
    fun passive_newPipeHistoryRecs_pass() {
        assertPassivePass(
            VideoPlatformRegistry.NEWPIPE,
            "History Related videos Trending recommended 12:00 45:00",
            max40Promise
        )
    }

    @Test
    fun passive_instagramExploreProfile_pass() {
        assertPassivePass(
            VideoPlatformRegistry.INSTAGRAM,
            "Explore Profile Suggested for you Stories Reels shelf",
            "no reels during study"
        )
    }

    @Test
    fun passive_facebookFeedReelsThumbs_pass() {
        assertPassivePass(
            VideoPlatformRegistry.FACEBOOK,
            "News Feed For you Following Reel thumbnails suggested for you",
            "no reels during study"
        )
    }

    @Test
    fun passive_tiktokProfileSearch_pass() {
        assertPassivePass(
            VideoPlatformRegistry.TIKTOK,
            "Profile Following Search results For you videos",
            "at most 10 shorts today"
        )
    }

    @Test
    fun passive_playStore_launcher_shade_tabSwitcher_discover_pass() {
        assertTrue(
            SurfaceDetector.detect("com.android.vending", "Play Store Install Ratings").isPassive
        )
        assertTrue(
            SurfaceDetector.detect("com.miui.home", "Search apps Widgets Phone").isPassive
        )
        assertTrue(
            SurfaceDetector.detect(
                "com.android.systemui",
                "Notification shade Quick settings Clear all"
            ).isPassive
        )
        assertTrue(
            SurfaceDetector.detect(
                VideoPlatformRegistry.CHROME,
                "Tabs Tab switcher New tab Incognito Close tab"
            ).isPassive
        )
        assertEquals(
            SurfaceDetectionResult.DISCOVER_NEWS,
            SurfaceDetector.detect(
                "com.google.android.googlequicksearchbox",
                "Discover For you Top stories news"
            ).surface
        )
    }

    @Test
    fun passive_igDmList_notMessagingThread() {
        val detection = SurfaceDetector.detect(
            VideoPlatformRegistry.INSTAGRAM,
            "Messages Inbox Chats Direct message list"
        )
        assertEquals(SurfaceDetectionResult.SOCIAL_DM, detection.surface)
        assertTrue(detection.isPassive)
        assertFalse(detection.surface == SurfaceDetectionResult.MESSAGING_THREAD)
    }

    // --- 10 active enforce cases ---

    @Test
    fun active_chromePlayer_underMin30_blocks() {
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                VideoPlatformRegistry.CHROME,
                min30Promise,
                "Hide player controls Time duration 12 minutes YouTube Playback Settings",
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
    }

    @Test
    fun active_chromePlayer_overMin30_allows() {
        assertEquals(
            MediaLengthLocalDecision.ALLOW,
            MediaLengthEnforcement.decide(
                VideoPlatformRegistry.CHROME,
                min30Promise,
                "Hide player controls Time duration 52 minutes YouTube Playback Settings",
                structuredMaxBlockMinutes = null,
                structuredMinBlockMinutes = 30
            )
        )
    }

    @Test
    fun active_youtubePlayer_overMax40_blocks() {
        assertEquals(
            MediaLengthLocalDecision.BLOCK,
            MediaLengthEnforcement.decide(
                VideoPlatformRegistry.YOUTUBE,
                max40Promise,
                "Hide player controls Time duration 1 hour 25 minutes Subscribe",
                structuredMaxBlockMinutes = 40
            )
        )
    }

    @Test
    fun active_youtubePlayer_underMax40_allows() {
        assertEquals(
            MediaLengthLocalDecision.ALLOW,
            MediaLengthEnforcement.decide(
                VideoPlatformRegistry.YOUTUBE,
                max40Promise,
                "Hide player controls Time duration 12 minutes Subscribe",
                structuredMaxBlockMinutes = 40
            )
        )
    }

    @Test
    fun active_youtubePlayer_missingDuration_waits() {
        assertEquals(
            MediaLengthLocalDecision.WAIT,
            MediaLengthEnforcement.decide(
                VideoPlatformRegistry.YOUTUBE,
                max40Promise,
                "Hide player controls Search Playback Settings",
                structuredMaxBlockMinutes = 40
            )
        )
    }

    @Test
    fun youtubeShortsEngagementColumn_isShortFormPlayer_homeIsNot() {
        val shortsNow =
            "Like Dislike Comment Share Remix Subscribe " +
                "funny clip channel name Home Shorts Subscriptions You"
        val detection = SurfaceDetector.detect(VideoPlatformRegistry.YOUTUBE, shortsNow)
        assertEquals(SurfaceDetectionResult.SHORT_FORM_PLAYER, detection.surface)
        assertTrue(detection.isShortFormPlay)

        val home = SurfaceDetector.detect(
            VideoPlatformRegistry.YOUTUBE,
            "Home Shorts Subscriptions Library recommended for you 0:45 1:12"
        )
        assertTrue(home.isPassive)
        assertFalse(home.isShortFormPlay)

        val watch = SurfaceDetector.detect(
            VideoPlatformRegistry.YOUTUBE,
            "Hide player controls Time duration Like Dislike Comment Share Subscribe 12:04 / 45:00"
        )
        assertFalse(watch.isShortFormPlay)
    }

    @Test
    fun active_shortsPlayer_quotaCounts() {
        val gate = ShortFormQuotaGate()
        val d = gate.evaluate(
            VideoPlatformRegistry.YOUTUBE,
            "Hide player controls Shorts player swipe up for next 0:45 funny clip",
            limit = 10
        )
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, d.action)
        assertTrue(d.counted)
    }

    @Test
    fun active_newPipeShort_quotaCounts() {
        val gate = ShortFormQuotaGate()
        val d = gate.evaluate(
            VideoPlatformRegistry.NEWPIPE,
            "Play Pause 00:15 / 00:59 Let’s talk now… Samay Raina",
            limit = 5
        )
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, d.action)
        assertEquals(SurfaceDetectionResult.SHORT_FORM_PLAYER, d.surface)
    }

    @Test
    fun active_instagramReelPlayer_isActiveShort() {
        val detection = SurfaceDetector.detect(
            VideoPlatformRegistry.INSTAGRAM,
            "Reel player Audio Liked Send Pause Play 0:22"
        )
        assertTrue(detection.isActivePlayer)
        assertEquals(SurfaceDetectionResult.SHORT_FORM_PLAYER, detection.surface)
    }

    @Test
    fun active_messagingThread_isActiveBehavior() {
        val detection = SurfaceDetector.detect(
            VideoPlatformRegistry.INSTAGRAM,
            "Messages Thread Type a message Send message"
        )
        assertEquals(SurfaceDetectionResult.MESSAGING_THREAD, detection.surface)
        assertTrue(detection.isActiveBehaviorSurface)
    }

    @Test
    fun active_installFlow_isActiveBehavior() {
        val detection = SurfaceDetector.detect(
            "com.google.android.packageinstaller",
            "Do you want to install this application App permissions"
        )
        assertEquals(SurfaceDetectionResult.INSTALL_FLOW, detection.surface)
        assertTrue(detection.isActiveBehaviorSurface)
    }

    @Test
    fun playStore_browseStaysPassive_confirmIsInstallFlow() {
        val browse = SurfaceDetector.detect("com.android.vending", "Play Store Install Ratings")
        assertEquals(SurfaceDetectionResult.PLAY_STORE, browse.surface)
        assertTrue(browse.isPassive)
        val confirm = SurfaceDetector.detect(
            "com.android.vending",
            "Do you want to install this application App permissions Cancel"
        )
        assertEquals(SurfaceDetectionResult.INSTALL_FLOW, confirm.surface)
        assertTrue(confirm.isActiveBehaviorSurface)
    }

    @Test
    fun active_paymentCheckout_isActiveBehavior() {
        val detection = SurfaceDetector.detect(
            VideoPlatformRegistry.CHROME,
            "Checkout Payment method Order summary Pay now"
        )
        assertEquals(SurfaceDetectionResult.PAYMENT_FLOW, detection.surface)
        assertTrue(detection.isActiveBehaviorSurface)
    }

    @Test
    fun homeShortsShelf_neverQuotaCounts() {
        val gate = ShortFormQuotaGate()
        val d = gate.evaluate(
            VideoPlatformRegistry.YOUTUBE,
            "Home Shorts Subscriptions Library Shorts shelf recommended 0:45",
            limit = 10
        )
        assertEquals(ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY, d.action)
        assertFalse(d.counted)
    }

    @Test
    fun overlayClearReason_neverSaysWarnOnHomeWithNoActiveVideo() {
        assertFalse(
            SurfaceEnforcementGate.PASS_REASON.contains("warn", ignoreCase = true)
        )
        assertTrue(
            SurfaceEnforcementGate.PASS_REASON.contains("passive video surface")
        )
    }

    private fun assertPassivePass(packageName: String, screenText: String, goal: String) {
        val detection = SurfaceDetector.detect(packageName, screenText)
        assertTrue("Expected passive for: $screenText", detection.isPassive)
        assertFalse(detection.isActivePlayer)
        val media = MediaLengthEnforcement.decide(
            packageName,
            goal,
            screenText,
            structuredMaxBlockMinutes = 40,
            structuredMinBlockMinutes = null
        )
        if (PromiseIntentRules.hasMediaLengthLimit(goal) || goal.contains("40")) {
            assertEquals(MediaLengthLocalDecision.PASS, media)
        }
        val gate = SurfaceEnforcementGate.evaluate(packageName, screenText, goal)
        assertTrue(gate.isPass)
        assertFalse(SurfaceEnforcementGate.aiMayWarnOrBlock(gate))
    }
}
