package com.phonecodex.app.domain.promise

/**
 * Keyboard / ADB typos on unit words: `minutesl` → `minutes`.
 * Only a single extra letter after a recognized time unit. Never rewrites real words.
 */
object PromiseTextCleanup {

    fun normalizeTyped(text: String): String {
        if (text.isEmpty()) return text
        return ACCIDENTAL_UNIT_SUFFIX.replace(text) { match ->
            val unit = match.groupValues[1]
            val extra = match.groupValues[2]
            val combined = (unit + extra).lowercase()
            if (combined in KNOWN_UNITS) match.value else unit
        }
    }

    private val KNOWN_UNITS = setOf(
        "minutes",
        "minute",
        "seconds",
        "second",
        "hours",
        "hour"
    )

    // Digit|start is a valid attach point (`40minutesl`). Do not treat
    // `minutes` as `minute` + `s`.
    private val ACCIDENTAL_UNIT_SUFFIX = Regex(
        """(?<![A-Za-z])(minutes|minute|seconds|second|hours|hour)([A-Za-z])\b""",
        RegexOption.IGNORE_CASE
    )
}
