package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.promise.PromiseIntentRules
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog

/**
 * Fullscreen movie/OTT players often expose a blank or near-blank accessibility
 * tree. Silent ALLOW is illegal while a commitment is active.
 *
 * Media-length promises → WAIT/WARN ([VIDEO_APP_DURATION_UNAVAILABLE]).
 * Exception: official YouTube under a YouTube-content minimum-length promise fails closed.
 * Unknown duration is never treated as over/under the limit for generic / unknown players.
 * Monk / no-entertainment / no-movies / block-this-app → BLOCK
 * ([UNKNOWN_VIDEO_APP_BLANK_TREE]).
 * Named-app scope: if [enforcementScopePackages] is set, other packages skip this gate.
 * Emergency/safe packages never enter this gate.
 *
 * Default: never screenshot. The time-boxed [VisionCaptureGate] may request one JPEG
 * only on WAIT / duration-unavailable — never to guess a clock or override BLOCK.
 */
enum class BlankVideoTreeAction {
    NONE,
    WAIT,
    BLOCK
}

data class BlankVideoTreeResult(
    val action: BlankVideoTreeAction,
    val reasonCode: String,
    val reason: String
) {
    val isHardDecision: Boolean
        get() = action != BlankVideoTreeAction.NONE
}

object BlankVideoTreeGate {

    private val NONE = BlankVideoTreeResult(
        action = BlankVideoTreeAction.NONE,
        reasonCode = "CONTINUE",
        reason = "Not a blank video/movie tree"
    )

    fun evaluate(
        hasActiveSession: Boolean,
        packageName: String,
        screenText: String,
        goal: String,
        enforcementScopePackages: Collection<String> = emptyList(),
        contentBrands: Collection<String> = emptyList()
    ): BlankVideoTreeResult {
        if (!hasActiveSession) return NONE
        if (SafeAppsCatalog.isDefaultPackage(packageName)) return NONE
        if (
            !EnforcementScopeLaw.matches(
                packageName = packageName,
                screenText = screenText,
                scopePackages = enforcementScopePackages,
                contentBrands = contentBrands
            )
        ) {
            return NONE
        }
        if (!VideoPlatformRegistry.isVideoOrStreamingPackage(packageName)) return NONE
        val surface = SurfaceDetector.detect(packageName, screenText)
        // Shorts / Reels URL or player is identified short-form — not an unknown
        // blank movie tree. Media-length / quota law owns that surface.
        if (
            surface.isShortFormPlay ||
            AccessibilityUrlExtractor.containsShortFormVideoUrl(screenText, packageName)
        ) {
            return NONE
        }
        // Browser home / search / article dumps are not fullscreen OTT trees.
        if (
            EnforcementScopeLaw.looksLikeBrowserPackage(packageName) &&
            !surface.isActivePlayer
        ) {
            return NONE
        }
        if (!isNearBlankAccessibilityTree(screenText)) return NONE

        if (PromiseIntentRules.blocksEntertainmentOrMovies(goal)) {
            return BlankVideoTreeResult(
                action = BlankVideoTreeAction.BLOCK,
                reasonCode = EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE,
                reason = SessionEnforcementCopy.BLANK_TREE_MOVIE_BLOCK
            )
        }

        if (shouldFailClosedForYoutubeMinLength(packageName, goal, contentBrands)) {
            return BlankVideoTreeResult(
                action = BlankVideoTreeAction.BLOCK,
                reasonCode = EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
                reason = SessionEnforcementCopy.YOUTUBE_CLOCK_UNREADABLE_BLOCK
            )
        }

        return BlankVideoTreeResult(
            action = BlankVideoTreeAction.WAIT,
            reasonCode = EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
            reason = SessionEnforcementCopy.DURATION_UNAVAILABLE
        )
    }

    private fun shouldFailClosedForYoutubeMinLength(
        packageName: String,
        goal: String,
        contentBrands: Collection<String>
    ): Boolean {
        if (!VideoPlatformRegistry.isOfficialYouTube(packageName)) return false
        val brands = contentBrands.map { it.trim().lowercase() }
        if (EnforcementScopeLaw.BRAND_YOUTUBE !in brands &&
            !PromiseIntentRules.namesYouTubeContentBrand(goal)
        ) {
            return false
        }
        return PromiseIntentRules.extractGoalMinVideoLimitMinutes(goal) != null &&
            PromiseIntentRules.extractGoalMaxVideoLimitMinutes(goal) == null
    }

    /**
     * Empty, whitespace, or player-chrome-only trees (Play/Pause/fullscreen)
     * with no current-player clock and almost no title text.
     */
    fun isNearBlankAccessibilityTree(screenText: String): Boolean {
        if (VideoDurationParser.parse(screenText).currentPlayerDurationSeconds != null) {
            return false
        }
        val normalized = screenText.lowercase().replace(WHITESPACE, " ").trim()
        if (normalized.isEmpty()) return true
        val tokens = normalized.split(" ").filter { it.isNotBlank() }
        val meaningful = tokens.filter { token ->
            val cleaned = token.trim('.', ',', ':', '/', '|', '-', '•', '·')
            cleaned.isNotEmpty() &&
                cleaned !in PLAYER_CHROME_TOKENS &&
                !cleaned.all { ch -> ch.isDigit() || ch == ':' || ch == '.' }
        }
        return meaningful.size <= 2 && normalized.length < 96
    }

    private val WHITESPACE = Regex("\\s+")

    private val PLAYER_CHROME_TOKENS = setOf(
        "play",
        "pause",
        "resume",
        "stop",
        "fullscreen",
        "full",
        "screen",
        "player",
        "buffering",
        "loading",
        "seek",
        "mute",
        "unmute",
        "volume",
        "cast",
        "back",
        "close",
        "ok",
        "hd",
        "live"
    )
}
