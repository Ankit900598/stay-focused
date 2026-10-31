package com.phonecodex.app.mailtask

import androidx.compose.runtime.Immutable
import com.phonecodex.app.domain.delegation.SendState

/**
 * One turn in the mail task thread. Held in memory only: turns carry mail content and are
 * never persisted or logged.
 */
@Immutable
sealed class MailTurn(open val id: Long) {
    data class UserSaid(override val id: Long, val text: String) : MailTurn(id)

    data class Note(override val id: Long, val text: String, val tone: Tone = Tone.NEUTRAL) : MailTurn(id)

    data class MessageList(
        override val id: Long,
        val sender: String,
        val items: List<Item>,
        val droppedNotExactSender: Int
    ) : MailTurn(id) {
        data class Item(
            val messageId: String,
            val subject: String,
            val snippet: String,
            val receivedAtMillis: Long,
            val senderAuthenticated: Boolean?
        )
    }

    data class MessageText(override val id: Long, val messageId: String, val subject: String, val text: String, val truncated: Boolean) :
        MailTurn(id)

    data class Summary(
        override val id: Long,
        val messageId: String,
        val subject: String,
        val summary: MailSummary
    ) : MailTurn(id)

    /** Asks for AI consent before the first summary/draft when it was off at Start. */
    data class ConsentRequest(override val id: Long, val messageId: String, val purpose: AiPurpose) : MailTurn(id)

    data class Draft(
        override val id: Long,
        val draftId: String,
        val messageId: String,
        val to: String,
        val subject: String,
        val body: String,
        val editable: Boolean
    ) : MailTurn(id)

    data class ConfirmSend(override val id: Long, val draftId: String, val to: String, val subject: String, val body: String) :
        MailTurn(id)

    data class SendResult(
        override val id: Long,
        val draftId: String,
        val state: SendState,
        val text: String,
        val canCheckSent: Boolean = false
    ) : MailTurn(id)

    enum class Tone { NEUTRAL, SUCCESS, WARNING, REFUSAL }
}

enum class AiPurpose { SUMMARY, REPLY }

@Immutable
data class MailThreadState(
    val sender: String,
    val linkedAccount: String?,
    val aiConsent: Boolean,
    val turns: List<MailTurn>,
    val busyLabel: String?,
    val pendingConfirmationDraftId: String?
) {
    val busy: Boolean get() = busyLabel != null
}
