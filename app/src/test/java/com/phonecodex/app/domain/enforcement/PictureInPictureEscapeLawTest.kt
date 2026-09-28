package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.enforcement.PictureInPictureEscapeLaw.GUARD_WINDOW_MILLIS
import com.phonecodex.app.domain.enforcement.PictureInPictureEscapeLaw.RecentBlock
import com.phonecodex.app.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone 2026-09-28: a quota-exceeded Short was blocked, the session locked, and Home turned
 * YouTube into a PiP window that kept the Short on screen over Chrome.
 */
class PictureInPictureEscapeLawTest {

    private val yt = VideoPlatformRegistry.YOUTUBE
    private val now = 1_000_000_000L

    private fun mustPause(
        pip: String = yt,
        active: Boolean = true,
        locked: Boolean = false,
        safe: Boolean = false,
        mayUseWhileLocked: Boolean = true,
        recent: RecentBlock? = RecentBlock(yt, now - 5_000),
        at: Long = now
    ) = PictureInPictureEscapeLaw.mustPause(pip, active, locked, safe, mayUseWhileLocked, recent, at)

    @Test
    fun blockedVideo_thatShrinksIntoPip_staysPaused() {
        assertTrue(mustPause())
    }

    @Test
    fun lockedSession_pausesPipOfAnyAppItWouldLock_evenWithoutARecentBlock() {
        assertTrue(mustPause(locked = true, mayUseWhileLocked = false, recent = null))
        assertFalse(mustPause(locked = true, mayUseWhileLocked = true, recent = null))
    }

    @Test
    fun noCommitment_neverPauses() {
        assertFalse(mustPause(active = false))
        assertFalse(mustPause(active = false, locked = true, mayUseWhileLocked = false))
    }

    @Test
    fun safeApps_likeCallsAndMeetings_neverPause() {
        assertFalse(mustPause(pip = "com.google.android.apps.meetings", safe = true, recent = RecentBlock("com.google.android.apps.meetings", now)))
        assertFalse(mustPause(safe = true, locked = true, mayUseWhileLocked = false))
    }

    @Test
    fun pipOfAnotherApp_isNotGuardedByThisBlock() {
        assertFalse(mustPause(pip = VideoPlatformRegistry.CHROME))
    }

    @Test
    fun guardExpires_andIgnoresClockSkew() {
        assertTrue(mustPause(recent = RecentBlock(yt, now - GUARD_WINDOW_MILLIS + 1)))
        assertFalse(mustPause(recent = RecentBlock(yt, now - GUARD_WINDOW_MILLIS)))
        assertFalse(mustPause(recent = RecentBlock(yt, now + 60_000)))
    }

    @Test
    fun allowedScreenInTheSameApp_endsTheGuard_soAnAllowedVideoCanPlayInPip() {
        val recent = RecentBlock(yt, now)
        assertNull(PictureInPictureEscapeLaw.afterForegroundVerdict(recent, yt, LawVerdict.ALLOW))
        assertNull("a counted Short at the limit is WARN", PictureInPictureEscapeLaw.afterForegroundVerdict(recent, yt, LawVerdict.WARN))
        assertFalse(mustPause(recent = PictureInPictureEscapeLaw.afterForegroundVerdict(recent, yt, LawVerdict.ALLOW)))
    }

    @Test
    fun sessionEntryPoint_usesTheSessionLockLaw() {
        fun pause(pkg: String, status: SessionStatus?, goal: String, recent: RecentBlock?) =
            PictureInPictureEscapeLaw.mustPause(pkg, status, false, goal, listOf(yt), emptyList(), recent, now)

        assertTrue("locked, no goal: video apps stay locked", pause(yt, SessionStatus.LOCKED, "", null))
        assertFalse("locked: a notes app stays usable", pause("com.google.android.keep", SessionStatus.LOCKED, "", null))
        assertTrue("the escape path is caught by the recent block", pause(yt, SessionStatus.LOCKED, "only 2 YouTube Shorts today", RecentBlock(yt, now)))
        assertTrue(pause(yt, SessionStatus.ACTIVE, "only 2 YouTube Shorts today", RecentBlock(yt, now)))
        assertFalse("active, not recently blocked: an allowed video may play in PiP", pause(yt, SessionStatus.ACTIVE, "only 2 YouTube Shorts today", null))
        assertFalse(pause(yt, null, "", RecentBlock(yt, now)))
    }

    @Test
    fun blockWaitOrOtherApp_keepTheGuard() {
        val recent = RecentBlock(yt, now)
        for (v in listOf(LawVerdict.BLOCK, LawVerdict.WAIT, LawVerdict.IGNORE, LawVerdict.CONTINUE)) {
            assertEquals(v.name, recent, PictureInPictureEscapeLaw.afterForegroundVerdict(recent, yt, v))
        }
        assertEquals(recent, PictureInPictureEscapeLaw.afterForegroundVerdict(recent, VideoPlatformRegistry.CHROME, LawVerdict.ALLOW))
    }
}
