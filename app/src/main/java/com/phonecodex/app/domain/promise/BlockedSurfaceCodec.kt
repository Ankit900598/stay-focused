package com.phonecodex.app.domain.promise

/**
 * Persisted structured surface BLOCKs: `"<package>|<surface class>"`, e.g.
 * `com.google.android.youtube|short_form`. Only surface classes the runtime can detect
 * deterministically are accepted; anything else is dropped on read rather than guessed.
 */
object BlockedSurfaceCodec {

    const val SHORT_FORM = "short_form"

    private val KNOWN_SURFACES = setOf(SHORT_FORM)

    fun entry(packageName: String, surface: String): String = "${packageName.trim()}|$surface"

    fun encode(entries: List<String>): Set<String> = decode(entries).toSet()

    fun decode(raw: Collection<String>?): List<String> =
        raw.orEmpty().mapNotNull(::normalize).distinct()

    fun blocks(entries: List<String>, packageName: String, surface: String): Boolean =
        entries.contains(entry(packageName, surface))

    private fun normalize(raw: String): String? {
        val sep = raw.lastIndexOf('|')
        if (sep <= 0) return null
        val pkg = raw.substring(0, sep).trim()
        val surface = raw.substring(sep + 1).trim()
        if (pkg.isEmpty() || surface !in KNOWN_SURFACES) return null
        return entry(pkg, surface)
    }
}
