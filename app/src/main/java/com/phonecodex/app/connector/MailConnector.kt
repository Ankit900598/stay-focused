package com.phonecodex.app.connector

import com.phonecodex.app.domain.delegation.EmailAddress
import com.phonecodex.app.domain.delegation.OutgoingReply
import com.phonecodex.app.domain.delegation.SendOutcome
import com.phonecodex.app.domain.delegation.SendRecord

/**
 * Capability boundary for a delegated mail connector. Only what the confirmed task needs:
 * list one sender, read one message, send one reply, reconcile one uncertain send.
 * No labels, no delete, no archive, no other senders. One real implementation: Gmail REST.
 */
interface MailConnector {
    suspend fun verifyAccount(token: AccessToken, expectedAccount: String): ConnectorResult<Unit>

    suspend fun listFromExactSender(
        token: AccessToken,
        sender: EmailAddress,
        newerThanDays: Int,
        max: Int
    ): ConnectorResult<MailList>

    suspend fun readMessage(token: AccessToken, messageId: String, expectedSender: EmailAddress): ConnectorResult<MailMessage>

    suspend fun send(token: AccessToken, reply: OutgoingReply): SendOutcome

    /** Gmail id of the sent copy of [record], or null if it is not in Sent. */
    suspend fun findSentCopy(token: AccessToken, record: SendRecord): ConnectorResult<String?>
}

/** A short-lived OAuth access token held in memory only. */
data class AccessToken(val value: String, val grantedScopes: Set<String>, val account: String) {
    override fun toString(): String = "AccessToken(account=$account, scopes=$grantedScopes)"
}

data class MailHeader(
    val id: String,
    val threadId: String?,
    val from: EmailAddress,
    val fromDisplayName: String?,
    val subject: String,
    val internalDateMillis: Long,
    val snippet: String,
    /** true/false from Gmail's own Authentication-Results; null when Gmail recorded none. */
    val senderAuthenticated: Boolean?,
    val rfcMessageId: String?,
    val references: String?
)

data class MailList(
    val items: List<MailHeader>,
    /** Messages Gmail's fuzzy `from:` search returned that were not exactly the sender. */
    val droppedNotExactSender: Int
)

data class MailMessage(val header: MailHeader, val bodyText: String, val truncated: Boolean)

enum class ConnectorFailure {
    OFFLINE,
    AUTH_REVOKED,
    INSUFFICIENT_SCOPE,
    WRONG_ACCOUNT,
    SENDER_MISMATCH,
    RATE_LIMITED,
    NOT_FOUND,
    SERVER_ERROR,
    MALFORMED
}

sealed class ConnectorResult<out T> {
    data class Ok<T>(val value: T) : ConnectorResult<T>()
    data class Failure(val kind: ConnectorFailure) : ConnectorResult<Nothing>()
}
