package com.phonecodex.app.domain.enforcement

/**
 * Coarse in-app surface for video / social platforms.
 * Short-form quota and media-length rules must only hard-act on player surfaces.
 *
 * Truth table (activity):
 * PASSIVE — Home/Search/channel/subscriptions/feed, shelves, comments, SERP,
 *           tab switcher, shade, launcher, Play Store, Discover/news, profile,
 *           DM list without open thread, UNKNOWN without player evidence.
 * ACTIVE  — video/short player, install/permission/payment flow, messaging thread.
 */
enum class SurfaceDetectionResult {
    HOME,
    SEARCH,
    CHANNEL,
    SUBSCRIPTIONS,
    COMMENTS,
    SOCIAL_DM,
    SOCIAL_FEED,
    MESSAGING_THREAD,
    ACTIVE_VIDEO_PLAYER,
    SHORT_FORM_PLAYER,
    LONG_FORM_PLAYER,
    INSTALL_FLOW,
    PAYMENT_FLOW,
    PLAY_STORE,
    DISCOVER_NEWS,
    BROWSER_TAB_SWITCHER,
    SYSTEM_SHADE,
    LAUNCHER,
    UNKNOWN;

    /** Back-compat alias used by older call sites / tests. */
    @Deprecated("Use LONG_FORM_PLAYER", ReplaceWith("LONG_FORM_PLAYER"))
    val asLegacyLongVideo: SurfaceDetectionResult
        get() = if (this == LONG_FORM_PLAYER) LONG_FORM_PLAYER else this

    val isActivePlayer: Boolean
        get() = this == ACTIVE_VIDEO_PLAYER ||
            this == SHORT_FORM_PLAYER ||
            this == LONG_FORM_PLAYER

    val isActiveBehaviorSurface: Boolean
        get() = isActivePlayer ||
            this == INSTALL_FLOW ||
            this == PAYMENT_FLOW ||
            this == MESSAGING_THREAD

    val isSystemOrBrowserPassive: Boolean
        get() = this == PLAY_STORE ||
            this == DISCOVER_NEWS ||
            this == BROWSER_TAB_SWITCHER ||
            this == SYSTEM_SHADE ||
            this == LAUNCHER
}

/** @deprecated Prefer [SurfaceDetectionResult.LONG_FORM_PLAYER]. */
@Deprecated("Use LONG_FORM_PLAYER", ReplaceWith("SurfaceDetectionResult.LONG_FORM_PLAYER"))
val LONG_VIDEO_PLAYER_ALIAS = SurfaceDetectionResult.LONG_FORM_PLAYER

data class SurfaceDetection(
    val surface: SurfaceDetectionResult,
    val evidence: List<String>,
    val durationMinutes: Int? = null,
    val durationSeconds: Int? = null
) {
    val isActivePlayer: Boolean
        get() = surface.isActivePlayer

    val isActiveBehaviorSurface: Boolean
        get() = surface.isActiveBehaviorSurface

    val isPassive: Boolean
        get() = !isActiveBehaviorSurface

    val isShortFormPlay: Boolean
        get() = surface == SurfaceDetectionResult.SHORT_FORM_PLAYER

    val activity: SurfaceActivity
        get() = if (isActiveBehaviorSurface) SurfaceActivity.ACTIVE else SurfaceActivity.PASSIVE
}

/**
 * Strong-evidence surface classifier. Words like "Videos" / "Short videos" on
 * search or home are NOT enough for an active player.
 *
 * Short-form signals, strongest first:
 * 1. Extracted Shorts / Reels URL (`/shorts/`, `youtube.com/shorts`,
 *    `youtu.be?feature=shorts`, IG `/reel/` / `/reels/`)
 * 2. Player copy: `use this sound`, `remix this short`, `shorts player`
 * 3. Clock B: CURRENT_PLAYER total T with `0 < T <= [SHORT_FORM_MAX_SECONDS]`
 *    on a video platform — never recommendation clocks, never watch-time
 *
 * We refuse silent AV capture here: no screenshot / frame grab, no streaming
 * playing video or audio to Azure speech/vision as a default path. That would
 * be private-screen surveillance. AI is counsel; this detector is local law.
 */
