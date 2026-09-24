package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.AccessibilityUrlExtractor

/**
 * Site / host-pattern matcher over a11y text already on screen.
 *
 * Local parsing only — never fetches URLs, never captures the screen.
 * Generalises [AccessibilityUrlExtractor] to arbitrary hosts.
 *
 * Pattern syntax:
 * - `"*"`                     matches any host (used with BROWSER).
 * - `"youtube.com"`           matches `youtube.com`, `www.youtube.com`, `m.youtube.com`.
 * - `"*.example.com"`         matches `example.com` and any subdomain.
 * - `"youtu.be"`              matches `youtu.be` and `www.youtu.be`.
 */
object SiteResolver {

    private val HOST_TOKEN = Regex(
        """(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2}[a-z]*"""
    )

    /** Every hostname-shaped token found in the a11y text, lowercased. */
    fun extractHosts(screenText: String): List<String> {
        if (screenText.isBlank()) return emptyList()
        val lower = screenText.lowercase()
        val found = linkedSetOf<String>()
        for (match in HOST_TOKEN.findAll(lower)) {
            val token = normalizeHost(match.value)
            if (token.isNotEmpty()) found += token
        }
        // Reuse the existing URL extractor so we pick up youtube.com/watch?v=… tokens too.
        for (url in AccessibilityUrlExtractor.extract(screenText)) {
            val host = normalizeHost(url)
            if (host.isNotEmpty()) found += host
        }
        return found.toList()
    }

    fun hostMatchesAny(screenText: String, patterns: Collection<String>): Boolean {
        if (patterns.isEmpty()) return false
        if (patterns.any { it.trim() == "*" }) return true
        val hosts = extractHosts(screenText)
        if (hosts.isEmpty()) return false
        return patterns.any { pattern -> hosts.any { host -> hostMatchesPattern(host, pattern) } }
    }

    fun hostMatchesPattern(host: String, pattern: String): Boolean {
        val h = host.lowercase().trim().removePrefix("www.").removePrefix("m.")
        val p = pattern.lowercase().trim()
        if (p.isEmpty()) return false
        if (p == "*") return true
        if (p.startsWith("*.")) {
            val suffix = p.removePrefix("*.")
            return h == suffix || h.endsWith(".$suffix")
        }
        val bareP = p.removePrefix("www.").removePrefix("m.")
        return h == bareP || h.endsWith(".$bareP")
    }

    private fun normalizeHost(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.substringAfter("://", s)
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        s = s.substringBefore('#')
        s = s.trimEnd('.', ',', ';', ':', ')', ']', '}', '"', '\'')
        return s
    }
}
