package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.ShortFormLanguage
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.VideoDurationParser
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRuleBehavior

/**
 * Goal-text heuristics for accessibility enforcement.
 *
 * Clock rules:
 * - Media-length thresholds ("videos longer than 40 min" / "shorter than 30 min")
 *   are NOT Shorts bans.
 * - Usage quotas ("only 40 min entertainment") are NOT per-video length gates.
 * - Session duration lives in StudyWorldSettings, not here.
 */
object PromiseIntentRules {

    fun blocksShortForm(goal: String): Boolean {
        val normalized = goal.lowercase()
        // Long-video thresholds alone must not imply a Shorts ban.
        if (hasMediaLengthLimit(normalized) &&
            SHORT_FORM_BLOCK_PHRASES.none { phrase -> normalized.contains(phrase) } &&
            STUDY_ONLY_PHRASES.none { phrase -> normalized.contains(phrase) } &&
            MONK_PHRASES.none { phrase -> normalized.contains(phrase) }
        ) {
            return false
        }
        return SHORT_FORM_BLOCK_PHRASES.any { phrase -> normalized.contains(phrase) } ||
            STUDY_ONLY_PHRASES.any { phrase -> normalized.contains(phrase) } ||
            MONK_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    fun allowsShortForm(goal: String): Boolean {
        val normalized = goal.lowercase()
        return SHORT_FORM_ALLOW_PHRASES.any { phrase -> normalized.contains(phrase) } ||
            SHORT_FORM_QUOTA_REGEX.containsMatchIn(normalized)
    }

    /**
     * Monk / study-only / explicit no-movies / block-this-app promises: unknown
     * movie apps with blank trees must BLOCK, not wait for a duration clock.
     * Length-only phrases ("block videos longer than 2 hours") do not qualify.
     */
    fun blocksEntertainmentOrMovies(goal: String): Boolean {
        val normalized = goal.lowercase()
        if (hasCategoryOrLifestyleBan(normalized)) return true
        if (NO_MOVIE_PHRASES.any { phrase -> normalized.contains(phrase) }) return true
        return THIS_APP_BLOCK_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    fun hasCategoryOrLifestyleBan(goal: String): Boolean {
        val normalized = goal.lowercase()
        if (isLifestyleEntertainmentBan(normalized)) return true
        if (blocksGameCategory(normalized)) return true
        if (blocksSocialCategory(normalized)) return true
        if (blocksDatingCategory(normalized)) return true
        if (blocksGirlsChatCategory(normalized)) return true
        if (blocksMusicCategory(normalized)) return true
        if (NO_MOVIE_PHRASES.any { phrase -> normalized.contains(phrase) }) return true
        return THIS_APP_BLOCK_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    /**
     * Full monk / study-only / only-calls: unknown apps are guilty.
     * "No games" or "no social" alone are category bans, not this.
     */
    fun isLifestyleEntertainmentBan(goal: String): Boolean {
        val normalized = goal.lowercase()
        if (LIFESTYLE_PHRASES.any { phrase -> normalized.contains(phrase) }) return true
        if (STUDY_ONLY_PHRASES.any { phrase -> normalized.contains(phrase) }) return true
        return false
    }

    fun blocksGameCategory(goal: String): Boolean {
        val normalized = goal.lowercase()
        return isLifestyleEntertainmentBan(normalized) ||
            GAME_CATEGORY_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    fun blocksSocialCategory(goal: String): Boolean {
        val normalized = goal.lowercase()
        return isLifestyleEntertainmentBan(normalized) ||
            SOCIAL_CATEGORY_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    fun blocksDatingCategory(goal: String): Boolean {
        val normalized = goal.lowercase()
        return isLifestyleEntertainmentBan(normalized) ||
            blocksSocialCategory(normalized) ||
            DATING_CATEGORY_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    fun blocksMovieCategory(goal: String): Boolean {
        val normalized = goal.lowercase()
        return isLifestyleEntertainmentBan(normalized) ||
            NO_MOVIE_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    fun blocksGirlsChatCategory(goal: String): Boolean {
        val normalized = goal.lowercase()
        return isLifestyleEntertainmentBan(normalized) ||
            GIRLS_CHAT_PHRASES.any { phrase -> normalized.contains(phrase) } ||
            DATING_CATEGORY_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    fun blocksMusicCategory(goal: String): Boolean {
        val normalized = goal.lowercase()
        return MUSIC_CATEGORY_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    fun namesThisAppOnly(goal: String): Boolean {
        val normalized = goal.lowercase()
        return THIS_APP_SCOPE_PHRASES.any { phrase -> normalized.contains(phrase) }
    }

    /** Exclusive official-app lock. "YouTube video" is not this. */
    fun namesYouTubeAppOnly(goal: String): Boolean {
        val normalized = goal.lowercase()
        return YOUTUBE_APP_ONLY_REGEX.containsMatchIn(normalized)
    }

    /**
     * YouTube named as content/brand (video, lecture, length), not "YouTube app only".
     * Enforcement then matches the YouTube surface anywhere.
     */
    fun namesYouTubeContentBrand(goal: String): Boolean {
        val normalized = goal.lowercase()
        if (namesYouTubeAppOnly(normalized)) return false
        if (!normalized.contains("youtube") && !YT_TOKEN.containsMatchIn(normalized)) {
            return false
        }
        return normalized.contains("video") ||
            normalized.contains("content") ||
            normalized.contains("lecture") ||
            normalized.contains("watch") ||
            hasMediaLengthLimit(normalized)
    }

    fun hasExplicitSessionDuration(goal: String): Boolean {
        val normalized = goal.lowercase()
        return EXPLICIT_SESSION_DURATION_REGEX.containsMatchIn(normalized)
    }

    fun namesNetMirror(goal: String): Boolean {
        val normalized = goal.lowercase()
        return normalized.contains("netmirror") || normalized.contains("newtv")
    }

    fun allowsBroadChromeUse(goal: String): Boolean {
        val normalized = goal.lowercase()
        return normalized.contains("allow everything on chrome") ||
            normalized.contains("chrome allow everything") ||
            normalized.contains("anything on chrome") ||
            normalized.contains("everything in chrome")
    }

    /**
     * True when the promise sets a per-video length threshold (clock B).
     * Covers both max ("longer than 40") and min ("shorter than 30" / "at least 30").
     */
    fun hasMediaLengthLimit(goal: String): Boolean {
        val normalized = goal.lowercase()
        if (!MEDIA_LENGTH_LIMIT_REGEX.containsMatchIn(normalized) &&
            !MIN_LENGTH_PHRASE_REGEX.containsMatchIn(normalized)
        ) {
            return false
        }
        return MEDIA_LENGTH_SUBJECT_REGEX.containsMatchIn(normalized) ||
            normalized.contains("youtube") ||
            normalized.contains("chrome") ||
            normalized.contains("newpipe") ||
            normalized.contains("netmirror") ||
            normalized.contains("ott") ||
            normalized.contains("movie") ||
            normalized.contains("video")
    }

    /** @deprecated Prefer [hasMediaLengthLimit]; kept for call-site compatibility. */
    fun hasLongYouTubeLimit(goal: String): Boolean = hasMediaLengthLimit(goal)

    /**
     * Active embedded/native video player — NOT search results, tabs, or thumbnails.
     * Prefer package-aware overload when package is known.
     */
    fun isActiveVideoPlayerSurface(screenText: String): Boolean {
        return SurfaceDetector.detect(VideoPlatformRegistry.YOUTUBE, screenText).isActivePlayer ||
            SurfaceDetector.detect(VideoPlatformRegistry.CHROME, screenText).isActivePlayer ||
            SurfaceDetector.detect(VideoPlatformRegistry.NEWPIPE, screenText).isActivePlayer
    }

    fun isActiveVideoPlayerSurface(packageName: String, screenText: String): Boolean =
        SurfaceDetector.isActiveVideoPlayerSurface(packageName, screenText)

    /**
     * Chrome is showing an active YouTube/video player (not SERP / Videos tab).
     */
    fun isYouTubeSurfaceInChrome(screenText: String): Boolean =
        isActiveVideoPlayerSurface(VideoPlatformRegistry.CHROME, screenText)

    /**
     * @param structuredMaxBlockMinutes optional persisted ContentRule threshold
     * (block when individual video minutes > this). Falls back to goal-text parse.
     */
    fun chromeYouTubeVideoExceedsGoalLimit(
        goal: String,
        screenText: String,
        structuredMaxBlockMinutes: Int? = null,
        packageName: String = VideoPlatformRegistry.CHROME,
        structuredMinBlockMinutes: Int? = null
    ): Boolean = videoViolatesMediaLengthLimit(
        goal = goal,
        screenText = screenText,
        structuredMaxBlockMinutes = structuredMaxBlockMinutes,
        structuredMinBlockMinutes = structuredMinBlockMinutes,
        packageName = packageName
    )

    /**
     * True when an active player violates max (too long) or min (too short) length law.
     */
    fun videoViolatesMediaLengthLimit(
        goal: String,
        screenText: String,
        structuredMaxBlockMinutes: Int? = null,
        structuredMinBlockMinutes: Int? = null,
        packageName: String = VideoPlatformRegistry.CHROME
    ): Boolean {
        if (!isActiveVideoPlayerSurface(packageName, screenText)) return false
        val hasStructured = structuredMaxBlockMinutes != null || structuredMinBlockMinutes != null
        if (!hasStructured && !hasMediaLengthLimit(goal)) return false

        val videoSeconds = VideoDurationParser.extractPrimaryDurationSeconds(screenText) ?: return false
        val maxMinutes = structuredMaxBlockMinutes ?: extractGoalMaxVideoLimitMinutes(goal)
        val minMinutes = structuredMinBlockMinutes ?: extractGoalMinVideoLimitMinutes(goal)

        if (maxMinutes != null && videoSeconds.toLong() > maxMinutes.toLong() * 60L) return true
        if (minMinutes != null && videoSeconds.toLong() < minMinutes.toLong() * 60L) return true
        return false
    }

    fun needsMoreChromeYouTubeDurationText(
        goal: String,
        screenText: String,
        structuredMaxBlockMinutes: Int? = null,
        packageName: String = VideoPlatformRegistry.CHROME,
        structuredMinBlockMinutes: Int? = null
    ): Boolean {
        val hasLimit = structuredMaxBlockMinutes != null ||
            structuredMinBlockMinutes != null ||
            hasMediaLengthLimit(goal)
        return hasLimit &&
            isActiveVideoPlayerSurface(packageName, screenText) &&
            extractScreenVideoDurationMinutes(screenText) == null
    }

    /** Drive / Docs / Gmail — allow unless the promise names them as blocked. */
    fun isUnrelatedProductivityPackage(packageName: String, goal: String): Boolean {
        if (packageName !in PRODUCTIVITY_PACKAGES) return false
        val normalized = goal.lowercase()
        val blocksDocs = (normalized.contains("block") || normalized.contains("no ")) &&
            (normalized.contains("docs") || normalized.contains("drive") ||
                normalized.contains("gmail"))
        return !blocksDocs
    }

    /**
     * Stored app rule plus goal-text whole-app blocks, used by both enforcement paths.
     * A surface-qualified alias ("block youtube shorts") is never a whole-app block.
     */
    fun effectiveAppRule(
        stored: AppRuleBehavior?,
        goal: String,
        packageName: String
    ): AppRuleBehavior? =
        if (stored == AppRuleBehavior.BLOCK || explicitlyBlocksKnownPackage(goal, packageName)) {
            AppRuleBehavior.BLOCK
        } else {
            stored
        }

    fun explicitlyBlocksKnownPackage(goal: String, packageName: String): Boolean {
        val aliases = PACKAGE_ALIASES[packageName].orEmpty()
        if (aliases.isEmpty()) return false
        val normalized = goal.lowercase()
        return aliases.any { alias -> explicitlyBlocksAlias(normalized, alias) }
    }

    /**
     * The cue must be syntactically bound to the alias:
     *   (A) pre-cue immediately governing the alias
     *         ("no whatsapp", "block gmail", "don't use gmail", "cannot use whatsapp",
     *          "do not allow to use whatsapp", "not allow me to open gmail"), OR
     *   (B) state predicate immediately after the alias
     *         ("whatsapp is blocked", "gmail disabled"), OR
     *   (C) polarity-aware "except <alias>" — a positive polarity before "except"
     *         ("allow everything except X", "everything except X") blocks X; a
     *         negative polarity before "except" ("no messengers except X") allows X.
     *
     * A bare "no" / "stop" / "block" elsewhere in the sentence MUST NOT block a
     * named app the user explicitly allowed. See
     * docs/scope-semantics-v1-phase2-review.md §2 F2. Kept in sync with the
     * matching implementation in FocusPromiseParser.explicitlyBlocksNamedApp.
     */
    private fun explicitlyBlocksAlias(normalized: String, alias: String): Boolean {
        val a = Regex.escape(alias) + SURFACE_QUALIFIER_GUARD

        // (A) Block cue immediately governs the alias.
        val preCue = Regex(
            """(?<![a-z])(?:""" +
                """no|block|blocks|blocking|ban|bans|banning|forbid|forbids|""" +
                """stop|stops|disable|disables|disabling|blacklist|""" +
                """don't\s+use|dont\s+use|do\s+not\s+use|""" +
                """can't\s+use|cannot\s+use|no\s+using|""" +
                """(?:do\s+not|don't|dont|never)\s+allow""" +
                """(?:\s+(?:me\s+)?(?:to\s+)?(?:use|open|watch|access))?|""" +
                """not\s+allow""" +
                """(?:\s+(?:me\s+)?(?:to\s+)?(?:use|open|watch|access))?""" +
                """)\s+(?:the\s+)?""" + a + """\b""",
            RegexOption.IGNORE_CASE
        )
        if (preCue.containsMatchIn(normalized)) return true

        // (B) State predicate after the alias.
        val postCue = Regex(
            """\b""" + a + """\b\s+(?:is\s+|are\s+|will\s+be\s+|gets?\s+|stays?\s+|remains?\s+|)""" +
                """(?:blocked|banned|forbidden|disabled|blacklisted|off|not\s+allowed|closed|shut)\b""",
            RegexOption.IGNORE_CASE
        )
        if (postCue.containsMatchIn(normalized)) return true

        // (C) Polarity-aware "except <alias>".
        val exceptRe = Regex("""\bexcept\s+(?:the\s+)?""" + a + """\b""", RegexOption.IGNORE_CASE)
        val exceptMatch = exceptRe.find(normalized) ?: return false
        val before = normalized.substring(0, exceptMatch.range.first).lowercase()
        val lastAllow = listOf("allow", "everything", "permit").maxOf { before.lastIndexOf(it) }
        val lastBlock = listOf(
            " no ", " block", " don't", " dont", " do not", " not allow",
            " ban", " forbid", " stop", " disable"
        ).maxOf { before.lastIndexOf(it) }
        return lastAllow >= 0 && lastAllow > lastBlock
    }

    /**
     * Which side of a per-video length threshold the promise blocks.
     * [minBlockMinutes]: block videos shorter than N. [maxBlockMinutes]: block
     * videos longer than N. Both equal only when [exact] ("exactly N minutes").
     */
    data class MediaLengthIntent(
        val minBlockMinutes: Int?,
        val maxBlockMinutes: Int?,
        val exact: Boolean = false
    )

    /**
     * Polarity-aware reading of per-video length language. The comparator word
     * alone is not enough:
     *   "do not allow videos shorter than 30"  → block < 30 (min)
     *   "allow only videos shorter than 30"    → block > 30 (max)
     *   "allow only videos longer than 30"     → block < 30 (min)
     *   "block videos longer than 30"          → block > 30 (max)
     * With no allow/block cue, "shorter" reads as min and "longer" as max.
     */
    fun mediaLengthIntent(goal: String): MediaLengthIntent {
        val normalized = goal.lowercase()
        EXACT_LENGTH_REGEX.find(normalized)?.let { match ->
            val minutes = toMinutes(match.groupValues[1], match.groupValues[2])
            if (minutes != null) return MediaLengthIntent(minutes, minutes, exact = true)
        }
        var min: Int? = null
        var max: Int? = null
        for (match in LENGTH_COMPARATOR_REGEX.findAll(normalized)) {
            val minutes = toMinutes(match.groupValues[2], match.groupValues[3]) ?: continue
            val comparator = match.groupValues[1]
            val polarity = lengthPolarityBefore(normalized, match.range.first)
                ?: lengthPolarityAfter(normalized, match.range.last + 1)
            val blocksShorter = when {
                AT_LEAST_WORDS.containsMatchIn(comparator) -> true
                SHORTER_WORDS.containsMatchIn(comparator) -> polarity != LengthPolarity.ALLOW
                else -> polarity == LengthPolarity.ALLOW
            }
            if (blocksShorter) {
                if (min == null) min = minutes
            } else {
                if (max == null) max = minutes
            }
        }
        return MediaLengthIntent(min, max)
    }

    /** Max length: block videos longer than N minutes. */
    fun extractGoalMaxVideoLimitMinutes(goal: String): Int? =
        mediaLengthIntent(goal).maxBlockMinutes

    /** Min length: block videos shorter than N minutes (must be ≥ N). */
    fun extractGoalMinVideoLimitMinutes(goal: String): Int? =
        mediaLengthIntent(goal).minBlockMinutes

    private enum class LengthPolarity { ALLOW, BLOCK }

    private fun lengthPolarityBefore(text: String, comparatorStart: Int): LengthPolarity? {
        val windowStart = maxOf(
            0,
            comparatorStart - 80,
            text.lastIndexOf('.', comparatorStart - 1) + 1,
            text.lastIndexOf(';', comparatorStart - 1) + 1
        )
        val window = text.substring(windowStart, comparatorStart)
        val last = LENGTH_POLARITY_CUE_REGEX.findAll(window).lastOrNull() ?: return null
        return if (last.groups[1] != null) LengthPolarity.BLOCK else LengthPolarity.ALLOW
    }

    private fun lengthPolarityAfter(text: String, comparatorEnd: Int): LengthPolarity? {
        val window = text.substring(comparatorEnd, minOf(text.length, comparatorEnd + 40))
        return when {
            LENGTH_POST_BLOCK_REGEX.containsMatchIn(window) -> LengthPolarity.BLOCK
            LENGTH_POST_ALLOW_REGEX.containsMatchIn(window) -> LengthPolarity.ALLOW
            else -> null
        }
    }

    private fun toMinutes(amountRaw: String, unit: String): Int? {
        val amount = amountRaw.toIntOrNull() ?: return null
        if (amount <= 0) return null
        return if (unit.startsWith("h")) amount * 60 else amount
    }

    private fun isAllowOnlyLongerFloor(normalized: String): Boolean =
        ALLOW_ONLY_LONGER_REGEX.containsMatchIn(normalized)

    fun requiresStrictlyLongerThanFloor(goal: String): Boolean {
        val normalized = goal.lowercase()
        return isAllowOnlyLongerFloor(normalized) && !isBlockLongerCeiling(normalized)
    }

    private fun isBlockLongerCeiling(normalized: String): Boolean =
        BLOCK_LONGER_CEILING_REGEX.containsMatchIn(normalized)

    /** @deprecated Prefer [extractGoalMaxVideoLimitMinutes]. */
    fun extractGoalLongVideoLimitMinutes(goal: String): Int? =
        extractGoalMaxVideoLimitMinutes(goal)

    fun extractScreenVideoDurationMinutes(screenText: String): Int? =
        VideoDurationParser.extractPrimaryDurationMinutes(screenText)

    private val PRODUCTIVITY_PACKAGES = setOf(
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.google.android.apps.drive",
        "com.google.android.gm"
    )

    private const val APP_BLOCK_WINDOW_CHARS = 80

    private val APP_BLOCK_CUE = Regex(
        """\b(no|block|ban|forbid|stop|disable|except)\b|""" +
            """do not allow|dont allow|don't allow|not allow|""" +
            """not use|dont use|don't use|cannot use|can't use""",
        RegexOption.IGNORE_CASE
    )

    /** Alias followed by a short-form surface word names that surface, not the whole app. */
    internal const val SURFACE_QUALIFIER_GUARD =
        """(?!\s+(?:shorts?|reels?|short[\s-]*(?:form|videos?))\b)"""

    private val PACKAGE_ALIASES = mapOf(
        "com.whatsapp" to listOf("whatsapp", "whats app"),
        "org.telegram.messenger" to listOf("telegram"),
        "com.google.android.gm" to listOf("gmail"),
        "com.openai.chatgpt" to listOf("chatgpt", "chat gpt"),
        "com.google.android.apps.drive" to listOf("drive", "google drive"),
        "com.google.android.apps.docs" to listOf("docs", "google docs"),
        "com.google.android.apps.docs.editors.docs" to listOf("docs", "google docs"),
        "com.google.android.apps.docs.editors.sheets" to listOf("sheets", "google sheets"),
        "com.google.android.apps.docs.editors.slides" to listOf("slides", "google slides")
    )

    private val STUDY_ONLY_PHRASES = listOf(
        "only study",
        "study only",
        "only lecture",
        "lecture only",
        "educational only",
        "only educational"
    )

    private val LIFESTYLE_PHRASES = listOf(
        "monk",
        "no entertainment",
        "zero entertainment",
        "no distraction",
        "no timepass",
        "only calls and study",
        "only calls"
    )

    private val GAME_CATEGORY_PHRASES = listOf(
        "no games",
        "no game",
        "block games",
        "block game",
        "no gaming"
    )

    private val SOCIAL_CATEGORY_PHRASES = listOf(
        "no social",
        "no youtube",
        "no instagram",
        "no facebook",
        "no tiktok"
    )

    private val DATING_CATEGORY_PHRASES = listOf(
        "no dating",
        "no tinder",
        "block dating"
    )

    private val GIRLS_CHAT_PHRASES = listOf(
        "no girls",
        "girls chat",
        "girl chat",
        "girls related",
        "no girl"
    )

    private val MUSIC_CATEGORY_PHRASES = listOf(
        "no music",
        "don't listen to music",
        "dont listen to music",
        "block music",
        "no songs"
    )

    private val MONK_PHRASES = LIFESTYLE_PHRASES +
        GAME_CATEGORY_PHRASES +
        SOCIAL_CATEGORY_PHRASES

    private val NO_MOVIE_PHRASES = listOf(
        "no movie",
        "no movies",
        "block movie",
        "block movies",
        "don't watch movie",
        "dont watch movie",
        "no films",
        "no film",
        "no ott",
        "no netflix",
        "block ott",
        "block netflix"
    )

    private val THIS_APP_BLOCK_PHRASES = listOf(
        "block this app",
        "block netmirror",
        "no netmirror",
        "don't use this app",
        "dont use this app"
    )

    private val THIS_APP_SCOPE_PHRASES = listOf(
        "this app",
        "only this app",
        "lock this app",
        "only lock this"
    )

    private val YT_TOKEN = Regex("""\byt\b""")

    private val YOUTUBE_APP_ONLY_REGEX = Regex(
        """(?:youtube\s+app\s+only|only\s+(?:the\s+)?youtube\s+app|""" +
            """youtube\s+application\s+only|""" +
            """limit(?:\s+\w+){0,8}\s+to\s+(?:the\s+)?youtube\s+app|""" +
            """block(?:\s+\w+){0,2}\s+(?:the\s+)?youtube\s+app\b|""" +
            """ban(?:\s+\w+){0,2}\s+(?:the\s+)?youtube\s+app\b)"""
    )

    /** Companion app named in the same promise (e.g. "YouTube Music is allowed"). */
    fun namesYouTubeMusicAllowed(goal: String): Boolean {
        val normalized = goal.lowercase()
        if (!normalized.contains("youtube music") && !normalized.contains("yt music")) return false
        return normalized.contains("allowed") ||
            normalized.contains("allow ") ||
            normalized.contains("except") ||
            normalized.contains("but keep") ||
            normalized.contains("still available")
    }

    private val SHORT_FORM_BLOCK_PHRASES = listOf(
        "no shorts",
        "block shorts",
        "don't watch shorts",
        "dont watch shorts",
        "no reels",
        "block reels",
        "no short video",
        "block short video"
    )

    private val SHORT_FORM_ALLOW_PHRASES = listOf(
        "allow shorts",
        "shorts allowed",
        "can watch shorts",
        "allow reels",
        "reels allowed"
    )

    private val SHORT_FORM_QUOTA_REGEX = ShortFormLanguage.HAS_QUOTA

    private val EXPLICIT_SESSION_DURATION_REGEX =
        Regex("\\b(?:for|next)\\s+\\d+\\s*(?:min|mins|minutes?|hours?|hrs?)\\b")

    private val MEDIA_LENGTH_SUBJECT_REGEX =
        Regex(
            "\\b(?:video|videos|youtube|yt|lecture|lectures|episode|episodes|" +
                "newpipe|netmirror|ott|movie|movies)\\b"
        )

    private val MEDIA_LENGTH_LIMIT_REGEX =
        Regex(
            "\\b(?:greater than|more than|over|longer than|above|shorter than|less than|under|" +
                "length\\s+is\\s+(?:greater|more|longer|shorter|less)\\s+than|" +
                "at\\s+least|minimum\\s+of)\\s*\\d+\\s*" +
                "(?:min|mins|minutes?|hrs?|hours?)\\b"
        )

    private val MIN_LENGTH_PHRASE_REGEX =
        Regex(
            "\\b(?:do\\s+not\\s+allow|don't\\s+allow|dont\\s+allow|block|no)\\s+" +
                "(?:any\\s+)?(?:videos?|youtube).{0,40}?" +
                "(?:shorter|less)\\s+than\\s*\\d+\\s*(?:min|mins|minutes?|hrs?|hours?)\\b"
        )

    private val LENGTH_COMPARATOR_REGEX =
        Regex(
            "\\b(at\\s+least|minimum\\s+of|no\\s+shorter\\s+than|" +
                "shorter than|less than|under|below|" +
                "length\\s+is\\s+(?:shorter|less)\\s+than|" +
                "greater than|more than|over|longer than|above|" +
                "length\\s+is\\s+(?:greater|more|longer)\\s+than)\\s*(\\d+)\\s*" +
                "(min|mins|minutes?|hrs?|hours?)\\b"
        )

    private val EXACT_LENGTH_REGEX =
        Regex("\\bexactly\\s+(\\d+)\\s*(min|mins|minutes?|hrs?|hours?)\\b")

    private val AT_LEAST_WORDS = Regex("^(?:at\\s+least|minimum\\s+of|no\\s+shorter\\s+than)")

    private val SHORTER_WORDS = Regex("(?:shorter|less|under|below)")

    /** Group 1 = block cue, group 2 = allow cue. Block phrases listed first so "do not allow" wins over "allow". */
    private val LENGTH_POLARITY_CUE_REGEX =
        Regex(
            "\\b(do\\s+not\\s+allow|don'?t\\s+allow|not\\s+allow|never\\s+allow|" +
                "do\\s+not\\s+let\\s+me|don'?t\\s+let\\s+me|" +
                "do\\s+not\\s+watch|don'?t\\s+watch|" +
                "block|blocks|ban|stop|forbid|avoid|except|no)\\b|" +
                "\\b(allow\\s+only|only\\s+allow|allow|only|just|let\\s+me)\\b"
        )

    private val LENGTH_POST_BLOCK_REGEX =
        Regex("^\\s*(?:videos?\\s+)?(?:are|is|will\\s+be)?\\s*(?:not\\s+allowed|blocked|banned|forbidden)\\b")

    private val LENGTH_POST_ALLOW_REGEX =
        Regex("^\\s*(?:videos?\\s+)?(?:are|is|will\\s+be)?\\s*(?:allowed|ok|fine)\\b")

    /**
     * Allow-polarity floor: "allow only videos longer than 40",
     * "allow … all video whose length is greater than 40".
     */
    private val ALLOW_ONLY_LONGER_REGEX =
        Regex(
            "\\b(?:allow(?:\\s+only)?|only\\s+allow|only)\\b.{0,80}?" +
                "(?:videos?|youtube|all\\s+video).{0,60}?" +
                "(?:longer than|greater than|more than|" +
                "length\\s+is\\s+(?:greater|more|longer)\\s+than)\\s*(\\d+)\\s*" +
                "(min|mins|minutes?|hrs?|hours?)\\b"
        )

    /** Block-polarity ceiling: "do not allow / except / block videos longer than 40". */
    private val BLOCK_LONGER_CEILING_REGEX =
        Regex(
            "\\b(?:do\\s+not\\s+allow|don't\\s+allow|dont\\s+allow|block|except|no)\\b.{0,80}?" +
                "(?:greater than|more than|over|longer than|above|" +
                "length\\s+is\\s+(?:greater|more|longer)\\s+than)\\s*\\d+"
        )
}
