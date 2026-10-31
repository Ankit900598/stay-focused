package com.phonecodex.app.domain.enforcement

/**
 * Parses visible video duration from accessibility text.
 *
 * Two clocks that must never be mixed:
 * - **Clock B (item length):** current-player **total** T from `current / total`,
 *   `time duration …`, or a clock next to player chrome. This is "how long is
 *   this video?" `00:15 / 38:25` → T = 2305s (lecture), not a Short.
 * - **Clock C (watch-time / usage):** elapsed position or "watched 2305 seconds".
 *   That is how long they sat here, not whether the item is a Short.
 *
 * Invariant: media-length law and short-form duration math use ONLY
 * [VideoDurationParseResult.currentPlayerDurationSeconds] (clock B total).
 * Recommendation / related / shelf durations are collected separately and must
 * never drive BLOCK or "is this a Short?".
 *
 * We do not parse bare "N seconds" as a player total — that is usually
 * watch-time or rec metadata, not item length.
 */
enum class DurationSource {
    /** Duration from current/total clocks, seekbar, or "time duration" near player. */
    CURRENT_PLAYER,
    /** Only recommendation/related durations were visible — ignore for enforcement. */
    RECOMMENDATION_IGNORED,
    /** No duration-like text found. */
    NONE
}

data class VideoDurationParseResult(
    val currentPlayerDurationSeconds: Int?,
    val recommendationDurationsSeconds: List<Int>,
    val source: DurationSource
) {
    val currentPlayerDurationMinutes: Int?
        get() = currentPlayerDurationSeconds?.let { it / 60 }
}

object VideoDurationParser {

    fun parse(screenText: String): VideoDurationParseResult {
        val normalized = screenText.lowercase()
        val (playerZone, recommendationZone) = splitPlayerAndRecommendationZones(normalized)

        val playerSeconds = extractCurrentPlayerDurationSeconds(playerZone, normalized)
        val recommendationSeconds = extractRecommendationDurationsSeconds(
            recommendationZone = recommendationZone,
            fullText = normalized,
            playerSeconds = playerSeconds
        ).distinct().sorted()

        return when {
            playerSeconds != null -> VideoDurationParseResult(
                currentPlayerDurationSeconds = playerSeconds,
                recommendationDurationsSeconds = recommendationSeconds,
                source = DurationSource.CURRENT_PLAYER
            )
            recommendationSeconds.isNotEmpty() -> VideoDurationParseResult(
                currentPlayerDurationSeconds = null,
                recommendationDurationsSeconds = recommendationSeconds,
                source = DurationSource.RECOMMENDATION_IGNORED
            )
            else -> VideoDurationParseResult(
                currentPlayerDurationSeconds = null,
                recommendationDurationsSeconds = emptyList(),
                source = DurationSource.NONE
            )
        }
    }

    /** Current player total length in minutes — never recommendation cards. */
    fun extractPrimaryDurationMinutes(screenText: String): Int? =
        parse(screenText).currentPlayerDurationMinutes

    /** Current player total length in seconds — never recommendation cards. */
    fun extractPrimaryDurationSeconds(screenText: String): Int? =
        parse(screenText).currentPlayerDurationSeconds

    internal fun hasSpokenPlayerProgressClock(screenText: String): Boolean {
        val normalized = screenText.lowercase()
        val (playerZone, _) = splitPlayerAndRecommendationZones(normalized)
        return extractSpokenPlayerProgressTotalSeconds(playerZone) != null
    }

    /**
     * Clock-B short-form equation.
     *
     * If CURRENT_PLAYER total T exists and `0 < T <= [shortFormMaxSeconds]`,
     * the playing item is short-form **length**. False when:
     * - source is recommendation / none (no legal player total)
     * - T is null, ≤ 0, or > the threshold (e.g. 2305s = 38:25 lecture)
     * - the visible number is watch-time, not a player total
     */
    fun isShortFormItemLength(
        parsed: VideoDurationParseResult,
        shortFormMaxSeconds: Int
    ): Boolean {
        if (parsed.source != DurationSource.CURRENT_PLAYER) return false
        return isShortFormItemLengthSeconds(
            parsed.currentPlayerDurationSeconds,
            shortFormMaxSeconds
        )
    }

    fun isShortFormItemLengthSeconds(
        currentPlayerTotalSeconds: Int?,
        shortFormMaxSeconds: Int
    ): Boolean {
        val total = currentPlayerTotalSeconds ?: return false
        return total > 0 && total <= shortFormMaxSeconds
    }

