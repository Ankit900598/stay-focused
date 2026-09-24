package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.enforcement.trace.EnforcementTrace
import com.phonecodex.app.domain.enforcement.trace.EnforcementTraceEvaluator
import com.phonecodex.app.domain.enforcement.trace.TraceDecision
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.EnforcementScope.Companion.CURRENT_VERSION
import com.phonecodex.app.domain.scope.ScopeIdentity
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeKindPresets
import com.phonecodex.app.domain.scope.UnresolvedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A ordering / interaction tests. These lock in the exact interaction
 * between the new [com.phonecodex.app.domain.scope.ScopeGate] preflight and
 * the pre-existing law it sits in front of: safe apps, lock law, entertainment
 * ban, blank-tree adult, cross-app short-form quota, and dialer emergency.
 * See docs/scope-semantics-v1-phase2-review.md §4.
 */
class ScopeOrderingPhase2ATest {

    private val YT = VideoPlatformRegistry.YOUTUBE
    private val CHROME = VideoPlatformRegistry.CHROME
    private val IG = VideoPlatformRegistry.INSTAGRAM
    private val TT = VideoPlatformRegistry.TIKTOK

    // ---- monk + unknown app must still BLOCK even out of scope ------------

    @Test
    fun monk_unknownVideoApp_stillBlocks_whenOutOfScope() {
        val monkGoal = "Monk mode for 4 hours. Only calls and study. No entertainment."
        val settings = StudyWorldSettings(
            durationMinutes = 240,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScopePackages = listOf(YT) // scope names YT only
        )
        val trace = EnforcementTrace(
            id = "monk_unknown_video_still_blocks",
            packageName = "com.random.unknown.videoapp",
            screenText = "Discover Following Recommended videos",
            goal = monkGoal,
            settings = settings,
            appRule = AppRuleBehavior.AI_DECIDE,
            expectedDecisions = setOf(TraceDecision.BLOCK)
        )
        val r = EnforcementTraceEvaluator.evaluate(trace)
        assertEquals(TraceDecision.BLOCK, r.decision)
        assertNotEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, r.reasonCode)
    }

    // ---- locked session: unrelated app must still be allowed --------------

    @Test
    fun lockedSession_unrelatedApp_stillAllowedByLock_even_when_outOfScope() {
        val goal = "only YouTube videos longer than 40 minutes"
        val settings = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScopePackages = listOf(YT),
            enforcementScopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
        )
        // Pick an app that is: not safe/emergency, not monk-exempt, not in scope,
        // not a video/social platform, so SessionLockLaw.mayUseAppWhileLocked = true.
        val trace = EnforcementTrace(
            id = "locked_unrelated_allow_even_out_of_scope",
            packageName = "com.simplemobiletools.notes.pro",
            screenText = "My notes Untitled note",
            goal = goal,
            settings = settings,
            sessionStatus = SessionStatus.LOCKED,
            appRule = AppRuleBehavior.AI_DECIDE,
            expectedDecisions = setOf(TraceDecision.ALLOW)
        )
        val r = EnforcementTraceEvaluator.evaluate(trace)
        assertEquals(TraceDecision.ALLOW, r.decision)
        assertEquals(EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED, r.reasonCode)
    }

    // ---- blank-tree adult law still fires despite text-only identity miss --

    @Test
    fun blankTreeMovie_underMonk_stillBlocks() {
        // Monk override lets an unknown out-of-scope movie app through scope, then the
        // blank-tree law fires because the goal blocks entertainment/movies.
        val trace = EnforcementTrace(
            id = "blank_tree_monk_still_blocks",
            packageName = VideoPlatformRegistry.NETMIRROR,
            screenText = "  ",
            goal = "Monk mode for 3 hours. No entertainment. No movies.",
            settings = StudyWorldSettings(
                durationMinutes = 180,
                lockAttemptThreshold = 3,
                blockAttemptCooldownMinutes = 2
            ),
            appRule = AppRuleBehavior.AI_DECIDE,
            expectedDecisions = setOf(TraceDecision.BLOCK)
        )
        val r = EnforcementTraceEvaluator.evaluate(trace)
        assertEquals(TraceDecision.BLOCK, r.decision)
        assertTrue(
            "expected blank-tree or monk code, got ${r.reasonCode}",
            r.reasonCode == EnforcementReasonCodes.UNKNOWN_VIDEO_APP_BLANK_TREE ||
                r.reasonCode == EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK
        )
    }

    // ---- cross-app short-form quota counter --------------------------------

    @Test
    fun quota_countsAcrossYtIgTiktok_underShortFormAnyHostScope() {
        val quotaGoal = "at most 10 shorts today"
        val scope = EnforcementScope(
            version = CURRENT_VERSION,
            kind = ScopeKind.SHORT_FORM_ANY_HOST,
            identity = ScopeIdentity.SHORT_FORM_ANY,
            include = ScopeKindPresets.expand(ScopeKind.SHORT_FORM_ANY_HOST).include,
            confidence = 0.9,
            evidenceSpans = listOf("at most 10 shorts today")
        )
        val settings = StudyWorldSettings(
            durationMinutes = 1440,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            shortFormDailyQuotaLimit = 10,
            timeWindowKind = "calendar_day",
            enforcementScope = scope
        )
        // Use screen text that SurfaceDetector unambiguously classifies as
        // SHORT_FORM_PLAYER for each of the three platforms.
        val ytShort = trace(
            "q_yt",
            YT,
            "https://m.youtube.com/shorts/aaa111 Like Share Subscribe swipe up",
            quotaGoal, settings, quotaUsed = 0
        )
        val igShort = trace(
            "q_ig",
            IG,
            "Reels player swipe up for next Use this sound 00:07 / 00:19",
            quotaGoal, settings, quotaUsed = 1
        )
        val ttShort = trace(
            "q_tt",
            TT,
            "For You Like Share Comments 00:08 / 00:22 tiktok.com",
            quotaGoal, settings, quotaUsed = 2
        )

        val rYt = EnforcementTraceEvaluator.evaluate(ytShort)
        val rIg = EnforcementTraceEvaluator.evaluate(igShort)
        val rTt = EnforcementTraceEvaluator.evaluate(ttShort)

        // All three counted (single shared counter); reason is QUOTA_ALLOW_COUNT.
        assertEquals(EnforcementReasonCodes.QUOTA_ALLOW_COUNT, rYt.reasonCode)
        assertEquals(EnforcementReasonCodes.QUOTA_ALLOW_COUNT, rIg.reasonCode)
        assertEquals(EnforcementReasonCodes.QUOTA_ALLOW_COUNT, rTt.reasonCode)
    }

    // ---- dialer under UNRESOLVED / PACKAGE_PINNED must still allow --------

    @Test
    fun dialer_underUnresolvedScope_isAlwaysAllowedAsSafeApp() {
        val settings = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScope = EnforcementScope.unresolved(
                UnresolvedReason.HOST_AMBIGUOUS,
                evidenceSpans = listOf("only YouTube")
            )
        )
        val r = EnforcementTraceEvaluator.evaluate(
            EnforcementTrace(
                id = "dialer_under_unresolved",
                packageName = "com.android.phone",
                screenText = "Emergency call Phone keypad",
                goal = "only YouTube",
                settings = settings,
                appRule = AppRuleBehavior.AI_DECIDE,
                expectedDecisions = setOf(TraceDecision.ALLOW)
            )
        )
        assertEquals(TraceDecision.ALLOW, r.decision)
        assertEquals(EnforcementReasonCodes.EMERGENCY_ALLOW, r.reasonCode)
    }

    @Test
    fun dialer_underPackagePinnedYtScope_isAlwaysAllowedAsSafeApp() {
        val settings = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScopePackages = listOf(YT)
        )
        val r = EnforcementTraceEvaluator.evaluate(
            EnforcementTrace(
                id = "dialer_under_package_pinned",
                packageName = "com.android.phone",
                screenText = "Emergency call Phone keypad",
                goal = "study only 60 min",
                settings = settings,
                appRule = AppRuleBehavior.AI_DECIDE,
                expectedDecisions = setOf(TraceDecision.ALLOW)
            )
        )
        assertEquals(TraceDecision.ALLOW, r.decision)
        assertEquals(EnforcementReasonCodes.EMERGENCY_ALLOW, r.reasonCode)
    }

    private fun trace(
        id: String,
        pkg: String,
        text: String,
        goal: String,
        settings: StudyWorldSettings,
        quotaUsed: Int
    ) = EnforcementTrace(
        id = id,
        packageName = pkg,
        screenText = text,
        goal = goal,
        settings = settings,
        appRule = AppRuleBehavior.AI_DECIDE,
        quotaAlreadyCounted = quotaUsed,
        expectedDecisions = setOf(TraceDecision.ALLOW, TraceDecision.WAIT, TraceDecision.BLOCK)
    )
}
