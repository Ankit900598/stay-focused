package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.enforcement.PictureInPictureContainmentLaw.Attribution
import com.phonecodex.app.domain.enforcement.PictureInPictureContainmentLaw.Step
import com.phonecodex.app.domain.enforcement.PictureInPictureContainmentLaw.Unavailable
import com.phonecodex.app.domain.enforcement.PictureInPictureEscapeLaw.RecentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PictureInPictureContainmentLawTest {

    private val law = PictureInPictureContainmentLaw
    private val video = "com.example.videoapp"
    private val t0 = 1_000_000L

    private fun next(episode: PictureInPictureContainmentLaw.Episode, attribution: Attribution, call: Boolean = false, now: Long = t0) =
        law.next(episode, attribution, callOrMeetingAudio = call, nowMillis = now)

    // --- attribution ---

    @Test
    fun noPackageSource_isUnknown_whateverTheDeviceAudioDoes() {
        assertEquals(Attribution.UNKNOWN, law.attributionFrom(guardedPlayerPlaying = null))
        assertEquals(Attribution.GUARDED_PACKAGE_PLAYING, law.attributionFrom(guardedPlayerPlaying = true))
        assertEquals(Attribution.GUARDED_PACKAGE_NOT_PLAYING, law.attributionFrom(guardedPlayerPlaying = false))
    }

    @Test
    fun blockedPip_withAttributedPlayback_getsOneRequest() {
        assertEquals(Step.RequestControl, next(law.start(video), Attribution.GUARDED_PACKAGE_PLAYING))
    }

    @Test
    fun blockedPip_withUnknownPlayback_isReportedOnce_andNeverControlled() {
        var episode = law.start(video)
        val step = next(episode, Attribution.UNKNOWN)
        assertEquals(Step.Report(Unavailable.PLAYBACK_SOURCE_UNKNOWN), step)
        episode = law.afterReport(episode, Unavailable.PLAYBACK_SOURCE_UNKNOWN)
        repeat(20) { i -> assertEquals(Step.None, next(episode, Attribution.UNKNOWN, now = t0 + i * 2_000L)) }
        assertEquals(0, episode.requests)
        assertFalse(episode.playerStopObserved)
    }

    @Test
    fun allowedMusicOrPodcastPlaying_whenBlockedAppOpens_isNotInterrupted() {
        // Device audio is active because of another app; nothing attributes it to the blocked package.
        val step = next(law.start(video), law.attributionFrom(guardedPlayerPlaying = null))
        assertEquals(Step.Report(Unavailable.PLAYBACK_SOURCE_UNKNOWN), step)
    }

    @Test
    fun mutedPipVideo_deviceSilenceIsNotAStop() {
        val requested = law.afterRequest(law.start(video), granted = true, nowMillis = t0)
        val verified = law.afterVerify(requested, law.attributionFrom(guardedPlayerPlaying = null))
        assertFalse(verified.playerStopObserved)
        // An attributed source still sees a muted video as playing.
        assertEquals(Step.RequestControl, next(verified, Attribution.GUARDED_PACKAGE_PLAYING, now = t0 + 2_000L))
    }

    @Test
    fun onlyTheGuardedPlayerReportingNotPlaying_marksTheStopObserved() {
        val requested = law.afterRequest(law.start(video), granted = true, nowMillis = t0)
        assertFalse(requested.playerStopObserved)
        assertFalse(law.afterVerify(requested, Attribution.GUARDED_PACKAGE_PLAYING).playerStopObserved)
        assertFalse(law.afterVerify(requested, Attribution.UNKNOWN).playerStopObserved)
        assertTrue(law.afterVerify(requested, Attribution.GUARDED_PACKAGE_NOT_PLAYING).playerStopObserved)
    }

    // --- bounds, denial, timing ---

    @Test
    fun deniedControl_isUnavailable_neverObservedStopped_andNotRetried() {
        val denied = law.afterRequest(law.start(video), granted = false, nowMillis = t0)
        assertEquals(Unavailable.CONTROL_DENIED, denied.unavailable)
        assertFalse(denied.playerStopObserved)
        assertFalse(denied.awaitingVerify)
        assertEquals(Step.None, next(denied, Attribution.GUARDED_PACKAGE_PLAYING, now = t0 + 10_000L))
    }

    @Test
    fun requestAndVerification_timing() {
        val requested = law.afterRequest(law.start(video), granted = true, nowMillis = t0)
        assertTrue(requested.awaitingVerify)
        assertEquals(Step.None, next(requested, Attribution.GUARDED_PACKAGE_PLAYING, now = t0))
        assertEquals(Step.None, next(requested, Attribution.GUARDED_PACKAGE_PLAYING, now = t0 + law.VERIFY_DELAY_MILLIS - 1))
        assertEquals(Step.Verify, next(requested, Attribution.GUARDED_PACKAGE_PLAYING, now = t0 + law.VERIFY_DELAY_MILLIS))
        val verified = law.afterVerify(requested, Attribution.GUARDED_PACKAGE_PLAYING)
        assertNull(verified.requestedAtMillis)
    }

    @Test
    fun retriesStopAtTheBound_withOneReport_thenSilence() {
        var episode = law.start(video)
        var now = t0
        repeat(law.MAX_CONTROL_REQUESTS_PER_EPISODE) {
            assertEquals(Step.RequestControl, next(episode, Attribution.GUARDED_PACKAGE_PLAYING, now = now))
            episode = law.afterRequest(episode, granted = true, nowMillis = now)
            now += law.VERIFY_DELAY_MILLIS
            assertEquals(Step.Verify, next(episode, Attribution.GUARDED_PACKAGE_PLAYING, now = now))
            episode = law.afterVerify(episode, Attribution.GUARDED_PACKAGE_PLAYING)
        }
        assertEquals(Step.Report(Unavailable.STILL_PLAYING_AFTER_BOUND), next(episode, Attribution.GUARDED_PACKAGE_PLAYING, now = now))
        episode = law.afterReport(episode, Unavailable.STILL_PLAYING_AFTER_BOUND)
        repeat(10) { assertEquals(Step.None, next(episode, Attribution.GUARDED_PACKAGE_PLAYING, now = now + it * 2_000L)) }
        assertEquals(law.MAX_CONTROL_REQUESTS_PER_EPISODE, episode.requests)
    }

    // --- things that must stay untouched ---

    @Test
    fun callOrMeetingAudio_isNeverControlled_andReportedOnce() {
        val start = law.start(video)
        assertEquals(Step.Report(Unavailable.CALL_OR_MEETING_AUDIO), next(start, Attribution.GUARDED_PACKAGE_PLAYING, call = true))
        val reported = law.afterReport(start, Unavailable.CALL_OR_MEETING_AUDIO)
        assertEquals(Step.None, next(reported, Attribution.GUARDED_PACKAGE_PLAYING, call = true))
        assertEquals(0, reported.requests)
    }

    @Test
    fun attributedNotPlaying_meansNoRequest() {
        assertEquals(Step.None, next(law.start(video), Attribution.GUARDED_PACKAGE_NOT_PLAYING))
    }

    @Test
    fun guardExit_nextPipStartsFresh() {
        var episode = law.afterReport(law.afterRequest(law.start(video), granted = false, nowMillis = t0), Unavailable.CONTROL_DENIED)
        assertTrue(episode.exhausted)
        episode = law.start(video)
        assertEquals(0, episode.requests)
        assertNull(episode.unavailable)
        assertFalse(episode.awaitingVerify)
    }

    @Test
    fun noSessionAllowedLectureSafeAppOrOtherApp_neverReachContainment() {
        val now = 1_000_000L
        val block = RecentBlock(video, now - 1_000)
        fun guarded(pkg: String, active: Boolean, safe: Boolean, recent: RecentBlock?) =
            PictureInPictureEscapeLaw.mustPause(pkg, active, false, safe, true, recent, now)
        assertFalse("no session", guarded(video, active = false, safe = false, recent = block))
        assertFalse("call/meeting app", guarded("com.google.android.apps.meetings", active = true, safe = true, recent = block))
        assertFalse("unrelated app", guarded("com.whatsapp", active = true, safe = false, recent = block))
        val afterLecture = PictureInPictureEscapeLaw.afterForegroundVerdict(block, video, LawVerdict.ALLOW)
        assertFalse("allowed lecture ended the guard", guarded(video, active = true, safe = false, recent = afterLecture))
        assertTrue("blocked video in PiP", guarded(video, active = true, safe = false, recent = block))
    }

    // --- log contract ---

    private fun source(relative: String): String = listOf(File(relative), File("app/$relative"))
        .first { it.exists() }.readText()

    private val service get() = source("src/main/java/com/phonecodex/app/accessibility/PhoneCodexAccessibilityService.kt")
    private val lawSource get() = source("src/main/java/com/phonecodex/app/domain/enforcement/PictureInPictureContainmentLaw.kt")

    @Test
    fun eventNames_neverClaimMediaStoppedOrPaused() {
        PictureInPictureContainmentLaw.Event.values().forEach { event ->
            assertFalse(event.name, Regex("STOP_CONFIRMED|STOPPED|PAUSED").containsMatchIn(event.name))
        }
    }

    @Test
    fun service_neverSeizesDeviceAudio_orSendsMediaKeys() {
        val s = service
        listOf(
            "requestAudioFocus", "AUDIOFOCUS_GAIN", "AudioFocusRequest", "dispatchMediaKeyEvent",
            "KEYCODE_MEDIA", "Media paused", "MEDIA_STOP_CONFIRMED", "force-stop", "killBackgroundProcesses"
        ).forEach { assertFalse(it, s.contains(it)) }
    }

    @Test
    fun deviceWideAudio_isOnlyLoggedAsUnattributedContext() {
        val lines = service.lines().filter { "isMusicActive" in it }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.joinToString("\n"), lines.all { "device_wide_audio_active" in it })
        assertTrue(service.contains("(not_attributed_to_pkg)"))
    }

    @Test
    fun service_hasNoAttributedSource_soItNeverReportsAGuardedPlayerStop() {
        val s = service
        val calls = Regex("""attributionFrom\(([^)]*)\)""").findAll(s).map { it.groupValues[1].trim() }.toList()
        assertTrue(calls.isNotEmpty())
        assertTrue(calls.toString(), calls.all { it == "guardedPlayerPlaying = null" })
        assertFalse(s.contains("GUARDED_PLAYER_REPORTED_NOT_PLAYING"))
    }

    @Test
    fun law_hasNoAppSpecialCase() {
        assertFalse(lawSource.contains("youtube", ignoreCase = true))
    }
}
