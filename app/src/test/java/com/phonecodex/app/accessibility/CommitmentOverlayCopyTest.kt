package com.phonecodex.app.accessibility

import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.SessionEnforcementCopy
import com.phonecodex.app.domain.model.StrictnessLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentOverlayCopyTest {

    @Test
    fun overlayKind_mapsDecisions() {
        assertEquals(OverlayKind.WARN, CommitmentOverlayCopy.overlayKind("WARN"))
        assertEquals(OverlayKind.WARN, CommitmentOverlayCopy.overlayKind("ASK"))
        assertEquals(OverlayKind.BLOCK, CommitmentOverlayCopy.overlayKind("BLOCK"))
        assertEquals(OverlayKind.LOCK, CommitmentOverlayCopy.overlayKind("LOCK"))
        assertEquals(OverlayKind.BLOCK, CommitmentOverlayCopy.overlayKind("ALLOW"))
    }

    @Test
    fun evidenceLine_prefersHumanReason() {
        assertEquals(
            "Shorts will pull you off lecture mode.",
            CommitmentOverlayCopy.evidenceLine("BLOCK", "Shorts will pull you off lecture mode.")
        )
    }

    @Test
    fun evidenceLine_fallsBackByKind() {
        assertEquals(
            "This doesn’t match your current commitment.",
            CommitmentOverlayCopy.evidenceLine("BLOCK", "  ")
        )
        assertEquals(
            "This might pull you off your commitment.",
            CommitmentOverlayCopy.evidenceLine("WARN", "")
        )
        assertEquals(
            "Repeated attempts or a strict commitment paused this path.",
            CommitmentOverlayCopy.evidenceLine("LOCK", "")
        )
    }

    @Test
    fun evidenceLine_stripsPermanentGuardrailTechDump() {
        val cleaned = CommitmentOverlayCopy.evidenceLine(
            "BLOCK",
            "Permanent guardrail: Block Porn (matched: strong: porn)"
        )
        assertFalse(cleaned.contains("Permanent guardrail", ignoreCase = true))
        assertFalse(cleaned.contains("matched:", ignoreCase = true))
        assertEquals(
            "This doesn’t match your current commitment.",
            cleaned
        )
        assertEquals(
            SessionEnforcementCopy.ADULT_GUARDRAIL_BLOCK,
            SessionEnforcementCopy.overlayEvidence(
                EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
                "Permanent guardrail: Block Porn (matched: strong: porn)"
            )
        )
    }

    @Test
    fun evidenceLine_stripsPackageAndTechMeta() {
        val cleaned = CommitmentOverlayCopy.evidenceLine(
            "BLOCK",
            "com.google.android.youtube is conditional in Study World under STRICT " +
                "confidence=0.92 source=Policy reasonCategory=study_drift"
        )
        assertFalse(cleaned.contains("com.google.android.youtube"))
        assertFalse(cleaned.contains("confidence", ignoreCase = true))
        assertFalse(cleaned.contains("source=", ignoreCase = true))
        assertFalse(cleaned.contains("reasonCategory", ignoreCase = true))
        assertFalse(cleaned.contains("under STRICT", ignoreCase = true))
        assertTrue(cleaned.contains("Study World") || cleaned.contains("conditional"))
    }

    @Test
    fun shortReason_delegatesToEvidenceLine() {
        assertEquals(
            CommitmentOverlayCopy.evidenceLine("WARN", "Soft nudge."),
            CommitmentOverlayCopy.shortReason("WARN", "Soft nudge.")
        )
    }

    @Test
    fun promiseLine_truncatesToTwelveWords() {
        val longGoal =
            "YouTube only for calculus lectures no Shorts and also no gaming related videos ever"
        val line = CommitmentOverlayCopy.promiseLine(longGoal)
        assertTrue(line!!.endsWith("…"))
        assertEquals(12, line.removeSuffix("…").trim().split(Regex("\\s+")).size)
    }

    @Test
    fun promiseLine_hidesBlank() {
        assertNull(CommitmentOverlayCopy.promiseLine(null))
        assertNull(CommitmentOverlayCopy.promiseLine("  "))
    }

    @Test
    fun attemptLabel_hidesZero() {
        assertNull(CommitmentOverlayCopy.attemptLabel(null))
        assertNull(CommitmentOverlayCopy.attemptLabel(0))
        assertEquals("1 attempt", CommitmentOverlayCopy.attemptLabel(1))
        assertEquals("3 attempts", CommitmentOverlayCopy.attemptLabel(3))
    }

    @Test
    fun remainingLabel_lockPrefix() {
        assertEquals(
            "12m left",
            CommitmentOverlayCopy.remainingLabel(OverlayKind.BLOCK, 12 * 60_000L)
        )
        assertEquals(
            "Lock holds · 12m left",
            CommitmentOverlayCopy.remainingLabel(OverlayKind.LOCK, 12 * 60_000L)
        )
        assertNull(CommitmentOverlayCopy.remainingLabel(OverlayKind.WARN, 0L))
    }

    @Test
    fun quotaReminder_keepWatchingNotHome() {
        assertEquals(
            "9 shorts left of 10.",
            CommitmentOverlayCopy.headline(
                OverlayKind.WARN,
                EnforcementReasonCodes.QUOTA_ALLOW_COUNT,
                "9 shorts left of 10."
            )
        )
        assertEquals(
            SessionEnforcementCopy.QUOTA_KEEP_WATCHING,
            CommitmentOverlayCopy.primaryActionLabel(
                OverlayKind.WARN,
                EnforcementReasonCodes.QUOTA_ALLOW_COUNT
            )
        )
        assertEquals(
            SessionEnforcementCopy.QUOTA_REMINDER_COMPANION,
            CommitmentOverlayCopy.companionLine(
                OverlayKind.WARN,
                EnforcementReasonCodes.QUOTA_ALLOW_COUNT
            )
        )
        assertTrue(SessionEnforcementCopy.isQuotaReminder(EnforcementReasonCodes.QUOTA_ALLOW_COUNT))
        assertFalse(SessionEnforcementCopy.isQuotaReminder(EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED))
    }

    @Test
    fun headline_isContractToneNotShame() {
        assertEquals("Drift from your promise", CommitmentOverlayCopy.headline(OverlayKind.WARN))
        assertEquals("Outside your promise", CommitmentOverlayCopy.headline(OverlayKind.BLOCK))
        assertEquals("Your commitment is locked", CommitmentOverlayCopy.headline(OverlayKind.LOCK))
        listOf(OverlayKind.WARN, OverlayKind.BLOCK, OverlayKind.LOCK).forEach { kind ->
            val copy = CommitmentOverlayCopy.headline(kind) + " " +
                CommitmentOverlayCopy.companionLine(kind)
            assertFalse(copy.contains("Stay strong", ignoreCase = true))
            assertFalse(copy.contains("be better", ignoreCase = true))
            assertFalse(copy.contains("inappropriate", ignoreCase = true))
            assertFalse(copy.contains("AI thinks", ignoreCase = true))
        }
    }

    @Test
    fun strictnessCue_oneWordQuiet() {
        assertEquals("SOFT", CommitmentOverlayCopy.strictnessCue(StrictnessLevel.SOFT))
        assertEquals("SMART", CommitmentOverlayCopy.strictnessCue(StrictnessLevel.SMART))
        assertEquals("STRICT", CommitmentOverlayCopy.strictnessCue(StrictnessLevel.STRICT))
        assertEquals("LOCKED", CommitmentOverlayCopy.strictnessCue(StrictnessLevel.LOCKED))
        assertNull(CommitmentOverlayCopy.strictnessCue(null))
    }

    @Test
    fun youtubeHardBlock_durationUnavailable_isFirmNotContinue() {
        assertEquals(
            SessionEnforcementCopy.YOUTUBE_CLOCK_UNREADABLE_BLOCK,
            SessionEnforcementCopy.overlayEvidence(
                EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
                "blocked because greater than 2 hours",
                hardBlock = true
            )
        )
        assertFalse(
            SessionEnforcementCopy.hidePromiseLine(
                EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
                hardBlock = true
            )
        )
        assertFalse(
            SessionEnforcementCopy.forceContinue(
                EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
                hardBlock = true
            )
        )
        assertFalse(
            SessionEnforcementCopy.YOUTUBE_CLOCK_UNREADABLE_BLOCK.contains(
                "carefully",
                ignoreCase = true
            )
        )
        assertFalse(
            SessionEnforcementCopy.YOUTUBE_CLOCK_UNREADABLE_BLOCK.contains(
                "maybe",
                ignoreCase = true
            )
        )
        assertFalse(
            CommitmentOverlayCopy.allowsContinue(
                OverlayKind.BLOCK,
                StrictnessLevel.SOFT,
                forceContinue = false
            )
        )
    }

    @Test
    fun durationUnavailable_hidesPromiseAndForcesContinue() {
        assertTrue(
            SessionEnforcementCopy.hidePromiseLine(
                EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE
            )
        )
        assertTrue(
            SessionEnforcementCopy.forceContinue(
                EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE
            )
        )
        assertTrue(
            SessionEnforcementCopy.forceContinue(
                EnforcementReasonCodes.QUOTA_ALLOW_COUNT
            )
        )
        assertEquals(
            SessionEnforcementCopy.DURATION_UNAVAILABLE,
            SessionEnforcementCopy.overlayEvidence(
                EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
                "blocked because greater than 2 hours"
            )
        )
        assertFalse(
            SessionEnforcementCopy.DURATION_UNAVAILABLE.contains("greater than", ignoreCase = true)
        )
        assertFalse(
            SessionEnforcementCopy.DURATION_UNAVAILABLE.contains("blocked because", ignoreCase = true)
        )
        assertTrue(
            CommitmentOverlayCopy.allowsContinue(
                OverlayKind.WARN,
                StrictnessLevel.STRICT,
                forceContinue = true
            )
        )
    }

    @Test
    fun allowsContinue_onlyWarnSoftOrSmart() {
        assertTrue(
            CommitmentOverlayCopy.allowsContinue(OverlayKind.WARN, StrictnessLevel.SOFT)
        )
        assertTrue(
            CommitmentOverlayCopy.allowsContinue(OverlayKind.WARN, StrictnessLevel.SMART)
        )
        assertFalse(
            CommitmentOverlayCopy.allowsContinue(OverlayKind.WARN, StrictnessLevel.STRICT)
        )
        assertFalse(
            CommitmentOverlayCopy.allowsContinue(OverlayKind.WARN, StrictnessLevel.LOCKED)
        )
        assertFalse(
            CommitmentOverlayCopy.allowsContinue(OverlayKind.BLOCK, StrictnessLevel.SOFT)
        )
        assertFalse(
            CommitmentOverlayCopy.allowsContinue(OverlayKind.LOCK, StrictnessLevel.SOFT)
        )
        assertFalse(CommitmentOverlayCopy.allowsContinue(OverlayKind.WARN, null))
    }

    @Test
    fun primaryActionLabels_matchModeJobs() {
        assertEquals(
            "Return to safe path",
            CommitmentOverlayCopy.primaryActionLabel(OverlayKind.WARN, null)
        )
        assertEquals(
            "Leave this screen",
            CommitmentOverlayCopy.primaryActionLabel(OverlayKind.BLOCK)
        )
        assertEquals(
            "Back to safe screen",
            CommitmentOverlayCopy.primaryActionLabel(OverlayKind.LOCK)
        )
    }

    @Test
    fun decisionBadge_showsModeChip() {
        assertEquals("WARN", CommitmentOverlayCopy.decisionBadge(OverlayKind.WARN))
        assertEquals("BLOCK", CommitmentOverlayCopy.decisionBadge(OverlayKind.BLOCK))
        assertEquals("LOCK", CommitmentOverlayCopy.decisionBadge(OverlayKind.LOCK))
    }
}
