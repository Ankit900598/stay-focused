package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.promise.PromiseIntentRules
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.HostResolver
import com.phonecodex.app.domain.scope.LegacyScopeAdapter
import com.phonecodex.app.domain.scope.ScopeMatch
import com.phonecodex.app.domain.scope.ScopeMatcher

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

/** Why a [MediaLengthLocalDecision.BLOCK] happened. Drives reason code + evidence so logs cannot contradict. */
enum class MediaLengthBlockBasis {
    /** Current-player clock shorter than the promise minimum. */
    CURRENT_PLAYER_UNDER_MIN,
    /** Current-player clock longer than the promise maximum. */
    CURRENT_PLAYER_OVER_MAX,
    /** Active short-form player with no clock; a Short is always under a >1 min floor. */
    SHORT_FORM_SURFACE_UNDER_MIN
}

data class MediaLengthDecisionDetail(
    val decision: MediaLengthLocalDecision,
    val durationSource: DurationSource,
    val currentPlayerDurationSeconds: Int?,
    val recommendationDurationsSeconds: List<Int>,
    val blockBasis: MediaLengthBlockBasis? = null
) {
    /** MEDIA_BLOCK_* codes only when a current-player clock exists. */
    val blockReasonCode: String?
        get() = when (blockBasis) {
            MediaLengthBlockBasis.CURRENT_PLAYER_UNDER_MIN -> EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN
            MediaLengthBlockBasis.CURRENT_PLAYER_OVER_MAX -> EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
            MediaLengthBlockBasis.SHORT_FORM_SURFACE_UNDER_MIN -> EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN
            null -> null
        }

    /**
     * Evidence consistent with [blockReasonCode]. Pass the log header's
     * duration-source label so header and evidence print the same value.
     */
    fun blockEvidence(durationSourceLabel: String = durationSource.name.lowercase()): List<String> {
        val basisSignal = when (blockBasis) {
            MediaLengthBlockBasis.CURRENT_PLAYER_UNDER_MIN -> "media_length_under_min"
            MediaLengthBlockBasis.CURRENT_PLAYER_OVER_MAX -> "media_length_over_max"
            MediaLengthBlockBasis.SHORT_FORM_SURFACE_UNDER_MIN -> "short_form_player_under_min"
            null -> return emptyList()
        }
        return listOfNotNull(
            basisSignal,
            "duration_source=$durationSourceLabel",
            playerSecondsSignal()
        )
    }

    /** ALLOW counterpart of [blockEvidence]: same duration_source / player_seconds shape. */
    fun allowEvidence(durationSourceLabel: String = durationSource.name.lowercase()): List<String> {
        if (decision != MediaLengthLocalDecision.ALLOW) return emptyList()
        return listOfNotNull(
            "media_length_local_allow",
            "duration_source=$durationSourceLabel",
            playerSecondsSignal()
        )
    }

    private fun playerSecondsSignal(): String? =
        currentPlayerDurationSeconds
            ?.takeIf { durationSource == DurationSource.CURRENT_PLAYER }
            ?.let { "player_seconds=$it" }
}

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
        contentBrands: Collection<String> = emptyList(),
        typedScope: EnforcementScope? = null
    ): MediaLengthDecisionDetail {
        val parsed = VideoDurationParser.parse(screenText)
        val hasLimit = structuredMaxBlockMinutes != null ||
            structuredMinBlockMinutes != null ||
            PromiseIntentRules.hasMediaLengthLimit(goal)
        if (!hasLimit) {
            return detail(MediaLengthLocalDecision.NONE, parsed)
        }

        if (
            !VideoPlatformRegistry.enforcesMediaSurfaces(packageName) &&
            !typedScopeCoversBrowser(packageName, screenText, typedScope)
        ) {
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
            val clockSeconds = parsed.currentPlayerDurationSeconds
                ?.takeIf { parsed.source == DurationSource.CURRENT_PLAYER }
            val seconds = clockSeconds ?: SurfaceDetector.SHORT_FORM_MAX_SECONDS
            if (seconds < minFloorMinutes * 60) {
                val basis = if (clockSeconds != null) {
                    MediaLengthBlockBasis.CURRENT_PLAYER_UNDER_MIN
                } else {
                    MediaLengthBlockBasis.SHORT_FORM_SURFACE_UNDER_MIN
                }
                return detail(MediaLengthLocalDecision.BLOCK, parsed, basis)
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
            // Same order as videoViolatesMediaLengthLimit: max first, then min.
            val maxMinutes = structuredMaxBlockMinutes
                ?: PromiseIntentRules.extractGoalMaxVideoLimitMinutes(goal)
            val basis = if (
                maxMinutes != null &&
                parsed.currentPlayerDurationSeconds > maxMinutes * 60
            ) {
                MediaLengthBlockBasis.CURRENT_PLAYER_OVER_MAX
            } else {
                MediaLengthBlockBasis.CURRENT_PLAYER_UNDER_MIN
            }
            return detail(MediaLengthLocalDecision.BLOCK, parsed, basis)
        }
        if (
            structuredMinBlockMinutes != null &&
            PromiseIntentRules.requiresStrictlyLongerThanFloor(goal) &&
            parsed.currentPlayerDurationSeconds <= structuredMinBlockMinutes * 60
        ) {
            return detail(
                MediaLengthLocalDecision.BLOCK,
                parsed,
                MediaLengthBlockBasis.CURRENT_PLAYER_UNDER_MIN
            )
        }

        return detail(MediaLengthLocalDecision.ALLOW, parsed)
    }

    /**
     * The registry lists Chrome as its only browser. A confirmed typed scope that
     * already matched this verified browser's page extends clock B to it; legacy
     * sessions keep registry-only behaviour.
     */
    private fun typedScopeCoversBrowser(
        packageName: String,
        screenText: String,
        typedScope: EnforcementScope?
    ): Boolean {
        if (typedScope == null || LegacyScopeAdapter.isLegacyAdapted(typedScope)) return false
        if (!HostResolver.isVerifiedBrowser(packageName)) return false
        val detection = SurfaceDetector.detect(packageName, screenText)
        return ScopeMatcher.match(packageName, screenText, detection, typedScope) is ScopeMatch.InScope
    }

    private fun detail(
        decision: MediaLengthLocalDecision,
        parsed: VideoDurationParseResult,
        blockBasis: MediaLengthBlockBasis? = null
    ): MediaLengthDecisionDetail =
        MediaLengthDecisionDetail(
            decision = decision,
            durationSource = parsed.source,
            currentPlayerDurationSeconds = parsed.currentPlayerDurationSeconds,
            recommendationDurationsSeconds = parsed.recommendationDurationsSeconds,
            blockBasis = blockBasis
        )
}
