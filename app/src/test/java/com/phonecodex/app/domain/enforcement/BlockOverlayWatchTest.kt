package com.phonecodex.app.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockOverlayWatchTest {

    private val chrome = "com.android.chrome"
    private val systemUi = "com.android.systemui"
    private val miuiHome = "com.miui.home"

    private fun at(h: Int, m: Int, s: Int, ms: Int): Long =
        ((h * 3600L + m * 60L + s) * 1000L) + ms

    private fun chromeUrlBarPage(url: String) = OverlayLifecycleGate.pageIdentity(url, "")

    @Test
    fun report5b_homeOverOverlay_clearsWithinDebounceEvenWithoutMoreEvents() {
        val shown = at(9, 59, 39, 568)
        val watch = BlockOverlayWatch(chrome, chromeUrlBarPage("m.youtube.com/watch?v=dQw4w9WgXcQ"), shown)

        // 10:00:21.322 Foreground package: com.android.chrome
        // 10:00:21.354 Skipping stale screen text: event=com.android.chrome root=com.miui.home
        val first = watch.observe(chrome, miuiHome, null, at(10, 0, 21, 354))
        assertEquals(OverlayWatchAction.AWAY_PENDING, first.action)
        assertEquals("left_to_launcher", first.reason)
        assertEquals(miuiHome, first.effectivePackage)

        // Covered Chrome emits nothing; only the watchdog ticks read the root.
        val tick1 = watch.observe(miuiHome, miuiHome, null, at(10, 0, 22, 104))
        assertEquals(OverlayWatchAction.AWAY_PENDING, tick1.action)
        val tick2 = watch.observe(miuiHome, miuiHome, null, at(10, 0, 22, 854))
        assertEquals(OverlayWatchAction.CLEAR_LEFT_TARGET, tick2.action)
        assertTrue(tick2.awayMs >= OverlayLifecycleGate.AWAY_DEBOUNCE_MS)
        assertTrue(tick2.awayMs <= 1_600L)
    }

    @Test
    fun report5b_chromeReopenedOnNewVideo_systemUiEventsWithChromeRoot_reevaluate() {
        val shown = at(9, 59, 39, 568)
        val watch = BlockOverlayWatch(chrome, chromeUrlBarPage("m.youtube.com/watch?v=dQw4w9WgXcQ"), shown)

        // 10:00:26.199 event=com.android.systemui root=com.android.chrome, Chrome now on rfscVS0vtbw
        val verdict = watch.observe(
            systemUi,
            chrome,
            chromeUrlBarPage("m.youtube.com/watch?v=rfscVS0vtbw"),
            at(10, 0, 26, 199)
        )
        assertEquals(OverlayWatchAction.REEVALUATE_PAGE_CHANGED, verdict.action)
        assertEquals("target_page_changed", verdict.reason)
        assertEquals(systemUi, verdict.eventPackage)
        assertEquals(chrome, verdict.rootPackage)
        assertEquals("video:dQw4w9WgXcQ", verdict.blockedPage)
        assertEquals("video:rfscVS0vtbw", verdict.currentPage)
    }

    @Test
    fun report5c_navigatedToExampleCom_systemUiNoise_reevaluates() {
        val shown = at(10, 10, 49, 652)
        val watch = BlockOverlayWatch(chrome, chromeUrlBarPage("m.youtube.com/watch?v=rfscVS0vtbw"), shown)

        // 10:11:15.833 event=com.android.systemui root=com.android.chrome
        val verdict = watch.observe(systemUi, chrome, chromeUrlBarPage("example.com"), at(10, 11, 15, 833))
        assertEquals(OverlayWatchAction.REEVALUATE_PAGE_CHANGED, verdict.action)
        assertEquals("url:example.com", verdict.currentPage)
        assertTrue(verdict.logLine("event").contains("event=com.android.systemui root=com.android.chrome"))
        assertTrue(verdict.logLine("event").contains("elapsedMs=26181"))
    }

    @Test
    fun systemUiNoiseOnSamePage_retains() {
        val watch = BlockOverlayWatch(chrome, "video:abc", 0L)
        val verdict = watch.observe(systemUi, chrome, "video:abc", 30_000L)
        assertEquals(OverlayWatchAction.RETAIN, verdict.action)
        assertEquals("target_same_page", verdict.reason)
    }

    @Test
    fun shadeOrOwnPackageRoot_retainsAsNoise() {
        val watch = BlockOverlayWatch(chrome, "video:abc", 0L)
        assertEquals("noise_root", watch.observe(systemUi, systemUi, null, 5_000L).reason)
        assertEquals(
            "noise_root",
            watch.observe(OverlayLifecycleGate.OWN_PACKAGE, OverlayLifecycleGate.OWN_PACKAGE, null, 6_000L).reason
        )
        assertEquals(
            OverlayWatchAction.RETAIN,
            watch.observe(OverlayLifecycleGate.OWN_PACKAGE, null, null, 7_000L).action
        )
    }

    @Test
    fun hiddenUrlBar_unknownPage_retains() {
        val watch = BlockOverlayWatch(chrome, "video:abc", 0L)
        val verdict = watch.observe(chrome, chrome, null, 60_000L)
        assertEquals(OverlayWatchAction.RETAIN, verdict.action)
        assertEquals("target_page_unknown", verdict.reason)
    }

    @Test
    fun unknownBlockedPage_neverReevaluatesOnPageAlone() {
        val watch = BlockOverlayWatch("com.google.android.youtube", null, 0L)
        val verdict = watch.observe("com.google.android.youtube", null, "video:xyz", 60_000L)
        assertEquals(OverlayWatchAction.RETAIN, verdict.action)
        assertEquals("blocked_page_unknown", verdict.reason)
    }

    @Test
    fun pageChangeInsideHold_retains() {
        val watch = BlockOverlayWatch(chrome, "video:abc", 0L)
        val verdict = watch.observe(chrome, chrome, "video:def", OverlayLifecycleGate.DECISION_HOLD_MS - 1)
        assertEquals(OverlayWatchAction.RETAIN, verdict.action)
        assertEquals("page_change_hold", verdict.reason)
    }

    @Test
    fun launcherBlipThenBackToTarget_doesNotClear() {
        val watch = BlockOverlayWatch(chrome, "video:abc", 0L)
        assertEquals(OverlayWatchAction.AWAY_PENDING, watch.observe(miuiHome, miuiHome, null, 10_000L).action)
        assertEquals(OverlayWatchAction.RETAIN, watch.observe(chrome, chrome, "video:abc", 10_400L).action)
        // Away timer restarted, so a later launcher sighting is pending again, not a clear.
        assertEquals(OverlayWatchAction.AWAY_PENDING, watch.observe(miuiHome, miuiHome, null, 11_500L).action)
    }

    @Test
    fun otherAppStaysForeground_clearsAfterDebounce() {
        val watch = BlockOverlayWatch(chrome, "video:abc", 0L)
        val dialer = "com.google.android.dialer"
        assertEquals(OverlayWatchAction.AWAY_PENDING, watch.observe(dialer, dialer, null, 10_000L).action)
        val cleared = watch.observe(dialer, dialer, null, 11_200L)
        assertEquals(OverlayWatchAction.CLEAR_LEFT_TARGET, cleared.action)
        assertEquals("left_to_other_app", cleared.reason)
    }

    @Test
    fun pageIdentity_prefersUrlBarAndNormalizes() {
        assertEquals("video:dQw4w9WgXcQ", OverlayLifecycleGate.pageIdentity("m.youtube.com/watch?v=dQw4w9WgXcQ", ""))
        assertEquals("url:example.com", OverlayLifecycleGate.pageIdentity("https://www.example.com/?q=1#top", ""))
        assertEquals("url:example.com/docs", OverlayLifecycleGate.pageIdentity("example.com/docs/", ""))
        assertEquals(
            "video:rfscVS0vtbw",
            OverlayLifecycleGate.pageIdentity(null, "Chrome m.youtube.com/watch?v=rfscVS0vtbw Ask Gemini")
        )
        assertNull(OverlayLifecycleGate.pageIdentity(null, "TAP TO UNMUTE YouTube Home"))
        assertNull(OverlayLifecycleGate.pageIdentity("Search or type web address", ""))
    }
}
