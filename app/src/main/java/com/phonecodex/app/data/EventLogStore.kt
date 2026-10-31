package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences

class EventLogStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    init {
        redactLegacyBlockExplanations()
    }

    fun addEvent(message: String) {
        val entry = "${System.currentTimeMillis()}|$message"
        val updated = (listOf(entry) + getRawEvents()).take(MAX_EVENTS)
        prefs.edit()
            .putString(KEY_EVENTS, updated.joinToString("\n"))
            .apply()
    }

    fun getRecentEvents(): List<String> {
        return getRawEvents().map { raw ->
            val separator = raw.indexOf('|')
            if (separator <= 0) {
                raw
            } else {
                val timestamp = raw.substring(0, separator)
                val message = raw.substring(separator + 1)
                "$timestamp: $message"
            }
        }
    }

    private fun getRawEvents(): List<String> {
        val stored = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return stored
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun redactLegacyBlockExplanations() {
        val version = runCatching { prefs.getInt(KEY_PRIVACY_VERSION, 0) }.getOrDefault(0)
        if (version >= PRIVACY_VERSION) return

        val sanitizedEvents = getRawEvents().mapNotNull { raw ->
            val separator = raw.indexOf('|')
            if (separator <= 0) return@mapNotNull null
            val timestamp = raw.substring(0, separator)
            if (timestamp.toLongOrNull() == null) return@mapNotNull null
            val message = raw.substring(separator + 1)
            val safeMessage = LEGACY_BLOCK_EXPLANATION.matchEntire(message)?.let { match ->
                "Blocked ${match.groupValues[1]} after policy evaluation"
            } ?: message
            "$timestamp|$safeMessage"
        }
        prefs.edit()
            .putString(KEY_EVENTS, sanitizedEvents.joinToString("\n"))
            .putInt(KEY_PRIVACY_VERSION, PRIVACY_VERSION)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "phonecodex_event_log"
        private const val KEY_EVENTS = "events"
        private const val KEY_PRIVACY_VERSION = "privacyVersion"
        private const val PRIVACY_VERSION = 1
        private const val MAX_EVENTS = 20
        private val LEGACY_BLOCK_EXPLANATION = Regex("Blocked ([A-Za-z0-9_.]+) because .*")
    }
}
