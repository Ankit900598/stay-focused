package com.phonecodex.app.mailtask

import com.phonecodex.app.domain.delegation.EmailAddress

sealed class MailTaskCommand {
    data object Check : MailTaskCommand()
    data class ShowText(val index: Int) : MailTaskCommand()
    data class Summarize(val index: Int) : MailTaskCommand()
    data class Reply(val index: Int, val note: String) : MailTaskCommand()
    /** Anything that would change, pause, end or widen the commitment. Always refused here. */
    data object PolicyChangeRefused : MailTaskCommand()
    data class OtherSenderRefused(val address: String) : MailTaskCommand()
    data object Help : MailTaskCommand()
}

/**
 * Deterministic parser for what the user types in the task thread. Typed text can only
 * pick one of the confirmed task's actions; it can never reach the commitment.
 */
object MailTaskCommandParser {

    private val POLICY_CHANGE = Regex(
        """\b(?:""" +
            """unblock|unlock|open\s+(?:the\s+)?gmail|allow\s+(?:me\s+)?gmail|let\s+me\s+(?:open|use|into)\s+gmail|""" +
            """(?:end|stop|cancel|pause|quit|finish|break)\s+(?:the\s+|my\s+|this\s+)?(?:commitment|promise|session|block(?:ing)?)|""" +
            """turn\s+off|disable|extend|remove\s+(?:the\s+)?block|give\s+me\s+(?:gmail|access)|""" +
            """add\s+(?:another|a\s+new|more)\s+(?:sender|person|contact)|change\s+(?:the\s+)?(?:sender|address|recipient)""" +
            """)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ADDRESS = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val SUMMARIZE = Regex("""\bsummar|\btl;?dr\b|\bgist\b""", RegexOption.IGNORE_CASE)
    private val REPLY = Regex("""\b(reply|respond|answer|draft|write\s+back)\b""", RegexOption.IGNORE_CASE)
    private val SHOW = Regex("""\b(show|read|full\s+text|whole\s+(?:email|message|mail))\b""", RegexOption.IGNORE_CASE)
    private val CHECK = Regex("""\b(check|any|new|refresh|messages?|mails?|e-?mails?|inbox|anything|update)\b""", RegexOption.IGNORE_CASE)

    fun parse(raw: String, confirmedSender: EmailAddress): MailTaskCommand {
        val text = raw.trim()
        if (text.isEmpty()) return MailTaskCommand.Help
        if (POLICY_CHANGE.containsMatchIn(text)) return MailTaskCommand.PolicyChangeRefused
        ADDRESS.findAll(text).map { it.value }.firstOrNull { typed ->
            !confirmedSender.matchesExactly(EmailAddress.parseUserEntry(typed))
        }?.let { return MailTaskCommand.OtherSenderRefused(it) }
        val index = ordinal(text)
        return when {
            REPLY.containsMatchIn(text) -> MailTaskCommand.Reply(index, text.take(MAX_NOTE_CHARS))
            SUMMARIZE.containsMatchIn(text) -> MailTaskCommand.Summarize(index)
            SHOW.containsMatchIn(text) -> MailTaskCommand.ShowText(index)
            CHECK.containsMatchIn(text) -> MailTaskCommand.Check
            else -> MailTaskCommand.Help
        }
    }

    private fun ordinal(text: String): Int {
        val t = text.lowercase()
        Regex("""#?\b(\d{1,2})\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 1..25) return it - 1 }
        return when {
            Regex("""\bsecond\b|\b2nd\b""").containsMatchIn(t) -> 1
            Regex("""\bthird\b|\b3rd\b""").containsMatchIn(t) -> 2
            else -> 0
        }
    }

    const val MAX_NOTE_CHARS = 500
}
