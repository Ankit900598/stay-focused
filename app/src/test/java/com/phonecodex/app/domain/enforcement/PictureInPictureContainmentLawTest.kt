package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.enforcement.PictureInPictureContainmentLaw.Step
import com.phonecodex.app.domain.enforcement.PictureInPictureContainmentLaw.Unavailable
import com.phonecodex.app.domain.enforcement.PictureInPictureEscapeLaw.RecentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PictureInPictureContainmentLawTest {

    private val law = PictureInPictureContainmentLaw
    private val yt = "com.google.android.youtube"

    @Test
    fun forbiddenPip_withPlayingMedia_getsOneControlRequest_thenWaitsForVerification() {
        val start = law.start(yt)
        assertEquals(Step.RequestControl, law.next(start, mediaActive = true, callOrMeetingAudio = false))
        val requested = law.afterRequest(start, granted = true)
        assertEquals(1, requested.requests)
        assertEquals(Step.None, law.next(requested, mediaActive = true, callOrMeetingAudio = false))
    }

    @Test
    fun stopIsConfirmedOnlyWhenNoMediaPlaysAfterTheRequest() {
        val requested = law.afterRequest(law.start(yt), granted = true)
        assertFalse(requested.stopConfirmed)
        assertFalse(law.afterVerify(requested, mediaActive = true).stopConfirmed)
        assertTrue(law.afterVerify(requested, mediaActive = false).stopConfirmed)
    }

    @Test
    fun deniedRequest_isReportedUnavailable_neverAsStopped_andNotRetried() {
        val denied = law.afterRequest(law.start(yt), granted = false)
        assertEquals(Unavailable.FOCUS_DENIED, denied.unavailable)
        assertFalse(denied.stopConfirmed)
        assertFalse(denied.awaitingVerify)
        assertEquals(Step.None, law.next(denied, mediaActive = true, callOrMeetingAudio = false))
    }

    @Test
    fun retriesStopAtTheBound_withOneReport_thenSilence() {
        var episode = law.start(yt)
        repeat(PictureInPictureContainmentLaw.MAX_CONTROL_REQUESTS_PER_EPISODE) {
            assertEquals(Step.RequestControl, law.next(episode, mediaActive = true, callOrMeetingAudio = false))
            episode = law.afterVerify(law.afterRequest(episode, granted = true), mediaActive = true)
        }
        val report = law.next(episode, mediaActive = true, callOrMeetingAudio = false)
        assertEquals(Step.Report(Unavailable.STILL_PLAYING_AFTER_BOUND), report)
        episode = law.afterReport(episode, Unavailable.STILL_PLAYING_AFTER_BOUND)
        repeat(10) { assertEquals(Step.None, law.next(episode, mediaActive = true, callOrMeetingAudio = false)) }
        assertEquals(PictureInPictureContainmentLaw.MAX_CONTROL_REQUESTS_PER_EPISODE, episode.requests)
    }

    @Test
    fun callOrMeetingAudio_isNeverInterrupted_andReportedOnce() {
        val start = law.start(yt)
        val report = law.next(start, mediaActive = true, callOrMeetingAudio = true)
        assertEquals(Step.Report(Unavailable.CALL_OR_MEETING_AUDIO), report)
        val reported = law.afterReport(start, Unavailable.CALL_OR_MEETING_AUDIO)
        assertEquals(Step.None, law.next(reported, mediaActive = true, callOrMeetingAudio = true))
        assertEquals(0, reported.requests)
        assertEquals(Step.RequestControl, law.next(reported, mediaActive = true, callOrMeetingAudio = false))
    }

    @Test
    fun nothingPlaying_meansNoRequest() {
        assertEquals(Step.None, law.next(law.start(yt), mediaActive = false, callOrMeetingAudio = false))
    }

    @Test
    fun allowedLectureNoSessionSafeAppOrOtherApp_neverReachContainment() {
        val now = 1_000_000L
        val block = RecentBlock(yt, now - 1_000)
        fun guarded(pkg: String, active: Boolean, safe: Boolean, recent: RecentBlock?) =
            PictureInPictureEscapeLaw.mustPause(pkg, active, false, safe, true, recent, now)
        assertFalse("no session", guarded(yt, active = false, safe = false, recent = block))
        assertFalse("call/meeting app", guarded("com.google.android.apps.meetings", active = true, safe = true, recent = block))
        assertFalse("unrelated app", guarded("com.whatsapp", active = true, safe = false, recent = block))
        val afterLecture = PictureInPictureEscapeLaw.afterForegroundVerdict(block, yt, LawVerdict.ALLOW)
        assertFalse("allowed lecture ended the guard", guarded(yt, active = true, safe = false, recent = afterLecture))
        assertTrue("blocked Short in PiP", guarded(yt, active = true, safe = false, recent = block))
    }

    @Test
    fun service_neverSendsMediaKeys_andLogsOnlyTruthfulPipEvents() {
        val service = listOf(
            File("src/main/java/com/phonecodex/app/accessibility/PhoneCodexAccessibilityService.kt"),
            File("app/src/main/java/com/phonecodex/app/accessibility/PhoneCodexAccessibilityService.kt")
        ).first { it.exists() }.readText()
        assertFalse(service.contains("dispatchMediaKeyEvent"))
        assertFalse(service.contains("KEYCODE_MEDIA"))
        assertFalse(service.contains("Media paused"))
        assertFalse(service.contains("force-stop") || service.contains("killBackgroundProcesses"))
        val confirmLines = service.lines().filter { "Event.MEDIA_STOP_CONFIRMED" in it }
        assertTrue(confirmLines.isNotEmpty())
        assertTrue(confirmLines.all { "device_media_active=false" in it })
    }
}
