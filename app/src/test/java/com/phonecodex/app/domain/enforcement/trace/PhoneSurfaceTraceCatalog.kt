package com.phonecodex.app.domain.enforcement.trace

import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.EnforcementScopeLaw
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings

/**
 * Realistic Accessibility traces. Add a new phone bug here first, then fix law.
 */
object PhoneSurfaceTraceCatalog {

    private val YT = VideoPlatformRegistry.YOUTUBE
    private val CHROME = VideoPlatformRegistry.CHROME
    private val NEWPIPE = VideoPlatformRegistry.NEWPIPE
    private val IG = VideoPlatformRegistry.INSTAGRAM
    private val FB = VideoPlatformRegistry.FACEBOOK
    private val TT = VideoPlatformRegistry.TIKTOK
    private val VANCED = "com.vanced.android.youtube"

    private val MIN40_GOAL = "allow only YouTube videos longer than 40 minutes"
    private val MIN40_HINGLISH = "sirf 40 min se lamba youtube video chalne do"
    private val MAX40_GOAL = "for 1 hour do not allow to watch any video whose length is greater than 40 min"
    private val QUOTA_GOAL = "I want to watch at most 10 shorts today, but never adult shorts"
    private val MONK_GOAL = "Monk mode for 4 hours. Only calls and study. No entertainment."
    private val MOVIE_GOAL = "block movies and ott for 1 hour"
    private val APP_ONLY_GOAL = "YouTube app only: only allow videos longer than 40 min for 1 hour"

    fun all(): List<EnforcementTrace> =
        chromeYoutubeHome() +
            chromeWatchClocks() +
            chromeShorts() +
            youtubeApp() +
            newPipeAndClients() +
            chromeNormalPages() +
            passiveAndSystem() +
            lockAndSafe() +
            quotaAndAdult() +
            blankAndAmbiguous() +
            socialReels() +
            hinglishAndMessy() +
            extraCoverage() +
            scopePersistenceCorrupt()