    /**
     * Prefer total from `current / total` clocks, then explicit "time duration",
     * then spoken/clock duration adjacent to player chrome. Never bare max-of-all.
     */
    private fun extractCurrentPlayerDurationSeconds(
        playerZone: String,
        fullText: String
    ): Int? {
        val recommendationSplit = playerZone != fullText

        extractSpokenPlayerProgressTotalSeconds(playerZone)?.let { return it }
        if (!recommendationSplit) {
            extractSpokenPlayerProgressTotalSeconds(fullText)?.let { return it }
        }

        // 1) Position / total clocks (NewPipe + many native players).
        PLAYER_POSITION_TOTAL_REGEX.find(playerZone)?.let { match ->
            val total = clockToSeconds(
                match.groupValues[4],
                match.groupValues[5],
                match.groupValues[6]
            )
            if (total != null && total > 0) return total
        }
        // After a "More videos" cut, full-text clock pairs are shelf cards.
        if (!recommendationSplit) {
            PLAYER_POSITION_TOTAL_REGEX.find(fullText)?.let { match ->
                val total = clockToSeconds(
                    match.groupValues[4],
                    match.groupValues[5],
                    match.groupValues[6]
                )
                if (total != null && total > 0) return total
            }
        }

        // 2) Explicit a11y "time duration …" (YouTube / Chrome watch controls).
        // Nodes often glue: "1 secondTime duration 31 minutes, 33 seconds".
        // Do not require a word boundary before "time" — \btime misses that.
        extractSpokenTimeDurationSeconds(playerZone)?.let { return it }

        // 3) Duration adjacent to player chrome / seekbar (not recommendation prose).
        findDurationNearPlayerChrome(playerZone)?.let { return it }
        if (!recommendationSplit) {
            findDurationNearPlayerChrome(fullText)?.let { return it }
        }

        return null
    }

    private fun extractSpokenPlayerProgressTotalSeconds(text: String): Int? {
        val match = SPOKEN_PLAYER_PROGRESS_REGEX.find(text) ?: return null
        val position = spokenDurationSeconds(
            match.groupValues[1],
            match.groupValues[2],
            match.groupValues[3]
        ) ?: return null
        val total = spokenDurationSeconds(
            match.groupValues[4],
            match.groupValues[5],
            match.groupValues[6]
        ) ?: return null
        return total.takeIf { position <= it && it > 0 }
    }

