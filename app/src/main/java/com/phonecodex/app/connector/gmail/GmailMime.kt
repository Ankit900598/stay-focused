package com.phonecodex.app.connector.gmail

import com.phonecodex.app.domain.delegation.OutgoingReply
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Base64
import java.util.ArrayDeque

/** Pure Gmail payload/MIME helpers. No network, no Android. */
internal object GmailMime {

    const val MAX_BODY_CHARS = 20_000
    private const val GMAIL_AUTHSERV_ID = "mx.google.com"
    private const val MAX_MIME_PARTS = 4_096

    fun headerValues(payload: JSONObject?): Map<String, List<String>> {
        val arr = payload?.optJSONArray("headers") ?: return emptyMap()
        val out = linkedMapOf<String, MutableList<String>>()
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            val name = h.optString("name").lowercase()
            if (name.isEmpty()) continue
            out.getOrPut(name) { mutableListOf() } += h.optString("value")
        }
        return out
    }

    fun first(headers: Map<String, List<String>>, name: String): String? =
        headers[name.lowercase()]?.firstOrNull()

    /** Display name from a From header, without quotes; informational only. */
    fun displayName(from: String?): String? {
        val raw = from?.trim() ?: return null
        val open = raw.lastIndexOf('<')
        if (open <= 0) return null
        return raw.substring(0, open).trim().trim('"').trim().take(80).ifBlank { null }
    }

    /**
     * Uses only the Authentication-Results header Gmail itself added (authserv-id
     * mx.google.com); a sender can forge any other copy.
     */
    fun senderAuthenticated(authResults: List<String>, senderDomain: String): Boolean? {
        val gmail = authResults.filter { it.substringBefore(';').trim().equals(GMAIL_AUTHSERV_ID, ignoreCase = true) }
        if (gmail.isEmpty()) return null
        val domain = senderDomain.lowercase()
        fun aligned(d: String): Boolean {
            val x = d.lowercase().trim().removePrefix("@").substringAfter('@')
            return x == domain || domain.endsWith(".$x")
        }
        for (h in gmail) {
            val v = h.lowercase()
            if (Regex("""\bdmarc=pass\b""").containsMatchIn(v)) return true
            Regex("""\bdkim=pass\b[^;]*?\bheader\.[id]=([^\s;]+)""").findAll(v).forEach {
                if (aligned(it.groupValues[1])) return true
            }
            Regex("""\bspf=pass\b[^;]*?\bsmtp\.mailfrom=([^\s;]+)""").findAll(v).forEach {
                if (aligned(it.groupValues[1])) return true
            }
        }
        return false
    }

    fun plainText(payload: JSONObject?): String {
        if (payload == null) return ""
        findPart(payload, "text/plain")?.let { return decodePart(it).trim() }
        findPart(payload, "text/html")?.let { return htmlToText(decodePart(it)) }
        return ""
    }

    private fun findPart(part: JSONObject, mime: String): JSONObject? {
        val pending = ArrayDeque<JSONObject>()
        pending.addLast(part)
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_MIME_PARTS) {
            val current = pending.removeLast()
            visited++
            val type = current.optString("mimeType").lowercase()
            val isAttachment = current.optString("filename").isNotBlank()
            if (type == mime && !isAttachment && current.optJSONObject("body")?.optString("data").orEmpty().isNotEmpty()) {
                return current
            }
            val parts: JSONArray = current.optJSONArray("parts") ?: continue
            val available = MAX_MIME_PARTS - visited - pending.size
            val childCount = minOf(parts.length(), available)
            for (i in childCount - 1 downTo 0) {
                parts.optJSONObject(i)?.let(pending::addLast)
            }
        }
        return null
    }

    private fun decodePart(part: JSONObject): String {
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        val bytes = runCatching { Base64.getUrlDecoder().decode(data.trim()) }.getOrElse {
            runCatching { Base64.getDecoder().decode(data.trim()) }.getOrDefault(ByteArray(0))
        }
        val contentType = first(headerValues(part), "content-type").orEmpty()
        val charsetName = Regex("""charset="?([A-Za-z0-9._\-]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        val charset = runCatching { charsetName?.let { Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("""(?is)<(script|style|head)\b.*?</\1\s*>"""), " ")
        s = s.replace(Regex("""(?i)<br\s*/?>|</(p|div|li|tr|h[1-6])\s*>"""), "\n")
        s = s.replace(Regex("""(?s)<[^>]*>"""), " ")
        s = decodeEntities(s)
        return s.lines().joinToString("\n") { it.replace(Regex("""[ \t\u00A0]+"""), " ").trim() }
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    fun decodeEntities(s: String): String = s
        .replace(Regex("""&#(\d{1,6});""")) { m -> m.groupValues[1].toIntOrNull()?.let { codePointString(it) } ?: m.value }
        .replace(Regex("""&#x([0-9a-fA-F]{1,6});""")) { m -> m.groupValues[1].toIntOrNull(16)?.let { codePointString(it) } ?: m.value }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&")

    private fun codePointString(cp: Int): String =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else ""

    /** Header values never carry CR/LF or other control characters. */
    fun headerSafe(value: String): String =
        value.replace(Regex("""[\u0000-\u001F\u007F]+"""), " ").replace(Regex("""\s{2,}"""), " ").trim()

    private val MSG_ID = Regex("""<[^<>\s@]+@[^<>\s]+>""")

    fun messageIdOrNull(value: String?): String? =
        value?.let { MSG_ID.find(headerSafe(it))?.value }

    fun referencesOrNull(references: String?, inReplyTo: String?): String? {
        val ids = MSG_ID.findAll(headerSafe(references.orEmpty())).map { it.value }.toMutableList()
        messageIdOrNull(inReplyTo)?.let { if (it !in ids) ids += it }
        return ids.takeLast(10).joinToString(" ").ifBlank { null }
    }

    private fun encodeHeaderWord(value: String): String =
        if (value.all { it.code in 32..126 }) value
        else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="

    /** RFC 5322 message, base64url, ready for users.messages.send `raw`. */
    fun buildRawReply(reply: OutgoingReply): String {
        val lines = mutableListOf<String>()
        lines += "To: ${reply.to.value}"
        lines += "Subject: ${encodeHeaderWord(headerSafe(reply.subject))}"
        messageIdOrNull(reply.inReplyTo)?.let { lines += "In-Reply-To: $it" }
        referencesOrNull(reply.references, reply.inReplyTo)?.let { lines += "References: $it" }
        lines += "$DRAFT_ID_HEADER: ${headerSafe(reply.draftId).filter { it.isLetterOrDigit() || it == '-' }}"
        lines += "MIME-Version: 1.0"
        lines += "Content-Type: text/plain; charset=\"UTF-8\""
        lines += "Content-Transfer-Encoding: base64"
        val body = Base64.getMimeEncoder(76, "\r\n".toByteArray())
            .encodeToString(reply.body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
        val message = lines.joinToString("\r\n") + "\r\n\r\n" + body + "\r\n"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray(Charsets.UTF_8))
    }

    const val DRAFT_ID_HEADER = "X-Strlix-Draft-Id"
}