object SurfaceDetector {

    /**
     * Clock-B short-form threshold (item length, not watch-time).
     * Active player on a video platform with CURRENT_PLAYER total T where
     * `0 < T <= 90` → short-form. `00:15 / 38:25` (T=2305) is a lecture.
     */
    const val SHORT_FORM_MAX_SECONDS = 90
    /** Active player at/above this many minutes → long-form. */
    const val LONG_FORM_MIN_MINUTES = 20

    fun detect(packageName: String, screenText: String): SurfaceDetection {
        val normalized = screenText.lowercase()
        val evidence = mutableListOf<String>()

        if (looksLikeLauncher(packageName, normalized)) {
            evidence += "launcher"
            return SurfaceDetection(SurfaceDetectionResult.LAUNCHER, evidence)
        }

        if (looksLikeSystemShade(packageName, normalized)) {
            evidence += "system_shade"
            return SurfaceDetection(SurfaceDetectionResult.SYSTEM_SHADE, evidence)
        }

        if (looksLikeBrowserTabSwitcher(normalized)) {
            evidence += "browser_tab_switcher"
            return SurfaceDetection(SurfaceDetectionResult.BROWSER_TAB_SWITCHER, evidence)
        }

        if (looksLikeInstallFlow(packageName, normalized)) {
            evidence += "install_flow"
            return SurfaceDetection(SurfaceDetectionResult.INSTALL_FLOW, evidence)
        }

        if (looksLikePlayStore(packageName, normalized)) {
            evidence += "play_store"
            return SurfaceDetection(SurfaceDetectionResult.PLAY_STORE, evidence)
        }

        if (looksLikePaymentFlow(normalized)) {
            evidence += "payment_flow"
            return SurfaceDetection(SurfaceDetectionResult.PAYMENT_FLOW, evidence)
        }

        if (looksLikeDiscoverNews(packageName, normalized) &&
            !hasStrongPlayerEvidence(normalized, mutableListOf())
        ) {
            evidence += "discover_news"
            return SurfaceDetection(SurfaceDetectionResult.DISCOVER_NEWS, evidence)
        }

        if (looksLikeComments(normalized) &&
            !hasStrongPlayerEvidence(normalized, mutableListOf()) &&
            !hasPlatformShortPlayerEvidence(packageName, normalized, evidence)
        ) {
            evidence += "comments_surface"
            return SurfaceDetection(SurfaceDetectionResult.COMMENTS, evidence)
        }

        if (looksLikeMessagingThread(packageName, normalized)) {
            evidence += "messaging_thread"
            return SurfaceDetection(SurfaceDetectionResult.MESSAGING_THREAD, evidence)
        }

        if (looksLikeSocialDm(packageName, normalized)) {
            evidence += "social_dm"
            return SurfaceDetection(SurfaceDetectionResult.SOCIAL_DM, evidence)
        }

        if (looksLikeSearchSurface(normalized) && !hasStrongPlayerEvidence(normalized, evidence)) {
            evidence += "search_surface"
            return SurfaceDetection(SurfaceDetectionResult.SEARCH, evidence)
        }

        if (looksLikeChannelPage(normalized) &&
            !hasStrongPlayerEvidence(normalized, evidence) &&
            !hasPlatformShortPlayerEvidence(packageName, normalized, evidence)
        ) {
            evidence += "channel_page"
            return SurfaceDetection(SurfaceDetectionResult.CHANNEL, evidence)
        }

        if (looksLikeHomeSurface(packageName, normalized) &&
            !hasStrongPlayerEvidence(normalized, evidence) &&
            !hasPlatformShortPlayerEvidence(packageName, normalized, evidence)
        ) {
            evidence += "home_surface"
            return SurfaceDetection(SurfaceDetectionResult.HOME, evidence)
        }

        if (looksLikeSubscriptions(normalized) && !hasStrongPlayerEvidence(normalized, evidence)) {
            evidence += "subscriptions_feed"
            return SurfaceDetection(SurfaceDetectionResult.SUBSCRIPTIONS, evidence)
        }

        if (looksLikeSocialFeed(packageName, normalized) &&
            !hasStrongPlayerEvidence(normalized, evidence) &&
            !hasPlatformShortPlayerEvidence(packageName, normalized, evidence)
        ) {
            evidence += "social_feed"
            return SurfaceDetection(SurfaceDetectionResult.SOCIAL_FEED, evidence)
        }

        val parsedDuration = VideoDurationParser.parse(normalized)
        val durationSeconds = parsedDuration.currentPlayerDurationSeconds
        val durationMinutes = durationSeconds?.let { it / 60 }

        if (hasStrongPlayerEvidence(normalized, evidence) ||
            hasPlatformShortPlayerEvidence(packageName, normalized, evidence) ||
            (VideoPlatformRegistry.isNewPipe(packageName) &&
                hasNewPipePlayerEvidence(normalized, evidence))
        ) {
            val surface = classifyPlayerKind(
                packageName,
                normalized,
                parsedDuration,
                evidence
            )
            return SurfaceDetection(surface, evidence.distinct(), durationMinutes, durationSeconds)
        }

        // Thumbnail / recommendation duration alone on an otherwise unknown page ≠ player.
        if (durationSeconds != null && looksLikeRecommendationShelf(normalized)) {
            evidence += "recommendation_shelf"
            return SurfaceDetection(
                SurfaceDetectionResult.HOME,
                evidence.distinct(),
                durationMinutes,
                durationSeconds
            )
        }

        return SurfaceDetection(
            SurfaceDetectionResult.UNKNOWN,
            evidence.distinct(),
            durationMinutes,
            durationSeconds
        )
    }

