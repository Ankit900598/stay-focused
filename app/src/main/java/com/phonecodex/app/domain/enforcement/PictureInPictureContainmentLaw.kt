package com.phonecodex.app.domain.enforcement

/**
 * What to do about media still playing in a PiP window that [PictureInPictureEscapeLaw] says must not play.
 *
 * Android gives this app no way to stop one specific package's playback without new access
 * (a MediaController needs notification-listener access; dismissing PiP needs gestures). The only
 * control is requesting audio focus, which makes the system fade out whichever media app holds
 * focus. Success is observable only as "no media is playing on the device", never per package, so
 * anything else stays unresolved. Requests are bounded per PiP episode and never made during calls.
 */
object PictureInPictureContainmentLaw {

    const val MAX_CONTROL_REQUESTS_PER_EPISODE = 3

    /** Time for the system fade-out to finish before playback is re-observed. */
    const val VERIFY_DELAY_MILLIS = 1_500L

    enum class Event { PIP_GUARD_ENTERED, MEDIA_CONTROL_REQUESTED, MEDIA_STOP_CONFIRMED, CONTROL_UNAVAILABLE, GUARD_EXITED }

    enum class Unavailable { CALL_OR_MEETING_AUDIO, FOCUS_DENIED, STILL_PLAYING_AFTER_BOUND }

    data class Episode(
        val packageName: String,
        val requests: Int = 0,
        val awaitingVerify: Boolean = false,
        val stopConfirmed: Boolean = false,
        val unavailable: Unavailable? = null
    ) {
        val exhausted: Boolean get() = unavailable == Unavailable.STILL_PLAYING_AFTER_BOUND ||
            unavailable == Unavailable.FOCUS_DENIED
    }

    sealed interface Step {
        object None : Step
        object RequestControl : Step
        data class Report(val reason: Unavailable) : Step
    }

    fun start(packageName: String): Episode = Episode(packageName)

    fun next(episode: Episode, mediaActive: Boolean, callOrMeetingAudio: Boolean): Step = when {
        episode.awaitingVerify -> Step.None
        callOrMeetingAudio -> if (episode.unavailable == Unavailable.CALL_OR_MEETING_AUDIO) Step.None
            else Step.Report(Unavailable.CALL_OR_MEETING_AUDIO)
        episode.exhausted -> Step.None
        !mediaActive -> Step.None
        episode.requests >= MAX_CONTROL_REQUESTS_PER_EPISODE -> Step.Report(Unavailable.STILL_PLAYING_AFTER_BOUND)
        else -> Step.RequestControl
    }

    fun afterReport(episode: Episode, reason: Unavailable): Episode = episode.copy(unavailable = reason)

    fun afterRequest(episode: Episode, granted: Boolean): Episode =
        if (granted) {
            episode.copy(requests = episode.requests + 1, awaitingVerify = true, stopConfirmed = false)
        } else {
            episode.copy(requests = episode.requests + 1, unavailable = Unavailable.FOCUS_DENIED)
        }

    /** Stop is confirmed only by observing that no media plays at all after the request. */
    fun afterVerify(episode: Episode, mediaActive: Boolean): Episode =
        episode.copy(awaitingVerify = false, stopConfirmed = !mediaActive)
}
