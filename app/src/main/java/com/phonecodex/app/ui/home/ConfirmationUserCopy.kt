package com.phonecodex.app.ui.home

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.promise.PromiseClarificationLaw

/**
 * User-facing confirmation copy helpers.
 *
 * INTERNAL scope (packages) stays on [FocusPromise.suggestedAppRules] for PolicyEngine.
 * USER copy must stay category-level — never dump package names or bypass-class apps
 * (NewPipe/Chrome) unless the user named them.
 */
internal object ConfirmationUserCopy {

    const val UNRECOGNIZED_CLARIFICATION_CHOICE =
        PromiseClarificationLaw.UNRECOGNIZED_CLARIFICATION_CHOICE

    fun isUnrecognizedClarificationChoice(text: String): Boolean =
        PromiseClarificationLaw.isUnrecognizedClarificationChoice(text)

    fun shouldShowUnrecognizedClarificationChoice(
        selectedClarificationOptionId: String?,
        optionIds: Collection<String>
    ): Boolean =
        PromiseClarificationLaw.shouldShowUnrecognizedClarificationChoice(
            selectedClarificationOptionId,
            optionIds
        )


    /**
     * Android package IDs. Common prefixes match with one dot (com.foo);
     * ambiguous English-word prefixes (app/me/tv/dev) require two dots so
     * normal sentences never match.
     */
    private val PACKAGE_NAME = Regex(
        """\b(?:(?:com|org|io|net)\.[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)*""" +
            """|(?:app|me|tv|dev)\.[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)\b"""
    )

    /** Contract-approved category words for sanitized package IDs. */
    private const val CATEGORY_VIDEO = "YouTube/video apps"
    private const val CATEGORY_SHORT_FORM = "short-form video"
    private const val CATEGORY_ADULT = "adult content"
    private const val CATEGORY_BROWSER = "browser video"

    private val DUPLICATE_CATEGORY = Regex(
        "(" +
            listOf(CATEGORY_VIDEO, CATEGORY_SHORT_FORM, CATEGORY_ADULT, CATEGORY_BROWSER)
                .joinToString("|") { Regex.escape(it) } +
            ")" +
            """(\s*(?:,|and|&|·)\s*\1)+"""
    )

    private val BYPASS_CLASS_APPS = listOf(
        "NewPipe" to "newpipe",
        "Chrome" to "chrome"
    )

    private val SHORT_FORM_PACKAGES = setOf(
        "com.google.android.youtube",
        "org.schabi.newpipe",
        "com.instagram.android",
        "com.facebook.katana",
        "com.zhiliaoapp.musically",
        "com.snapchat.android",
        "com.android.chrome"
    )

    fun sanitize(text: String, promiseText: String): String {
        // Replace raw package IDs with category words (never render "com.*").
        var out = PACKAGE_NAME.replace(text) { match -> categoryWordForPackage(match.value) }
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        out = DUPLICATE_CATEGORY.replace(out) { it.groupValues[1] }
        out = out.replace(Regex("""\s*,\s*,+"""), ",").trim(',', ' ', '·')
        for ((label, cue) in BYPASS_CLASS_APPS) {
            if (!promiseText.contains(cue, ignoreCase = true)) {
                out = out.replace(Regex("""\b$label\b""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""(^|[\s(])/\s*|\s*/(?=\s*($|[),.]))"""), "$1")
                    .replace(Regex("""\s*,\s*,+"""), ",")
                    .replace(Regex("""\s{2,}"""), " ")
                    .trim(',', ' ', '·')
            }
        }
        // Collapse long app dumps into category language.
        out = out.replace(
            Regex(
                """\b(?:YouTube|Instagram|Facebook|TikTok|Snapchat|NewPipe|Chrome)""" +
                    """(?:\s*,\s*(?:YouTube|Instagram|Facebook|TikTok|Snapchat|NewPipe|Chrome)){2,}\b""",
                RegexOption.IGNORE_CASE
            ),
            "short-video and reels-style apps"
        )
        return out.trim().trimStart(',', ' ').trimEnd(',', ' ')
    }

    fun containsPackageName(text: String): Boolean = PACKAGE_NAME.containsMatchIn(text)

    /**
     * Map a leaked package ID to a contract-approved category word.
     * Unknown packages are dropped entirely (empty string).
     */
    private fun categoryWordForPackage(packageId: String): String {
        val pkg = packageId.lowercase()
        return when {
            listOf("porn", "xvideos", "xhamster", "xnxx", "adult", "sexual")
                .any { it in pkg } -> CATEGORY_ADULT
            listOf("instagram", "tiktok", "musically", "snapchat", "facebook", "reels", "likee", "moj")
                .any { it in pkg } -> CATEGORY_SHORT_FORM
            listOf("chrome", "browser", "firefox", "brave", "opera", "duckduckgo")
                .any { it in pkg } -> CATEGORY_BROWSER
            listOf("youtube", "newpipe", "vanced", "video", "vimeo", "dailymotion", "netmirror")
                .any { it in pkg } -> CATEGORY_VIDEO
            else -> ""
        }
    }