    fun isActiveVideoPlayerSurface(packageName: String, screenText: String): Boolean =
        detect(packageName, screenText).isActivePlayer

    /**
     * Classify an already-proven player. Duration math is clock B only:
     * CURRENT_PLAYER total T, `0 < T <= [SHORT_FORM_MAX_SECONDS]`, and only
     * on a video/streaming package. Elapsed watch-time is clock C (usage).
     */
    private fun classifyPlayerKind(
        packageName: String,
        normalized: String,
        parsedDuration: VideoDurationParseResult,
        evidence: MutableList<String>
    ): SurfaceDetectionResult {
        if (hasShortFormCopyOrUrl(packageName, normalized, evidence)) {
            evidence += "short_form_player"
            return SurfaceDetectionResult.SHORT_FORM_PLAYER
        }

        val durationSeconds = parsedDuration.currentPlayerDurationSeconds
        if (
            VideoPlatformRegistry.isVideoOrStreamingPackage(packageName) &&
            VideoDurationParser.isShortFormItemLength(parsedDuration, SHORT_FORM_MAX_SECONDS)
        ) {
            evidence += "duration_clock_b_le_${SHORT_FORM_MAX_SECONDS}s"
            return SurfaceDetectionResult.SHORT_FORM_PLAYER
        }

        if (durationSeconds != null && durationSeconds >= LONG_FORM_MIN_MINUTES * 60) {
            evidence += "long_form_player"
            return SurfaceDetectionResult.LONG_FORM_PLAYER
        }

        // Platform-native short players without readable duration (IG Reels / TikTok).
        if (hasPlatformShortPlayerEvidence(packageName, normalized, evidence) &&
            (durationSeconds == null ||
                VideoDurationParser.isShortFormItemLengthSeconds(
                    durationSeconds,
                    SHORT_FORM_MAX_SECONDS
                ))
        ) {
            evidence += "platform_short_player"
            return SurfaceDetectionResult.SHORT_FORM_PLAYER
        }

        evidence += "active_video_player"
        return SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER
    }

    /**
     * Rank 1–2 short-form evidence: extracted Shorts/Reels URL, then copy
     * cues (`use this sound`, `remix this short`). Not the Home nav word
     * "Shorts" and not a generic `youtu.be` lecture link.
     */
    private fun hasShortFormCopyOrUrl(
        packageName: String,
        normalized: String,
        evidence: MutableList<String>
    ): Boolean {
        if (SHORT_FORM_PLAYER_PHRASES.any { normalized.contains(it) }) {
            evidence += "short_form_copy"
            return true
        }
        if (AccessibilityUrlExtractor.containsYouTubeShortsUrl(normalized)) {
            evidence += "shorts_url"
            return true
        }
        if (AccessibilityUrlExtractor.containsInstagramReelUrl(normalized, packageName)) {
            evidence += "instagram_reel_url"
            return true
        }
        return false
    }

