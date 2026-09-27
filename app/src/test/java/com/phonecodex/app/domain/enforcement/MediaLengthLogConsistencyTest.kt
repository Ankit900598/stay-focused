package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.enforcement.trace.PhoneSurfaceTraceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone P0 2026-09-25: decision logs contradicted themselves
 * (`durationSource=none` header with `duration_source=current_player` evidence,
 * and `media_length_over_limit` on under-minimum blocks). Logs must agree with
 * the decision and MEDIA_BLOCK_* must imply a current-player clock.
 */
class MediaLengthLogConsistencyTest {

    private val min30Goal = "do not allow videos shorter than 30 minutes for next 1 hour"

    private fun decide(pkg: String, screen: String, min: Int?, max: Int?, goal: String = min30Goal) =
        MediaLengthEnforcement.decideDetailed(
            packageName = pkg,
            goal = goal,
            screenText = screen,
            structuredMaxBlockMinutes = max,
            structuredMinBlockMinutes = min
        )

    /** Mirrors how the Accessibility service formats the BLOCK log line. */
    private fun blockLogLine(pkg: String, screen: String, detail: MediaLengthDecisionDetail): String {
        val context = EnforcementContext.from(pkg, screen)
        val label = context.durationSource.name.lowercase()
        return EnforcementDecisionLog.format(
            packageName = pkg,
            surface = SurfaceDetector.detect(pkg, screen).surface.name,
            rule = "Duration Rule",
            decision = "BLOCK",
            reasonCode = detail.blockReasonCode!!,
            reason = "Video length conflicts with the promise",
            evidence = detail.blockEvidence(label),
            durationSource = label
        )
    }

    private fun headerAndEvidenceAgree(line: String) {
        val header = Regex("durationSource=(\\w+)").find(line)!!.groupValues[1]
        val evidence = Regex("duration_source=(\\w+)").find(line)!!.groupValues[1]
        assertEquals("header vs evidence in: $line", header, evidence)
    }

    @Test
    fun realShortWithoutClock_isShortFormCode_notMediaLengthCode() {
        val screen = "Home Shorts Subscriptions Remix this short Pause like comment"
        val detail = decide(VideoPlatformRegistry.YOUTUBE, screen, min = 30, max = null)
        assertEquals(MediaLengthLocalDecision.BLOCK, detail.decision)
        assertEquals(MediaLengthBlockBasis.SHORT_FORM_SURFACE_UNDER_MIN, detail.blockBasis)
        assertEquals(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN, detail.blockReasonCode)

        val line = blockLogLine(VideoPlatformRegistry.YOUTUBE, screen, detail)
        headerAndEvidenceAgree(line)
        assertTrue(line, line.contains("durationSource=none"))
        assertFalse(line, line.contains("media_length_over_limit"))
        assertFalse(line, line.contains("current_player"))
        assertFalse(line, line.contains(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN))
    }

    @Test
    fun shortWatchPageWithClock_underMin_saysUnderMinAndPrintsParsedLength() {
        val screen = "youtube.com/watch?v=dQw4w9WgXcQ Rick Astley Never Gonna Give You Up " +
            "0:10 / 3:33 Pause Hide player controls"
        val detail = decide(VideoPlatformRegistry.CHROME, screen, min = 30, max = null)
        assertEquals(MediaLengthLocalDecision.BLOCK, detail.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN, detail.blockReasonCode)

        val line = blockLogLine(VideoPlatformRegistry.CHROME, screen, detail)
        headerAndEvidenceAgree(line)
        assertTrue(line, line.contains("durationSource=current_player"))
        assertTrue(line, line.contains("media_length_under_min"))
        assertTrue(line, line.contains("player_seconds=213"))
        assertFalse(line, line.contains("media_length_over"))
    }

    @Test
    fun longVideoOverMax_saysOverMax() {
        val screen = "youtube.com/watch?v=rfscVS0vtbw Python for Beginners Full Course " +
            "1:02:03 / 4:26:52 Pause Hide player controls"
        val detail = decide(
            VideoPlatformRegistry.CHROME,
            screen,
            min = null,
            max = 30,
            goal = "block videos longer than 30 minutes"
        )
        assertEquals(MediaLengthLocalDecision.BLOCK, detail.decision)
        assertEquals(EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX, detail.blockReasonCode)
        val line = blockLogLine(VideoPlatformRegistry.CHROME, screen, detail)
        headerAndEvidenceAgree(line)
        assertTrue(line, line.contains("media_length_over_max"))
    }

    @Test
    fun longVideoUnderMin30Promise_isAllowed() {
        val screen = "youtube.com/watch?v=rfscVS0vtbw Python for Beginners Full Course " +
            "1:02:03 / 4:26:52 Pause Hide player controls"
        val detail = decide(VideoPlatformRegistry.CHROME, screen, min = 30, max = null)
        assertNotEquals(MediaLengthLocalDecision.BLOCK, detail.decision)
        assertNull(detail.blockReasonCode)
    }

    @Test
    fun allowEvidence_printsPlayerSecondsLikeBlock() {
        val screen = "youtube.com/watch?v=rfscVS0vtbw Python for Beginners Full Course " +
            "1:02:03 / 4:26:52 Pause Hide player controls"
        val detail = decide(VideoPlatformRegistry.CHROME, screen, min = 30, max = null)
        assertEquals(MediaLengthLocalDecision.ALLOW, detail.decision)
        val evidence = detail.allowEvidence("current_player")
        assertTrue(evidence.toString(), evidence.contains("duration_source=current_player"))
        assertTrue(evidence.toString(), evidence.contains("player_seconds=16012"))
        assertTrue(detail.blockEvidence().isEmpty())
    }

    @Test
    fun watchPageWithoutPlayerClock_neverMediaLengthBlocks() {
        val screen = "youtube.com/watch?v=dQw4w9WgXcQ Rick Astley Never Gonna Give You Up " +
            "Like Share Subscribe Comments Up next 5:32"
        val detail = decide(VideoPlatformRegistry.CHROME, screen, min = 30, max = null)
        assertNotEquals(MediaLengthLocalDecision.BLOCK, detail.decision)
        assertNull(detail.blockReasonCode)
    }

    @Test
    fun everyCatalogTrace_mediaBlockCodesRequireCurrentPlayerClock() {
        val clockCodes = setOf(
            EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
            EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
        )
        for (trace in PhoneSurfaceTraceCatalog.all()) {
            val detail = MediaLengthEnforcement.decideDetailed(
                packageName = trace.packageName,
                goal = trace.goal,
                screenText = trace.screenText,
                structuredMaxBlockMinutes = trace.settings.maxVideoLengthBlockMinutes,
                structuredMinBlockMinutes = trace.settings.minVideoLengthBlockMinutes
            )
            if (detail.decision != MediaLengthLocalDecision.BLOCK) {
                assertNull("${trace.id} non-block has basis", detail.blockBasis)
                continue
            }
            val code = detail.blockReasonCode
            if (code in clockCodes) {
                val context = EnforcementContext.from(trace.packageName, trace.screenText)
                assertEquals(
                    "${trace.id} $code without current-player clock",
                    EnforcementDurationSource.CURRENT_PLAYER,
                    context.durationSource
                )
            }
            val evidence = detail.blockEvidence().joinToString("|")
            assertFalse("${trace.id} legacy evidence", evidence.contains("media_length_over_limit"))
        }
    }
}
