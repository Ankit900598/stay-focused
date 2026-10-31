package com.phonecodex.app.mailtask

import com.phonecodex.app.connector.MailHeader
import com.phonecodex.app.domain.delegation.MailDelegationGrant
import com.phonecodex.app.domain.delegation.OutgoingReply

/**
 * Builds the only reply shape Strlix can send. The recipient is the grant's confirmed
 * address: never the message's Reply-To, never anything in the body or the AI draft.
 */
object ReplyComposer {

    const val MAX_BODY_CHARS = 5_000

    fun compose(draftId: String, grant: MailDelegationGrant, original: MailHeader, body: String): OutgoingReply =
        OutgoingReply(
            draftId = draftId,
            to = grant.senderAddress,
            subject = replySubject(original.subject),
            body = body.take(MAX_BODY_CHARS),
            threadId = original.threadId,
            inReplyTo = original.rfcMessageId,
            references = original.references
        )

    fun replySubject(original: String): String {
        val clean = original.replace(Regex("""[\u0000-\u001F\u007F]+"""), " ").replace(Regex("""\s{2,}"""), " ").trim()
            .ifBlank { "(no subject)" }
        return (if (clean.startsWith("re:", ignoreCase = true)) clean else "Re: $clean").take(200)
    }

    /** AI drafts are body text only; header-looking lines at the top are dropped. */
    fun cleanAiDraft(text: String): String {
        val lines = text.replace("\r\n", "\n").lines().toMutableList()
        while (lines.isNotEmpty() &&
            (lines.first().isBlank() || HEADER_LINE.containsMatchIn(lines.first()))
        ) {
            lines.removeAt(0)
        }
        return lines.joinToString("\n").trim().take(MAX_BODY_CHARS)
    }

    private val HEADER_LINE = Regex("""^\s*(to|cc|bcc|from|subject|reply-to)\s*:""", RegexOption.IGNORE_CASE)
}
