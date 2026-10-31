package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.FocusPromiseParser
import com.phonecodex.app.domain.promise.PromiseIntentRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full path: promise text → FocusPromise → ConfirmedPromiseBinder → coordinator.
 * Proves AppRule ALLOW cannot skip content gates, and NetMirror blank-tree law.
 */
class PromiseFullPathEnforcementTest {

    private val parser = FocusPromiseParser()
    private val allowOnly40 =
        "allow only videos longer than 40 minutes for 1 hour"
    private val playerShort = "Play Pause 00:15 / 00:59 short clip Hide player controls"
    private val playerLong = "Play Pause 00:15 / 45:00 lecture Hide player controls"
    private val ytHome =
        "YouTube Home Search Shorts Subscriptions You Explore recommended for you 0:45 1:12"
    private val chromeSearch =
        "Google Search Videos Short videos Images About 1,200,000 results filters lecture 12:04"

    @Test
    fun allowOnlyLongerThan40_persistsMinVideoLength() {
        val draft = parser.parse(allowOnly40)
        val stored = ConfirmedPromiseBinder.bind(draft)
        assertEquals(40, stored.minVideoLengthBlockMinutes)
        assertNull(stored.maxVideoLengthBlockMinutes)
        assertTrue(
            "generic video promises must not shrink to one package",
            stored.enforcementScopePackages.isEmpty()
        )
        assertEquals(
            40,
            PromiseIntentRules.extractGoalMinVideoLimitMinutes(draft.rawText)
        )
        assertNull(PromiseIntentRules.extractGoalMaxVideoLimitMinutes(draft.rawText))
        assertTrue(ConfirmedPromiseBinder.hasStructuredMediaRule(stored))
    }