    private fun min40Yt(min: Int = 40) = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        minVideoLengthBlockMinutes = min,
        enforcementContentBrands = listOf(EnforcementScopeLaw.BRAND_YOUTUBE),
        enforcementScopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_CONTENT
    )

    private fun max40Any() = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        maxVideoLengthBlockMinutes = 40
    )

    private fun appOnly40() = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        minVideoLengthBlockMinutes = 40,
        enforcementScopePackages = listOf(YT),
        enforcementScopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
    )

    private fun quota10() = StudyWorldSettings(
        durationMinutes = 1440,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        shortFormDailyQuotaLimit = 10,
        timeWindowKind = "calendar_day"
    )

    private fun monk() = StudyWorldSettings(
        durationMinutes = 240,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2
    )

    private fun movies() = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2
    )

    private fun t(
        id: String,
        pkg: String,
        screen: String,
        goal: String,
        settings: StudyWorldSettings,
        expected: Set<TraceDecision>,
        reasons: Set<String> = emptySet(),
        forbidden: Set<String> = emptySet(),
        surface: String? = null,
        activity: String? = null,
        duration: String? = null,
        session: SessionStatus = SessionStatus.ACTIVE,
        adult: Boolean = false,
        quotaUsed: Int = 0,
        appRule: AppRuleBehavior? = AppRuleBehavior.AI_DECIDE,
        copy: String? = null,
        notes: String = ""
    ) = EnforcementTrace(
        id = id,
        packageName = pkg,
        screenText = screen,
        goal = goal,
        settings = settings,
        sessionStatus = session,
        appRule = appRule,
        permanentAdultGuardrail = adult,
        quotaAlreadyCounted = quotaUsed,
        expectedDecisions = expected,
        expectedReasonCodes = reasons,
        forbiddenReasonCodes = forbidden,
        expectedSurface = surface,
        expectedActivity = activity,
        expectedDurationSource = duration,
        userFacingCopy = copy,
        notes = notes
    )

    private fun chromeYoutubeHome(): List<EnforcementTrace> = listOf(
        t(
            id = "chrome_yt_home_passive",
            pkg = CHROME,
            screen = "https://m.youtube.com Home Shorts Subscriptions You Library " +
                "recommended for you 0:45 1:12 Chip design 8 minutes 55 seconds",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.SURFACE_PASS_PASSIVE),
            forbidden = setOf(
                EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
            ),
            activity = "PASSIVE",
            notes = "Chrome YouTube home must not enforce length"
        ),
        t(
            id = "chrome_yt_home_no_https",
            pkg = CHROME,
            screen = "m.youtube.com Home Shorts Subscriptions You Explore " +
                "Trending Music Gaming News",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "yt_app_home_passive",
            pkg = YT,
            screen = "YouTube Home Search Shorts Subscriptions You Explore " +
                "recommended for you 0:45 1:12",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.SURFACE_PASS_PASSIVE)
        )
    )

    private fun chromeWatchClocks(): List<EnforcementTrace> = listOf(
        t(
            id = "chrome_yt_spoken_31m33_blocks_min40",
            pkg = CHROME,
            screen = "Time elapsed 1 secondTime duration 31 minutes, 33 seconds",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK),
            duration = "CURRENT_PLAYER",
            notes = "REGRESSION: spoken 31:33 used to WAIT"
        ),
        t(
            id = "chrome_yt_spoken_41m_allows_min40",
            pkg = CHROME,
            screen = "Time elapsed 1 secondTime duration 41 minutes",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT),
            duration = "CURRENT_PLAYER"
        ),
        t(
            id = "chrome_yt_spoken_hour_comma",
            pkg = CHROME,
            screen = "Time elapsed 8 secondsTime duration 1 hour, 2 minutes, 3 seconds",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT)
        ),
        t(
            id = "chrome_yt_clock_pair_31_33",
            pkg = CHROME,
            screen = "https://www.youtube.com/watch?v=abc Hide player controls 0:01 / 31:33",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "min40_boundary_40_00_blocks_strict_longer_than",
            pkg = CHROME,
            screen = "Hide player controls YouTube Time duration 40 minutes",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN),
            notes = "Strict English: 'longer than 40' excludes exactly 40:00."
        ),
        t(
            id = "min40_boundary_40_01_allows",
            pkg = CHROME,
            screen = "Hide player controls YouTube 0:01 / 40:01",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT)
        ),
        t(
            id = "max40_boundary_40_00_allows",
            pkg = CHROME,
            screen = "Hide player controls youtube.com Time duration 40 minutes",
            goal = MAX40_GOAL,
            settings = max40Any(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT)
        ),
        t(
            id = "max40_boundary_40_01_blocks",
            pkg = CHROME,
            screen = "Hide player controls youtube.com Time duration 41 minutes",
            goal = MAX40_GOAL,
            settings = max40Any(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX)
        ),
        t(
            id = "chrome_yt_embed_watch",
            pkg = CHROME,
            screen = "https://www.youtube.com/embed/abc Hide player controls " +
                "Time duration 22 minutes, 10 seconds Play Pause",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "scope_content_chrome_not_official_only",
            pkg = CHROME,
            screen = "https://m.youtube.com/watch?v=x Time duration 12 minutes Hide player controls",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN),
            forbidden = setOf(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW),
            notes = "REGRESSION: YouTube content must hit Chrome youtube.com"
        ),
        t(
            id = "scope_app_only_chrome_watch_out",
            pkg = CHROME,
            screen = "https://m.youtube.com/watch?v=x Time duration 12 minutes Hide player controls",
            goal = APP_ONLY_GOAL,
            settings = appOnly40(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW),
            notes = "REGRESSION contrast: YouTube app only leaves Chrome out"
        )
    )

    private fun chromeShorts(): List<EnforcementTrace> = listOf(
        t(
            id = "chrome_shorts_url_min40_blocks",
            pkg = CHROME,
            screen = "Address bar https://www.youtube.com/shorts/dQw4w9WgXcQ " +
                "Like Share Comments Shorts player swipe up",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN),
            duration = "NONE"
        ),
        t(
            id = "chrome_m_shorts_no_duration_blocks",
            pkg = CHROME,
            screen = "m.youtube.com/shorts Back Search Like Share " +
                "The Blueprint for Success #motivation",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN),
            duration = "NONE"
        ),
        t(
            id = "chrome_shorts_locked_never_unrelated",
            pkg = CHROME,
            screen = "m.youtube.com/shorts Back Search Like Share " +
                "The Blueprint for Success #motivation",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK, TraceDecision.LOCK),
            forbidden = setOf(EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED),
            session = SessionStatus.LOCKED,
            notes = "REGRESSION: locked session used to ALLOW Chrome Shorts"
        ),
        t(
            id = "chrome_shorts_youtu_be_feature",
            pkg = CHROME,
            screen = "https://youtu.be/abcXYZ12?feature=shorts Hide player controls",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN),
            duration = "NONE"
        ),
        // ---- P0 pre-work (2026-09-25): watch page + engagement + NO clock  -----
        // Reported bug: Chrome playing a normal YouTube watch page under a pre-roll
        // ad shows the full engagement row (Like/Dislike/Comment/Share/Subscribe)
        // AND the word "Shorts" in the bottom nav. The old
        // `engagement >= 3 && (subscribe || contains("shorts"))` heuristic mis-classified
        // this as SHORT_FORM_PLAYER and used a recommendation duration as CURRENT_PLAYER,
        // firing MEDIA_BLOCK_UNDER_MIN with `durationSource=RECOMMENDATION`.
        // Correct verdict: no CURRENT_PLAYER clock => WAIT, surface != SHORT_FORM_PLAYER.
        // See docs/reliability-design-2026-09-25.md §Fix 2.
        t(
            id = "chrome_yt_watch_preroll_ad_engagement_no_clock_waits",
            pkg = CHROME,
            screen = "https://www.youtube.com/watch?v=abc123 " +
                "Like Dislike Comment Share Subscribe " +
                "Home Shorts Subscriptions You " +
                "Up next 8 minutes 12 seconds Chip design 15 minutes 4 seconds",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.WAIT, TraceDecision.ALLOW),
            forbidden = setOf(
                EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
            ),
            notes = "REGRESSION: engagement>=3 + 'shorts' nav word must not mean SHORT_FORM_PLAYER"
        ),
        t(
            id = "yt_app_watch_preroll_ad_engagement_no_clock_waits",
            pkg = YT,
            screen = "Like Dislike Comment Share Subscribe " +
                "Home Shorts Subscriptions You " +
                "Up next 8:12 15:04",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.WAIT, TraceDecision.ALLOW),
            forbidden = setOf(
                EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
            ),
            notes = "REGRESSION: bottom-nav 'Shorts' word must not force SHORT_FORM_PLAYER on watch page"
        )
    )

    private fun youtubeApp(): List<EnforcementTrace> = listOf(
        t(
            id = "yt_app_shorts_player_blocks",
            pkg = YT,
            screen = "Shorts player swipe up for next Like Dislike Comment Share " +
                "remix this short 00:08 / 00:21",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "yt_app_watch_31m33_blocks",
            pkg = YT,
            screen = "Time elapsed 1 secondTime duration 31 minutes, 33 seconds",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "yt_app_lecture_52m_allows",
            pkg = YT,
            screen = "Play Pause 00:15 / 52:00 lecture Hide player controls Time duration 52 minutes",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT)
        ),
        t(
            id = "yt_app_search_passive",
            pkg = YT,
            screen = "Search results filters lecture 12:04 8:10 About 1,200,000 results",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.SURFACE_PASS_PASSIVE)
        ),
        t(
            id = "yt_app_channel_passive",
            pkg = YT,
            screen = "Neso Academy Subscribe 2.1M subscribers Videos Shorts Playlists About",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "yt_app_comments_passive",
            pkg = YT,
            screen = "Comments 1.2K Add a comment Reply 3 hours ago Love this lecture",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        )
    )

    private fun newPipeAndClients(): List<EnforcementTrace> = listOf(
        t(
            id = "newpipe_short_blocks",
            pkg = NEWPIPE,
            screen = "Play Pause 00:15 / 00:59 Hide player controls",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "newpipe_long_allows",
            pkg = NEWPIPE,
            screen = "Play Pause 00:10 / 52:00 lecture Hide player controls",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT)
        ),
        t(
            id = "vanced_short_blocks",
            pkg = VANCED,
            screen = "Play Pause 00:12 / 00:48 Hide player controls YouTube",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        )
    )

    private fun chromeNormalPages(): List<EnforcementTrace> {
        val pages = listOf(
            "chrome_google_search" to
                "Google Search weather tomorrow Videos Images Maps About 12,400,000 results",
            "chrome_gmail" to
                "https://mail.google.com/mail Inbox Primary Compose Search mail",
            "chrome_wikipedia" to
                "https://en.wikipedia.org/wiki/Android_(operating_system) Edit Search 2008 2024 views",
            "chrome_bbc" to
                "https://www.bbc.com/news World 2023 1.2M views 8 minutes read",
            "chrome_docs" to
                "https://docs.google.com/document Untitled document File Edit View",
            "chrome_stackoverflow" to
                "https://stackoverflow.com/questions/123 asked 2019 42 votes 2021"
        )
        return pages.map { (id, screen) ->
            t(
                id = id,
                pkg = CHROME,
                screen = screen,
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.ALLOW),
                forbidden = setOf(
                    EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                    EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
                )
            )
        } + listOf(
            t(
                id = "chrome_recs_no_player_never_block",
                pkg = CHROME,
                screen = "youtube.com/watch?v=chip Chip design Show player controls " +
                    "More videos Can This Vibe Coder 8 minutes 55 seconds " +
                    "Andrew Tate 4 minutes 48 seconds 2:14 8:10",
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.WAIT, TraceDecision.ALLOW),
                forbidden = setOf(
                    EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                    EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
                ),
                notes = "Thumbnails must never BLOCK"
            )
        )
    }

    private fun passiveAndSystem(): List<EnforcementTrace> = listOf(
        t(
            id = "launcher_home",
            pkg = "com.miui.home",
            screen = "Search apps Widgets Phone Messages Chrome YouTube",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.EMERGENCY_ALLOW)
        ),
        t(
            id = "systemui_shade",
            pkg = "com.android.systemui",
            screen = "Notification shade Quick settings Clear all Wi-Fi Battery",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.EMERGENCY_ALLOW)
        ),
        t(
            id = "chrome_tab_switcher",
            pkg = CHROME,
            screen = "Tab switcher 12 tabs New tab Incognito Close tab grid",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "play_store_browse",
            pkg = "com.android.vending",
            screen = "Play Store Games Apps Search Install Free",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        )
    )

    private fun lockAndSafe(): List<EnforcementTrace> {
        val lockedSafe = listOf(
            "lock_contacts" to "com.android.contacts",
            "lock_dialer" to "com.android.dialer",
            "lock_phone" to "com.android.phone",
            "lock_chatgpt" to "com.openai.chatgpt",
            "lock_emergency" to "com.android.emergency"
        ).map { (id, pkg) ->
            t(
                id = id,
                pkg = pkg,
                screen = "Home Recents Favorites",
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.ALLOW),
                reasons = setOf(EnforcementReasonCodes.EMERGENCY_ALLOW),
                session = SessionStatus.LOCKED
            )
        }
        val lockedUnrelated = listOf(
            "lock_calendar" to "com.google.android.calendar",
            "lock_searchbox" to "com.google.android.googlequicksearchbox"
        ).map { (id, pkg) ->
            t(
                id = id,
                pkg = pkg,
                screen = "Today Agenda Search",
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.ALLOW),
                reasons = setOf(EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED),
                session = SessionStatus.LOCKED
            )
        }
        return lockedSafe + lockedUnrelated + listOf(
            t(
                id = "lock_chrome_gmail_unrelated",
                pkg = CHROME,
                screen = "https://mail.google.com/mail Inbox Primary",
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.ALLOW),
                reasons = setOf(EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED),
                session = SessionStatus.LOCKED
            ),
            t(
                id = "lock_yt_app_shorts_blocks",
                pkg = YT,
                screen = "Shorts player swipe up for next Like Share 00:09 / 00:18",
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.BLOCK, TraceDecision.LOCK),
                forbidden = setOf(EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED),
                session = SessionStatus.LOCKED
            )
        )
    }

    private fun quotaAndAdult(): List<EnforcementTrace> = listOf(
        t(
            id = "quota_first_short_allows",
            pkg = YT,
            screen = "Shorts player swipe up for next Calculus intro clip 00:12 / 00:40",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.QUOTA_ALLOW_COUNT),
            quotaUsed = 0
        ),
        t(
            id = "quota_tenth_allows",
            pkg = YT,
            screen = "Shorts player swipe up for next Distinct title ten 00:10 / 00:35",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.QUOTA_ALLOW_COUNT),
            quotaUsed = 9
        ),
        t(
            id = "quota_eleventh_blocks",
            pkg = YT,
            screen = "Shorts player swipe up for next Distinct title eleven 00:11 / 00:36",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED),
            quotaUsed = 10
        ),
        t(
            id = "quota_adult_blocks_without_count",
            pkg = YT,
            screen = "Shorts player swipe up porn xxx nude 00:08 / 00:20",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(
                EnforcementReasonCodes.QUOTA_BLOCK_ADULT,
                EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL
            ),
            adult = true,
            quotaUsed = 3
        ),
        t(
            id = "quota_home_does_not_count",
            pkg = YT,
            screen = "YouTube Home Search Shorts Subscriptions recommended for you",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED),
            quotaUsed = 10
        )
    )

    private fun blankAndAmbiguous(): List<EnforcementTrace> = listOf(
        t(
            id = "active_player_no_clock_waits",
            pkg = CHROME,
            screen = "https://www.youtube.com/watch?v=abc Hide player controls " +
                "Playback Settings Like Share Subscribe",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.WAIT),
            reasons = setOf(EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK),
            forbidden = setOf(
                EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
            )
        ),
        t(
            id = "blank_unknown_ott_media_waits",
            pkg = "com.unknown.ott.stream",
            screen = "Play Pause",
            goal = MAX40_GOAL,
            settings = max40Any(),
            expected = setOf(TraceDecision.WAIT, TraceDecision.WARN),
            reasons = setOf(EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE)
        ),
        t(
            id = "blank_unknown_ott_movies_blocks",
            pkg = "com.unknown.ott.stream",
            screen = "",
            goal = MOVIE_GOAL,
            settings = movies(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE)
        ),
        t(
            id = "blank_netmirror_monk_blocks",
            pkg = VideoPlatformRegistry.NETMIRROR,
            screen = "Play Pause",
            goal = MONK_GOAL,
            settings = monk(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(
                EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE,
                EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK
            )
        )
    )

    private fun socialReels(): List<EnforcementTrace> = listOf(
        t(
            id = "ig_reel_url_min40_blocks",
            pkg = IG,
            screen = "https://www.instagram.com/reel/CxyzABC123 audio original Play Pause 00:08 / 00:22",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW, TraceDecision.BLOCK),
            forbidden = emptySet(),
            notes = "IG reel is not YouTube brand unless promise is all-video; scope may allow"
        ),
        t(
            id = "ig_reel_quota_counts",
            pkg = IG,
            screen = "https://www.instagram.com/reel/CxyzABC123 audio original liked send",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.QUOTA_ALLOW_COUNT),
            quotaUsed = 0
        ),
        t(
            id = "ig_feed_passive",
            pkg = IG,
            screen = "Home Search Reels Shop Profile Your story Suggested for you",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED)
        ),
        t(
            id = "fb_reel_quota",
            pkg = FB,
            screen = "Reels player swipe up for next Use this sound 00:07 / 00:19",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW, TraceDecision.BLOCK),
            quotaUsed = 0
        ),
        t(
            id = "tiktok_for_you_quota",
            pkg = TT,
            screen = "For You Following swipe up for next 00:09 / 00:15",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW, TraceDecision.BLOCK),
            quotaUsed = 0
        )
    )

    private fun hinglishAndMessy(): List<EnforcementTrace> = listOf(
        t(
            id = "hinglish_min40_spoken_blocks",
            pkg = CHROME,
            screen = "Time elapsed 1 secondTime duration 31 minutes, 33 seconds",
            goal = MIN40_HINGLISH,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        ),
        t(
            id = "shots_typo_quota_home_pass",
            pkg = YT,
            screen = "YouTube Home Search Shorts Subscriptions",
            goal = "allow me to watch only upto 10 shots today",
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED)
        ),
        t(
            id = "user_copy_no_package_leak",
            pkg = CHROME,
            screen = "https://m.youtube.com Home Shorts Subscriptions You Library " +
                "recommended for you",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            copy = "Applies to: YouTube videos anywhere — app, browser pages, and other YouTube clients"
        )
    )

    /**
     * v1 "browser video" promise whose typed scope record is unreadable and whose legacy
     * mirror is Chrome-only. Must never enforce as Chrome-only: unverified, not enforced,
     * safety paths intact, nothing broadly blocked.
     */
    private fun corruptBrowserVideoChromeMirror() = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        minVideoLengthBlockMinutes = 30,
        enforcementScopePackages = listOf(CHROME),
        enforcementScopeKind = "chrome_browser",
        typedScopeCorruptDetail = "not_enforceable:MALFORMED_CLAUSE"
    )

    private fun scopePersistenceCorrupt(): List<EnforcementTrace> {
        val goal = "Do not allow videos shorter than 30 minutes in browsers for the next 1 hour."
        val corrupt = setOf(EnforcementReasonCodes.SCOPE_PERSISTENCE_CORRUPT)
        return listOf(
            t(
                id = "scope_corrupt_firefox_player_not_silently_out_of_scope",
                pkg = "org.mozilla.firefox",
                screen = "m.youtube.com/watch?v=abc123xyz Hide player controls 1:02 / 12:40",
                goal = goal,
                settings = corruptBrowserVideoChromeMirror(),
                expected = setOf(TraceDecision.ALLOW),
                reasons = corrupt,
                forbidden = setOf(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW)
            ),
            t(
                id = "scope_corrupt_chrome_player_not_chrome_only_enforcement",
                pkg = CHROME,
                screen = "m.youtube.com/watch?v=abc123xyz Hide player controls 1:02 / 12:40",
                goal = goal,
                settings = corruptBrowserVideoChromeMirror(),
                expected = setOf(TraceDecision.ALLOW),
                reasons = corrupt,
                forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
            ),
            t(
                id = "scope_corrupt_locked_chrome_player_no_legacy_lock",
                pkg = CHROME,
                screen = "m.youtube.com/watch?v=abc123xyz Hide player controls 1:02 / 12:40",
                goal = goal,
                settings = corruptBrowserVideoChromeMirror(),
                expected = setOf(TraceDecision.ALLOW),
                reasons = corrupt,
                session = SessionStatus.LOCKED
            ),
            t(
                id = "scope_corrupt_emergency_dialer_still_allowed",
                pkg = "com.android.phone",
                screen = "Emergency call Phone keypad",
                goal = goal,
                settings = corruptBrowserVideoChromeMirror(),
                expected = setOf(TraceDecision.ALLOW),
                reasons = setOf(EnforcementReasonCodes.EMERGENCY_ALLOW)
            ),
            t(
                id = "scope_corrupt_unrelated_app_not_broadly_blocked",
                pkg = "com.whatsapp",
                screen = "Voice message Pause 0:12 / 1:30 Type a message",
                goal = goal,
                settings = corruptBrowserVideoChromeMirror(),
                expected = setOf(TraceDecision.ALLOW)
            )
        )
    }

    private fun extraCoverage(): List<EnforcementTrace> {
        val noSession = t(
            id = "no_session_always_allows_watch",
            pkg = CHROME,
            screen = "Time duration 12 minutes Hide player controls youtube.com",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.NO_ACTIVE_SESSION_ALLOW),
            session = SessionStatus.ENDED
        )
        val landscape = t(
            id = "yt_fullscreen_landscape_spoken",
            pkg = YT,
            screen = "Hide player controls fullscreen Time duration 18 minutes, 4 seconds " +
                "Playback Settings",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        )
        val settingsA11y = t(
            id = "settings_not_tamper_plain",
            pkg = "com.android.settings",
            screen = "Settings Wi-Fi Bluetooth Battery Display",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.EMERGENCY_ALLOW)
        )
        val moreArticles = listOf(
            "https://www.nytimes.com 2016 election 2020 3 minutes read 4.2M",
            "https://medium.com/@dev Android 2018 15 min read views",
            "https://news.ycombinator.com 128 points 2022 4 hours ago"
        ).mapIndexed { i, screen ->
            t(
                id = "chrome_article_$i",
                pkg = CHROME,
                screen = screen,
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.ALLOW),
                forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
            )
        }
        val moreHomes = listOf(
            "Subscriptions Latest Neso Academy 12:04 45:00",
            "Library History Watch later Playlists Downloads",
            "YouTube Music Home Explore Library"
        ).mapIndexed { i, screen ->
            t(
                id = "yt_app_nav_$i",
                pkg = YT,
                screen = screen,
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.ALLOW),
                forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
            )
        }
        val moreShorts = listOf(
            "https://m.youtube.com/shorts/aaa111 Like Share",
            "https://www.youtube.com/shorts/bbb222 swipe up",
            "youtube.com/shorts/ccc333 Shorts player"
        ).mapIndexed { i, screen ->
            t(
                id = "chrome_shorts_variant_$i",
                pkg = CHROME,
                screen = screen,
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.BLOCK),
                reasons = setOf(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN),
                forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN),
                duration = "NONE"
            )
        }
        val moreWatch = listOf(
            "Time duration 5 minutes, 1 second" to TraceDecision.BLOCK,
            "Time duration 2 hours, 5 minutes" to TraceDecision.ALLOW,
            "Time duration 39 minutes, 59 seconds" to TraceDecision.BLOCK,
            "0:00 / 41:00 Hide player controls YouTube" to TraceDecision.ALLOW
        ).mapIndexed { i, (screen, dec) ->
            t(
                id = "watch_variant_$i",
                pkg = YT,
                screen = "Hide player controls $screen",
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(dec),
                reasons = if (dec == TraceDecision.BLOCK) {
                    setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
                } else {
                    setOf(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT)
                }
            )
        }
        val monkChrome = t(
            id = "monk_chrome_youtube_home",
            pkg = CHROME,
            screen = "https://m.youtube.com Home Shorts Subscriptions",
            goal = MONK_GOAL,
            settings = monk(),
            expected = setOf(TraceDecision.BLOCK, TraceDecision.ALLOW),
            notes = "Monk may class-block YouTube; home must not silently skip law"
        )
        val chromeBlockRule = t(
            id = "yt_promise_chrome_gmail_apprule_block",
            pkg = CHROME,
            screen = "https://mail.google.com/mail Inbox",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.APP_RULE_BLOCK),
            appRule = AppRuleBehavior.BLOCK
        )
        val snapSpotlight = t(
            id = "snapchat_spotlight_quota",
            pkg = VideoPlatformRegistry.SNAPCHAT,
            screen = "Spotlight player swipe up for next Play Pause 00:08 / 00:16",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW, TraceDecision.BLOCK),
            quotaUsed = 0
        )
        val fbFeed = t(
            id = "fb_feed_passive",
            pkg = FB,
            screen = "Home Friends Watch Marketplace Notifications Stories Suggested for you",
            goal = QUOTA_GOAL,
            settings = quota10(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED)
        )
        val ytMusic = t(
            id = "chrome_youtube_music_home",
            pkg = CHROME,
            screen = "https://music.youtube.com Home Explore Library Search",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN)
        )
        val lockIg = t(
            id = "lock_ig_feed_unrelated_to_yt_promise",
            pkg = IG,
            screen = "Home Search Reels Shop Profile Suggested for you",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.ALLOW),
            reasons = setOf(EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED),
            session = SessionStatus.LOCKED
        )
        val incognitoShorts = t(
            id = "chrome_incognito_shorts_blocks",
            pkg = CHROME,
            screen = "Incognito https://m.youtube.com/shorts/zzz999 Like Share Comments",
            goal = MIN40_GOAL,
            settings = min40Yt(),
            expected = setOf(TraceDecision.BLOCK),
            reasons = setOf(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN),
            forbidden = setOf(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN),
            duration = "NONE"
        )
        return listOf(
            noSession,
            landscape,
            settingsA11y,
            monkChrome,
            chromeBlockRule,
            snapSpotlight,
            fbFeed,
            ytMusic,
            lockIg,
            incognitoShorts
        ) + moreArticles + moreHomes + moreShorts + moreWatch + playingBeforeControls()
    }

    /**
     * Chrome m.youtube.com watch page while the video plays and the controls are hidden.
     * No clock, and the watch URL was cut off by the 3000-char text cap, so only the
     * header + comments teaser is readable. Must WAIT, never SURFACE_PASS_PASSIVE.
     */
    private fun playingBeforeControls(): List<EnforcementTrace> {
        val rickNoUrl = "TAP TO UNMUTE YouTube Home Search YouTube More options " +
            "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster) " +
            "@RickAstleyYT 19 million likes 1.8B views 16 years ago Subscribe Share " +
            "Download Clip Save Comments 2.4M Add a comment... " +
            "Up next Rick Astley - Together Forever 10:18"
        val pythonNoUrl = "TAP TO UNMUTE YouTube Home Search YouTube More options " +
            "Learn Python - Full Course for Beginners [Tutorial] @freecodecamp " +
            "1.1 million likes 49M views 8 years ago Subscribe Share Comments 30K " +
            "Top comments View replies"
        val forbidden = setOf(
            EnforcementReasonCodes.SURFACE_PASS_PASSIVE,
            EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
            EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
        )
        return listOf(
            t(
                id = "chrome_watch_playing_no_controls_max_waits",
                pkg = CHROME,
                screen = rickNoUrl,
                goal = MAX40_GOAL,
                settings = max40Any(),
                expected = setOf(TraceDecision.WAIT),
                reasons = setOf(EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK),
                forbidden = forbidden,
                surface = "ACTIVE_VIDEO_PLAYER",
                notes = "REGRESSION: playing watch page read as COMMENTS and ALLOWed"
            ),
            t(
                id = "chrome_watch_playing_no_controls_min_waits",
                pkg = CHROME,
                screen = pythonNoUrl,
                goal = MIN40_GOAL,
                settings = min40Yt(),
                expected = setOf(TraceDecision.WAIT),
                reasons = setOf(EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK),
                forbidden = forbidden,
                surface = "ACTIVE_VIDEO_PLAYER"
            ),
            t(
                id = "chrome_watch_playing_url_bar_prepended_waits",
                pkg = CHROME,
                screen = "m.youtube.com/watch?v=dQw4w9WgXcQ $rickNoUrl",
                goal = MAX40_GOAL,
                settings = max40Any(),
                expected = setOf(TraceDecision.WAIT),
                reasons = setOf(EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK),
                forbidden = forbidden,
                surface = "ACTIVE_VIDEO_PLAYER"
            )
        )
    }
}
