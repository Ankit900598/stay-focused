package com.phonecodex.app.domain.promise

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseIntentRulesTest {

    @Test
    fun studyOnlyBlocksShortForm() {
        assertTrue(PromiseIntentRules.blocksShortForm("Only study videos for 30 minutes"))
    }

    @Test
    fun explicitShortsAllowanceWinsForQuotaPromises() {
        assertTrue(PromiseIntentRules.allowsShortForm("Allow 40 shorts today but no adult content"))
        assertTrue(
            PromiseIntentRules.allowsShortForm(
                "allow me to watch only up to Max 10 shots and remind me how much Sort is left"
            )
        )
    }

    @Test
    fun chromeExceptionDoesNotMeanShortsBan() {
        val goal = "allow everything on chrome except youtube whose length is greater than 20 min for next 1 hrs"

        assertTrue(PromiseIntentRules.allowsBroadChromeUse(goal))
        assertFalse(PromiseIntentRules.blocksShortForm(goal))
        assertTrue(PromiseIntentRules.hasLongYouTubeLimit(goal))
    }

    @Test
    fun blockOnlyLongYoutube_doesNotBanShorts() {
        val goal = "block YouTube videos longer than 20 min for next 1 hour"

        assertTrue(PromiseIntentRules.hasLongYouTubeLimit(goal))
        assertTrue(PromiseIntentRules.hasMediaLengthLimit(goal))
        assertFalse(PromiseIntentRules.blocksShortForm(goal))
    }

    @Test
    fun anyVideoLengthPromise_countsAsMediaLengthWithoutYouTubeWord() {
        val goal =
            "for 1 hour do not allow to watch any video whose length is greater than 40 min"

        assertTrue(PromiseIntentRules.hasMediaLengthLimit(goal))
        assertFalse(PromiseIntentRules.blocksShortForm(goal))
        assertTrue(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = goal,
                screenText = "Hide player controls Time duration 1 hour 10 minutes YouTube"
            )
        )
    }

    @Test
    fun normalChromePage_notBlockedByVideoLengthPromise() {
        val goal =
            "for 1 hour do not allow to watch any video whose length is greater than 40 min"

        assertFalse(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = goal,
                screenText = "Wikipedia Android (operating system) Edit Search"
            )
        )
        assertFalse(
            PromiseIntentRules.needsMoreChromeYouTubeDurationText(
                goal = goal,
                screenText = "Wikipedia Android (operating system) Edit Search"
            )
        )
    }

    @Test
    fun explicitNoShortsBlocksShortForm() {
        assertTrue(PromiseIntentRules.blocksShortForm("Use YouTube for OS lectures but no shorts"))
    }

    @Test
    fun chromeYouTubePlayerSurfaceDetected() {
        assertTrue(
            PromiseIntentRules.isYouTubeSurfaceInChrome(
                "Hide player controls Playback Settings Search YouTube More options"
            )
        )
    }

    @Test
    fun chromeYouTubeDurationOverGoalLimitDetected() {
        val goal = "allow everything on chrome except youtube whose length is greater than 20 min"

        assertTrue(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = goal,
                screenText = "Hide player controls Time duration 1 hour 25 minutes YouTube"
            )
        )
    }

    @Test
    fun chromeYouTubeDurationUnderGoalLimitAllowedByRule() {
        val goal = "allow everything on chrome except youtube whose length is greater than 20 min"

        assertFalse(
            PromiseIntentRules.chromeYouTubeVideoExceedsGoalLimit(
                goal = goal,
                screenText = "Hide player controls Time duration 12 minutes YouTube"
            )
        )
    }

    @Test
    fun chromeYouTubeSurfaceWithoutDurationWaitsForMoreText() {
        val goal = "allow everything on chrome except youtube whose length is greater than 20 min"

        assertTrue(
            PromiseIntentRules.needsMoreChromeYouTubeDurationText(
                goal = goal,
                screenText = "Hide player controls Search YouTube"
            )
        )
    }

    @Test
    fun driveDocsAreUnrelatedProductivityUnlessNamedBlocked() {
        assertTrue(
            PromiseIntentRules.isUnrelatedProductivityPackage(
                "com.google.android.apps.docs",
                "no porn for 1 year rest normal"
            )
        )
        assertFalse(
            PromiseIntentRules.isUnrelatedProductivityPackage(
                "com.google.android.apps.docs",
                "block docs and drive for 2 hours"
            )
        )
    }

    @Test
    fun explicitKnownPackageBlockUnderstandsMessyWhatsappPromise() {
        assertTrue(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "dp not allow to use whatsapp fo 3 days",
                packageName = "com.whatsapp"
            )
        )
        assertTrue(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "allow everything except whatsapp for 1 hour",
                packageName = "com.whatsapp"
            )
        )
        assertFalse(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "study for 30 minutes, whatsapp ok",
                packageName = "com.whatsapp"
            )
        )
        assertFalse(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "dp not allow to use whatsapp fo 3 days",
                packageName = "com.android.phone"
            )
        )
    }

    @Test
    fun monkAndNoMovies_blockEntertainment_mediaLengthAloneDoesNot() {
        assertTrue(PromiseIntentRules.isLifestyleEntertainmentBan("monk mode for 2 hours"))
        assertTrue(PromiseIntentRules.isLifestyleEntertainmentBan("study only for 3 hours"))
        assertFalse(PromiseIntentRules.isLifestyleEntertainmentBan("no movies tonight"))
        assertFalse(PromiseIntentRules.isLifestyleEntertainmentBan("block this app"))
        assertFalse(PromiseIntentRules.isLifestyleEntertainmentBan("no games for 4 hours"))
        assertFalse(PromiseIntentRules.isLifestyleEntertainmentBan("no social media tonight"))
        assertTrue(PromiseIntentRules.blocksGameCategory("no games for 4 hours"))
        assertTrue(PromiseIntentRules.blocksSocialCategory("no social media tonight"))
        assertFalse(PromiseIntentRules.blocksGameCategory("no social media tonight"))
        assertTrue(PromiseIntentRules.blocksGirlsChatCategory("no girls chat"))
        assertTrue(PromiseIntentRules.blocksMusicCategory("don't listen to music"))
        assertFalse(PromiseIntentRules.isLifestyleEntertainmentBan("don't listen to music"))
        assertTrue(PromiseIntentRules.blocksEntertainmentOrMovies("monk mode for 2 hours"))
        assertTrue(PromiseIntentRules.blocksEntertainmentOrMovies("no movies tonight"))
        assertTrue(PromiseIntentRules.blocksEntertainmentOrMovies("no entertainment during study"))
        assertTrue(PromiseIntentRules.blocksEntertainmentOrMovies("block movies"))
        assertTrue(PromiseIntentRules.blocksEntertainmentOrMovies("block this app"))
        assertTrue(
            PromiseIntentRules.blocksEntertainmentOrMovies(
                "Monk mode for 4 hours. Only calls and study. No social, no shorts, no games."
            )
        )
        assertTrue(PromiseIntentRules.blocksEntertainmentOrMovies("no social media tonight"))
        assertTrue(PromiseIntentRules.blocksEntertainmentOrMovies("no games for 4 hours"))
        assertFalse(
            PromiseIntentRules.blocksEntertainmentOrMovies(
                "allow only videos longer than 40 minutes for 1 hour"
            )
        )
        assertFalse(
            PromiseIntentRules.blocksEntertainmentOrMovies(
                "block videos longer than 2 hours for 1 hour"
            )
        )
        assertTrue(PromiseIntentRules.hasMediaLengthLimit("block videos longer than 2 hours for 1 hour"))
        assertTrue(PromiseIntentRules.namesThisAppOnly("only lock this app's content"))
        assertTrue(PromiseIntentRules.namesNetMirror("block netmirror movies"))
        assertTrue(
            PromiseIntentRules.namesYouTubeContentBrand(
                "allow YouTube video longer than 40 min only"
            )
        )
        assertFalse(
            PromiseIntentRules.namesYouTubeAppOnly(
                "allow YouTube video longer than 40 min only"
            )
        )
        assertTrue(PromiseIntentRules.namesYouTubeAppOnly("YouTube app only for 1 hour"))
        assertFalse(
            PromiseIntentRules.namesYouTubeContentBrand("YouTube app only for 1 hour")
        )
    }
}
