package com.phonecodex.app.domain.delegation

import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import java.security.MessageDigest

enum class MailDenial {
    NO_ACTIVE_COMMITMENT,
    COMMITMENT_ENDED,
    GRANT_FOR_OTHER_COMMITMENT,
    NO_GRANT,
    ACCOUNT_NOT_LINKED,
    SENT_RECORD_ACCOUNT_MISMATCH,
    WRONG_SENDER,
    AI_CONSENT_MISSING,
    SEND_NOT_ALLOWED,
    RECIPIENT_NOT_CONFIRMED_SENDER,
    NOT_CONFIRMED,
    CONFIRMATION_STALE,
    TEXT_CHANGED_AFTER_CONFIRMATION,
    EMPTY_TEXT
}

sealed class MailAuthorization {
    data object Allowed : MailAuthorization()
    data class Denied(val reason: MailDenial) : MailAuthorization()

    val allowed: Boolean get() = this is Allowed
}

/** A reply Strlix may send. [to] always comes from the grant, never from mail or AI output. */
data class OutgoingReply(
    val draftId: String,
    val to: EmailAddress,
    val subject: String,
    val body: String,
    val threadId: String?,
    val inReplyTo: String?,
    val references: String?
) {
    val bodySha256: String get() = sha256(body)
}

/** What the user saw and approved on the confirm step. */
data class SendConfirmation(
    val draftId: String,
    val recipient: EmailAddress,
    val subject: String,
    val bodySha256: String,
    val confirmedAtMillis: Long
) {
    companion object {
        fun of(reply: OutgoingReply, nowMillis: Long) = SendConfirmation(
            draftId = reply.draftId,
            recipient = reply.to,
            subject = reply.subject,
            bodySha256 = reply.bodySha256,
            confirmedAtMillis = nowMillis
        )
    }
}

/**
 * The law for delegated mail actions. Pure: every connector call and every send passes
 * through here first. Mail content and AI output have no input into these decisions except
 * the parsed sender address, which must match the grant exactly.
 */
object MailDelegationPolicy {

    const val CONFIRMATION_TTL_MS = 10 * 60 * 1000L

    fun authorizeCheck(grant: MailDelegationGrant?, session: FocusSession?, nowMillis: Long): MailAuthorization =
        sessionGate(grant, session, nowMillis)
            ?: if (grant!!.accountEmail.isNullOrBlank()) deny(MailDenial.ACCOUNT_NOT_LINKED) else MailAuthorization.Allowed

    fun authorizeRead(
        grant: MailDelegationGrant?,
        session: FocusSession?,
        nowMillis: Long,
        messageSender: EmailAddress?
    ): MailAuthorization {
        authorizeCheck(grant, session, nowMillis).let { if (!it.allowed) return it }
        return if (grant!!.senderAddress.matchesExactly(messageSender)) MailAuthorization.Allowed
        else deny(MailDenial.WRONG_SENDER)
    }

    fun authorizeSentReconciliation(
        grant: MailDelegationGrant?,
        session: FocusSession?,
        nowMillis: Long,
        record: SendRecord
    ): MailAuthorization {
        authorizeCheck(grant, session, nowMillis).let { if (!it.allowed) return it }
        val g = grant!!
        val recipient = EmailAddress.parseUserEntry(record.recipient)
        if (!g.senderAddress.matchesExactly(recipient)) return deny(MailDenial.WRONG_SENDER)
        if (record.accountEmail.isNullOrBlank() || !record.accountEmail.equals(g.accountEmail, ignoreCase = true)) {
            return deny(MailDenial.SENT_RECORD_ACCOUNT_MISMATCH)
        }
        return MailAuthorization.Allowed
    }

    fun authorizeAiAssist(
        grant: MailDelegationGrant?,
        session: FocusSession?,
        nowMillis: Long,
        messageSender: EmailAddress?
    ): MailAuthorization {
        authorizeRead(grant, session, nowMillis, messageSender).let { if (!it.allowed) return it }
        return if (grant!!.aiAssistConsent) MailAuthorization.Allowed else deny(MailDenial.AI_CONSENT_MISSING)
    }

    fun authorizeSend(
        grant: MailDelegationGrant?,
        session: FocusSession?,
        nowMillis: Long,
        reply: OutgoingReply,
        confirmation: SendConfirmation?
    ): MailAuthorization {
        authorizeCheck(grant, session, nowMillis).let { if (!it.allowed) return it }
        val g = grant!!
        if (!g.allowSend) return deny(MailDenial.SEND_NOT_ALLOWED)
        if (!g.senderAddress.matchesExactly(reply.to)) return deny(MailDenial.RECIPIENT_NOT_CONFIRMED_SENDER)
        if (reply.body.isBlank()) return deny(MailDenial.EMPTY_TEXT)
        val c = confirmation ?: return deny(MailDenial.NOT_CONFIRMED)
        if (c.draftId != reply.draftId || !c.recipient.matchesExactly(reply.to)) return deny(MailDenial.NOT_CONFIRMED)
        if (c.subject != reply.subject || c.bodySha256 != reply.bodySha256) {
            return deny(MailDenial.TEXT_CHANGED_AFTER_CONFIRMATION)
        }
        if (nowMillis - c.confirmedAtMillis > CONFIRMATION_TTL_MS || nowMillis < c.confirmedAtMillis) {
            return deny(MailDenial.CONFIRMATION_STALE)
        }
        return MailAuthorization.Allowed
    }

    /** A grant is valid only while the exact commitment it was confirmed with is running. */
    fun grantIsLive(grant: MailDelegationGrant?, session: FocusSession?, nowMillis: Long): Boolean =
        sessionGate(grant, session, nowMillis) == null

    private fun sessionGate(grant: MailDelegationGrant?, session: FocusSession?, nowMillis: Long): MailAuthorization? {
        if (session == null) return deny(MailDenial.NO_ACTIVE_COMMITMENT)
        if (session.status != SessionStatus.ACTIVE && session.status != SessionStatus.LOCKED) {
            return deny(MailDenial.COMMITMENT_ENDED)
        }
        if (nowMillis >= session.deadlineMillis) return deny(MailDenial.COMMITMENT_ENDED)
        if (grant == null) return deny(MailDenial.NO_GRANT)
        if (grant.sessionId != session.id) return deny(MailDenial.GRANT_FOR_OTHER_COMMITMENT)
        return null
    }

    private fun deny(reason: MailDenial) = MailAuthorization.Denied(reason)
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