    /**
     * Category line for "Applies to" — never a package dump.
     */
    fun appliesToLabel(draft: FocusPromise): String? {
        val packages = draft.suggestedAppRules.map { it.packageName.lowercase() }.toSet() +
            draft.contentRules.mapNotNull { it.packageName?.lowercase() }
        val hasShortFormRule = draft.contentRules.any {
            it.contentType.equals("short_form_video", ignoreCase = true) ||
                it.surface.equals("shorts", ignoreCase = true) ||
                it.surface.equals("reels", ignoreCase = true)
        }
        val shortFormScope = hasShortFormRule ||
            packages.any { it in SHORT_FORM_PACKAGES } ||
            draft.conditionalSummaries.any { it.contains("short-video", ignoreCase = true) } ||
            draft.cautionMessages.any { it.contains("short-form", ignoreCase = true) }

        if (shortFormScope) {
            return "short-video apps, social reels apps, and browser video pages"
        }

        val userNamed = userNamedAppLabels(draft.rawText, draft.suggestedAppRules)
        if (userNamed.isNotEmpty()) {
            return when {
                userNamed.any { it.contains("YouTube", ignoreCase = true) } &&
                    userNamed.size == 1 -> "YouTube-like apps"
                else -> userNamed.joinToString(", ")
            }
        }

        val fromConditional = draft.conditionalSummaries
            .firstOrNull { it.startsWith("Applies to:", ignoreCase = true) }
            ?.removePrefix("Applies to:")
            ?.removePrefix("applies to:")
            ?.trim()
            ?.let { sanitize(it, draft.rawText) }
        return fromConditional?.takeIf { it.isNotBlank() }
    }

    fun userVisibleAppLabels(draft: FocusPromise): List<String> =
        userNamedAppLabels(draft.rawText, draft.suggestedAppRules)

    private fun userNamedAppLabels(promiseText: String, rules: List<AppRule>): List<String> {
        val lower = promiseText.lowercase()
        return rules.map { it.label.trim() }
            .filter { it.isNotEmpty() }
            .filter { !PACKAGE_NAME.containsMatchIn(it) }
            .filter { label ->
                val cue = label.lowercase()
                when (cue) {
                    "newpipe" -> lower.contains("newpipe")
                    "chrome" -> lower.contains("chrome")
                    "netmirror" -> lower.contains("netmirror") || lower.contains("newtv")
                    "this app" -> lower.contains("this app")
                    else -> lower.contains(cue) ||
                        (cue == "youtube" && (lower.contains("youtube") || lower.contains("yt")))
                }
            }
            .distinctBy { it.lowercase() }
    }

    fun describeContentRuleForUser(rule: ContentRule, promiseText: String): String {
        val subject = when {
            rule.contentType.equals("short_form_video", ignoreCase = true) ->
                "short-form video"
            rule.contentType.equals("long_form_video", ignoreCase = true) ->
                "longer videos"
            rule.contentType.equals("adult_sexual", ignoreCase = true) ->
                "adult content"
            rule.surface.equals("shorts", ignoreCase = true) ||
                rule.surface.equals("reels", ignoreCase = true) ->
                "short-form video"
            rule.appLabel != null &&
                promiseText.contains(rule.appLabel!!, ignoreCase = true) ->
                sanitize(rule.appLabel!!, promiseText)
            else -> "video"
        }
        val threshold = if (rule.operator != null && rule.value != null && rule.unit != null) {
            " ${operatorLabel(rule.operator)} ${rule.value} ${rule.unit}"
        } else {
            ""
        }
        return sanitize("$subject$threshold → ${rule.action.name}", promiseText)
    }

    private fun operatorLabel(op: String): String = when (op) {
        "gt" -> "longer than"
        "gte" -> "at least"
        "lt" -> "shorter than"
        "lte" -> "up to"
        "eq" -> "exactly"
        else -> op
    }

    fun stripAppliesToLines(summaries: List<String>): List<String> =
        summaries.filterNot { it.startsWith("Applies to:", ignoreCase = true) }
}

private fun String.trim(vararg chars: Char): String {
    var start = 0
    var end = length
    while (start < end && this[start] in chars) start++
    while (end > start && this[end - 1] in chars) end--
    return substring(start, end)
}