    @Test
    fun chromeYoutubeNewPipe_shortBlocks_longAllows_homeSearchPass() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(allowOnly40))
        for (pkg in listOf(
            VideoPlatformRegistry.CHROME,
            VideoPlatformRegistry.YOUTUBE,
            VideoPlatformRegistry.NEWPIPE
        )) {
            val shortResult = evaluate(
                packageName = pkg,
                screenText = playerShort,
                settings = stored,
                appRule = AppRuleBehavior.AI_DECIDE
            )
            assertEquals("$pkg short", DecisionType.BLOCK, shortResult.decision)
            assertEquals(
                "$pkg short code",
                EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                shortResult.reasonCode
            )
            assertTrue(shortResult.contentGatesRanBeforeAppRule)

            val longResult = evaluate(
                packageName = pkg,
                screenText = playerLong,
                settings = stored,
                appRule = AppRuleBehavior.AI_DECIDE
            )
            assertEquals("$pkg long", DecisionType.ALLOW, longResult.decision)
            assertEquals(
                EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT,
                longResult.reasonCode
            )
        }

        for ((pkg, text) in listOf(
            VideoPlatformRegistry.YOUTUBE to ytHome,
            VideoPlatformRegistry.CHROME to chromeSearch
        )) {
            val result = evaluate(
                packageName = pkg,
                screenText = text,
                settings = stored,
                appRule = AppRuleBehavior.AI_DECIDE
            )
            assertNotEquals("$pkg home/search must not BLOCK", DecisionType.BLOCK, result.decision)
            assertEquals(EnforcementReasonCodes.SURFACE_PASS_PASSIVE, result.reasonCode)
        }
    }

    @Test
    fun chromeAppRuleAllow_stillBlocksActiveShort() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(allowOnly40))
        val result = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = playerShort,
            settings = stored,
            appRule = AppRuleBehavior.ALLOW
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, result.reasonCode)
        assertTrue(result.contentGatesRanBeforeAppRule)
        assertNotEquals(EnforcementReasonCodes.APP_RULE_ALLOW_DEFERRED, result.reasonCode)
        assertEquals(AppRuleBehavior.ALLOW.name, result.appRuleBehavior)
    }

    @Test
    fun youtubeAppRuleAllow_stillBlocksActiveShort() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(allowOnly40))
        val result = evaluate(
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = playerShort,
            settings = stored,
            appRule = AppRuleBehavior.ALLOW
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, result.reasonCode)
        assertTrue(result.contentGatesRanBeforeAppRule)
    }

    @Test
    fun noActiveSession_allowsChromeAndYoutube() {
        val leftover = ConfirmedPromiseBinder.bind(parser.parse(allowOnly40))
        for (pkg in listOf(VideoPlatformRegistry.CHROME, VideoPlatformRegistry.YOUTUBE)) {
            val result = evaluate(
                hasActiveSession = false,
                packageName = pkg,
                screenText = playerShort,
                settings = leftover,
                appRule = AppRuleBehavior.ALLOW
            )
            assertEquals(DecisionType.ALLOW, result.decision)
            assertEquals(EnforcementReasonCodes.NO_ACTIVE_SESSION_ALLOW, result.reasonCode)
            assertFalse(result.contentGatesRanBeforeAppRule)
        }
    }

    @Test
    fun activeSession_nullMediaSettings_logsNoStructuredMediaRule() {
        val empty = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2
        )
        assertFalse(ConfirmedPromiseBinder.hasStructuredMediaRule(empty))
        val result = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = playerShort,
            settings = empty,
            goal = "focus for 1 hour",
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertTrue(result.prelude.contains("code=${EnforcementReasonCodes.NO_STRUCTURED_MEDIA_RULE}"))
        assertNotEquals(
            "must not silent-allow without logging the missing-rule code",
            "",
            result.prelude
        )
    }

    @Test
    fun chromeShortsUrl_minLengthPromise_blocksAsActiveShort() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(allowOnly40))
        val result = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = "Address bar https://www.youtube.com/shorts/abcXYZ12 YouTube",
            settings = stored,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN, result.reasonCode)
        assertTrue(result.contentGatesRanBeforeAppRule)
    }

    @Test
    fun lectureClock_38_25_isNotShort_allowsMin30() {
        val min30 = "do not allow videos shorter than 30 minutes for next 1 hour"
        val stored = ConfirmedPromiseBinder.bind(parser.parse(min30))
        val lecture = "Play Pause 00:15 / 38:25 OS lecture Hide player controls"
        assertFalse(SurfaceDetector.detect(VideoPlatformRegistry.NEWPIPE, lecture).isShortFormPlay)
        val result = evaluate(
            packageName = VideoPlatformRegistry.NEWPIPE,
            screenText = lecture,
            settings = stored,
            goal = min30,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.ALLOW, result.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT, result.reasonCode)
    }

    @Test
    fun messyOneHrsMinLength_activeYouTubeShortsBlock_evenWithoutClock() {
        val text = "allow only videos longer than 40 minutes for 1 hrs"
        val draft = parser.parse(text)
        val stored = ConfirmedPromiseBinder.bind(draft)
        assertEquals(60, stored.durationMinutes)
        assertEquals(40, stored.minVideoLengthBlockMinutes)
        assertEquals(60 * 60_000L, ConfirmedPromiseBinder.sessionDurationMillis(stored))

        val shortsNoClock =
            "Home Shorts Subscriptions Remix this short Pause like comment"
        val result = evaluate(
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = shortsNoClock,
            settings = stored,
            goal = text,
            appRule = AppRuleBehavior.ALLOW
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN, result.reasonCode)
        assertTrue(result.contentGatesRanBeforeAppRule)
    }

    @Test
    fun noShortsOneHour_activeYouTubeShortsBlock() {
        val text = "no shorts for 1 hr"
        val stored = ConfirmedPromiseBinder.bind(parser.parse(text))
        assertEquals(60, stored.durationMinutes)
        val result = evaluate(
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = "Home Shorts Subscriptions Remix this short Pause",
            settings = stored,
            goal = text,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.CONTENT_SIGNAL_BLOCK, result.reasonCode)
    }

    @Test
    fun leftoverThirtySettings_areNotUsedWhenBinderSaysSixty() {
        val leftoverThirty = StudyWorldSettings(
            durationMinutes = 30,
            lockAttemptThreshold = 10,
            blockAttemptCooldownMinutes = 2
        )
        val bound = ConfirmedPromiseBinder.bind(parser.parse("study for 1 hour no shorts"))
        assertEquals(60, bound.durationMinutes)
        assertNotEquals(leftoverThirty.durationMinutes, bound.durationMinutes)
        assertEquals(60 * 60_000L, ConfirmedPromiseBinder.sessionDurationMillis(bound))
    }

    @Test
    fun noSessionAllow_aliasStillExistsForGrep() {
        assertEquals("NO_SESSION_ALLOW", EnforcementReasonCodes.NO_SESSION_ALLOW)
        assertEquals("NO_ACTIVE_SESSION_ALLOW", EnforcementReasonCodes.NO_ACTIVE_SESSION_ALLOW)
    }

    @Test
    fun monkFourHours_chromeCommentsAndYoutubeHome_block_notSurfacePass() {
        val monk =
            "Monk mode for 4 hours. Only calls and study. No social, no shorts, no games."
        val stored = StudyWorldSettings(
            durationMinutes = 240,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2
        )
        val chromeComments = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = "Comments Add a comment Top comments View replies",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.BLOCK, chromeComments.decision)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, chromeComments.reasonCode)
        assertNotEquals(EnforcementReasonCodes.SURFACE_PASS_PASSIVE, chromeComments.reasonCode)

        val ytHome = evaluate(
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = ytHome,
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.ALLOW
        )
        assertEquals(DecisionType.BLOCK, ytHome.decision)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, ytHome.reasonCode)
    }

    @Test
    fun monkFourHours_chromePornPlayer_blocks_calculusLectureAllows() {
        val monk =
            "Monk mode for 4 hours. Only calls and study. No social, no shorts, no games."
        val stored = StudyWorldSettings(
            durationMinutes = 240,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2
        )
        val porn = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = "Hide player controls Time duration 12 minutes Playback Settings Watch now",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.BLOCK, porn.decision)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, porn.reasonCode)

        val lecture = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText =
                "Play Pause 00:15 / 45:00 calculus lecture Hide player controls Time duration 45 minutes",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.ALLOW, lecture.decision)
        assertEquals(EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW, lecture.reasonCode)
    }

    @Test
    fun monk_leftoverMinLengthClock_cannotSurfacePassChromeComments() {
        val monk =
            "Monk mode for 4 hours. Only calls and study. No social, no shorts, no games."
        val leftoverCalculusClock = StudyWorldSettings(
            durationMinutes = 240,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            minVideoLengthBlockMinutes = 30
        )
        val result = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = "Comments Add a comment Top comments View replies",
            settings = leftoverCalculusClock,
            goal = monk,
            appRule = AppRuleBehavior.ALLOW
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, result.reasonCode)
        assertNotEquals(EnforcementReasonCodes.SURFACE_PASS_PASSIVE, result.reasonCode)
    }

    @Test
    fun monk_namedVideoScope_stillBlocksDatingAndInstall() {
        val monk =
            "Monk mode for 4 hours. Only calls and study. No social, no shorts, no games."
        val stored = StudyWorldSettings(
            durationMinutes = 240,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScopePackages = listOf(
                VideoPlatformRegistry.YOUTUBE,
                VideoPlatformRegistry.CHROME,
                VideoPlatformRegistry.NEWPIPE,
                VideoPlatformRegistry.INSTAGRAM,
                VideoPlatformRegistry.FACEBOOK,
                VideoPlatformRegistry.TIKTOK,
                VideoPlatformRegistry.SNAPCHAT
            )
        )
        val jola = evaluate(
            packageName = EntertainmentClassDetector.JOLA_VIDEO,
            screenText = "Discover Following You have not followed anyone yet",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.BLOCK, jola.decision)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, jola.reasonCode)
        assertNotEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, jola.reasonCode)

        val contacts = evaluate(
            packageName = "com.android.contacts",
            screenText = "Contacts",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.ALLOW, contacts.decision)
        assertEquals(EnforcementReasonCodes.EMERGENCY_ALLOW, contacts.reasonCode)

        val chatgpt = evaluate(
            packageName = "com.openai.chatgpt",
            screenText = "New chat",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.ALLOW, chatgpt.decision)
        assertEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, chatgpt.reasonCode)

        val playBrowse = evaluate(
            packageName = "com.android.vending",
            screenText = "Play Store Install Ratings",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.BLOCK, playBrowse.decision)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, playBrowse.reasonCode)

        val unknownChat = evaluate(
            packageName = "com.xyz.randomchat",
            screenText = "Home Discover",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.BLOCK, unknownChat.decision)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, unknownChat.reasonCode)
        assertNotEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, unknownChat.reasonCode)

        val install = evaluate(
            packageName = "com.google.android.packageinstaller",
            screenText = "Do you want to install this application App permissions Cancel",
            settings = stored,
            goal = monk,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.BLOCK, install.decision)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, install.reasonCode)
    }

    @Test
    fun lengthOnly_doesNotInventDatingBan() {
        val calculus = "allow only calculus lecture videos longer than 30 minutes for 1 hour"
        val stored = ConfirmedPromiseBinder.bind(parser.parse(calculus))
        val result = evaluate(
            packageName = EntertainmentClassDetector.JOLA_VIDEO,
            screenText = "Discover Following Video call",
            settings = stored,
            goal = calculus,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertNotEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, result.reasonCode)
    }

    @Test
    fun calculusMinLength_stillPassesYoutubeHome() {
        val calculus = "allow only calculus lecture videos longer than 30 minutes for 1 hour"
        val stored = ConfirmedPromiseBinder.bind(parser.parse(calculus))
        val result = evaluate(
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = ytHome,
            settings = stored,
            goal = calculus,
            appRule = AppRuleBehavior.AI_DECIDE
        )
        assertEquals(DecisionType.ALLOW, result.decision)
        assertEquals(EnforcementReasonCodes.SURFACE_PASS_PASSIVE, result.reasonCode)
    }

    private fun evaluate(
        packageName: String,
        screenText: String,
        settings: StudyWorldSettings,
        appRule: AppRuleBehavior?,
        goal: String = allowOnly40,
        hasActiveSession: Boolean = true
    ): PromiseEvalResult =
        PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = hasActiveSession,
                goal = goal,
                settings = settings,
                appRule = appRule,
                packageName = packageName,
                screenText = screenText
            )
        )

    private val youtubeContent40 = "allow YouTube video longer than 40 min only"
    private val youtubeAppOnly40 =
        "YouTube app only: only allow videos longer than 40 min for 1 hour"
    private val chromeYoutubeShortPlayer =
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ Hide player controls Play Pause 00:15 / 12:00"
    private val chromeGmailInbox =
        "https://mail.google.com/mail Inbox Primary Compose Search mail"
    private val newPipeShortPlayer =
        "Play Pause 00:15 / 00:59 Hide player controls"
    private val vancedYoutube = "com.vanced.android.youtube"

    @Test
    fun youtubeContent_officialAppUnderMin_blocks() {
        val draft = parser.parse(youtubeContent40)
        val stored = ConfirmedPromiseBinder.bind(draft)
        assertEquals(40, stored.minVideoLengthBlockMinutes)
        assertTrue(stored.enforcementContentBrands.contains("youtube"))
        assertTrue(
            "YouTube video is a brand, not the official package lock",
            stored.enforcementScopePackages.isEmpty()
        )
        val result = evaluate(
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = newPipeShortPlayer,
            settings = stored,
            appRule = AppRuleBehavior.AI_DECIDE,
            goal = youtubeContent40
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, result.reasonCode)
    }

    @Test
    fun youtubeContent_chromeYoutubeHostUnderMin_blocks() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(youtubeContent40))
        val result = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = chromeYoutubeShortPlayer,
            settings = stored,
            appRule = AppRuleBehavior.AI_DECIDE,
            goal = youtubeContent40
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, result.reasonCode)
        assertNotEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, result.reasonCode)
    }

    @Test
    fun youtubeContent_newPipeAndAlternateClientUnderMin_blocks() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(youtubeContent40))
        for (pkg in listOf(VideoPlatformRegistry.NEWPIPE, vancedYoutube)) {
            val result = evaluate(
                packageName = pkg,
                screenText = newPipeShortPlayer,
                settings = stored,
                appRule = AppRuleBehavior.AI_DECIDE,
                goal = youtubeContent40
            )
            assertEquals("$pkg short", DecisionType.BLOCK, result.decision)
            assertEquals(
                "$pkg code",
                EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                result.reasonCode
            )
        }
    }

    @Test
    fun youtubeContent_nonYouTubeChromePage_notBlocked_unlessChromeAppRule() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(youtubeContent40))
        val gmail = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = chromeGmailInbox,
            settings = stored,
            appRule = AppRuleBehavior.AI_DECIDE,
            goal = youtubeContent40
        )
        assertNotEquals(DecisionType.BLOCK, gmail.decision)
        assertEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, gmail.reasonCode)

        val blockChrome = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = chromeGmailInbox,
            settings = stored,
            appRule = AppRuleBehavior.BLOCK,
            goal = youtubeContent40
        )
        assertEquals(DecisionType.BLOCK, blockChrome.decision)
        assertEquals(EnforcementReasonCodes.APP_RULE_BLOCK, blockChrome.reasonCode)
    }

    @Test
    fun youtubeAppOnly_doesNotEnforceChromeYoutubeHost() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(youtubeAppOnly40))
        assertTrue(stored.enforcementScopePackages.contains(VideoPlatformRegistry.YOUTUBE))
        assertTrue(stored.enforcementContentBrands.isEmpty())
        val chrome = evaluate(
            packageName = VideoPlatformRegistry.CHROME,
            screenText = chromeYoutubeShortPlayer,
            settings = stored,
            appRule = AppRuleBehavior.AI_DECIDE,
            goal = youtubeAppOnly40
        )
        assertEquals(DecisionType.ALLOW, chrome.decision)
        assertEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, chrome.reasonCode)
        val youtube = evaluate(
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = newPipeShortPlayer,
            settings = stored,
            appRule = AppRuleBehavior.AI_DECIDE,
            goal = youtubeAppOnly40
        )
        assertEquals(DecisionType.BLOCK, youtube.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, youtube.reasonCode)
    }
}