    private fun hasStrongPlayerEvidence(
        normalized: String,
        evidence: MutableList<String>
    ): Boolean {
        var hit = false
        for (phrase in ACTIVE_PLAYER_STRONG_PHRASES) {
            if (normalized.contains(phrase)) {
                evidence += "player:$phrase"
                hit = true
            }
        }
        if (WATCH_URL_REGEX.containsMatchIn(normalized)) {
            evidence += "watch_url"
            hit = true
        }
        // Current/total clock pair with play/pause chrome (not thumbnail "52 minutes").
        if (PLAYER_CLOCK_PAIR_REGEX.containsMatchIn(normalized) &&
            (normalized.contains("play") ||
                normalized.contains("pause") ||
                normalized.contains("playback") ||
                normalized.contains("seekbar"))
        ) {
            evidence += "player_clock_pair"
            hit = true
        }
        if (normalized.contains("player controls") &&
            (normalized.contains("playback") ||
                normalized.contains("youtube") ||
                normalized.contains("hide player") ||
                normalized.contains("show player"))
        ) {
            evidence += "player_controls_with_context"
            hit = true
        }
        // Mobile-web watch page while playing with controls hidden: muted-autoplay chip
        // plus the watch header's like/view counts. Comments alone must not win here.
        if (normalized.contains("tap to unmute") &&
            WATCH_PAGE_LIKES_REGEX.containsMatchIn(normalized) &&
            normalized.contains(" views")
        ) {
            evidence += "watch_page_inline_player"
            hit = true
        }
        return hit
    }

