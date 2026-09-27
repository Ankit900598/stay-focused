package com.phonecodex.app.domain.promise

/**
 * A legacy compile (hosted v07/v08, `scopeContract` absent or `legacy`) only carries whole-app
 * rules, short-form surface blocks, media length, quotas and the YouTube scope kinds. Promises
 * that name a surface or host those fields cannot express (social DMs or feed, browser-only,
 * a specific website) must not start from a legacy response: either side of the gap would
 * silently widen (whole-app block) or drop (nothing enforced) what the user asked for.
 *
 * Deterministic and text-only on purpose: the check must not depend on the model that just
 * failed to return typed scope. Never infers the compiler version.
 */
object LegacyScopeContractGuard {

    const val SOCIAL_MESSAGING_SURFACE = "legacy_cannot_scope_social_messaging"
    const val SOCIAL_FEED_SURFACE = "legacy_cannot_scope_social_feed"
    const val BROWSER_ONLY_HOST = "legacy_cannot_scope_browser_only"
    const val WEBSITE_SCOPE = "legacy_cannot_scope_website"

    const val START_BLOCKED_COPY =
        "This promise needs a newer promise compiler than the one connected. " +
            "Reconnect and try again, or reword it as a whole-app or Shorts/Reels rule."

    private val SOCIAL_APP =
        Regex("""\b(instagram|insta|ig|facebook|fb|snapchat|snap|tiktok)\b""")
    private val MESSAGING_SURFACE =
        Regex("""\b(dms?|direct messages?|messages?|messaging|chats?|chatting|inbox|msgs?)\b""")
    private val FEED_SURFACE = Regex("""\b(feed|timeline|explore|scrolling feed)\b""")
    private val BROWSER_ONLY =
        Regex("""\b(?:only\s+(?:in|on)\s+(?:the\s+)?(?:chrome|browser)|(?:in|on)\s+(?:the\s+)?(?:chrome|browser)\s+only)\b""")
    private val WEBSITE = Regex("""\b[a-z0-9-]+\.(?:com|in|org|net|tv|io|co)\b""")
    private val YOUTUBE_SITE = Regex("""\b(?:m\.)?youtube\.com\b|\byoutu\.be\b""")
    // Chats of a messaging app are that whole app, which legacy rules already represent.
    private val MESSAGING_APP_SURFACE =
        Regex("""\b(?:whatsapp|telegram|signal|sms|text)\s+(?:dms?|messages?|chats?|msgs?|inbox)\b""")

    /** Non-null reason when [rawText] needs typed scope that a legacy response cannot carry. */
    fun gap(rawText: String, scopeContractV1: Boolean): String? {
        if (scopeContractV1) return null
        val text = MESSAGING_APP_SURFACE.replace(rawText.lowercase(), " ")
        val social = SOCIAL_APP.containsMatchIn(text)
        return when {
            social && MESSAGING_SURFACE.containsMatchIn(text) -> SOCIAL_MESSAGING_SURFACE
            social && FEED_SURFACE.containsMatchIn(text) -> SOCIAL_FEED_SURFACE
            BROWSER_ONLY.containsMatchIn(text) -> BROWSER_ONLY_HOST
            WEBSITE.containsMatchIn(YOUTUBE_SITE.replace(text, " ")) -> WEBSITE_SCOPE
            else -> null
        }
    }
}
