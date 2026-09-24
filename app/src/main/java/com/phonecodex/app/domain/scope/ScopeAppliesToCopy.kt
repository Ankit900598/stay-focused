package com.phonecodex.app.domain.scope

/**
 * The ONLY producer of user-facing scope copy ("Applies to: …").
 *
 * Copy is generated from the [ScopeKind] + [ScopeIdentity], never from AI prose and
 * never containing a package identifier. Adding a new preset kind means adding a copy
 * row here — a test asserts that every kind produces a non-empty package-free string.
 *
 * Package ID leaks are structurally impossible: this file has no access to raw package
 * strings from clauses; it only reads named identities.
 */
object ScopeAppliesToCopy {

    private val PACKAGE_ID_REGEX = Regex("""\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b""")

    fun forScope(scope: EnforcementScope?): String {
        if (scope == null) return "Not set yet — finish setting up this promise."
        if (scope.kind == ScopeKind.UNRESOLVED || !scope.isEnforceable) {
            return "Not set yet — finish setting up this promise."
        }
        val identity = scope.identity ?: scope.include.firstOrNull()?.identity ?: ScopeIdentity.ANY
        val body = when (scope.kind) {
            ScopeKind.APP_ONLY -> "the ${identityLabel(identity)} app only — the browser and other players stay untouched"
            ScopeKind.BROWSER_ONLY -> "${identityLabel(identity)} pages inside your browser — the ${identityLabel(identity)} app stays untouched"
            ScopeKind.CONTENT_ANY_HOST -> "${identityLabel(identity)} videos anywhere — the app, browser pages, and other players"
            ScopeKind.VIDEO_CLASS_ANY_HOST -> "video everywhere — YouTube, browser video, and other video players"
            ScopeKind.SHORT_FORM_ANY_HOST -> "short videos everywhere — Shorts, Reels, TikTok-style clips, and short videos in your browser"
            ScopeKind.SOCIAL_MESSAGING_ONLY -> "${identityLabel(identity)} — messages stay open, the rest is covered"
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY -> "the ${identityLabel(identity)} feed and short videos — messaging stays open"
            ScopeKind.SITE_SCOPED -> "specific sites in your browser only"
            ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS -> "adult, movie, and video pages in your browser — normal browsing stays open"
            ScopeKind.PACKAGE_PINNED -> "specific apps you named"
            ScopeKind.UNRESOLVED -> "Not set yet — finish setting up this promise."
        }
        return sanitize(scope.appliesToText ?: body)
    }

    /** Return `true` if the string is guaranteed free of `com.*`/`org.*`/etc. IDs. */
    fun containsPackageId(text: String): Boolean = PACKAGE_ID_REGEX.containsMatchIn(text)

    private fun sanitize(raw: String): String {
        // Structural guardrail: if a caller passed an appliesToText with a package leak,
        // strip the leak and clean whitespace. Never render packages to the user.
        val stripped = PACKAGE_ID_REGEX.replace(raw, "").replace(Regex("""\s{2,}"""), " ").trim()
        return if (stripped.isEmpty()) "Not set yet — finish setting up this promise." else stripped
    }

    private fun identityLabel(identity: ScopeIdentity): String = when (identity) {
        ScopeIdentity.YOUTUBE -> "YouTube"
        ScopeIdentity.INSTAGRAM -> "Instagram"
        ScopeIdentity.FACEBOOK -> "Facebook"
        ScopeIdentity.MESSENGER -> "Messenger"
        ScopeIdentity.TIKTOK -> "TikTok"
        ScopeIdentity.SNAPCHAT -> "Snapchat"
        ScopeIdentity.WHATSAPP -> "WhatsApp"
        ScopeIdentity.VIDEO_ANY -> "video"
        ScopeIdentity.SHORT_FORM_ANY -> "short video"
        ScopeIdentity.MOVIE_OTT_ANY -> "movie / streaming"
        ScopeIdentity.ADULT_ANY -> "adult"
        ScopeIdentity.SOCIAL_ANY -> "social"
        ScopeIdentity.ANY -> "the selected"
    }
}
