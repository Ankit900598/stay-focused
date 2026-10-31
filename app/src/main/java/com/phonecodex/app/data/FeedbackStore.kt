package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences
import com.phonecodex.app.domain.model.FeedbackEntry

class FeedbackStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    init {
        removeLegacyPrivateContext()
    }

    fun addFeedback(entry: FeedbackEntry) {
        val encoded = encode(entry)
        val updated = (listOf(encoded) + getRawEntries()).take(MAX_ENTRIES)
        prefs.edit()
            .putString(KEY_ENTRIES, updated.joinToString("\n"))
            .apply()
    }

    fun getRecentFeedback(): List<FeedbackEntry> {
        return getRawEntries()
            .mapNotNull { decode(it) }
    }

    fun getFeedbackCount(): Int {
        return getRawEntries().size
    }

    fun clearFeedback() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun getRawEntries(): List<String> {
        val stored = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return stored
            .lineSequence()
            .map { it.removeSuffix("\r") }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun encode(entry: FeedbackEntry): String {
        return listOf(
            entry.timestampMillis.toString(),
            entry.packageName,
            entry.screenTextPreview,
            entry.originalDecision,
            entry.correctedDecision,
            entry.reason
        ).joinToString(FIELD_SEPARATOR) { sanitizeField(it) }
    }

    private fun decode(raw: String): FeedbackEntry? {
        val parts = raw.split(FIELD_SEPARATOR, limit = Int.MAX_VALUE)
        if (parts.size < 6) return null

        val timestampMillis = parts[0].toLongOrNull() ?: return null
        return FeedbackEntry(
            timestampMillis = timestampMillis,
            packageName = parts[1],
            screenTextPreview = parts[2],
            originalDecision = parts[3],
            correctedDecision = parts[4],
            reason = parts.drop(5).joinToString(FIELD_SEPARATOR)
        )
    }

    private fun sanitizeField(value: String): String {
        return value
            .replace(FIELD_SEPARATOR, " ")
            .replace('\n', ' ')
            .trim()
    }

    private fun removeLegacyPrivateContext() {
        val version = runCatching { prefs.getInt(KEY_PRIVACY_VERSION, 0) }.getOrDefault(0)
        if (version >= PRIVACY_VERSION) return

        val sanitizedEntries = getRawEntries().mapNotNull { raw ->
            val fields = raw.split(FIELD_SEPARATOR, limit = Int.MAX_VALUE).toMutableList()
            if (fields.size < 6) return@mapNotNull null
            listOf(fields[0], fields[1], "", fields[3], fields[4], "")
                .joinToString(FIELD_SEPARATOR)
        }
        prefs.edit()
            .putString(KEY_ENTRIES, sanitizedEntries.joinToString("\n"))
            .putInt(KEY_PRIVACY_VERSION, PRIVACY_VERSION)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "phonecodex_feedback"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_PRIVACY_VERSION = "privacyVersion"
        private const val PRIVACY_VERSION = 1
        private const val MAX_ENTRIES = 50
        private const val FIELD_SEPARATOR = "\u001E"
    }
}
