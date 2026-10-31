package com.phonecodex.app.domain.delegation

/**
 * The single exception the user confirmed together with the commitment: Strlix may read
 * mail from exactly [senderAddress] and send only to that address, for this session only.
 * It never changes the commitment and never opens the Gmail app.
 */
data class MailDelegationGrant(
    val sessionId: String,
    val senderAddress: EmailAddress,
    /** Conspicuous consent: the one message the user picks may go to the AI to summarize/draft. */
    val aiAssistConsent: Boolean,
    val allowSend: Boolean,
    val createdAtMillis: Long,
    /** Google account the user linked; every token must belong to it. */
    val accountEmail: String? = null
) {
    /** This version only checks when asked. */
    val proactiveAlerts: Boolean get() = false
}
