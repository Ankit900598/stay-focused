package com.phonecodex.app.domain.enforcement

/**
 * What to do about playback in a PiP window that [PictureInPictureEscapeLaw] says must not play.
 *
 * A control may act only on the guarded package's own player, and only when playback is attributed to
 * that package. Device-wide signals (`AudioManager.isMusicActive`, audio focus) cannot name a package:
 * a muted PiP video is silent while playing, and another app's allowed music makes the device "active".
 * Under the current grants nothing attributes playback, so every episode reports
 * [Unavailable.PLAYBACK_SOURCE_UNKNOWN] and nothing is interrupted. A package-attributed source (e.g. a
 * MediaController) needs new user-granted access and is a product decision, not a default.
 */
object PictureInPictureContainmentLaw {

    const val MAX_CONTROL_REQUESTS_PER_EPISODE = 3

    /** Time for the guarded player to react before its state is re-observed. */
    const val VERIFY_DELAY_MILLIS = 1_500L

    enum class Event {
        PIP_GUARD_ENTERED,
        MEDIA_CONTROL_REQUESTED,
        GUARDED_PLAYER_REPORTED_NOT_PLAYING,
        CONTROL_UNAVAILABLE,
        GUARD_EXITED
    }

    enum class Attribution { GUARDED_PACKAGE_PLAYING, GUARDED_PACKAGE_NOT_PLAYING, UNKNOWN }

    enum class Unavailable {
        CALL_OR_MEETING_AUDIO,
        PLAYBACK_SOURCE_UNKNOWN,
        NO_PACKAGE_TARGETED_CONTROL,
        CONTROL_DENIED,
        STILL_PLAYING_AFTER_BOUND
    }

    data class Episode(
        val packageName: String,
        val requests: Int = 0,
        val requestedAtMillis: Long? = null,
        val playerStopObserved: Boolean = false,
        val unavailable: Unavailable? = null
    ) {
        val awaitingVerify: Boolean get() = requestedAtMillis != null
        val exhausted: Boolean get() = unavailable == Unavailable.STILL_PLAYING_AFTER_BOUND ||
            unavailable == Unavailable.CONTROL_DENIED ||
            unavailable == Unavailable.NO_PACKAGE_TARGETED_CONTROL
    }

    sealed interface Step {
        object None : Step
        object RequestControl : Step
        object Verify : Step
        data class Report(val reason: Unavailable) : Step
    }

    /**
     * [guardedPlayerPlaying] is the guarded package's own player state, or null when no source attributes
     * playback to that package. Device-wide audio never decides attribution.
     */
    fun attributionFrom(guardedPlayerPlaying: Boolean?): Attribution = when (guardedPlayerPlaying) {
        null -> Attribution.UNKNOWN
        true -> Attribution.GUARDED_PACKAGE_PLAYING
        false -> Attribution.GUARDED_PACKAGE_NOT_PLAYING
    }

    fun start(packageName: String): Episode = Episode(packageName)

    fun next(episode: Episode, attribution: Attribution, callOrMeetingAudio: Boolean, nowMillis: Long): Step {
        val requestedAt = episode.requestedAtMillis
        if (requestedAt != null) {
            return if (nowMillis - requestedAt >= VERIFY_DELAY_MILLIS) Step.Verify else Step.None
        }
        return when {
            callOrMeetingAudio -> reportOnce(episode, Unavailable.CALL_OR_MEETING_AUDIO)
            episode.exhausted -> Step.None
            attribution == Attribution.UNKNOWN -> reportOnce(episode, Unavailable.PLAYBACK_SOURCE_UNKNOWN)
            attribution == Attribution.GUARDED_PACKAGE_NOT_PLAYING -> Step.None
            episode.requests >= MAX_CONTROL_REQUESTS_PER_EPISODE -> Step.Report(Unavailable.STILL_PLAYING_AFTER_BOUND)
            else -> Step.RequestControl
        }
    }

    private fun reportOnce(episode: Episode, reason: Unavailable): Step =
        if (episode.unavailable == reason) Step.None else Step.Report(reason)

    fun afterReport(episode: Episode, reason: Unavailable): Episode = episode.copy(unavailable = reason)

    fun afterRequest(episode: Episode, granted: Boolean, nowMillis: Long): Episode =
        if (granted) {
            episode.copy(requests = episode.requests + 1, requestedAtMillis = nowMillis, playerStopObserved = false, unavailable = null)
        } else {
            episode.copy(requests = episode.requests + 1, requestedAtMillis = null, unavailable = Unavailable.CONTROL_DENIED)
        }

    /** Only the guarded package's own player reporting "not playing" counts; unknown stays unresolved. */
    fun afterVerify(episode: Episode, attribution: Attribution): Episode = episode.copy(
        requestedAtMillis = null,
        playerStopObserved = attribution == Attribution.GUARDED_PACKAGE_NOT_PLAYING
    )
}