    private fun spokenDurationSeconds(hoursRaw: String, minutesRaw: String, secondsRaw: String): Int? {
        val hours = hoursRaw.toIntOrNull() ?: 0
        val minutes = minutesRaw.toIntOrNull() ?: 0
        val seconds = secondsRaw.toIntOrNull() ?: 0
        if (hoursRaw.isBlank() && minutesRaw.isBlank() && secondsRaw.isBlank()) return null
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun findDurationNearPlayerChrome(text: String): Int? {
        for (anchor in PLAYER_CHROME_ANCHORS) {
            var start = 0
            while (true) {
                val idx = text.indexOf(anchor, start)
                if (idx < 0) break
                val windowStart = (idx - 100).coerceAtLeast(0)
                val windowEnd = (idx + anchor.length + 120).coerceAtMost(text.length)
                val window = text.substring(windowStart, windowEnd)
                // Prefer total from clock pair inside the window.
                PLAYER_POSITION_TOTAL_REGEX.find(window)?.let { match ->
                    clockToSeconds(
                        match.groupValues[4],
                        match.groupValues[5],
                        match.groupValues[6]
                    )?.let { return it }
                }
                extractSpokenTimeDurationSeconds(window)?.let { return it }
                // Single clock next to controls — treat as total only if not a tiny position.
                CLOCK_DURATION_REGEX.find(window)?.let { match ->
                    clockToSeconds(
                        match.groupValues[1],
                        match.groupValues[2],
                        match.groupValues.getOrNull(3)
                    )?.let { seconds ->
                        // Allow Shorts-scale totals near controls (≥15s), not only ≥60s.
                        if (seconds >= 15) return seconds
                    }
                }
                start = idx + anchor.length
            }
        }
        return null
    }

    private fun extractRecommendationDurationsSeconds(
        recommendationZone: String,
        fullText: String,
        playerSeconds: Int?
    ): List<Int> {
        val zones = listOf(recommendationZone, fullText).filter { it.isNotBlank() }
        val out = mutableListOf<Int>()
        for (zone in zones) {
            for (match in SPOKEN_MIN_SEC_REGEX.findAll(zone)) {
                val minutes = match.groupValues[1].toIntOrNull() ?: continue
                val seconds = match.groupValues[2].toIntOrNull() ?: 0
                val total = minutes * 60 + seconds
                if (playerSeconds == null || total != playerSeconds) out += total
            }
            for (match in BARE_MINUTES_REGEX.findAll(zone)) {
                // Skip "time duration N minutes" — that is player metadata.
                val prefixStart = (match.range.first - 16).coerceAtLeast(0)
                val prefix = zone.substring(prefixStart, match.range.first)
                if (prefix.contains("time duration")) continue
                val minutes = match.groupValues[1].toIntOrNull() ?: continue
                val total = minutes * 60
                if (playerSeconds == null || total != playerSeconds) out += total
            }
            for (match in REC_META_DURATION_REGEX.findAll(zone)) {
                val minutes = match.groupValues[1].toIntOrNull() ?: continue
                val seconds = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                val total = minutes * 60 + seconds
                if (playerSeconds == null || total != playerSeconds) out += total
            }
        }
        return out
    }

    private fun splitPlayerAndRecommendationZones(normalized: String): Pair<String, String> {
        var cut = -1
        for (marker in RECOMMENDATION_SECTION_MARKERS) {
            val idx = normalized.indexOf(marker)
            if (idx >= 0 && (cut < 0 || idx < cut)) cut = idx
        }
        return if (cut >= 0) {
            normalized.substring(0, cut) to normalized.substring(cut)
        } else {
            // No explicit "More videos" header — still treat spoken min+sec lists as recs later.
            normalized to normalized
        }
    }

    /**
     * YouTube / Chrome spoken seekbar: hours, minutes, seconds with optional commas.
     * "Time duration" is clock B (item length). "Time elapsed" is clock C — ignored.
     */
    private fun extractSpokenTimeDurationSeconds(text: String): Int? {
        val match = TIME_DURATION_SPOKEN_REGEX.find(text) ?: return null
        val hours = match.groupValues[1].toIntOrNull()
        val minutes = match.groupValues[2].toIntOrNull()
        val seconds = match.groupValues[3].toIntOrNull()
        if (hours == null && minutes == null && seconds == null) return null
        val total = (hours ?: 0) * 3600 + (minutes ?: 0) * 60 + (seconds ?: 0)
        return total.takeIf { it > 0 }
    }

    private fun clockToSeconds(firstRaw: String, secondRaw: String, thirdRaw: String?): Int? {
        val first = firstRaw.toIntOrNull() ?: return null
        val second = secondRaw.toIntOrNull() ?: return null
        val third = thirdRaw?.toIntOrNull()
        return if (third != null) {
            first * 3600 + second * 60 + third
        } else {
            first * 60 + second
        }
    }

    private val PLAYER_POSITION_TOTAL_REGEX =
        Regex(
            """\b(\d{1,2}):(\d{2})(?::(\d{2}))?\s*/\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\b"""
        )

    /**
     * No leading \b: a11y concatenates "1 second" + "Time duration" → "secondTime duration".
     * Optional commas: "31 minutes, 33 seconds" / "1 hour, 2 minutes, 3 seconds".
     */
    private val TIME_DURATION_SPOKEN_REGEX =
        Regex(
            """time\s+duration\s+""" +
                """(?:(\d+)\s*(?:hours?|hrs?)\s*,?\s*)?""" +
                """(?:(\d+)\s*(?:minutes?|mins?)\s*,?\s*)?""" +
                """(?:(\d+)\s*(?:seconds?|secs?))?"""
        )

    private val SPOKEN_PLAYER_PROGRESS_REGEX = Regex(
        """\b(?:(\d{1,3})\s*(?:hours?|hrs?)\s*,?\s*)?""" +
            """(?:(\d{1,3})\s*(?:minutes?|mins?)\s*,?\s*)?""" +
            """(?:(\d{1,2})\s*(?:seconds?|secs?))?\s+of\s+""" +
            """(?:(\d{1,3})\s*(?:hours?|hrs?)\s*,?\s*)?""" +
            """(?:(\d{1,3})\s*(?:minutes?|mins?)\s*,?\s*)?""" +
            """(?:(\d{1,2})\s*(?:seconds?|secs?))?\b"""
    )

    private val CLOCK_DURATION_REGEX =
        Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\b""")

    /** YouTube recommendation cards: "8 minutes 55 seconds". */
    private val SPOKEN_MIN_SEC_REGEX =
        Regex("""\b(\d{1,3})\s*minutes?\s+(\d{1,2})\s*seconds?\b""")

    private val BARE_MINUTES_REGEX =
        Regex("""\b(\d{1,3})\s*(?:minutes?|mins?)\b""")

    /**
     * Classic related-row metadata:
     * "by Channel … views … ago … 8 minutes 55 seconds"
     */
    private val REC_META_DURATION_REGEX =
        Regex(
            """\bby\b.{0,120}?(?:\d+(?:\.\d+)?\s*[kmb]?\s*views?).{0,80}?""" +
                """(\d{1,3})\s*minutes?(?:\s+(\d{1,2})\s*seconds?)?\b"""
        )

    private val RECOMMENDATION_SECTION_MARKERS = listOf(
        "more videos",
        "up next",
        "watch next",
        "related",
        "recommended",
        "recommendations",
        "you may also like",
        "suggested videos"
    )

    private val PLAYER_CHROME_ANCHORS = listOf(
        "show player controls",
        "hide player controls",
        "player controls",
        "seekbar",
        "seek bar",
        "playback",
        "progress bar",
        "time duration",
        "time elapsed"
    )
}
