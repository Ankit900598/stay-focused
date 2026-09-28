package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.SessionStatus

/**
 * A blocked video can keep playing after it shrinks into a picture-in-picture window: the app
 * underneath is now another package, so the video app sends no foreground events and no overlay
 * can follow it. Decides which PiP windows must stay paused.
 *
 * Only a PiP that continues a block is paused. Calls, meetings and other safe apps are never
 * touched, and an allowed screen in the same app (a lecture, YouTube Home) ends the guard, so an
 * allowed video can still play in PiP.
 */
object PictureInPictureEscapeLaw {

    /** A block this old no longer guards a PiP that appears later; refreshed while a guarded PiP stays open. */
    const val GUARD_WINDOW_MILLIS = 10 * 60_000L

    data class RecentBlock(val packageName: String, val atMillis: Long)

    fun mustPause(
        pipPackage: String,
        sessionStatus: SessionStatus?,
        isSafePackage: Boolean,
        goal: String,
        enforcementScopePackages: Collection<String>,
        contentBrands: Collection<String>,
        recentBlock: RecentBlock?,
        nowMillis: Long
    ): Boolean = mustPause(
        pipPackage = pipPackage,
        sessionActiveOrLocked = sessionStatus == SessionStatus.ACTIVE || sessionStatus == SessionStatus.LOCKED,
        sessionLocked = sessionStatus == SessionStatus.LOCKED,
        isSafePackage = isSafePackage,
        mayUseWhileLocked = sessionStatus != SessionStatus.LOCKED ||
            SessionLockLaw.mayUseAppWhileLocked(
                packageName = pipPackage,
                goal = goal,
                enforcementScopePackages = enforcementScopePackages,
                contentBrands = contentBrands
            ),
        recentBlock = recentBlock,
        nowMillis = nowMillis
    )

    fun mustPause(
        pipPackage: String,
        sessionActiveOrLocked: Boolean,
        sessionLocked: Boolean,
        isSafePackage: Boolean,
        mayUseWhileLocked: Boolean,
        recentBlock: RecentBlock?,
        nowMillis: Long
    ): Boolean = when {
        !sessionActiveOrLocked -> false
        isSafePackage -> false
        sessionLocked && !mayUseWhileLocked -> true
        recentBlock == null || recentBlock.packageName != pipPackage -> false
        else -> nowMillis - recentBlock.atMillis in 0 until GUARD_WINDOW_MILLIS
    }

    /** An allowed (or allowed-with-warning) foreground screen in the blocked app ends its guard. */
    fun afterForegroundVerdict(
        recentBlock: RecentBlock?,
        packageName: String,
        verdict: LawVerdict
    ): RecentBlock? =
        if (recentBlock?.packageName == packageName &&
            (verdict == LawVerdict.ALLOW || verdict == LawVerdict.WARN)
        ) {
            null
        } else {
            recentBlock
        }
}