class BlankVideoTreeGateTest {

    private val netmirror = VideoPlatformRegistry.NETMIRROR
    private val blank = ""
    private val nearBlank = "Play Pause"
    private val min40Goal = "allow only videos longer than 40 minutes for 1 hour"
    private val monkGoal = "monk mode, no entertainment, no movies for 2 hours"
    private val parser = FocusPromiseParser()

    @Test
    fun netmirror_isRegisteredMovieStreaming() {
        assertTrue(VideoPlatformRegistry.isRegistered(netmirror))
        assertTrue(VideoPlatformRegistry.enforcesMediaSurfaces(netmirror))
        assertTrue(VideoPlatformRegistry.looksLikeUnknownStreamingPackage(netmirror))
        assertEquals(
            VideoPlatformKind.MOVIE_STREAMING,
            VideoPlatformRegistry.platformKind(netmirror)
        )
    }

    @Test
    fun unknownStreamingHints_detectMovieOttPlayerPackages() {
        assertTrue(VideoPlatformRegistry.looksLikeUnknownStreamingPackage("com.foo.ott.player"))
        assertTrue(VideoPlatformRegistry.looksLikeUnknownStreamingPackage("tv.example.cinema"))
        assertTrue(VideoPlatformRegistry.looksLikeUnknownStreamingPackage("com.anime.watch.app"))
        assertFalse(VideoPlatformRegistry.looksLikeUnknownStreamingPackage("com.example.random"))
        assertFalse(VideoPlatformRegistry.looksLikeUnknownStreamingPackage("com.whatsapp"))
        assertFalse(VideoPlatformRegistry.looksLikeUnknownStreamingPackage("com.android.chrome"))
    }

