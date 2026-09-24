package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.AccessibilityUrlExtractor
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.VideoPlatformKind
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.guardrail.AdultContentLaw

/**
 * Content identity resolution — "is this screen about YouTube / Instagram / short-form / adult / …?"
 *
 * Combines package identity (from [VideoPlatformRegistry]) with URL evidence from
 * a11y text (via [AccessibilityUrlExtractor]) and, for the `*_ANY` categories, class
 * heuristics from [VideoPlatformRegistry.isVideoOrStreamingPackage] +
 * [VideoPlatformRegistry.looksLikeUnknownStreamingPackage].
 *
 * Local text only. Never fetches URLs, never captures the screen.
 * Adult detection reuses [AdultContentLaw.isHardBlock] (no new heuristics here).
 */
object IdentityResolver {

    /** Known packages for named identities. Kept SMALL — anything unlisted goes via `*_ANY`. */
    private val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.youtube.kids",
        "com.google.android.apps.youtube.creator",
        "com.vanced.android.youtube",
        "com.google.android.youtube.tv",
        "org.schabi.newpipe",
        "org.schabi.newpipe.legacy"
    )

    private val INSTAGRAM_PACKAGES = setOf(
        "com.instagram.android",
        "com.instagram.lite",
        "com.instagram.threads"
    )

    private val FACEBOOK_PACKAGES = setOf(
        "com.facebook.katana",
        "com.facebook.lite"
    )

    private val MESSENGER_PACKAGES = setOf(
        "com.facebook.orca",
        "com.facebook.mlite"
    )

    private val TIKTOK_PACKAGES = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.ss.android.ugc.tiktok"
    )

    private val SNAPCHAT_PACKAGES = setOf(
        "com.snapchat.android",
        "com.snap.snapchat"
    )

    private val WHATSAPP_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.gbwhatsapp"
    )

    private val YT_URL_TOKENS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com")
    private val IG_URL_TOKENS = listOf("instagram.com", "instagr.am", "cdninstagram.com")
    private val FB_URL_TOKENS = listOf("facebook.com", "fb.com", "fb.watch")
    private val MESSENGER_URL_TOKENS = listOf("messenger.com", "m.me")
    private val TIKTOK_URL_TOKENS = listOf("tiktok.com", "vm.tiktok.com", "tiktokcdn.com")
    private val SNAPCHAT_URL_TOKENS = listOf("snapchat.com", "story.snapchat.com")
    private val WHATSAPP_URL_TOKENS = listOf("wa.me", "whatsapp.com")

    /** Movie / OTT / long-form streaming hints for [ScopeIdentity.MOVIE_OTT_ANY]. */
    private val MOVIE_URL_TOKENS = listOf(
        "netflix.com",
        "primevideo.com",
        "hotstar.com",
        "jiocinema.com",
        "sonyliv.com",
        "zee5.com",
        "aha.video",
        "voot.com",
        "netmirror",
        "erosnow.com",
        "mxplayer.in"
    )
    private val MOVIE_WORDS = listOf(
        "watch full movie",
        "full movie",
        "episode 1",
        "watch series",
        "web series",
        "ott release",
        "watch online free"
    )

    fun matches(identity: ScopeIdentity, packageName: String, screenText: String): Boolean {
        val pkg = packageName.trim().lowercase()
        val text = screenText.lowercase()
        return when (identity) {
            ScopeIdentity.ANY -> true
            ScopeIdentity.YOUTUBE ->
                pkg in YOUTUBE_PACKAGES ||
                    hasAnyUrlToken(text, YT_URL_TOKENS) ||
                    looksLikeYouTubeText(text) ||
                    browserWithYouTubeBrand(packageName, screenText, text)
            ScopeIdentity.INSTAGRAM ->
                pkg in INSTAGRAM_PACKAGES || hasAnyUrlToken(text, IG_URL_TOKENS)
            ScopeIdentity.FACEBOOK ->
                pkg in FACEBOOK_PACKAGES || hasAnyUrlToken(text, FB_URL_TOKENS)
            ScopeIdentity.MESSENGER ->
                pkg in MESSENGER_PACKAGES || hasAnyUrlToken(text, MESSENGER_URL_TOKENS)
            ScopeIdentity.TIKTOK ->
                pkg in TIKTOK_PACKAGES || hasAnyUrlToken(text, TIKTOK_URL_TOKENS)
            ScopeIdentity.SNAPCHAT ->
                pkg in SNAPCHAT_PACKAGES || hasAnyUrlToken(text, SNAPCHAT_URL_TOKENS)
            ScopeIdentity.WHATSAPP ->
                pkg in WHATSAPP_PACKAGES || hasAnyUrlToken(text, WHATSAPP_URL_TOKENS)
            ScopeIdentity.VIDEO_ANY ->
                isVideoOrStreaming(pkg) ||
                    hasAnyUrlToken(
                        text,
                        YT_URL_TOKENS + TIKTOK_URL_TOKENS + IG_URL_TOKENS + FB_URL_TOKENS + MOVIE_URL_TOKENS
                    )
            ScopeIdentity.SHORT_FORM_ANY ->
                participatesInShortForm(pkg) ||
                    AccessibilityUrlExtractor.containsShortFormVideoUrl(screenText, packageName) ||
                    hasAnyUrlToken(text, TIKTOK_URL_TOKENS)
            ScopeIdentity.MOVIE_OTT_ANY ->
                isMoviePackage(pkg) ||
                    hasAnyUrlToken(text, MOVIE_URL_TOKENS) ||
                    MOVIE_WORDS.any { text.contains(it) }
            ScopeIdentity.ADULT_ANY ->
                AdultContentLaw.isHardBlock(screenText)
            ScopeIdentity.SOCIAL_ANY ->
                pkg in INSTAGRAM_PACKAGES ||
                    pkg in FACEBOOK_PACKAGES ||
                    pkg in MESSENGER_PACKAGES ||
                    pkg in TIKTOK_PACKAGES ||
                    pkg in SNAPCHAT_PACKAGES ||
                    pkg in WHATSAPP_PACKAGES ||
                    hasAnyUrlToken(
                        text,
                        IG_URL_TOKENS + FB_URL_TOKENS + MESSENGER_URL_TOKENS +
                            TIKTOK_URL_TOKENS + SNAPCHAT_URL_TOKENS
                    )
        }
    }

    /**
     * Only meaningful for `PackageMatchMode.CLASS_RESOLVED` with a non-empty package list:
     * returns true if the package is either literally listed OR class-resolvable to the
     * identity via heuristics.
     */
    fun classResolvedIncludes(
        identity: ScopeIdentity,
        packageName: String,
        screenText: String
    ): Boolean = matches(identity, packageName, screenText)

    // --- helpers -------------------------------------------------------------

    private fun hasAnyUrlToken(text: String, tokens: List<String>): Boolean =
        tokens.any { token -> text.contains(token) }

    /**
     * Mirror of [com.phonecodex.app.domain.enforcement.EnforcementScopeLaw.isYouTubeSurface]'s
     * "browser package showing YouTube brand tokens on an active player" branch.
     * Kept in lockstep with the old law so the legacy adapter path is behavior-preserving.
     * See docs/scope-semantics-v1-phase2-review.md §2 F1/F5.
     */
    private fun browserWithYouTubeBrand(
        packageName: String,
        screenText: String,
        lowercaseText: String
    ): Boolean {
        if (!HostResolver.isBrowser(packageName)) return false
        if (!YT_BRAND_TOKEN.containsMatchIn(lowercaseText)) return false
        return SurfaceDetector.detect(packageName, screenText).isActivePlayer
    }

    private val YT_BRAND_TOKEN = Regex("""\byoutube\b""")

    private fun looksLikeYouTubeText(text: String): Boolean {
        // Match EnforcementScopeLaw.looksLikeYouTubePlayerAccessibility exactly:
        // YouTube chrome names two nodes "Time elapsed" and "Time duration" together;
        // almost no other Android app emits both tokens simultaneously. Requiring an
        // additional "youtube"/"subscribe" token here was too strict -- Chrome playing a
        // YouTube video with spoken-only durations ("Time elapsed 1 second / Time duration
        // 31 minutes, 33 seconds") is legit YouTube playback and must resolve as YOUTUBE.
        // Keeping this in lockstep with EnforcementScopeLaw is what makes the
        // LegacyScopeAdapter -> ScopeMatcher path byte-identical to the old law's verdict.
        return text.contains("time elapsed") && text.contains("time duration")
    }

    private fun isVideoOrStreaming(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return VideoPlatformRegistry.isVideoOrStreamingPackage(packageName) ||
            VideoPlatformRegistry.looksLikeUnknownStreamingPackage(packageName)
    }

    private fun participatesInShortForm(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (VideoPlatformRegistry.participatesInShortFormQuota(packageName)) return true
        // Unregistered but clearly short-form-adjacent by name: `reels`, `shorts`, `tiktok`.
        val lower = packageName.lowercase()
        return lower.contains("reels") ||
            lower.contains("shorts") ||
            lower.contains("tiktok") ||
            lower.contains("musically")
    }

    private fun isMoviePackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return VideoPlatformRegistry.platformKind(packageName) == VideoPlatformKind.MOVIE_STREAMING ||
            VideoPlatformRegistry.looksLikeUnknownStreamingPackage(packageName)
    }
}
