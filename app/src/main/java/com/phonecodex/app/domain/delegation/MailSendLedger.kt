package com.phonecodex.app.domain.delegation

enum class SendState {
    /** Written before the network call. Survives process death as UNCERTAIN. */
    SENDING,
    SENT,
    /** Definitely not sent (rejected before or by Gmail). A new confirmation may retry. */
    FAILED_NOT_SENT,
    /** The request may have reached Gmail. Never retried automatically. */
    UNCERTAIN
}

/** Delivery metadata only: no body text or body-derived hash is persisted. */
data class SendRecord(
    val draftId: String,
    val recipient: String,
    val attemptedAtMillis: Long,
    val state: SendState,
    val gmailMessageId: String? = null,
    val accountEmail: String? = null
)

interface SendRecordStore {
    fun load(): List<SendRecord>
    fun save(records: List<SendRecord>): Boolean
}

sealed class SendOutcome {
    data class Sent(val gmailMessageId: String?) : SendOutcome()
    data class NotSent(val reason: String) : SendOutcome()
    data class Uncertain(val reason: String) : SendOutcome()
}

sealed class BeginSend {
    data object Proceed : BeginSend()
    data class Refused(val existing: SendState) : BeginSend()
    data object StorageUnavailable : BeginSend()
}

/**
 * One-send-only bookkeeping. [begin] is the only door to the network: a draft that is
 * SENDING, SENT or UNCERTAIN can never start another attempt.
 */
class MailSendLedger(private val store: SendRecordStore) {

    private val lock get() = processLock

    fun begin(reply: OutgoingReply, nowMillis: Long, accountEmail: String? = null): BeginSend = synchronized(lock) {
        val records = runCatching { store.load() }.getOrElse { return BeginSend.StorageUnavailable }
        val existing = records.firstOrNull { it.draftId == reply.draftId }
        if (existing != null && existing.state != SendState.FAILED_NOT_SENT) {
            return BeginSend.Refused(existing.state)
        }
        val unresolvedForRecipient = records.firstOrNull {
            it.state in setOf(SendState.SENDING, SendState.UNCERTAIN) &&
                it.accountEmail?.trim()?.lowercase() == accountEmail?.trim()?.lowercase() &&
                EmailAddress.parseUserEntry(it.recipient)?.matchesExactly(reply.to) == true
        }
        if (unresolvedForRecipient != null) return BeginSend.Refused(unresolvedForRecipient.state)
        val record = SendRecord(
            draftId = reply.draftId,
            recipient = reply.to.value,
            attemptedAtMillis = nowMillis,
            state = SendState.SENDING,
            accountEmail = accountEmail?.trim()?.lowercase()?.ifBlank { null }
        )
        val saved = runCatching { store.save(records.filterNot { it.draftId == reply.draftId } + record) }
            .getOrDefault(false)
        if (!saved) return BeginSend.StorageUnavailable
        BeginSend.Proceed
    }

    fun complete(draftId: String, outcome: SendOutcome): SendState = synchronized(lock) {
        val state = when (outcome) {
            is SendOutcome.Sent -> SendState.SENT
            is SendOutcome.NotSent -> SendState.FAILED_NOT_SENT
            is SendOutcome.Uncertain -> SendState.UNCERTAIN
        }
        if (runCatching {
                update(draftId) { it.copy(state = state, gmailMessageId = (outcome as? SendOutcome.Sent)?.gmailMessageId) }
            }.getOrDefault(false)
        ) state else SendState.UNCERTAIN
    }

    /** After a restart, an attempt still marked SENDING may or may not have reached Gmail. */
    fun recoverAfterRestart(): List<SendRecord> = synchronized(lock) {
        val records = runCatching { store.load() }.getOrElse { return emptyList() }
        val recovered = records.map { if (it.state == SendState.SENDING) it.copy(state = SendState.UNCERTAIN) else it }
        if (recovered != records) runCatching { store.save(recovered) }
        recovered.filter { it.state == SendState.UNCERTAIN }
    }

    /**
     * Reconcile an uncertain send against the Sent folder. A positive match resolves it;
     * absence from search results is not proof that Gmail did not accept the send.
     */
    fun resolveUncertain(draftId: String, foundInSent: Boolean, sentMessageId: String? = null): SendState =
        synchronized(lock) {
            val record = runCatching { store.load().firstOrNull { it.draftId == draftId } }.getOrNull()
                ?: return SendState.UNCERTAIN
            if (record.state != SendState.UNCERTAIN) return record.state
            val next = when {
                foundInSent -> SendState.SENT
                else -> SendState.UNCERTAIN
            }
            if (runCatching {
                    update(draftId) { it.copy(state = next, gmailMessageId = sentMessageId ?: it.gmailMessageId) }
                }.getOrDefault(false)
            ) next else SendState.UNCERTAIN
        }

    fun stateOf(draftId: String): SendState? = synchronized(lock) {
        runCatching { store.load().firstOrNull { it.draftId == draftId }?.state }
            .getOrElse { SendState.UNCERTAIN }
    }

    fun record(draftId: String): SendRecord? = synchronized(lock) {
        runCatching { store.load().firstOrNull { it.draftId == draftId } }.getOrNull()
    }

    fun retainUnresolved(): Boolean = synchronized(lock) {
        val records = runCatching { store.load() }.getOrNull() ?: return false
        runCatching { store.save(records.filter { it.state == SendState.SENDING || it.state == SendState.UNCERTAIN }) }
            .getOrDefault(false)
    }

    fun clear(): Boolean = synchronized(lock) { runCatching { store.save(emptyList()) }.getOrDefault(false) }

    private fun update(draftId: String, change: (SendRecord) -> SendRecord): Boolean {
        val records = store.load()
        if (records.none { it.draftId == draftId }) return false
        return store.save(records.map { if (it.draftId == draftId) change(it) else it })
    }

    companion object {
        private val processLock = Any()
    }
}
