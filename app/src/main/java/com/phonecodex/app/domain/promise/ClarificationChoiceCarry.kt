package com.phonecodex.app.domain.promise

import org.json.JSONArray

/**
 * Clarification options exactly as shown to the user, bound to the promise
 * text they were generated for. The backend treats this snapshot as the only
 * authority for what a tapped option means.
 */
data class ClarificationChoiceSnapshot(
    val promiseText: String,
    val optionsJson: String
)

/** Wire payload for a pick; null fields mean "cannot prove it" (backend fails closed). */
data class ClarificationChoicePayload(
    val selectedOptionId: String,
    val selectedOptionJson: String?,
    val snapshotJson: String?,
    val snapshotPromiseText: String?
)

object ClarificationChoiceCarry {

    /** Snapshot to cache after a compile response; null when no options were shown. */
    fun snapshotFrom(promiseText: String, optionsRawJson: String?): ClarificationChoiceSnapshot? {
        val raw = optionsRawJson?.trim().orEmpty()
        if (raw.isEmpty() || raw == "[]") return null
        return ClarificationChoiceSnapshot(promiseText = promiseText, optionsJson = raw)
    }

    /**
     * Build the pick payload. A snapshot generated for different promise text is
     * never sent: an old choice must not be applied to an edited promise.
     */
    fun payloadFor(
        selectedOptionId: String,
        currentPromiseText: String,
        snapshot: ClarificationChoiceSnapshot?
    ): ClarificationChoicePayload {
        val usable = snapshot?.takeIf { sameText(it.promiseText, currentPromiseText) }
        return ClarificationChoicePayload(
            selectedOptionId = selectedOptionId,
            selectedOptionJson = usable?.let { findUniqueOption(it.optionsJson, selectedOptionId) },
            snapshotJson = usable?.optionsJson,
            snapshotPromiseText = usable?.promiseText
        )
    }

    /** The option JSON when [optionId] appears exactly once; null on missing or duplicate ids. */
    fun findUniqueOption(optionsJson: String, optionId: String): String? {
        val wanted = optionId.trim()
        if (wanted.isEmpty()) return null
        return try {
            val arr = JSONArray(optionsJson)
            val matches = (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("id").trim().equals(wanted, ignoreCase = true) }
            matches.singleOrNull()?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun sameText(a: String, b: String): Boolean =
        normalize(a) == normalize(b)

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()
}
