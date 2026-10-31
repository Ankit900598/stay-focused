package com.phonecodex.app.domain.scope

/**
 * The ONLY producer of user-facing scope copy ("Applies to: …").
 *
 * Copy is generated from the [ScopeKind] + [ScopeIdentity], never from AI prose and
 * never containing a package identifier. Adding a new preset kind means adding a copy
 * row here — a test asserts that every kind produces a non-empty package-free string.
 *
 * Package IDs are never rendered. `PACKAGE_PINNED` uses only the safe display labels
 * whose packages passed the user-promise name check at the compiler boundary.
 */
object ScopeAppliesToCopy {

    private val PACKAGE_ID_REGEX = Regex("""\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b""")
    private const val SOCIAL_ANY_OPTION_LABEL =
        "Recognized social apps/sites only (Instagram, Facebook, Messenger, TikTok, Snapchat, WhatsApp)"
    private const val SOCIAL_ANY_APPLIES_TO =
        "recognized Instagram, Facebook, Messenger, TikTok, Snapchat, and WhatsApp apps; matching sites are supported for the first five only"

    fun forScope(scope: EnforcementScope?): String {
        if (scope == null) return "Not set yet — finish setting up this promise."
        if (scope.kind == ScopeKind.UNRESOLVED || !scope.isEnforceable) {
            return "Not set yet — finish setting up this promise."
        }
        val identity = identityOf(scope)
        // appliesToText is model prose: never rendered, so copy is identical across compiles.
        val body = when (scope.kind) {
            ScopeKind.APP_ONLY -> "the ${identityLabel(identity)} app only — the browser and other players stay untouched"
            ScopeKind.BROWSER_ONLY -> "${identityLabel(identity)} pages inside your browser — the ${identityLabel(identity)} app stays untouched"
            ScopeKind.BROWSER_VIDEO -> "videos playing in any browser — search, docs, and normal browsing stay open"
            ScopeKind.CONTENT_ANY_HOST -> "${identityLabel(identity)} videos anywhere — the app, browser pages, and other players"
            ScopeKind.VIDEO_CLASS_ANY_HOST -> "videos playing in any video app or browser — search and normal browsing stay open"
            ScopeKind.SHORT_FORM_ANY_HOST -> "short videos everywhere — Shorts, Reels, TikTok-style clips, and short videos in your browser"
            ScopeKind.APP_SHORT_FORM_ONLY -> "short videos in the ${identityLabel(identity)} app only — long videos and other apps stay untouched"
            ScopeKind.SOCIAL_MESSAGING_ONLY -> if (identity == ScopeIdentity.SOCIAL_ANY) {
                "Only $SOCIAL_ANY_APPLIES_TO: messages stay open; other screens in those apps are covered. Other apps and sites are outside this promise"
            } else {
                "${identityLabel(identity)} — messages stay open, the rest is covered"
            }
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY -> if (identity == ScopeIdentity.SOCIAL_ANY) {
                "Feeds and short videos in $SOCIAL_ANY_APPLIES_TO are in scope; messages and other apps/sites stay outside this promise"
            } else {
                "the ${identityLabel(identity)} feed and short videos — messaging stays open"
            }
            ScopeKind.SITE_SCOPED -> "specific sites in your browser only"
            ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS -> "adult, movie, and video pages in your browser — normal browsing stays open"
            ScopeKind.PACKAGE_PINNED -> packagePinnedCopy(scope.include.flatMap { it.packages })
            ScopeKind.UNRESOLVED -> "Not set yet — finish setting up this promise."
        }
        return sanitize(body)
    }

    /**
     * Clarification option label for an enforceable scope. Mirrors `scopeOptionLabel`
     * in backend/promiseCompilerService.js so card, pick, and saved scope agree.
     */
    fun optionLabel(scope: EnforcementScope?): String? {
        if (scope == null || !scope.isEnforceable) return null
        val identity = identityOf(scope)
        val name = if (identity == ScopeIdentity.SOCIAL_ANY) {
            SOCIAL_ANY_OPTION_LABEL
        } else {
            identityLabel(identity)
        }
        return when (scope.kind) {
            ScopeKind.APP_ONLY -> "$name app only"
            ScopeKind.BROWSER_ONLY -> "$name in your browser only"
            ScopeKind.BROWSER_VIDEO -> "Video in any browser"
            ScopeKind.CONTENT_ANY_HOST -> "$name anywhere"
            ScopeKind.VIDEO_CLASS_ANY_HOST -> "All video apps and browsers"
            ScopeKind.SHORT_FORM_ANY_HOST -> "Short videos everywhere"
            ScopeKind.APP_SHORT_FORM_ONLY -> "$name short videos only"
            ScopeKind.SOCIAL_MESSAGING_ONLY -> "$name except messages"
            ScopeKind.SOCIAL_FEED_AND_REELS_ONLY -> "$name feed and short videos"
            ScopeKind.SITE_SCOPED -> "Specific sites in your browser"
            ScopeKind.HOST_WITH_CATEGORY_EXEMPTIONS -> "Adult, movie, and video pages in your browser"
            ScopeKind.PACKAGE_PINNED -> packagePinnedOptionLabel(scope.include.flatMap { it.packages })
            ScopeKind.UNRESOLVED -> null
        }
    }

    private fun identityOf(scope: EnforcementScope): ScopeIdentity =
        scope.identity ?: scope.include.firstOrNull()?.identity ?: ScopeIdentity.ANY

    private fun packagePinnedCopy(packages: List<String>): String {
        val labels = ScopePackageMentionLaw.displayLabels(packages)
        return when (labels.size) {
            0 -> "specific apps you named"
            1 -> "Only the ${labels.single()} app"
            else -> "Only ${joinAppLabels(labels)}"
        }
    }

    private fun packagePinnedOptionLabel(packages: List<String>): String {
        val labels = ScopePackageMentionLaw.displayLabels(packages)
        return if (labels.isEmpty()) "Specific apps you named" else "${joinAppLabels(labels)} only"
    }

    private fun joinAppLabels(labels: List<String>): String = when (labels.size) {
        0 -> ""
        1 -> labels.single()
        2 -> "${labels[0]} and ${labels[1]}"
        else -> "${labels.dropLast(1).joinToString(", ")}, and ${labels.last()}"
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