    private fun hasPlatformShortPlayerEvidence(
        packageName: String,
        normalized: String,
        evidence: MutableList<String>
    ): Boolean {
        // Chrome omnibox / YouTube share / IG reel href — URL or copy is enough.
        if (hasShortFormCopyOrUrl(packageName, normalized, evidence)) {
            return true
        }
        val kind = VideoPlatformRegistry.platformKind(packageName)
        when (kind) {
            VideoPlatformKind.INSTAGRAM -> {
                if (normalized.contains("reel") ||
                    normalized.contains("reels") ||
                    normalized.contains("swipe up")
                ) {
                    // Shelf/nav alone is not enough — need player chrome.
                    if (normalized.contains("audio") ||
                        normalized.contains("send") ||
                        normalized.contains("liked") ||
                        normalized.contains("pause") ||
                        normalized.contains("play") ||
                        CLOCK_TOKEN_REGEX.containsMatchIn(normalized)
                    ) {
                        evidence += "instagram_reel_player"
                        return true
                    }
                }
            }
            VideoPlatformKind.TIKTOK -> {
                if (normalized.contains("for you") &&
                    (normalized.contains("like") || normalized.contains("comment") ||
                        CLOCK_TOKEN_REGEX.containsMatchIn(normalized))
                ) {
                    evidence += "tiktok_player"
                    return true
                }
            }
            VideoPlatformKind.FACEBOOK -> {
                if (normalized.contains("reel") &&
                    (normalized.contains("play") || CLOCK_TOKEN_REGEX.containsMatchIn(normalized))
                ) {
                    evidence += "facebook_reel_player"
                    return true
                }
            }
            VideoPlatformKind.SNAPCHAT -> {
                if (normalized.contains("spotlight") &&
                    (normalized.contains("play") || CLOCK_TOKEN_REGEX.containsMatchIn(normalized))
                ) {
                    evidence += "snap_spotlight_player"
                    return true
                }
            }
            VideoPlatformKind.OFFICIAL_YOUTUBE,
            VideoPlatformKind.CHROME_WEB,
            VideoPlatformKind.NEWPIPE -> {
                if (looksLikeYoutubeShortsPlayer(normalized)) {
                    evidence += "youtube_shorts_engagement"
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    /**
     * Player-copy evidence only. YouTube Shorts trees say `shorts player`,
     * `swipe up for next`, `remix this short`, or `use this sound`; those are
     * safe positive signals. The remix/sound tokens co-occur with 2+ engagement
     * icons on real Shorts, never on a normal watch page, so they stay.
     *
     * DELETED 2026-09-25 (docs/reliability-design-2026-09-25.md §Fix 2):
     * the generic `engagement >= 3 && (subscribe || contains("shorts"))` branch.
     * Every normal YouTube watch page shows Like / Dislike / Comment / Share /
     * Subscribe under a pre-roll ad, and the bottom-nav word "Shorts" is
     * literally on screen — that used to force SHORT_FORM_PLAYER on a watch
     * page and let recommendation clocks masquerade as CURRENT_PLAYER, firing
     * a bogus MEDIA_BLOCK_UNDER_MIN. Real Shorts still get caught upstream by
     * a `/shorts/` URL (`hasShortFormCopyOrUrl`) or short-form player copy.
     */
    internal fun looksLikeYoutubeShortsPlayer(normalized: String): Boolean {
        if (normalized.contains("recommended for you")) return false
        if (normalized.contains("hide player controls") ||
            normalized.contains("show player controls")
        ) {
            return false
        }
        if (SHORT_FORM_PLAYER_PHRASES.any { phrase -> normalized.contains(phrase) }) {
            return true
        }
        val like = normalized.contains("like")
        val dislike = normalized.contains("dislike")
        val comment = normalized.contains("comment")
        val share = normalized.contains("share")
        val remix = normalized.contains("remix") ||
            normalized.contains("use this sound") ||
            normalized.contains("add sound")
        val engagement = listOf(like, dislike, comment, share).count { it }
        // Remix/sound tokens are Shorts-specific player chrome, not watch-page chrome.
        return remix && engagement >= 2
    }

    private fun hasNewPipePlayerEvidence(
        normalized: String,
        evidence: MutableList<String>
    ): Boolean {
        val hasSlashClockPair = NEWPIPE_CLOCK_PAIR_REGEX.containsMatchIn(normalized)
        val hasPlayChrome =
            normalized.contains("play") ||
                normalized.contains("pause") ||
                normalized.contains("playback") ||
                normalized.contains("seekbar") ||
                normalized.contains("position")
        // Position/total with slash is strong only with playback chrome.
        if (hasSlashClockPair && hasPlayChrome) {
            evidence += "newpipe_clock_pair"
            evidence += "newpipe_playback_chrome"
            return true
        }
        // Loose dual clocks on history/recs are NOT a player (thumbnail durations).
        if (hasSlashClockPair && hasPlayChrome.not()) {
            return false
        }
        return false
    }

    private fun looksLikeSearchSurface(normalized: String): Boolean {
        if (SEARCH_MARKERS.any { normalized.contains(it) }) return true
        return normalized.contains("results") &&
            (normalized.contains("videos") || normalized.contains("short videos"))
    }

    private fun looksLikeHomeSurface(packageName: String, normalized: String): Boolean {
        if (HOME_NAV_MARKERS.count { normalized.contains(it) } >= 2 &&
            !hasActiveShortPlayerMarkers(normalized, packageName)
        ) {
            return true
        }
        // Chrome or native YouTube home: Home + Shorts nav/shelf without player chrome.
        val youtubeLikeHome =
            (normalized.contains("youtube") ||
                VideoPlatformRegistry.isOfficialYouTube(packageName) ||
                VideoPlatformRegistry.isChrome(packageName)) &&
                normalized.contains("home") &&
                normalized.contains("shorts") &&
                !normalized.contains("hide player") &&
                !normalized.contains("show player") &&
                !hasActiveShortPlayerMarkers(normalized, packageName)
        if (youtubeLikeHome) return true
        if (VideoPlatformRegistry.isOfficialYouTube(packageName) &&
            normalized.contains("home") &&
            normalized.contains("shorts") &&
            !normalized.contains("hide player") &&
            !normalized.contains("show player") &&
            !hasActiveShortPlayerMarkers(normalized, packageName)
        ) {
            return true
        }
        return false
    }

    /** Home/shelf nav word "Shorts" is not a player. URL + copy cues are. */
    private fun hasActiveShortPlayerMarkers(normalized: String, packageName: String): Boolean =
        SHORT_FORM_PLAYER_PHRASES.any { normalized.contains(it) } ||
            AccessibilityUrlExtractor.containsShortFormVideoUrl(normalized, packageName) ||
            looksLikeYoutubeShortsPlayer(normalized)

    private fun looksLikeChannelPage(normalized: String): Boolean {
        return (normalized.contains("channel") || normalized.contains("subscribers")) &&
            (normalized.contains("videos") || normalized.contains("about") ||
                normalized.contains("home") || normalized.contains("playlists")) &&
            !normalized.contains("hide player") &&
            !normalized.contains("show player")
    }

    private fun looksLikeSubscriptions(normalized: String): Boolean {
        // Bottom-nav "Subscriptions" alone is not enough (also present on Home).
        if (!normalized.contains("subscriptions")) return false
        return (normalized.contains("latest") ||
            normalized.contains("today") ||
            normalized.contains("all videos") ||
            normalized.contains("subscriptions feed")) &&
            !normalized.contains("hide player") &&
            !normalized.contains("show player")
    }

    private fun looksLikeRecommendationShelf(normalized: String): Boolean {
        return RECOMMENDATION_MARKERS.any { normalized.contains(it) } ||
            (normalized.contains("home") && normalized.contains("shorts"))
    }

    private fun looksLikeSocialFeed(packageName: String, normalized: String): Boolean {
        val kind = VideoPlatformRegistry.platformKind(packageName)
        if (kind !in setOf(
                VideoPlatformKind.INSTAGRAM,
                VideoPlatformKind.FACEBOOK,
                VideoPlatformKind.TIKTOK,
                VideoPlatformKind.SNAPCHAT
            )
        ) {
            return false
        }
        return FEED_MARKERS.any { normalized.contains(it) } &&
            !normalized.contains("reel player") &&
            !normalized.contains("shorts player")
    }

    private fun looksLikeSocialDm(packageName: String, normalized: String): Boolean {
        val kind = VideoPlatformRegistry.platformKind(packageName)
        if (kind != VideoPlatformKind.INSTAGRAM &&
            kind != VideoPlatformKind.FACEBOOK &&
            kind != VideoPlatformKind.SNAPCHAT
        ) {
            return false
        }
        return MESSAGE_LIST_MARKERS.any { normalized.contains(it) } &&
            !looksLikeMessagingThread(packageName, normalized) &&
            !hasStrongPlayerEvidence(normalized, mutableListOf())
    }

    private fun looksLikeMessagingThread(packageName: String, normalized: String): Boolean {
        val kind = VideoPlatformRegistry.platformKind(packageName)
        if (kind != VideoPlatformKind.INSTAGRAM &&
            kind != VideoPlatformKind.FACEBOOK &&
            kind != VideoPlatformKind.SNAPCHAT
        ) {
            return false
        }
        val hasThreadChrome =
            normalized.contains("type a message") ||
                normalized.contains("send message") ||
                normalized.contains("message…") ||
                normalized.contains("message...") ||
                (normalized.contains("thread") && normalized.contains("message"))
        return hasThreadChrome && MESSAGE_MARKERS.any { normalized.contains(it) }
    }

    private fun looksLikeComments(normalized: String): Boolean {
        return COMMENTS_MARKERS.any { normalized.contains(it) } &&
            !hasStrongPlayerEvidence(normalized, mutableListOf())
    }

    private fun looksLikeInstallFlow(packageName: String, normalized: String): Boolean {
        if (packageName.contains("packageinstaller", ignoreCase = true)) return true
        if (INSTALL_MARKERS.any { normalized.contains(it) }) return true
        val storeish =
            packageName == "com.android.vending" ||
                packageName.contains("mipicks", ignoreCase = true)
        if (!storeish) return false
        if (INSTALL_CONFIRM_MARKERS.any { normalized.contains(it) }) return true
        return normalized.contains("install") && normalized.contains("cancel")
    }

    private fun looksLikePaymentFlow(normalized: String): Boolean {
        return PAYMENT_MARKERS.count { normalized.contains(it) } >= 2 ||
            (normalized.contains("checkout") && normalized.contains("pay"))
    }

    private fun looksLikePlayStore(packageName: String, normalized: String): Boolean {
        if (packageName == "com.android.vending") return true
        return normalized.contains("play store") &&
            (normalized.contains("install") || normalized.contains("ratings"))
    }

    private fun looksLikeDiscoverNews(packageName: String, normalized: String): Boolean {
        if (packageName.contains("googlequicksearchbox") ||
            packageName.contains("android.googlequicksearchbox")
        ) {
            return normalized.contains("discover") || normalized.contains("for you")
        }
        return (normalized.contains("discover") || normalized.contains("top stories")) &&
            (normalized.contains("news") || normalized.contains("for you")) &&
            !hasStrongPlayerEvidence(normalized, mutableListOf())
    }

    private fun looksLikeBrowserTabSwitcher(normalized: String): Boolean {
        return (normalized.contains("tabs") || normalized.contains("tab switcher")) &&
            (normalized.contains("new tab") || normalized.contains("incognito") ||
                normalized.contains("close tab") || normalized.contains("grid"))
    }

    private fun looksLikeSystemShade(packageName: String, normalized: String): Boolean {
        if (packageName == "com.android.systemui" || packageName.contains("systemui")) {
            return true
        }
        return normalized.contains("notification") &&
            (normalized.contains("quick settings") || normalized.contains("clear all"))
    }

    private fun looksLikeLauncher(packageName: String, normalized: String): Boolean {
        if (packageName.contains("launcher") ||
            packageName == "com.miui.home" ||
            packageName.contains("nexuslauncher")
        ) {
            return true
        }
        return normalized.contains("search apps") && normalized.contains("widgets")
    }

    private val ACTIVE_PLAYER_STRONG_PHRASES = listOf(
        "hide player controls",
        "show player controls",
        "playback settings",
        "time duration"
    )

    private val SHORT_FORM_PLAYER_PHRASES = listOf(
        "shorts player",
        "reel player",
        "reels player",
        "swipe up for next",
        "remix this short",
        "use this sound",
        "see more videos using this sound"
    )

    private val SEARCH_MARKERS = listOf(
        "search results",
        "showing results",
        "filters",
        "about ",
        " results"
    )

    private val HOME_NAV_MARKERS = listOf(
        "home",
        "subscriptions",
        "library",
        "explore",
        "trending"
    )

    private val RECOMMENDATION_MARKERS = listOf(
        "recommended",
        "recommendations",
        "for you",
        "continue watching",
        "mixed for you"
    )

    private val FEED_MARKERS = listOf(
        "explore",
        "for you",
        "following",
        "stories",
        "suggested for you",
        "profile"
    )

    private val MESSAGE_MARKERS = listOf(
        "message",
        "messages",
        "inbox",
        "chat",
        "direct message",
        "dms"
    )

    private val MESSAGE_LIST_MARKERS = listOf(
        "messages",
        "inbox",
        "chats",
        "direct message",
        "dms"
    )

    private val COMMENTS_MARKERS = listOf(
        "comments",
        "add a comment",
        "view replies",
        "top comments"
    )

    private val INSTALL_MARKERS = listOf(
        "install anyway",
        "allow from this source",
        "package installer",
        "do you want to install",
        "app permissions"
    )

    private val INSTALL_CONFIRM_MARKERS = listOf(
        "this app can access",
        "you're about to install",
        "you are about to install",
        "install this application",
        "confirm installation",
        "complete installation"
    )

    private val PAYMENT_MARKERS = listOf(
        "checkout",
        "payment method",
        "order summary",
        "pay now",
        "billing",
        "add card"
    )

    private val WATCH_URL_REGEX =
        Regex("""(?:youtube\.com/watch|youtu\.be/[a-zA-Z0-9_-]{6,})""")

    private val NEWPIPE_CLOCK_PAIR_REGEX =
        Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s*/\s*\d{1,2}:\d{2}(?::\d{2})?\b""")

    private val PLAYER_CLOCK_PAIR_REGEX =
        Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s*/\s*\d{1,2}:\d{2}(?::\d{2})?\b""")

    private val CLOCK_TOKEN_REGEX =
        Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")

    private val WATCH_PAGE_LIKES_REGEX =
        Regex("""\b\d[\d.,]*\s*(?:k|m|b|thousand|million|billion)?\s+likes\b""")
}
