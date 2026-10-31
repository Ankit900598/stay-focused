package com.phonecodex.app.mailtask

import com.phonecodex.app.connector.ConnectorFailure
import com.phonecodex.app.domain.delegation.MailDenial

/** User-facing copy for refusals and failures. Never includes mail content. */
internal object MailCopy {

    fun denial(reason: MailDenial, sender: String): String = when (reason) {
        MailDenial.NO_ACTIVE_COMMITMENT, MailDenial.COMMITMENT_ENDED ->
            "Your commitment has ended, so the mail helper is off. Gmail follows your normal rules again."
        MailDenial.GRANT_FOR_OTHER_COMMITMENT, MailDenial.NO_GRANT ->
            "This commitment has no mail helper. Start a new promise to add one."
        MailDenial.ACCOUNT_NOT_LINKED -> "Connect your Google account first."
        MailDenial.SENT_RECORD_ACCOUNT_MISMATCH ->
            "This uncertain reply was approved with a different Google account. I won't search this account's Sent folder."
        MailDenial.WRONG_SENDER -> "That message is not from exactly $sender, so I won't open it."
        MailDenial.AI_CONSENT_MISSING -> "AI help is off for this commitment."
        MailDenial.SEND_NOT_ALLOWED -> "Sending wasn't part of this commitment. I can prepare a draft, but I can't send it."
        MailDenial.RECIPIENT_NOT_CONFIRMED_SENDER -> "I can only send to $sender."
        MailDenial.NOT_CONFIRMED -> "Review the exact recipient and text, then tap Send."
        MailDenial.CONFIRMATION_STALE -> "That approval expired. Review the reply again, then tap Send."
        MailDenial.TEXT_CHANGED_AFTER_CONFIRMATION -> "The reply changed after you approved it. Approve the new text first."
        MailDenial.EMPTY_TEXT -> "The reply is empty."
    }

    fun connector(kind: ConnectorFailure, sender: String, account: String?): String = when (kind) {
        ConnectorFailure.OFFLINE -> "I couldn't reach Gmail. Check your connection and try again."
        ConnectorFailure.AUTH_REVOKED ->
            "Google access for ${account ?: "your account"} was revoked or expired. Reconnect to continue."
        ConnectorFailure.INSUFFICIENT_SCOPE -> "Google didn't grant the access this step needs. Reconnect and allow it."
        ConnectorFailure.WRONG_ACCOUNT ->
            "Google gave access for a different account than ${account ?: "the one you linked"}. Reconnect with that account."
        ConnectorFailure.SENDER_MISMATCH -> "That message is not from exactly $sender, so I won't open it."
        ConnectorFailure.RATE_LIMITED -> "Gmail asked me to slow down. Try again in a minute."
        ConnectorFailure.NOT_FOUND -> "That message is no longer in your mailbox."
        ConnectorFailure.SERVER_ERROR -> "Gmail had a problem. Try again in a minute."
        ConnectorFailure.MALFORMED -> "Gmail sent a response I couldn't read. Try again."
    }

    fun assist(kind: AssistFailure): String = when (kind) {
        AssistFailure.OFFLINE -> "I couldn't reach Strlix's AI. Check your connection and try again."
        AssistFailure.NOT_SIGNED_IN -> "Sign in with Google again to use AI help."
        AssistFailure.CONSENT_REQUIRED -> "AI help needs your consent first."
        AssistFailure.UNAVAILABLE -> "AI help isn't available on this server yet. You can still read the message and write the reply yourself."
        AssistFailure.RATE_LIMITED -> "AI help is busy. Try again in a minute."
        AssistFailure.FAILED -> "AI help failed this time. You can read the message or write the reply yourself."
    }

    fun token(result: TokenResult): String = when (result) {
        TokenResult.NotConfigured -> "Gmail access isn't set up in this build yet."
        TokenResult.Declined -> "Google access wasn't granted, so I didn't touch your mail."
        TokenResult.Offline -> "You're offline. I'll need a connection to reach Gmail."
        TokenResult.Failed -> "Google sign-in failed. Try again."
        is TokenResult.Ok -> ""
    }

    const val POLICY_REFUSAL =
        "I can't change your commitment from here. Gmail stays closed until it ends. " +
            "To end it, use End Commitment on the card above."
    const val HELP = "Try: “check”, “summarize the first one”, “show the second one”, or “reply saying I'll submit by Friday”."
    const val PROACTIVE_LIMIT = "I check only when you ask. This version does not alert you when mail arrives."
    const val AI_DATA_HANDLING =
        "The selected email is sent to Strlix's server and Microsoft Azure OpenAI for this request. " +
            "Strlix's mail-assist code does not persist or log the email body. Azure's default abuse monitoring may retain content flagged for review unless this resource is approved for modified monitoring; Strlix has not verified that setting. " +
            "Do not use AI with sensitive mail unless you are comfortable with this processing."
}
