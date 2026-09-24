package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.SurfaceDetection
import com.phonecodex.app.domain.enforcement.SurfaceDetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Algebra + docs/scope-semantics-v1.md §6.3 rows 1–29.
 *
 * Each row is one call to a helper that builds a scope via presets, builds a screen
 * snapshot, and asserts an expected [ScopeMatch] shape. Naming mirrors the row id
 * in §6.3 exactly so failures point straight to a design row.
 */
class ScopeMatcherTest {

    // ---------- algebra ----------------------------------------------------

    @Test
    fun nullScopeIsUnresolved() {
        val m = ScopeMatcher.match("com.google.android.youtube", "", detection(SurfaceDetectionResult.HOME), null)
        assertTrue(m is ScopeMatch.Unresolved)
    }

    @Test
    fun unresolvedScopeReturnsUnresolvedWithReason() {
        val scope = EnforcementScope.unresolved(UnresolvedReason.HOST_AMBIGUOUS, listOf("only YouTube"))
        val m = ScopeMatcher.match("com.google.android.youtube", "", detection(SurfaceDetectionResult.HOME), scope)
        assertTrue(m is ScopeMatch.Unresolved)
        assertEquals(UnresolvedReason.HOST_AMBIGUOUS, (m as ScopeMatch.Unresolved).reason)
    }

    @Test
    fun exemptClauseWinsOverInclude() {
        val scope = build(ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeIdentity.INSTAGRAM)
        // IG thread — matches both include (surfaces=[ANY]) and exempt (DM_THREAD).
        // Exempt must win.
        val m = ScopeMatcher.match(
            "com.instagram.android",
            "Type a message",
            detection(SurfaceDetectionResult.MESSAGING_THREAD),
            scope
        )
        assertTrue("expected Exempt, got $m", m is ScopeMatch.Exempt)
    }

