package com.phonecodex.app.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentTransparencyTest {

    private val now = 1_800_000_000_000L

    @Test
    fun emptyState_isClearAndHasNoPackageDump() {
        val explained = CommitmentTransparency.explain(
            LastDecisionSnapshot(
                promiseText = null,
                decision = null,
                reason = null,
                source = null,
                packageName = null,
                reasonCode = null,
                surface = null,
                activity = null,
                durationSource = null,
                matchedSignals = emptyList(),
                updatedMillis = 0L,
                nowMillis = now
            )
        )
        assertFalse(explained.hasDecision)
        assertNotNull(explained.emptyOrStaleMessage)
        assertTrue(explained.emptyOrStaleMessage!!.contains("No recent decision"))
        assertEquals("none", explained.userDecision)
        assertFalse(userCopyContainsPackage(explained))
        assertTrue(explained.authorityLine.contains("PolicyEngine"))
    }

    @Test
    fun safeAppAllowWithoutPromise_doesNotReferToPromiseMatch() {
        val explained = CommitmentTransparency.explain(
            chromeLongAllow().copy(
                promiseText = null,
                reason = "Always allowed safe app",
                source = "Safe App",
                reasonCode = EnforcementReasonCodes.EMERGENCY_ALLOW,
                matchedSignals = listOf(EnforcementReasonCodes.EMERGENCY_ALLOW)
            )
        )

        assertEquals("No active promise at the time of this decision.", explained.userPromise)
        assertEquals(
            "This app is always allowed. Start a promise to set commitment-specific rules.",
            explained.userNext
        )
    }

    @Test
    fun regularAllowWithoutPromiseDoesNotClaimPromiseMatch() {
        val explained = CommitmentTransparency.explain(
            chromeLongAllow().copy(promiseText = null)
        )

        assertEquals("No active promise at the time of this decision.", explained.userPromise)
        assertEquals(
            "No commitment was active for this decision. Start a promise to set commitment-specific rules.",
            explained.userNext
        )
    }

    @Test
    fun staleDecision_isLabeled() {
        val explained = CommitmentTransparency.explain(
            chromeLongAllow().copy(
                updatedMillis = now - CommitmentTransparency.STALE_AFTER_MS - 1L,
                nowMillis = now
            )
        )
        assertTrue(explained.hasDecision)
        assertTrue(explained.isStale)
        assertTrue(explained.emptyOrStaleMessage!!.contains("older than 30 minutes"))
    }

    @Test
    fun userFacingExplanation_hasNoRawPackageDumps() {
        val explained = CommitmentTransparency.explain(
            chromeLongAllow().copy(
                reason = "Allowed com.android.chrome lecture on com.google.android.youtube"
            )
        )
        assertFalse(userCopyContainsPackage(explained))
        assertFalse(explained.userWhy.contains("com.android"))
        assertFalse(explained.userEvidence.contains("com.google"))
        assertTrue(explained.userDecision == "allowed")
        assertTrue(explained.userWhy.contains("Chrome") || explained.userWhy.contains("video"))
    }

    @Test
    fun engineeringDetail_includesPackageAndReasonCode() {
        val explained = CommitmentTransparency.explain(chromeLongAllow())
        assertEquals("com.android.chrome", explained.engineering.packageName)
        assertEquals(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT, explained.engineering.reasonCode)
        assertEquals("CURRENT_PLAYER", explained.engineering.durationSource)
        assertEquals("Deterministic policy", explained.engineering.sourceKind)
        assertTrue(explained.authorityLine.contains("PolicyEngine"))
        assertTrue(explained.authorityLine.contains("counsel"))
    }

    @Test
    fun chromeLongAllow_explainsDurationSource() {
        val explained = CommitmentTransparency.explain(chromeLongAllow())
        assertTrue(explained.userEvidence.contains("player clock", ignoreCase = true))
        assertTrue(explained.userEvidence.contains("current player", ignoreCase = true))
        assertTrue(explained.userWhy.contains("inside your promise") || explained.userWhy.contains("allowed"))
        assertFalse(userCopyContainsPackage(explained))
    }

    @Test
    fun shortsBlock_explainsShortFormEvidence() {
        val explained = CommitmentTransparency.explain(
            LastDecisionSnapshot(
                promiseText = "No Shorts for 2 hours",
                decision = "BLOCK",
                reason = "Blocked YouTube Shorts",
                source = "Content Signals",
                packageName = "com.google.android.youtube",
                reasonCode = EnforcementReasonCodes.CONTENT_SIGNAL_BLOCK,
                surface = "SHORT_FORM_PLAYER",
                activity = "ACTIVE_SHORT_PLAYER",
                durationSource = "CURRENT_PLAYER",
                matchedSignals = listOf("youtube_shorts", "short_form_player"),
                updatedMillis = now - 5_000L,
                screenTextPreview = "Shorts 00:12 / 00:34 com.google.android.youtube",
                nowMillis = now
            )
        )
        assertEquals("blocked", explained.userDecision)
        assertTrue(explained.userEvidence.contains("Short", ignoreCase = true))
        assertFalse(userCopyContainsPackage(explained))
        assertEquals("com.google.android.youtube", explained.engineering.packageName)
    }

    @Test
    fun youtubeBlankTreeBlock_explainsUnreadableClockAndStrictPromise() {
        val explained = CommitmentTransparency.explain(
            LastDecisionSnapshot(
                promiseText = "Only YouTube videos longer than 30 minutes",
                decision = "BLOCK",
                reason = SessionEnforcementCopy.YOUTUBE_CLOCK_UNREADABLE_BLOCK,
                source = "BlankVideoTree",
                packageName = "com.google.android.youtube",
                reasonCode = EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE,
                surface = "ACTIVE_VIDEO_PLAYER",
                activity = "ACTIVE_PLAYER",
                durationSource = "NONE",
                matchedSignals = listOf(EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE),
                updatedMillis = now - 2_000L,
                nowMillis = now
            )
        )
        assertEquals("blocked", explained.userDecision)
        assertTrue(explained.userWhy.contains("hid the video length") || explained.userWhy.contains("blocked"))
        assertTrue(
            explained.userEvidence.contains("clock", ignoreCase = true) ||
                explained.userEvidence.contains("length", ignoreCase = true)
        )
        assertTrue(explained.userEvidence.contains("strict") || explained.userWhy.contains("longer"))
        assertEquals("Deterministic policy", explained.engineering.sourceKind)
        assertFalse(userCopyContainsPackage(explained))
    }

    @Test
    fun copiedReport_isSanitizedAndOmitsScreenTextByDefault() {
        val private = "Hey Ankit OTP482913SECRET and privatebankpin9999 " +
            "please send money to my account tonight"
        val snapshot = chromeLongAllow().copy(
            reason = "Allowed lecture on com.android.chrome",
            screenTextPreview = private
        )
        val report = CommitmentTransparency.debugReport(snapshot, includeScreenPreview = false)
        assertTrue(report.contains("SANITIZED"))
        assertTrue(report.contains("not included (private)"))
        assertFalse(report.contains("OTP482913SECRET"))
        assertFalse(report.contains("privatebankpin9999"))
        assertFalse(report.contains(private))
        assertTrue(report.contains("com.android.chrome"))
        assertTrue(report.contains(EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT))
        assertTrue(report.contains("PolicyEngine"))
    }

    @Test
    fun copiedReport_withPreview_truncatesAndLabels_noFullLeak() {
        val private = "SECRETSCREENBLOB-" + "leakword ".repeat(40)
        val snapshot = chromeLongAllow().copy(screenTextPreview = private)
        val report = CommitmentTransparency.debugReport(snapshot, includeScreenPreview = true)
        assertTrue(report.contains("SCREEN TEXT PREVIEW (truncated"))
        assertTrue(report.contains("may be private"))
        assertFalse(report.contains(private))
        val previewLine = report.lineSequence()
            .dropWhile { !it.startsWith("SCREEN TEXT PREVIEW") }
            .drop(1)
            .first()
        assertTrue(previewLine.length <= CommitmentTransparency.SCREEN_PREVIEW_COPY_LIMIT)
    }

    @Test
    fun aiCounsel_isLabeledDistinctFromPolicyLaw() {
        val explained = CommitmentTransparency.explain(
            LastDecisionSnapshot(
                promiseText = "Study only",
                decision = "WARN",
                reason = "Model thinks this is entertainment",
                source = "Backend AI",
                packageName = "com.google.android.youtube",
                reasonCode = EnforcementReasonCodes.AI_WARN,
                surface = "ACTIVE_VIDEO_PLAYER",
                activity = "ACTIVE_PLAYER",
                durationSource = "NONE",
                matchedSignals = listOf(EnforcementReasonCodes.AI_WARN),
                updatedMillis = now - 1_000L,
                nowMillis = now
            )
        )
        assertEquals("AI counsel", explained.engineering.sourceKind)
        assertTrue(explained.authorityLine.contains("counsel"))
        assertTrue(explained.authorityLine.contains("PolicyEngine"))
        assertTrue(explained.userWhy.contains("counsel") || explained.userWhy.contains("law"))
        assertFalse(userCopyContainsPackage(explained))
    }

    @Test
    fun inferReasonCode_fromMatchedSignals() {
        val code = CommitmentTransparency.inferReasonCode(
            explicit = null,
            signals = listOf("youtube_shorts", EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED)
        )
        assertEquals(EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED, code)
    }

    private fun chromeLongAllow(): LastDecisionSnapshot = LastDecisionSnapshot(
        promiseText = "Only videos longer than 30 minutes in Chrome",
        decision = "ALLOW",
        reason = "Video length is within the promise limit",
        source = "Duration Rule",
        packageName = "com.android.chrome",
        reasonCode = EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT,
        surface = "LONG_FORM_PLAYER",
        activity = "ACTIVE_LONG_PLAYER",
        durationSource = "CURRENT_PLAYER",
        matchedSignals = listOf("media_length_local_allow", "duration_source=current_player"),
        updatedMillis = now - 8_000L,
        screenTextPreview = "31:33 time duration Lecture OS",
        nowMillis = now
    )

    private fun userCopyContainsPackage(explained: LastDecisionExplanation): Boolean {
        val blob = listOf(
            explained.userPromise,
            explained.userDecision,
            explained.userWhy,
            explained.userEvidence,
            explained.userNext,
            explained.authorityLine,
            explained.emptyOrStaleMessage.orEmpty()
        ).joinToString("\n")
        return CommitmentTransparency.containsPackageName(blob)
    }
}
