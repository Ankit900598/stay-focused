package com.phonecodex.app.domain.enforcement.trace

import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.ui.home.ConfirmationUserCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSurfaceTraceReplayTest {

    @Test
    fun catalogHasAtLeast80Traces() {
        val traces = PhoneSurfaceTraceCatalog.all()
        assertTrue(
            "Need >= 80 traces, got ${traces.size}",
            traces.size >= 80
        )
        val ids = traces.map { it.id }
        assertTrue("duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size == ids.toSet().size)
    }

    @Test
    fun catalogIncludesTheThreePhoneRegressions() {
        val ids = PhoneSurfaceTraceCatalog.all().map { it.id }.toSet()
        assertTrue(ids.contains("chrome_yt_spoken_31m33_blocks_min40"))
        assertTrue(ids.contains("chrome_shorts_locked_never_unrelated"))
        assertTrue(ids.contains("scope_content_chrome_not_official_only"))
        assertTrue(ids.contains("scope_app_only_chrome_watch_out"))
    }

    @Test
    fun replayEveryTrace() {
        val traces = PhoneSurfaceTraceCatalog.all()
        val failures = mutableListOf<String>()
        for (trace in traces) {
            val actual = EnforcementTraceEvaluator.evaluate(trace)
            val problems = mutableListOf<String>()
            if (actual.decision !in trace.expectedDecisions) {
                problems += "decision"
            }
            if (
                trace.expectedReasonCodes.isNotEmpty() &&
                actual.reasonCode !in trace.expectedReasonCodes
            ) {
                problems += "reasonCode"
            }
            if (actual.reasonCode in trace.forbiddenReasonCodes) {
                problems += "forbiddenReason"
            }
            if (
                !trace.expectedSurface.isNullOrBlank() &&
                actual.surface != trace.expectedSurface
            ) {
                problems += "surface"
            }
            if (
                !trace.expectedActivity.isNullOrBlank() &&
                actual.activity != trace.expectedActivity
            ) {
                problems += "activity"
            }
            if (
                !trace.expectedDurationSource.isNullOrBlank() &&
                actual.durationSource != trace.expectedDurationSource
            ) {
                problems += "durationSource"
            }
            if (!trace.userFacingCopy.isNullOrBlank()) {
                val cleaned = ConfirmationUserCopy.sanitize(trace.userFacingCopy, trace.goal)
                if (cleaned.contains("com.") || cleaned.contains("org.schabi", ignoreCase = true)) {
                    problems += "packageLeak"
                }
            }
            if (problems.isNotEmpty()) {
                failures += EnforcementTraceEvaluator.formatFailure(trace, actual) +
                    " mismatch=${problems.joinToString(",")}"
            }
        }
        assertTrue(
            failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun explicitBlockOverridesConvenienceSafeApp() {
        val result = EnforcementTraceEvaluator.evaluate(
            EnforcementTrace(
                id = "whatsapp_explicit_block_regression",
                packageName = "com.whatsapp",
                screenText = "WhatsApp Chats Updates Calls Archived",
                goal = "do not allow me to use WhatsApp for 3 days",
                settings = StudyWorldSettings(
                    durationMinutes = 4320,
                    lockAttemptThreshold = 3,
                    blockAttemptCooldownMinutes = 5
                ),
                appRule = AppRuleBehavior.BLOCK,
                expectedDecisions = setOf(TraceDecision.BLOCK)
            )
        )

        assertEquals(TraceDecision.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.APP_RULE_BLOCK, result.reasonCode)
    }

    @Test
    fun explicitGoalTextBlocksConvenienceSafeAppEvenWhenStoredRuleIsStaleAiDecide() {
        val result = EnforcementTraceEvaluator.evaluate(
            EnforcementTrace(
                id = "whatsapp_goal_text_block_regression",
                packageName = "com.whatsapp",
                screenText = "WhatsApp Chats Updates Calls Archived",
                goal = "dp not allow to use whatsapp fo 3 days",
                settings = StudyWorldSettings(
                    durationMinutes = 4320,
                    lockAttemptThreshold = 3,
                    blockAttemptCooldownMinutes = 5
                ),
                appRule = AppRuleBehavior.AI_DECIDE,
                expectedDecisions = setOf(TraceDecision.BLOCK)
            )
        )

        assertEquals(TraceDecision.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.APP_RULE_BLOCK, result.reasonCode)
    }

    @Test
    fun explicitGoalTextBlockStillWinsAfterSessionIsLocked() {
        val result = EnforcementTraceEvaluator.evaluate(
            EnforcementTrace(
                id = "whatsapp_locked_goal_text_block_regression",
                packageName = "com.whatsapp",
                screenText = "WhatsApp Chats Updates Calls Archived",
                goal = "dp not allow to use whatsapp fo 3 days",
                settings = StudyWorldSettings(
                    durationMinutes = 4320,
                    lockAttemptThreshold = 3,
                    blockAttemptCooldownMinutes = 5
                ),
                sessionStatus = SessionStatus.LOCKED,
                appRule = AppRuleBehavior.AI_DECIDE,
                expectedDecisions = setOf(TraceDecision.BLOCK)
            )
        )

        assertEquals(TraceDecision.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.APP_RULE_BLOCK, result.reasonCode)
    }

    @Test
    fun explicitBlockCannotOverrideEmergencyPhonePath() {
        val result = EnforcementTraceEvaluator.evaluate(
            EnforcementTrace(
                id = "phone_explicit_block_still_emergency_allow",
                packageName = "com.android.phone",
                screenText = "Emergency call Phone keypad",
                goal = "block phone app",
                settings = StudyWorldSettings(
                    durationMinutes = 60,
                    lockAttemptThreshold = 3,
                    blockAttemptCooldownMinutes = 5
                ),
                appRule = AppRuleBehavior.BLOCK,
                expectedDecisions = setOf(TraceDecision.ALLOW)
            )
        )

        assertEquals(TraceDecision.ALLOW, result.decision)
        assertEquals(EnforcementReasonCodes.EMERGENCY_ALLOW, result.reasonCode)
    }
}
