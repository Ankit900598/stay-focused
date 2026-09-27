package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentRuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPromiseParserTest {

    private val parser = FocusPromiseParser()

    @Test
    fun brokenGrammar_allowLongYoutubeOrChrome_asksClarification_andDoesNotStealSessionDuration() {
        val draft = parser.parse(
            "allow me youtube chrome videos longer than 40 min for study"
        )

        assertEquals(30, draft.sessionDurationMinutes)
        assertTrue(draft.needsClarification)
        assertNotNull(draft.clarificationQuestion)
        assertTrue(
            draft.contentRules.any {
                it.operator == "gt" &&
                    it.value == 40 &&
                    it.action == ContentRuleAction.ALLOW &&
                    (it.packageName == "com.google.android.youtube" ||
                        it.packageName == "com.android.chrome" ||
                        it.appLabel == null)
            }
        )
        assertTrue(draft.sessionDurationMinutes != 40)
    }

    @Test
    fun onlyAllowYoutubeLongerThan40_allowsLongBlocksShorter_noClarification() {
        val draft = parser.parse(
            "only allow YouTube videos longer than 40 min for next 2 hours"
        )

        assertEquals(120, draft.sessionDurationMinutes)
        assertNull(draft.clarificationQuestion)
        assertTrue(
            draft.contentRules.any {
                it.packageName == "com.google.android.youtube" &&
                    it.operator == "gt" &&
                    it.value == 40 &&
                    it.action == ContentRuleAction.ALLOW
            }
        )
        assertTrue(
            draft.contentRules.any {
                it.packageName == "com.google.android.youtube" &&
                    it.operator == "lte" &&
                    it.value == 40 &&
                    it.action == ContentRuleAction.BLOCK
            }
        )
        assertEquals(
            AppRuleBehavior.AI_DECIDE,
            draft.suggestedAppRules.first { it.packageName == "com.google.android.youtube" }.behavior
        )
        assertTrue(draft.allowedSummaries.any { it.contains("longer than", ignoreCase = true) })
        assertTrue(draft.blockedSummaries.any { it.contains("40", ignoreCase = true) })
    }

    @Test
    fun allowGreaterThan40WithoutOnly_requiresClarification() {
        val draft = parser.parse(
            "allow YouTube videos greater than 40 min for next 1 hour"
        )

        assertEquals(60, draft.sessionDurationMinutes)
        assertTrue(draft.needsClarification)
        assertTrue(
            draft.clarificationQuestion!!.contains("shorter", ignoreCase = true)
        )
    }

    @Test
    fun whoseLengthIsGreaterThan_chromeAnywhere_messyGrammar() {
        val draft = parser.parse(
            "allow everything on chrome and anywhere youtube videos whose length is greater than 20 min for next 1 hrs"
        )

        assertEquals(60, draft.sessionDurationMinutes)
        assertTrue(draft.contentRules.any { it.operator == "gt" && it.value == 20 })
        assertTrue(
            draft.suggestedAppRules.any { it.packageName == "com.android.chrome" }
        )
        assertTrue(
            draft.suggestedAppRules.any { it.packageName == "com.google.android.youtube" }
        )
    }

    @Test
    fun makeMeStrictToday_requiresClarification() {
        val draft = parser.parse("make me strict today")

        assertTrue(draft.needsClarification)
        assertNotNull(draft.clarificationQuestion)
        assertEquals(24 * 60, draft.sessionDurationMinutes)
    }

    @Test
    fun focusMode_requiresClarification() {
        val draft = parser.parse("focus mode")

        assertTrue(draft.needsClarification)
        assertTrue(draft.clarificationQuestion!!.contains("Focus", ignoreCase = true))
    }

    @Test
    fun kalOsExam_nesoPlaylist_3Hours() {
        val draft = parser.parse(
            "kal OS exam hai only Neso Academy playlist 3 hours"
        )

        assertEquals(180, draft.sessionDurationMinutes)
        assertFalse(draft.needsClarification)
        assertTrue(
            draft.suggestedAppRules.any {
                it.packageName == "com.google.android.youtube" &&
                    it.behavior == AppRuleBehavior.AI_DECIDE
            }
        )
        assertTrue(
            draft.contentRules.any {
                it.packageName == "com.google.android.youtube" &&
                    it.surface == "playlist"
            }
        )
        assertTrue(draft.allowedSummaries.any { it.contains("Study", ignoreCase = true) })
    }

    @Test
    fun noPornFor1YearRestNormal() {
        val draft = parser.parse("no porn for 1 year rest normal")

        assertEquals(365 * 24 * 60, draft.sessionDurationMinutes)
        assertTrue(
            draft.contentRules.any {
                it.contentType == "adult_sexual" && it.action == ContentRuleAction.BLOCK
            }
        )
        assertTrue(draft.cautionMessages.any { it.contains("normal", ignoreCase = true) })
        assertFalse(
            draft.suggestedAppRules.any {
                it.packageName == "com.google.android.apps.docs"
            }
        )
    }

    @Test
    fun blockYoutubeLongerThan20_forNext1Hour_splitsContentAndSession() {
        val draft = parser.parse(
            "block YouTube videos longer than 20 min for next 1 hour"
        )

        assertEquals(60, draft.sessionDurationMinutes)
        assertFalse(draft.needsClarification)
        assertTrue(
            draft.contentRules.any {
                it.packageName == "com.google.android.youtube" &&
                    it.operator == "gt" &&
                    it.value == 20 &&
                    it.action == ContentRuleAction.BLOCK
            }
        )
        // Length threshold → AI_DECIDE app rule, not whole-app BLOCK (Shorts stay usable).
        assertEquals(
            AppRuleBehavior.AI_DECIDE,
            draft.suggestedAppRules.first { it.packageName == "com.google.android.youtube" }.behavior
        )
    }

    @Test
    fun blockYoutubeGtSymbol_forNext1Hour() {
        val draft = parser.parse("block YouTube videos > 20 min for next 1 hour")

        assertEquals(60, draft.sessionDurationMinutes)
        assertTrue(
            draft.contentRules.any {
                it.operator == "gt" && it.value == 20 && it.action == ContentRuleAction.BLOCK
            }
        )
    }

    @Test
    fun instagramOnlyMessages_noReels_for7Days() {
        val draft = parser.parse(
            "use Instagram only for messages for 7 days. no reels"
        )

        assertEquals(7 * 24 * 60, draft.sessionDurationMinutes)
        // "Only messages" also means no feed, which only typed scope can enforce.
        assertEquals(LegacyScopeContractGuard.SOCIAL_MESSAGING_SURFACE, draft.legacyContractGap)
        assertTrue(draft.needsClarification)
        assertTrue(
            draft.contentRules.any {
                it.packageName == "com.instagram.android" &&
                    it.surface == "messages" &&
                    it.action == ContentRuleAction.ALLOW
            }
        )
        assertTrue(
            draft.contentRules.any {
                it.packageName == "com.instagram.android" &&
                    it.surface == "reels" &&
                    it.action == ContentRuleAction.BLOCK
            }
        )
        assertEquals(
            AppRuleBehavior.AI_DECIDE,
            draft.suggestedAppRules.first { it.packageName == "com.instagram.android" }.behavior
        )
    }

    @Test
    fun noPornFor1Year_sessionIsYear_andAdultBlocked() {
        val draft = parser.parse("No adult content for 1 year. Locked.")

        assertEquals(365 * 24 * 60, draft.sessionDurationMinutes)
        assertTrue(
            draft.contentRules.any {
                it.contentType == "adult_sexual" && it.action == ContentRuleAction.BLOCK
            }
        )
        assertEquals(
            com.phonecodex.app.domain.model.StrictnessLevel.LOCKED,
            draft.strictness
        )
    }

    @Test
    fun beStrictToday_requiresClarification() {
        val draft = parser.parse("be strict today")

        assertTrue(draft.needsClarification)
        assertNotNull(draft.clarificationQuestion)
        assertTrue(draft.clarificationQuestion!!.contains("apps", ignoreCase = true))
        assertEquals(24 * 60, draft.sessionDurationMinutes)
    }

    @Test
    fun useInstaLess_requiresClarification() {
        val draft = parser.parse("use insta less")

        assertTrue(draft.needsClarification)
        assertNotNull(draft.clarificationQuestion)
        assertTrue(draft.clarificationQuestion!!.contains("Instagram", ignoreCase = true))
    }

    @Test
    fun studyOsExam_nesoYoutube_3Hours_conditionalYoutube() {
        val draft = parser.parse(
            "Tomorrow I have OS exam. Keep me only on Neso Academy OS playlist on " +
                "YouTube for 3 hours. No shorts."
        )

        assertEquals(180, draft.sessionDurationMinutes)
        assertFalse(draft.needsClarification)
        assertTrue(
            draft.suggestedAppRules.any {
                it.packageName == "com.google.android.youtube" &&
                    it.behavior == AppRuleBehavior.AI_DECIDE
            }
        )
        assertTrue(
            draft.contentRules.any {
                it.packageName == "com.google.android.youtube" &&
                    it.action == ContentRuleAction.AI_DECIDE
            }
        )
    }

    @Test
    fun allowLongVideos_withShorterPolicy_noClarification() {
        val draft = parser.parse(
            "allow YouTube videos longer than 40 min and block shorter ones for next 2 hours"
        )

        assertEquals(120, draft.sessionDurationMinutes)
        assertNull(draft.clarificationQuestion)
    }

    @Test
    fun greaterThanWithoutAllowVerb_stillTreatsAsContentRule_notSession() {
        val draft = parser.parse(
            "youtube videos greater than 40 minutes during my focus block for next 90 minutes"
        )

        assertEquals(90, draft.sessionDurationMinutes)
        assertTrue(draft.contentRules.any { it.value == 40 && it.operator == "gt" })
        assertTrue(draft.needsClarification)
    }

    @Test
    fun hinglishMessy_instagramCollegeReplies() {
        val draft = parser.parse(
            "yaar Instagram only college replies ke liye for 20 minutes, no reels please"
        )

        assertEquals(20, draft.sessionDurationMinutes)
        assertTrue(
            draft.contentRules.any {
                it.surface == "messages" && it.action == ContentRuleAction.ALLOW
            }
        )
        assertTrue(
            draft.contentRules.any {
                it.surface == "reels" && it.action == ContentRuleAction.BLOCK
            }
        )
    }

    @Test
    fun hinglish_padhai2Hours_youtubeLectures() {
        val draft = parser.parse("bhai 2 hours padhai, only youtube lectures, no shorts")

        assertEquals(120, draft.sessionDurationMinutes)
        assertFalse(draft.needsClarification)
        assertTrue(draft.suggestedAppRules.any { it.packageName == "com.google.android.youtube" })
    }

    @Test
    fun hinglish_aajStrictMatKar_vague() {
        val draft = parser.parse("aaj focus mode chahiye")

        assertTrue(draft.needsClarification)
    }

    @Test
    fun hinglish_instagramDmOnlyKalTak() {
        val draft = parser.parse(
            "instagram sirf messages ke liye for 1 day, reels mat dikhana"
        )

        assertEquals(24 * 60, draft.sessionDurationMinutes)
        assertTrue(draft.contentRules.any { it.surface == "messages" })
        assertTrue(draft.contentRules.any { it.surface == "reels" && it.action == ContentRuleAction.BLOCK })
    }

    @Test
    fun hinglish_youtube40SeLambaOnly() {
        val draft = parser.parse(
            "only allow youtube videos longer than 40 min for next 90 minutes"
        )

        assertNull(draft.clarificationQuestion)
        assertTrue(draft.contentRules.any { it.operator == "lte" && it.action == ContentRuleAction.BLOCK })
    }

    @Test
    fun hinglish_chromeMeStudySearch() {
        val draft = parser.parse(
            "Chrome only educational search ke liye for 45 minutes. Shopping band."
        )

        assertEquals(45, draft.sessionDurationMinutes)
        assertTrue(
            draft.suggestedAppRules.any {
                it.packageName == "com.android.chrome" &&
                    it.behavior == AppRuleBehavior.AI_DECIDE
            }
        )
    }

    @Test
    fun hinglish_noAdultEkSaal() {
        val draft = parser.parse("1 year tak no porn, baaki normal")

        assertEquals(365 * 24 * 60, draft.sessionDurationMinutes)
        assertTrue(draft.contentRules.any { it.contentType == "adult_sexual" })
    }

    @Test
    fun hinglish_nesoOsKal() {
        val draft = parser.parse(
            "kal OS paper hai, 3 hours sirf Neso Academy youtube playlist"
        )

        assertEquals(180, draft.sessionDurationMinutes)
        assertTrue(draft.contentRules.any { it.surface == "playlist" })
    }

    @Test
    fun hinglish_blockYtBadaWala() {
        val draft = parser.parse(
            "block youtube videos longer than 20 min for next 1 hour, shorts ok"
        )

        assertEquals(60, draft.sessionDurationMinutes)
        assertEquals(
            AppRuleBehavior.AI_DECIDE,
            draft.suggestedAppRules.first { it.packageName == "com.google.android.youtube" }.behavior
        )
    }

    @Test
    fun hinglish_whatsappOkTelegramSoft() {
        val draft = parser.parse("study for 30 minutes, whatsapp ok, telegram soft warn")

        assertEquals(30, draft.sessionDurationMinutes)
        assertTrue(draft.suggestedAppRules.any { it.packageName == "com.whatsapp" })
        assertTrue(draft.suggestedAppRules.any { it.packageName == "org.telegram.messenger" })
    }

    @Test
    fun messyNoWhatsapp_compilesWholeAppBlock() {
        val draft = parser.parse("dp not allow to use whatsapp fo 3 days")

        assertEquals(
            AppRuleBehavior.BLOCK,
            draft.suggestedAppRules.first { it.packageName == "com.whatsapp" }.behavior
        )
    }

    @Test
    fun allowEverythingExceptWhatsapp_compilesWholeAppBlock() {
        val draft = parser.parse("allow everything except whatsapp for next 1 hour")

        assertEquals(
            AppRuleBehavior.BLOCK,
            draft.suggestedAppRules.first { it.packageName == "com.whatsapp" }.behavior
        )
    }

    @Test
    fun hinglish_makeMeStrictAaj() {
        val draft = parser.parse("make me strict aaj")

        assertTrue(draft.needsClarification)
    }

    @Test
    fun monkModeTwoHours_lockedStrictness() {
        val draft = parser.parse("Monk mode for 2 hours. Only calls and study.")

        assertEquals(120, draft.sessionDurationMinutes)
        assertEquals(
            com.phonecodex.app.domain.model.StrictnessLevel.LOCKED,
            draft.strictness
        )
        assertFalse(draft.needsClarification)
    }

    @Test
    fun chromeEducationalSearch_suggestsChromeAiDecide() {
        val draft = parser.parse(
            "Allow Chrome only for educational search for 45 minutes. Block shopping."
        )

        assertEquals(45, draft.sessionDurationMinutes)
        assertTrue(
            draft.suggestedAppRules.any {
                it.packageName == "com.android.chrome" &&
                    it.behavior == AppRuleBehavior.AI_DECIDE
            }
        )
    }

    @Test
    fun messyAllowLongerThan50ForOneHours_session60Min50_noClarification() {
        val draft = parser.parse(
            "allow video longer than 50 min for one hours shorter video block"
        )

        assertEquals(60, draft.sessionDurationMinutes)
        assertFalse(draft.needsClarification)
        assertNull(draft.clarificationQuestion)
        assertTrue(
            draft.contentRules.any {
                it.operator == "gt" &&
                    it.value == 50 &&
                    it.action == ContentRuleAction.ALLOW
            }
        )
        assertTrue(
            draft.contentRules.any {
                it.action == ContentRuleAction.BLOCK &&
                    (it.operator == "lt" || it.operator == "lte") &&
                    it.value == 50
            }
        )
        assertEquals(50, PromiseContentRuleSupport.minVideoLengthBlockMinutes(draft))
    }

    @Test
    fun messyOneHourPhrases_allBindToSixtyMinutes() {
        val phrases = listOf(
            "1 hrs",
            "1 hr",
            "one hours",
            "for one hour",
            "for 1 hour",
            "no shorts for 1 hrs",
            "allow only videos longer than 40 minutes for 1 hrs"
        )
        for (phrase in phrases) {
            val draft = parser.parse(phrase)
            val stored = ConfirmedPromiseBinder.bind(draft)
            assertEquals("$phrase session", 60, draft.sessionDurationMinutes)
            assertEquals("$phrase bound", 60, stored.durationMinutes)
            assertEquals(
                "$phrase millis",
                60 * 60_000L,
                ConfirmedPromiseBinder.sessionDurationMillis(draft)
            )
        }
    }

    @Test
    fun allowEverythingOnChrome_suggestsAllow() {
        val draft = parser.parse(
            "allow everything on chrome except youtube whose length is greater than 20 min for next 1 hrs"
        )

        assertEquals(
            AppRuleBehavior.ALLOW,
            draft.suggestedAppRules.first { it.packageName == "com.android.chrome" }.behavior
        )
    }

    @Test
    fun netmirrorThisApp_scopesOnlyToNetMirror() {
        val draft = parser.parse(
            "block videos longer than 2 hours for 1 hour only on this app netmirror",
            "app.netmirror.newtv"
        )
        assertTrue(draft.scopePackages.contains("app.netmirror.newtv"))
        assertEquals(
            "app.netmirror.newtv",
            ConfirmedPromiseBinder.bind(draft).enforcementScopePackages.single()
        )
        assertFalse(draft.scopePackages.contains("com.android.chrome"))
        assertFalse(draft.scopePackages.contains("com.google.android.youtube"))
        assertEquals(120, ConfirmedPromiseBinder.bind(draft).maxVideoLengthBlockMinutes)
    }

    @Test
    fun thisAppHint_withoutNetMirrorWord_stillScopesHintPackage() {
        val draft = parser.parse(
            "only lock this app's content",
            "app.netmirror.newtv"
        )
        assertTrue(draft.scopePackages.contains("app.netmirror.newtv"))
        assertEquals(
            AppRuleBehavior.AI_DECIDE,
            draft.suggestedAppRules.first { it.packageName == "app.netmirror.newtv" }.behavior
        )
    }

    @Test
    fun blockThisAppNetMirror_suggestsBlock() {
        val draft = parser.parse("block this app netmirror", "app.netmirror.newtv")
        assertEquals(
            AppRuleBehavior.BLOCK,
            draft.suggestedAppRules.first { it.packageName == "app.netmirror.newtv" }.behavior
        )
    }

    @Test
    fun genericTwoHourVideoPromise_doesNotScopeToOnePackage() {
        val draft = parser.parse("block videos longer than 2 hours for 1 hour")
        assertTrue(draft.scopePackages.isEmpty())
        assertTrue(ConfirmedPromiseBinder.bind(draft).enforcementScopePackages.isEmpty())
    }

    @Test
    fun youtubeVideoPromise_bindsContentBrandNotOfficialPackage() {
        val draft = parser.parse("allow YouTube video longer than 40 min only")
        val stored = ConfirmedPromiseBinder.bind(draft)
        assertTrue(draft.contentBrands.contains("youtube"))
        assertEquals("youtube_content", draft.scopeKind)
        assertTrue(draft.scopePackages.isEmpty())
        assertTrue(stored.enforcementContentBrands.contains("youtube"))
        assertTrue(stored.enforcementScopePackages.isEmpty())
        assertEquals(40, stored.minVideoLengthBlockMinutes)
        assertFalse(draft.needsClarification)
    }

    @Test
    fun youtubeAppOnly_bindsOfficialPackageNotContentBrand() {
        val draft = parser.parse(
            "YouTube app only: only allow videos longer than 40 min for 1 hour"
        )
        val stored = ConfirmedPromiseBinder.bind(draft)
        assertTrue(PromiseIntentRules.namesYouTubeAppOnly(draft.rawText))
        assertTrue(stored.enforcementScopePackages.contains("com.google.android.youtube"))
        assertTrue(stored.enforcementContentBrands.isEmpty())
        assertFalse(stored.enforcementScopePackages.contains("com.android.chrome"))
    }
}
