package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.promise.PromiseIntentRules

/**
 * Local media-length enforcement (clock B) for registered video platforms.
 *
 * Only active video player surfaces may BLOCK / WAIT / allow-under-limit.
 * Google Search results, Videos tabs, thumbnails, and home tiles must not.
 * Duration must come from [DurationSource.CURRENT_PLAYER] only — never recommendation cards.
 */
enum class MediaLengthLocalDecision {
    /** No media-length gate configured. */
    NONE,
    /**
     * Passive non-player surface under a media-length promise — hard PASS.
     * Callers must clear overlay and must not ask backend AI to nudge.
     */
    PASS,
    /** Active player under the length limit, or Chrome non-player legacy allow. */
    ALLOW,
    /** Active player whose visible duration violates the promise threshold. */
    BLOCK,
    /** Active player but duration not readable yet — wait, do not classify. */
    WAIT
}

data class MediaLengthDecisionDetail(
    val decision: MediaLengthLocalDecision,
    val durationSource: DurationSource,
    val currentPlayerDurationSeconds: Int?,
    val recommendationDurationsSeconds: List<Int>
)

object MediaLengthEnforcement {

    @Deprecated("Use VideoPlatformRegistry.YOUTUBE", ReplaceWith("VideoPlatformRegistry.YOUTUBE"))
    const val YOUTUBE_PACKAGE = VideoPlatformRegistry.YOUTUBE

    @Deprecated("Use VideoPlatformRegistry.CHROME", ReplaceWith("VideoPlatformRegistry.CHROME"))
    const val CHROME_PACKAGE = VideoPlatformRegistry.CHROME

    fun decide(
        packageName: String,
        goal: String,
        screenText: String,
        structuredMaxBlockMinutes: Int?,
        structuredMinBlockMinutes: Int? = null,
        enforcementScopePackages: Collection<String> = emptyList(),
        contentBrands: Collection<String> = emptyList()
    ): MediaLengthLocalDecision =
        decideDetailed(
            packageName = packageName,
            goal = goal,
            screenText = screenText,
            structuredMaxBlockMinutes = structuredMaxBlockMinutes,
            structuredMinBlockMinutes = structuredMinBlockMinutes,
            enforcementScopePackages = enforcementScopePackages,
            contentBrands = contentBrands
        ).decision

    /**
     * Phase 2A: scope preflight moved to [com.phonecodex.app.domain.scope.ScopeGate].
     * `enforcementScopePackages` and `contentBrands` remain as `@Deprecated` no-op
     * parameters so the 80+ trace callers compile unchanged.
     * See docs/scope-semantics-v1-phase2-review.md §3.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decideDetailed(
        packageName: String,
        goal: String,
        screenText: String,
        structuredMaxBlockMinutes: Int?,
        structuredMinBlockMinutes: Int? = null,
        // @Deprecated: Scope handled by ScopeGate in Phase 2A. Kept for source
        // compatibility with the 80+ existing trace callers.
        enforcementScopePackages: Collection<String> = emptyList(),
        contentBrands: Collection<String> = emptyList()
    ): MediaLengthDecisionDetail {
        val parsed = VideoDurationParser.parse(screenText)
        val hasLimit = structuredMaxBlockMinutes != null ||
            structuredMinBlockMinutes != null ||
            PromiseIntentRules.hasMediaLengthLimit(goal)
        if (!hasLimit) {
            return detail(MediaLengthLocalDecision.NONE, parsed)
        }

        if (!VideoPlatformRegistry.enforcesMediaSurfaces(packageName)) {
            return detail(MediaLengthLocalDecision.NONE, parsed)
        }

        val surface = SurfaceDetector.detect(packageName, screenText)
        if (!surface.isActivePlayer) {
            if (
                BlankVideoTreeGate.isNearBlankAccessibilityTree(screenText) &&
                VideoPlatformRegistry.isVideoOrStreamingPackage(packageName)
            ) {
                return detail(MediaLengthLocalDecision.WAIT, parsed)
            }
            return detail(MediaLengthLocalDecision.PASS, parsed)
        }

        val minFloorMinutes = structuredMinBlockMinutes
            ?: PromiseIntentRules.extractGoalMinVideoLimitMinutes(goal)
        if (
            surface.isShortFormPlay &&
            minFloorMinutes != null &&
            minFloorMinutes > 1
        ) {
            val seconds = parsed.currentPlayerDurationSeconds
                ?: SurfaceDetector.SHORT_FORM_MAX_SECONDS
            if (seconds < minFloorMinutes * 60) {
                return detail(MediaLengthLocalDecision.BLOCK, parsed)
            }
        }

        // Never BLOCK from recommendation durations alone — WAIT for current player clock.
        if (parsed.currentPlayerDurationSeconds == null) {
            return detail(MediaLengthLocalDecision.WAIT, parsed)
        }

        // Single source of truth: EnforcementContext invariant must hold before any BLOCK.
        // Recommendation duration or passive text can NEVER reach the BLOCK branch below.
        val context = EnforcementContext.fromParts(packageName, screenText, surface, parsed)
        if (!context.mayApplyMediaLengthBlock()) {
            return detail(MediaLengthLocalDecision.WAIT, parsed)
        }

        if (
            PromiseIntentRules.videoViolatesMediaLengthLimit(
                goal = goal,
                screenText = screenText,
                structuredMaxBlockMinutes = structuredMaxBlockMinutes,
                structuredMinBlockMinutes = structuredMinBlockMinutes,
                packageName = packageName
            )
        ) {
            return detail(MediaLengthLocalDecision.BLOCK, parsed)
        }
        if (
            structuredMinBlockMinutes != null &&
            PromiseIntentRules.requiresStrictlyLongerThanFloor(goal) &&
            parsed.currentPlayerDurationSeconds <= structuredMinBlockMinutes * 60
        ) {
            return detail(MediaLengthLocalDecision.BLOCK, parsed)
        }

        return detail(MediaLengthLocalDecision.ALLOW, parsed)
    }

    private fun detail(
        decision: MediaLengthLocalDecision,
        parsed: VideoDurationParseResult
    ): MediaLengthDecisionDetail =
        MediaLengthDecisionDetail(
            decision = decision,
            durationSource = parsed.source,
            currentPlayerDurationSeconds = parsed.currentPlayerDurationSeconds,
            recommendationDurationsSeconds = parsed.recommendationDurationsSeconds
        )
}