    @Test
    fun sessionOff_blankNetmirror_allows() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(min40Goal))
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = false,
                goal = min40Goal,
                settings = stored,
                appRule = null,
                packageName = netmirror,
                screenText = blank
            )
        )
        assertEquals(DecisionType.ALLOW, result.decision)
        assertEquals(EnforcementReasonCodes.NO_ACTIVE_SESSION_ALLOW, result.reasonCode)
    }

    @Test
    fun sessionOn_mediaLength_blankNetmirror_waitsNotSilentAllow() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(min40Goal))
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = min40Goal,
                settings = stored,
                appRule = AppRuleBehavior.ALLOW,
                packageName = netmirror,
                screenText = blank
            )
        )
        assertEquals(DecisionType.WARN, result.decision)
        assertEquals(
            EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
            result.reasonCode
        )
        assertTrue(result.contentGatesRanBeforeAppRule)
        assertNotEquals(DecisionType.ALLOW, result.decision)
    }

    @Test
    fun youtubeContentMinLength_blankOfficialYoutube_failsClosed() {
        val text = "for 1 hour allow only YouTube video longer than 40 minutes"
        val stored = ConfirmedPromiseBinder.bind(parser.parse(text))
        assertTrue(stored.enforcementContentBrands.contains("youtube"))
        assertTrue(stored.enforcementScopePackages.isEmpty())

        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = text,
                settings = stored,
                appRule = AppRuleBehavior.ALLOW,
                packageName = VideoPlatformRegistry.YOUTUBE,
                screenText = blank
            )
        )

        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(
            EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
            result.reasonCode
        )
        assertTrue(result.contentGatesRanBeforeAppRule)
    }

    @Test
    fun sessionOn_monk_blankNetmirror_blocks() {
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = monkGoal,
                settings = StudyWorldSettings(
                    durationMinutes = 120,
                    lockAttemptThreshold = 3,
                    blockAttemptCooldownMinutes = 2
                ),
                appRule = null,
                packageName = netmirror,
                screenText = nearBlank
            )
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(
            EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE,
            result.reasonCode
        )
    }

    @Test
    fun hintDetectedUnlistedOtt_blankTree_waitsDuringMediaPromise() {
        val stored = ConfirmedPromiseBinder.bind(parser.parse(min40Goal))
        val pkg = "com.unknown.ott.stream"
        assertFalse(VideoPlatformRegistry.isRegistered(pkg))
        assertTrue(VideoPlatformRegistry.looksLikeUnknownStreamingPackage(pkg))
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = min40Goal,
                settings = stored,
                appRule = null,
                packageName = pkg,
                screenText = ""
            )
        )
        assertEquals(
            EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
            result.reasonCode
        )
        assertNotEquals(DecisionType.ALLOW, result.decision)
    }

    @Test
    fun emergencyDialer_blankTree_isNeverBlocked() {
        val result = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = "com.android.dialer",
            screenText = "",
            goal = monkGoal
        )
        assertEquals(BlankVideoTreeAction.NONE, result.action)
    }

    @Test
    fun playerClocks_areNotNearBlank() {
        assertFalse(
            BlankVideoTreeGate.isNearBlankAccessibilityTree(
                "Play Pause 00:15 / 45:00 lecture Hide player controls"
            )
        )
        assertTrue(BlankVideoTreeGate.isNearBlankAccessibilityTree(""))
        assertTrue(BlankVideoTreeGate.isNearBlankAccessibilityTree("Play Pause"))
    }

    @Test
    fun netmirrorBlank_maxTwoHours_warnsDurationUnavailable_notOverMax() {
        val text = "block videos longer than 2 hours for 1 hour"
        val stored = ConfirmedPromiseBinder.bind(parser.parse(text))
        assertEquals(120, stored.maxVideoLengthBlockMinutes)
        assertNull(stored.minVideoLengthBlockMinutes)
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = text,
                settings = stored,
                appRule = null,
                packageName = netmirror,
                screenText = blank
            )
        )
        assertEquals(DecisionType.WARN, result.decision)
        assertEquals(
            EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
            result.reasonCode
        )
        assertNotEquals(EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX, result.reasonCode)
        assertNotEquals(DecisionType.BLOCK, result.decision)
        assertEquals(
            SessionEnforcementCopy.DURATION_UNAVAILABLE,
            BlankVideoTreeGate.evaluate(
                hasActiveSession = true,
                packageName = netmirror,
                screenText = blank,
                goal = text
            ).reason
        )
        assertFalse(result.reasonCode.contains("OVER_MAX"))
    }

    @Test
    fun netmirrorBlank_blockMovies_blocks() {
        val text = "block movies for 1 hour"
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = text,
                settings = ConfirmedPromiseBinder.bind(parser.parse(text)),
                appRule = null,
                packageName = netmirror,
                screenText = nearBlank
            )
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(
            EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE,
            result.reasonCode
        )
    }

    @Test
    fun netmirrorBlank_noMovies_blocks() {
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = "no movies tonight",
                settings = StudyWorldSettings(
                    durationMinutes = 60,
                    lockAttemptThreshold = 3,
                    blockAttemptCooldownMinutes = 2
                ),
                appRule = null,
                packageName = netmirror,
                screenText = blank
            )
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(
            EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE,
            result.reasonCode
        )
    }

    @Test
    fun twoHourLength_youtubeHomePass_activeShortBlocksOnMinPromise() {
        val maxTwoHours = "block videos longer than 2 hours for 1 hour"
        val maxStored = ConfirmedPromiseBinder.bind(parser.parse(maxTwoHours))
        val home = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = maxTwoHours,
                settings = maxStored,
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = VideoPlatformRegistry.YOUTUBE,
                screenText =
                    "YouTube Home Search Shorts Subscriptions You Explore recommended for you 0:45 1:12"
            )
        )
        assertEquals(DecisionType.ALLOW, home.decision)
        assertEquals(EnforcementReasonCodes.SURFACE_PASS_PASSIVE, home.reasonCode)

        val minPromise = "do not allow videos shorter than 30 minutes for next 1 hour"
        val minStored = ConfirmedPromiseBinder.bind(parser.parse(minPromise))
        val short = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = minPromise,
                settings = minStored,
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = VideoPlatformRegistry.YOUTUBE,
                screenText = "Play Pause 00:15 / 00:59 short clip Hide player controls"
            )
        )
        assertEquals(DecisionType.BLOCK, short.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, short.reasonCode)
    }

    @Test
    fun netmirrorOnlyScope_chromeYoutubeLectureAllows() {
        val text = "block videos longer than 2 hours for 1 hour only on this app netmirror"
        val stored = ConfirmedPromiseBinder.bind(
            parser.parse(text, VideoPlatformRegistry.NETMIRROR)
        )
        assertTrue(stored.enforcementScopePackages.contains(netmirror))
        val chromeLecture =
            "Play Pause 00:12 / 42:00 OS lecture Hide player controls YouTube"
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = text,
                settings = stored,
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = VideoPlatformRegistry.CHROME,
                screenText = chromeLecture
            )
        )
        assertEquals(DecisionType.ALLOW, result.decision)
        assertEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, result.reasonCode)
        assertNotEquals(DecisionType.BLOCK, result.decision)
        assertNotEquals(DecisionType.WARN, result.decision)
    }

    @Test
    fun chromeYoutubeHomeThinDump_isNotBlankMovieTree() {
        val result = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = VideoPlatformRegistry.CHROME,
            screenText = "https://m.youtube.com Home",
            goal = min40Goal
        )
        assertEquals(BlankVideoTreeAction.NONE, result.action)
    }

    @Test
    fun chromeShortsUrlThinDump_isNotBlankMovieTree_blocksMinLength() {
        val shorts = "youtube.com/shorts/ccc333 Shorts player"
        val gate = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = VideoPlatformRegistry.CHROME,
            screenText = shorts,
            goal = min40Goal
        )
        assertEquals(BlankVideoTreeAction.NONE, gate.action)

        val stored = ConfirmedPromiseBinder.bind(parser.parse(min40Goal))
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = min40Goal,
                settings = stored,
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = VideoPlatformRegistry.CHROME,
                screenText = shorts
            )
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN, result.reasonCode)
    }

    @Test
    fun shortOnlyPromise_blankYoutubeTree_doesNotClaimDurationLimit() {
        val goal = "Block only YouTube shorts for 5 minutes"
        val result = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = nearBlank,
            goal = goal
        )

        assertFalse(PromiseIntentRules.hasMediaLengthLimit(goal))
        assertEquals(BlankVideoTreeAction.NONE, result.action)
    }

    @Test
    fun shortOnlyPromise_legacyYoutubeScope_blankTree_doesNotWarnInCoordinator() {
        val goal = "Block only YouTube shorts for 5 minutes"
        val settings = StudyWorldSettings(
            durationMinutes = 5,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScopePackages = listOf(VideoPlatformRegistry.YOUTUBE)
        )
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = goal,
                settings = settings,
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = VideoPlatformRegistry.YOUTUBE,
                screenText = nearBlank
            )
        )

        assertNotEquals(DecisionType.WARN, result.decision)
        assertNotEquals(
            EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
            result.reasonCode
        )
    }

    @Test
    fun officialYoutube_minLengthPromise_blankTree_hardBlocks() {
        val goal = "for 1 hour allow only YouTube video longer than 40 minutes"
        val result = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText = "Play Pause",
            goal = goal,
            contentBrands = listOf(EnforcementScopeLaw.BRAND_YOUTUBE)
        )
        assertEquals(BlankVideoTreeAction.BLOCK, result.action)
        assertEquals(
            EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
            result.reasonCode
        )
        assertEquals(SessionEnforcementCopy.YOUTUBE_CLOCK_UNREADABLE_BLOCK, result.reason)
        assertFalse(result.reason.contains("carefully", ignoreCase = true))
    }

    @Test
    fun unknownOtt_minLengthPromise_blankTree_stillWaits() {
        val goal = "for 1 hour allow only YouTube video longer than 40 minutes"
        val result = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = "com.unknown.ott.stream",
            screenText = "Play Pause",
            goal = goal
        )
        assertEquals(BlankVideoTreeAction.WAIT, result.action)
        assertEquals(
            EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
            result.reasonCode
        )
        assertEquals(SessionEnforcementCopy.DURATION_UNAVAILABLE, result.reason)
    }
}