    @Test
    fun badVersionOnScopeIsUnresolved() {
        val exp = ScopeKindPresets.expand(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE)
        val scope = EnforcementScope(
            version = 42,
            kind = ScopeKind.APP_ONLY,
            identity = ScopeIdentity.YOUTUBE,
            include = exp.include,
            confidence = 0.9,
            evidenceSpans = listOf("YouTube app only")
        )
        val m = ScopeMatcher.match(
            "com.google.android.youtube",
            "",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertTrue(m is ScopeMatch.Unresolved)
    }

    // ---------- §6.3 rows 1–29 --------------------------------------------

    // YouTube — host discrimination

    @Test
    fun row01_scope_app_only_yt_player() {
        val scope = build(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.google.android.youtube",
            "Time elapsed 00:15 Time duration 00:59 Subscribe",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row02_scope_app_only_chrome_yt_watch_out() {
        val scope = build(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "https://www.youtube.com/watch?v=abcd Time elapsed 00:15",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun row03_scope_app_only_newpipe_out() {
        val scope = build(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "org.schabi.newpipe",
            "Time elapsed 00:15 Time duration 00:59",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        // PINNED excludes NewPipe (only com.google.android.youtube is in package list),
        // AND NewPipe is NATIVE_APP so the browser-site fallback also does not apply.
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun row04_scope_browser_only_chrome_yt_in() {
        val scope = build(ScopeKind.BROWSER_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "https://www.youtube.com/watch?v=abcd Time elapsed 01:00",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row05_scope_browser_only_yt_app_out() {
        val scope = build(ScopeKind.BROWSER_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.google.android.youtube",
            "Time elapsed 00:15 Time duration 00:59",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        // Native app is not host=BROWSER -> OutOfScope. (Design row 5.)
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun row06_scope_browser_only_firefox_yt_in() {
        // The killer row: today's chrome_browser rematerialize fails this. Ours must pass.
        val scope = build(ScopeKind.BROWSER_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "org.mozilla.firefox",
            "https://www.youtube.com/watch?v=abcd",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row07_scope_any_host_yt_app_in() {
        val scope = build(ScopeKind.CONTENT_ANY_HOST, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.google.android.youtube",
            "Time elapsed 00:15",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row08_scope_any_host_chrome_yt_in() {
        val scope = build(ScopeKind.CONTENT_ANY_HOST, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "https://www.youtube.com/watch",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row09_scope_any_host_newpipe_in() {
        val scope = build(ScopeKind.CONTENT_ANY_HOST, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "org.schabi.newpipe",
            "Time elapsed 00:15",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row10_scope_any_host_vanced_in() {
        val scope = build(ScopeKind.CONTENT_ANY_HOST, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.vanced.android.youtube",
            "Time elapsed 00:15",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row11_scope_yt_home_passive_no_enforce() {
        // Scope is InScope; verdict is a Phase-2 gate concern. Scope must not carry the verdict.
        val scope = build(ScopeKind.CONTENT_ANY_HOST, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.google.android.youtube",
            "Home Shorts shelf",
            detection(SurfaceDetectionResult.HOME),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row12_scope_yt_shorts_url_in_chrome() {
        val scope = build(ScopeKind.SHORT_FORM_ANY_HOST, ScopeIdentity.SHORT_FORM_ANY)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "https://www.youtube.com/shorts/abc",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    // Chrome / browser / sites

    @Test
    fun row13_scope_chrome_except_adult_serp_out() {
        val scope = build(ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS, ScopeIdentity.ANY)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "Google Search: dsa tutorial linked list",
            detection(SurfaceDetectionResult.SEARCH),
            scope
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun row14_scope_chrome_except_adult_adult_in() {
        val scope = build(ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS, ScopeIdentity.ANY)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "pornhub.com — video",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row15_scope_chrome_except_adult_movie_in() {
        val scope = build(ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS, ScopeIdentity.ANY)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "netmirror — Watch full movie: Interstellar",
            detection(SurfaceDetectionResult.HOME),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row16_scope_chrome_except_adult_docs_out() {
        val scope = build(ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS, ScopeIdentity.ANY)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "docs.google.com — Untitled document",
            detection(SurfaceDetectionResult.HOME),
            scope
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun row17_scope_site_scoped_only_named_host() {
        val scope = build(ScopeKind.SITE_SCOPED, ScopeIdentity.ANY, sites = listOf("nesoacademy.org"))
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "otherplayer.example.com/video",
            detection(SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER),
            scope
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun row18_scope_chrome_tab_switcher_system() {
        // Any scope; SYSTEM surface should not match player/browse/search-restricted clauses.
        val scope = build(ScopeKind.BROWSER_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "Tab switcher",
            detection(SurfaceDetectionResult.BROWSER_TAB_SWITCHER),
            scope
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    // Instagram / Facebook / surface + companion apps

    @Test
    fun row19_scope_ig_messaging_only_thread_exempt() {
        val scope = build(ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeIdentity.INSTAGRAM)
        val m = ScopeMatcher.match(
            "com.instagram.android",
            "Type a message",
            detection(SurfaceDetectionResult.MESSAGING_THREAD),
            scope
        )
        assertTrue(m is ScopeMatch.Exempt)
    }

    @Test
    fun row20_scope_ig_messaging_only_dm_list_exempt() {
        val scope = build(ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeIdentity.INSTAGRAM)
        val m = ScopeMatcher.match(
            "com.instagram.android",
            "Direct — Inbox",
            detection(SurfaceDetectionResult.SOCIAL_DM),
            scope
        )
        assertTrue(m is ScopeMatch.Exempt)
    }

    @Test
    fun row21_scope_ig_messaging_only_reel_in() {
        val scope = build(ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeIdentity.INSTAGRAM)
        val m = ScopeMatcher.match(
            "com.instagram.android",
            "Reels — like",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row22_scope_ig_messaging_only_feed_in() {
        val scope = build(ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeIdentity.INSTAGRAM)
        val m = ScopeMatcher.match(
            "com.instagram.android",
            "Instagram — Feed",
            detection(SurfaceDetectionResult.SOCIAL_FEED),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row23_scope_fb_feed_only_feed_in() {
        val scope = build(
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY,
            ScopeIdentity.FACEBOOK,
            companionPackage = "com.facebook.orca"
        )
        val m = ScopeMatcher.match(
            "com.facebook.katana",
            "Facebook — Feed",
            detection(SurfaceDetectionResult.SOCIAL_FEED),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row24_scope_fb_feed_only_messenger_exempt() {
        val scope = build(
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY,
            ScopeIdentity.FACEBOOK,
            companionPackage = "com.facebook.orca"
        )
        val m = ScopeMatcher.match(
            "com.facebook.orca",
            "Messenger — Chat",
            detection(SurfaceDetectionResult.MESSAGING_THREAD),
            scope
        )
        assertTrue("expected Exempt, got $m", m is ScopeMatch.Exempt)
    }

    @Test
    fun row25_scope_fb_feed_only_fb_reel_in() {
        val scope = build(
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY,
            ScopeIdentity.FACEBOOK,
            companionPackage = "com.facebook.orca"
        )
        val m = ScopeMatcher.match(
            "com.facebook.katana",
            "Facebook Reels",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    // Short-form category & TikTok

    @Test
    fun row26_scope_short_form_tiktok_in() {
        val scope = build(ScopeKind.SHORT_FORM_ANY_HOST, ScopeIdentity.SHORT_FORM_ANY)
        val m = ScopeMatcher.match(
            "com.zhiliaoapp.musically",
            "For You",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row27_scope_short_form_yt_lecture_out() {
        val scope = build(ScopeKind.SHORT_FORM_ANY_HOST, ScopeIdentity.SHORT_FORM_ANY)
        val m = ScopeMatcher.match(
            "com.google.android.youtube",
            "Time elapsed 00:15 Time duration 52:10",
            detection(SurfaceDetectionResult.LONG_FORM_PLAYER),
            scope
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    @Test
    fun row28_scope_short_form_unknown_clone_in() {
        val scope = build(ScopeKind.SHORT_FORM_ANY_HOST, ScopeIdentity.SHORT_FORM_ANY)
        val m = ScopeMatcher.match(
            "com.xyz.reelsvideo",
            "For You",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun row29_scope_short_form_ig_dm_out() {
        val scope = build(ScopeKind.SHORT_FORM_ANY_HOST, ScopeIdentity.SHORT_FORM_ANY)
        val m = ScopeMatcher.match(
            "com.instagram.android",
            "Type a message",
            detection(SurfaceDetectionResult.MESSAGING_THREAD),
            scope
        )
        assertEquals(ScopeMatch.OutOfScope, m)
    }

    // ---------- Phase 2A F1 pre-work: PLAYER_ANY must mean the player family --------

    /**
     * BROWSER_ONLY(YOUTUBE) preset emits `surfaces = {PLAYER_ANY, BROWSE, SEARCH}`.
     * When Chrome plays a 52-minute YouTube lecture, SurfaceDetector returns
     * LONG_FORM_PLAYER -> ScopeSurfaceClass.PLAYER_LONG. That must still match a
     * clause that lists PLAYER_ANY -- otherwise "YouTube only in Chrome" silently
     * allows every long lecture. See docs/scope-semantics-v1-phase2-review.md §2 F1.
     */
    @Test
    fun f1_browser_only_yt_chrome_long_player_in() {
        val scope = build(ScopeKind.BROWSER_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "https://www.youtube.com/watch?v=abcd Time elapsed 05:12 Time duration 52:03",
            detection(SurfaceDetectionResult.LONG_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    @Test
    fun f1_browser_only_yt_chrome_short_player_in() {
        val scope = build(ScopeKind.BROWSER_ONLY, ScopeIdentity.YOUTUBE)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "https://www.youtube.com/shorts/abcd Time elapsed 00:12 Time duration 00:58",
            detection(SurfaceDetectionResult.SHORT_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    /**
     * HOST_WITH_CATEGORY_EXEMPTIONS clause `i3` restricts `VIDEO_ANY` to
     * `surfaces = {PLAYER_ANY}`. A long lecture on an unlisted site in Chrome
     * must still land InScope so the media/entertainment gates can run.
     */
    @Test
    fun f1_host_with_category_exemptions_long_player_unlisted_site_in() {
        val scope = build(ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS)
        val m = ScopeMatcher.match(
            "com.android.chrome",
            "https://vimeo.com/12345 Time elapsed 05:12 Time duration 52:03",
            detection(SurfaceDetectionResult.LONG_FORM_PLAYER),
            scope
        )
        assertInScope(m)
    }

    // ---------- helpers ---------------------------------------------------

    private fun build(
        kind: ScopeKind,
        identity: ScopeIdentity? = null,
        sites: List<String> = emptyList(),
        packages: List<String> = emptyList(),
        companionPackage: String? = null
    ): EnforcementScope {
        val exp = ScopeKindPresets.expand(kind, identity, sites, packages, companionPackage)
        return EnforcementScope(
            version = 1,
            kind = kind,
            identity = identity,
            include = exp.include,
            exempt = exp.exempt,
            confidence = 0.9,
            evidenceSpans = listOf("test evidence")
        )
    }

    private fun detection(surface: SurfaceDetectionResult): SurfaceDetection =
        SurfaceDetection(surface = surface, evidence = emptyList())

    private fun assertInScope(m: ScopeMatch) {
        assertTrue("expected InScope, got $m", m is ScopeMatch.InScope)
    }
}
