package com.phonecodex.app.domain.delegation

/**
 * A single, lower-cased, ASCII mailbox address. Equality is exact: no plus-address folding,
 * no dot folding, no display-name matching. A lookalike is a different sender.
 */
class EmailAddress private constructor(val value: String) {

    val domain: String get() = value.substringAfter('@')

    fun matchesExactly(other: EmailAddress?): Boolean = other != null && other.value == value

    override fun equals(other: Any?): Boolean = other is EmailAddress && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH = 254
        private val LOCAL = Regex("""^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*$""")
        private val LABEL = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$""")

        /** What the user typed as "my professor's address". One address, nothing else. */
        fun parseUserEntry(raw: String?): EmailAddress? {
            var s = raw?.trim() ?: return null
            if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length - 1).trim()
            if (s.isEmpty() || s.length > MAX_LENGTH) return null
            if (s.any { it.code > 126 || it.code < 33 }) return null
            val lower = s.lowercase()
            val at = lower.lastIndexOf('@')
            if (at <= 0 || at != lower.indexOf('@') || at == lower.length - 1) return null
            val local = lower.substring(0, at)
            val domain = lower.substring(at + 1)
            if (local.length > 64 || !LOCAL.matches(local)) return null
            val labels = domain.split('.')
            if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return null
            if (labels.last().all { it.isDigit() }) return null
            return EmailAddress(lower)
        }

        /**
         * Mailbox from an RFC 5322 From header. Quoted display names and comments are
         * removed first so an address written inside a display name is never used.
         * A header naming more than one mailbox yields null.
         */
        fun fromHeader(header: String?): EmailAddress? {
            val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val stripped = stripQuoted(raw)?.let(::stripComments) ?: return null
            val angleCount = stripped.count { it == '<' }
            if (angleCount > 1 || stripped.count { it == '>' } != angleCount) return null
            if (angleCount == 1) {
                val open = stripped.indexOf('<')
                val close = stripped.indexOf('>', open)
                if (close < 0) return null
                if (stripped.substring(close + 1).isNotBlank()) return null
                val display = stripped.substring(0, open)
                if (display.contains('@') || display.contains(',')) return null
                return parseUserEntry(stripped.substring(open + 1, close))
            }
            if (stripped.contains(',')) return null
            return parseUserEntry(stripped)
        }

        private fun stripQuoted(s: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    i++
                    var closed = false
                    while (i < s.length) {
                        if (s[i] == '\\') { i += 2; continue }
                        if (s[i] == '"') { closed = true; break }
                        i++
                    }
                    if (!closed) return null
                    out.append(' ')
                } else {
                    out.append(c)
                }
                i++
            }
            return out.toString()
        }

        private fun stripComments(s: String): String? {
            val out = StringBuilder()
            var depth = 0
            for (c in s) {
                when {
                    c == '(' -> depth++
                    c == ')' -> { depth--; if (depth < 0) return null }
                    depth == 0 -> out.append(c)
                }
            }
            return if (depth == 0) out.toString().trim() else null
        }
    }
}
