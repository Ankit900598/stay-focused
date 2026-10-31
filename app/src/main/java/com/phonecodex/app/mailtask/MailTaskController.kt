package com.phonecodex.app.mailtask

import com.phonecodex.app.connector.AccessToken
import com.phonecodex.app.connector.ConnectorFailure
import com.phonecodex.app.connector.ConnectorResult
import com.phonecodex.app.connector.MailConnector
import com.phonecodex.app.connector.MailHeader
import com.phonecodex.app.connector.MailMessage
import com.phonecodex.app.domain.delegation.BeginSend
import com.phonecodex.app.domain.delegation.MailAuthorization
import com.phonecodex.app.domain.delegation.MailDelegationGrant
import com.phonecodex.app.domain.delegation.MailDelegationPolicy
import com.phonecodex.app.domain.delegation.MailDenial
import com.phonecodex.app.domain.delegation.MailSendLedger
import com.phonecodex.app.domain.delegation.OutgoingReply
import com.phonecodex.app.domain.delegation.SendConfirmation
import com.phonecodex.app.domain.delegation.SendOutcome
import com.phonecodex.app.domain.delegation.SendState
import com.phonecodex.app.domain.model.FocusSession
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Runs the confirmed mail task: check one sender, show/summarize one message, draft a
 * reply, send it once after exact approval. Every step passes [MailDelegationPolicy] first
 * and again immediately before a send. Email bodies and AI output are data: nothing in
 * them selects an action, a message, or a recipient.
 *
 * Call from one thread (the UI's main dispatcher). A step already running makes new taps
 * no-ops; [MailSendLedger] additionally refuses a second send of the same draft.
 */
class MailTaskController(
    private val grantSource: () -> MailDelegationGrant?,
    private val sessionSource: () -> FocusSession?,
    private val saveGrant: (MailDelegationGrant) -> Boolean,
    private val connector: MailConnector,
    private val authorizer: MailAuthorizer,
    private val assistant: MailAssistant,
    private val ledger: MailSendLedger,
    private val isOnline: () -> Boolean,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val newDraftId: () -> String = { UUID.randomUUID().toString() },
    private val onState: (MailThreadState) -> Unit = {}
) {
    private var nextTurnId = 1L
    private var operationEpoch = 0L
    private var grantUnavailableForSession = false
    private val headers = linkedMapOf<String, MailHeader>()
    private val messages = linkedMapOf<String, MailMessage>()
    private val drafts = linkedMapOf<String, OutgoingReply>()
    private var confirmation: SendConfirmation? = null

    var state: MailThreadState = emptyState()
        private set

    private fun emptyState(): MailThreadState {
        val g = currentGrant()
        return MailThreadState(
            sender = g?.senderAddress?.value.orEmpty(),
            linkedAccount = g?.accountEmail,
            aiConsent = g?.aiAssistConsent == true,
            turns = emptyList(),
            busyLabel = null,
            pendingConfirmationDraftId = null
        )
    }

    /** Call once when the thread appears. Surfaces sends a previous process left unresolved. */
    fun start() {
        val g = currentGrant() ?: return
        state = state.copy(sender = g.senderAddress.value, linkedAccount = g.accountEmail, aiConsent = g.aiAssistConsent)
        if (state.turns.isEmpty()) {
            add(MailTurn.Note(0, "I'll read mail only from ${g.senderAddress.value}. ${MailCopy.PROACTIVE_LIMIT}"))
        }
        ledger.recoverAfterRestart().forEach { record ->
            add(
                MailTurn.SendResult(
                    0, record.draftId, SendState.UNCERTAIN,
                    "A reply you approved earlier may or may not have been sent. Check your Sent folder before sending again.",
                    canCheckSent = !g.accountEmail.isNullOrBlank() &&
                        record.accountEmail.equals(g.accountEmail, ignoreCase = true)
                )
            )
        }
        publish()
    }

    fun onAccountLinked(email: String) {
        val g = currentGrant() ?: return
        val normalized = email.trim().lowercase()
        if (normalized.isEmpty()) return
        val accountChanged = !g.accountEmail.isNullOrBlank() && g.accountEmail != normalized
        if (accountChanged) {
            invalidateOperations()
            authorizer.clear()
            clearAccountThread(normalized)
        }
        val updatedGrant = g.copy(
            accountEmail = normalized,
            aiAssistConsent = if (accountChanged) false else g.aiAssistConsent,
            allowSend = if (accountChanged) false else g.allowSend
        )
        if (!saveGrant(updatedGrant)) {
            invalidateOperations()
            grantUnavailableForSession = true
            authorizer.clear()
            state = state.copy(linkedAccount = null, aiConsent = false)
            note("I couldn't save this account change securely. Mail access is disabled for this commitment; start a new promise before trying again.", MailTurn.Tone.WARNING)
            publish()
            return
        }
        state = state.copy(linkedAccount = normalized, aiConsent = updatedGrant.aiAssistConsent)
        refreshUncertainSendActions(normalized)
        add(
            MailTurn.Note(
                0,
                if (accountChanged) "Connected as $normalized. AI and sending consent were reset for this account; create a new mail commitment to enable either."
                else "Connected as $normalized.",
                if (accountChanged) MailTurn.Tone.WARNING else MailTurn.Tone.SUCCESS
            )
        )
        publish()
    }

    fun enableAiConsent() {
        val g = currentGrant() ?: return
        if (!MailDelegationPolicy.grantIsLive(g, sessionSource(), clock())) return
        if (!saveGrant(g.copy(aiAssistConsent = true))) {
            note("I couldn't save your AI consent securely, so I won't send message content to AI. Try again.", MailTurn.Tone.WARNING)
            publish()
            return
        }
        state = state.copy(aiConsent = true)
        log("MAIL_TASK action=AI_CONSENT outcome=GRANTED")
        publish()
    }

    suspend fun submitTyped(text: String) {
        val g = currentGrant() ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        add(MailTurn.UserSaid(0, trimmed.take(500)))
        when (val cmd = MailTaskCommandParser.parse(trimmed, g.senderAddress)) {
            MailTaskCommand.Check -> check(echo = false)
            is MailTaskCommand.ShowText -> itemAt(cmd.index)?.let { showText(it, echo = false) } ?: noListYet()
            is MailTaskCommand.Summarize -> itemAt(cmd.index)?.let { summarize(it, echo = false) } ?: noListYet()
            is MailTaskCommand.Reply -> itemAt(cmd.index)?.let { draftReply(it, cmd.note, echo = false) } ?: noListYet()
            MailTaskCommand.PolicyChangeRefused -> {
                log("MAIL_TASK action=TYPED outcome=REFUSED_POLICY_CHANGE")
                note(MailCopy.POLICY_REFUSAL, MailTurn.Tone.REFUSAL)
            }
            is MailTaskCommand.OtherSenderRefused -> {
                log("MAIL_TASK action=TYPED outcome=REFUSED_OTHER_SENDER")
                note(
                    "This commitment lets me read and reply only to ${g.senderAddress.value}. " +
                        "Other senders stay closed until it ends.",
                    MailTurn.Tone.REFUSAL
                )
            }
            MailTaskCommand.Help -> note(MailCopy.HELP)
        }
    }

    suspend fun check(echo: Boolean = true) = step("Checking Gmail…") { epoch ->
        val g = currentGrant()
        if (echo) add(MailTurn.UserSaid(0, "Check messages from ${g?.senderAddress?.value.orEmpty()}"))
        if (!authorized(MailDelegationPolicy.authorizeCheck(g, sessionSource(), clock()), g)) return@step
        val grant = g!!
        val token = readToken(grant, epoch) ?: return@step
        if (!operationIsCurrent(epoch, grant)) return@step
        val listed = withTokenRetry(grant, token, epoch) { t ->
            connector.listFromExactSender(t, grant.senderAddress, NEWER_THAN_DAYS, MAX_RESULTS)
        }?.value ?: return@step
        if (!operationIsCurrent(epoch, grant)) return@step
        listed.items.forEach { headers[it.id] = it }
        log("MAIL_TASK action=CHECK outcome=OK count=${listed.items.size} dropped=${listed.droppedNotExactSender}")
        if (listed.items.isEmpty()) {
            note("No messages from exactly ${grant.senderAddress.value} in the last $NEWER_THAN_DAYS days.")
        } else {
            add(
                MailTurn.MessageList(
                    0, grant.senderAddress.value,
                    listed.items.map {
                        MailTurn.MessageList.Item(it.id, it.subject.ifBlank { "(no subject)" }, it.snippet, it.internalDateMillis, it.senderAuthenticated)
                    },
                    listed.droppedNotExactSender
                )
            )
        }
        if (listed.droppedNotExactSender > 0) {
            note(
                "Gmail also matched ${listed.droppedNotExactSender} message(s) from other or lookalike addresses. " +
                    "I hid them because they are not exactly ${grant.senderAddress.value}.",
                MailTurn.Tone.WARNING
            )
        }
    }

    suspend fun showText(messageId: String, echo: Boolean = true) = step("Opening the message…") { epoch ->
        if (echo) add(MailTurn.UserSaid(0, "Show the full text"))
        val grant = currentGrant() ?: return@step
        val message = loadMessage(messageId, epoch, grant) ?: return@step
        if (!operationIsCurrent(epoch, grant)) return@step
        log("MAIL_TASK action=SHOW outcome=OK")
        add(MailTurn.MessageText(0, messageId, message.header.subject, message.bodyText.take(DISPLAY_CHARS), message.truncated || message.bodyText.length > DISPLAY_CHARS))
    }

    suspend fun summarize(messageId: String, echo: Boolean = true) = step("Summarizing…") { epoch ->
        if (echo) add(MailTurn.UserSaid(0, "Summarize this message"))
        val header = headers[messageId] ?: return@step noListYet()
        val g = currentGrant()
        val auth = MailDelegationPolicy.authorizeAiAssist(g, sessionSource(), clock(), header.from)
        if (auth is MailAuthorization.Denied && auth.reason == MailDenial.AI_CONSENT_MISSING) {
            add(MailTurn.ConsentRequest(0, messageId, AiPurpose.SUMMARY))
            return@step
        }
        if (!authorized(auth, g)) return@step
        val grant = g!!
        val message = loadMessage(messageId, epoch, grant) ?: return@step
        if (!operationIsCurrent(epoch, grant)) return@step
        val identity = authorizer.identityToken(grant.accountEmail!!)
        if (!operationIsCurrent(epoch, grant)) return@step
        if (identity == null) return@step note(MailCopy.assist(AssistFailure.NOT_SIGNED_IN))
        if (!isOnline()) return@step note(MailCopy.assist(AssistFailure.OFFLINE))
        if (!authorized(MailDelegationPolicy.authorizeAiAssist(currentGrant(), sessionSource(), clock(), header.from), currentGrant())) return@step
        when (val r = assistant.summarize(identity, message)) {
            is AssistResult.Ok -> {
                if (!operationIsCurrent(epoch, grant)) return@step
                log("MAIL_TASK action=SUMMARIZE outcome=OK flagged=${r.value.containsInstructionsToAssistant}")
                add(MailTurn.Summary(0, messageId, message.header.subject, r.value))
            }
            is AssistResult.Failure -> {
                if (!operationIsCurrent(epoch, grant)) return@step
                log("MAIL_TASK action=SUMMARIZE outcome=${r.kind}")
                note(MailCopy.assist(r.kind), MailTurn.Tone.WARNING)
            }
        }
    }

    suspend fun draftReply(messageId: String, userNote: String, echo: Boolean = true) = step("Preparing a reply…") { epoch ->
        if (echo && userNote.isNotBlank()) add(MailTurn.UserSaid(0, userNote.take(500)))
        val header = headers[messageId] ?: return@step noListYet()
        val g = currentGrant()
        val auth = MailDelegationPolicy.authorizeAiAssist(g, sessionSource(), clock(), header.from)
        if (auth is MailAuthorization.Denied && auth.reason == MailDenial.AI_CONSENT_MISSING) {
            add(MailTurn.ConsentRequest(0, messageId, AiPurpose.REPLY))
            return@step
        }
        if (!authorized(auth, g)) return@step
        val grant = g!!
        val message = loadMessage(messageId, epoch, grant) ?: return@step
        if (!operationIsCurrent(epoch, grant)) return@step
        val identity = authorizer.identityToken(grant.accountEmail!!)
        if (!operationIsCurrent(epoch, grant)) return@step
        if (identity == null) return@step note(MailCopy.assist(AssistFailure.NOT_SIGNED_IN))
        if (!isOnline()) return@step note(MailCopy.assist(AssistFailure.OFFLINE))
        if (!authorized(MailDelegationPolicy.authorizeAiAssist(currentGrant(), sessionSource(), clock(), header.from), currentGrant())) return@step
        when (val r = assistant.draftReply(identity, message, userNote.take(MailTaskCommandParser.MAX_NOTE_CHARS))) {
            is AssistResult.Ok -> {
                if (!operationIsCurrent(epoch, grant)) return@step
                log("MAIL_TASK action=DRAFT outcome=OK")
                addDraft(grant, header, ReplyComposer.cleanAiDraft(r.value))
            }
            is AssistResult.Failure -> {
                if (!operationIsCurrent(epoch, grant)) return@step
                log("MAIL_TASK action=DRAFT outcome=${r.kind}")
                note(MailCopy.assist(r.kind), MailTurn.Tone.WARNING)
            }
        }
    }

    /** A blank draft the user writes; no AI involved. */
    fun writeReplyMyself(messageId: String) {
        if (state.busy) return
        val g = currentGrant()
        val header = headers[messageId] ?: return noListYet().also { publish() }
        val auth = MailDelegationPolicy.authorizeRead(g, sessionSource(), clock(), header.from)
        if (!authorized(auth, g)) return publish()
        if (g == null) return publish()
        addDraft(g, header, "")
        publish()
    }

    fun editDraft(draftId: String, body: String) {
        val reply = drafts[draftId] ?: return
        if (ledger.stateOf(draftId).let { it == SendState.SENT || it == SendState.SENDING || it == SendState.UNCERTAIN }) return
        val updated = reply.copy(body = body.take(ReplyComposer.MAX_BODY_CHARS))
        drafts[draftId] = updated
        if (confirmation?.draftId == draftId) confirmation = null
        state = state.copy(
            turns = state.turns
                .filterNot { it is MailTurn.ConfirmSend && it.draftId == draftId }
                .map { if (it is MailTurn.Draft && it.draftId == draftId) it.copy(body = updated.body) else it },
            pendingConfirmationDraftId = state.pendingConfirmationDraftId.takeIf { it != draftId }
        )
        publish()
    }

    /** Shows the exact recipient/subject/text. Nothing is sent until [confirmSend]. */
    fun reviewSend(draftId: String) {
        if (state.busy) return
        val reply = drafts[draftId] ?: return
        val g = currentGrant()
        val pre = MailDelegationPolicy.authorizeSend(g, sessionSource(), clock(), reply, null)
        if (pre is MailAuthorization.Denied && pre.reason != MailDenial.NOT_CONFIRMED) {
            authorized(pre, g)
            return publish()
        }
        when (ledger.stateOf(draftId)) {
            SendState.SENT -> return note("This reply was already sent.", MailTurn.Tone.SUCCESS).also { publish() }
            SendState.SENDING, SendState.UNCERTAIN ->
                return note("This reply may already have been sent. Check your Sent folder first.", MailTurn.Tone.WARNING).also { publish() }
            else -> Unit
        }
        confirmation = null
        state = state.copy(
            turns = state.turns.filterNot { it is MailTurn.ConfirmSend } +
                MailTurn.ConfirmSend(nextTurnId++, draftId, reply.to.value, reply.subject, reply.body),
            pendingConfirmationDraftId = draftId
        )
        publish()
    }

    fun cancelSend() {
        confirmation = null
        state = state.copy(turns = state.turns.filterNot { it is MailTurn.ConfirmSend }, pendingConfirmationDraftId = null)
        publish()
    }

    suspend fun confirmSend(draftId: String) {
        if (state.busy || state.pendingConfirmationDraftId != draftId) return
        val reply = drafts[draftId] ?: return
        // The approval is consumed by this tap, whatever happens next.
        val approval = SendConfirmation.of(reply, clock())
        state = state.copy(turns = state.turns.filterNot { it is MailTurn.ConfirmSend }, pendingConfirmationDraftId = null)
        step("Sending…") { epoch ->
            val g = currentGrant()
            if (!authorized(MailDelegationPolicy.authorizeSend(g, sessionSource(), clock(), reply, approval), g)) return@step
            val grant = g!!
            val tokenResult = authorizer.sendToken(grant.accountEmail!!)
            if (!operationIsCurrent(epoch, grant)) return@step
            val token = when (val t = tokenResult) {
                is TokenResult.Ok -> t.token
                else -> return@step note(MailCopy.token(t), MailTurn.Tone.WARNING)
            }
            // Consent UI may have taken a while: the commitment must still be running.
            if (!operationIsCurrent(epoch, grant)) return@step
            if (!authorized(MailDelegationPolicy.authorizeSend(currentGrant(), sessionSource(), clock(), reply, approval), currentGrant())) {
                return@step
            }
            if (!isOnline()) return@step note("You're offline. Nothing was sent.", MailTurn.Tone.WARNING)
            if (!operationIsCurrent(epoch, grant)) return@step
            when (val begin = ledger.begin(reply, clock(), grant.accountEmail)) {
                is BeginSend.Refused -> {
                    log("MAIL_TASK action=SEND outcome=REFUSED_DUPLICATE state=${begin.existing}")
                    note(
                        if (begin.existing == SendState.SENT) "This reply was already sent."
                        else "This reply may already have been sent. Check your Sent folder first.",
                        MailTurn.Tone.WARNING
                    )
                    return@step
                }
                BeginSend.StorageUnavailable -> {
                    log("MAIL_TASK action=SEND outcome=REFUSED_STORAGE_UNAVAILABLE")
                    note("I couldn't safely save the send history, so nothing was sent. Try again after restarting Strlix.", MailTurn.Tone.WARNING)
                    return@step
                }
                BeginSend.Proceed -> Unit
            }
            val outcome = try {
                connector.send(token, reply)
            } catch (e: CancellationException) {
                ledger.complete(draftId, SendOutcome.Uncertain("send_cancelled"))
                throw e
            } catch (_: Exception) {
                SendOutcome.Uncertain("unexpected_send_failure")
            }
            val final = ledger.complete(draftId, outcome)
            if (!operationIsCurrent(epoch, grant)) return@step
            log("MAIL_TASK action=SEND outcome=$final")
            if (outcome is SendOutcome.NotSent && outcome.reason == "auth_revoked") authorizer.invalidate(token)
            lockDraftIf(draftId, final)
            add(
                MailTurn.SendResult(
                    0,
                    draftId,
                    final,
                    sendResultText(final, outcome, reply),
                    canCheckSent = final == SendState.UNCERTAIN && !grant.accountEmail.isNullOrBlank()
                )
            )
        }
    }

    suspend fun checkSent(draftId: String) = step("Checking your Sent folder…") { epoch ->
        val g = currentGrant()
        val record = ledger.record(draftId) ?: return@step note("I have no record of that reply.")
        if (record.state != SendState.UNCERTAIN) {
            return@step note(if (record.state == SendState.SENT) "That reply was sent." else "That reply was not sent.")
        }
        if (!authorized(MailDelegationPolicy.authorizeSentReconciliation(g, sessionSource(), clock(), record), g)) return@step
        val grant = g!!
        val token = readToken(grant, epoch) ?: return@step
        if (!operationIsCurrent(epoch, grant)) return@step
        val found = withTokenRetry(grant, token, epoch) { t -> connector.findSentCopy(t, record) } ?: return@step
        if (!operationIsCurrent(epoch, grant)) return@step
        val next = ledger.resolveUncertain(draftId, foundInSent = found.value != null, sentMessageId = found.value)
        log("MAIL_TASK action=CHECK_SENT outcome=$next")
        lockDraftIf(draftId, next)
        add(
            MailTurn.SendResult(
                0, draftId, next,
                when (next) {
                    SendState.SENT -> "Found it in your Sent folder: the reply was sent once."
                    else -> "Gmail hasn't confirmed whether it sent. I won't treat this as unsent; don't create another reply to this message until confirmed."
                },
                canCheckSent = next == SendState.UNCERTAIN &&
                    record.accountEmail.equals(grant.accountEmail, ignoreCase = true)
            )
        )
    }

    fun onCommitmentEnded() {
        invalidateOperations()
        authorizer.clear()
        ledger.retainUnresolved()
        headers.clear(); messages.clear(); drafts.clear(); confirmation = null
        state = emptyState()
        publish()
    }

    fun onAccountDisconnected() {
        invalidateOperations()
        authorizer.clear()
        clearAccountThread(null)
        state = state.copy(linkedAccount = null, aiConsent = false)
        publish()
    }

    fun beginNewCommitment() {
        invalidateOperations()
        grantUnavailableForSession = false
    }

    // --- internals ---

    private suspend fun step(label: String, block: suspend (Long) -> Unit) {
        if (state.busy) return
        val epoch = operationEpoch
        state = state.copy(busyLabel = label)
        publish()
        try {
            block(epoch)
        } finally {
            if (epoch == operationEpoch) {
                state = state.copy(busyLabel = null)
                publish()
            }
        }
    }

    private fun authorized(result: MailAuthorization, grant: MailDelegationGrant?): Boolean {
        if (result is MailAuthorization.Denied) {
            log("MAIL_TASK outcome=DENIED reason=${result.reason}")
            note(MailCopy.denial(result.reason, grant?.senderAddress?.value.orEmpty()), MailTurn.Tone.REFUSAL)
            return false
        }
        return true
    }

    private suspend fun readToken(grant: MailDelegationGrant, epoch: Long): AccessToken? {
        if (!operationIsCurrent(epoch, grant)) return null
        if (!isOnline()) {
            note(MailCopy.token(TokenResult.Offline), MailTurn.Tone.WARNING)
            return null
        }
        val account = grant.accountEmail ?: return null
        val tokenResult = authorizer.readToken(account)
        if (!operationIsCurrent(epoch, grant)) return null
        return when (val t = tokenResult) {
            is TokenResult.Ok -> {
                val verification = connector.verifyAccount(t.token, account)
                if (!operationIsCurrent(epoch, grant)) return null
                when (val v = verification) {
                    is ConnectorResult.Ok -> t.token
                    is ConnectorResult.Failure -> {
                        authorizer.invalidate(t.token)
                        log("MAIL_TASK action=VERIFY_ACCOUNT outcome=${v.kind}")
                        note(MailCopy.connector(v.kind, grant.senderAddress.value, account), MailTurn.Tone.WARNING)
                        null
                    }
                }
            }
            else -> {
                note(MailCopy.token(t), MailTurn.Tone.WARNING)
                null
            }
        }
    }

    /** One silent retry with a fresh token when Google says the token was revoked/expired. */
    private suspend fun <T> withTokenRetry(
        grant: MailDelegationGrant,
        first: AccessToken,
        epoch: Long,
        call: suspend (AccessToken) -> ConnectorResult<T>
    ): ConnectorResult.Ok<T>? {
        if (!operationIsCurrent(epoch, grant)) return null
        var result = call(first)
        if (!operationIsCurrent(epoch, grant)) return null
        if (result is ConnectorResult.Failure && result.kind == ConnectorFailure.AUTH_REVOKED) {
            authorizer.invalidate(first)
            val fresh = readToken(grant, epoch) ?: return null
            if (!operationIsCurrent(epoch, grant)) return null
            result = call(fresh)
            if (!operationIsCurrent(epoch, grant)) return null
        }
        return when (result) {
            is ConnectorResult.Ok -> result
            is ConnectorResult.Failure -> {
                if (result.kind == ConnectorFailure.AUTH_REVOKED) authorizer.clear()
                log("MAIL_TASK outcome=${result.kind}")
                note(MailCopy.connector(result.kind, grant.senderAddress.value, grant.accountEmail), MailTurn.Tone.WARNING)
                null
            }
        }
    }

    private suspend fun loadMessage(
        messageId: String,
        epoch: Long,
        expectedGrant: MailDelegationGrant? = null
    ): MailMessage? {
        if (!operationIsCurrent(epoch, expectedGrant)) return null
        val header = headers[messageId] ?: run { noListYet(); return null }
        val g = currentGrant()
        if (!authorized(MailDelegationPolicy.authorizeRead(g, sessionSource(), clock(), header.from), g)) return null
        messages[messageId]?.let { return it }
        val grant = g!!
        if (expectedGrant != null && grant != expectedGrant) return null
        val token = readToken(grant, epoch) ?: return null
        if (!operationIsCurrent(epoch, grant)) return null
        val result = withTokenRetry(grant, token, epoch) { t -> connector.readMessage(t, messageId, grant.senderAddress) } ?: return null
        if (!operationIsCurrent(epoch, grant)) return null
        messages[messageId] = result.value
        return result.value
    }

    private fun addDraft(grant: MailDelegationGrant, header: MailHeader, body: String) {
        val reply = ReplyComposer.compose(newDraftId(), grant, header, body)
        drafts[reply.draftId] = reply
        add(MailTurn.Draft(0, reply.draftId, header.id, reply.to.value, reply.subject, reply.body, editable = true))
    }

    private fun lockDraftIf(draftId: String, s: SendState) {
        if (s != SendState.SENT && s != SendState.UNCERTAIN) return
        state = state.copy(turns = state.turns.map { if (it is MailTurn.Draft && it.draftId == draftId) it.copy(editable = false) else it })
    }

    private fun unlockDraft(draftId: String) {
        state = state.copy(turns = state.turns.map { if (it is MailTurn.Draft && it.draftId == draftId) it.copy(editable = true) else it })
    }

    private fun sendResultText(s: SendState, outcome: SendOutcome, reply: OutgoingReply): String = when (s) {
        SendState.SENT -> "Sent to ${reply.to.value}."
        SendState.FAILED_NOT_SENT -> when ((outcome as? SendOutcome.NotSent)?.reason) {
            "auth_revoked" -> "Not sent: Google access was revoked or expired. Reconnect, then approve again."
            "insufficient_scope" -> "Not sent: Google didn't grant send access. Nothing left your phone."
            "offline" -> "Not sent: no connection. Nothing left your phone."
            "rate_limited" -> "Not sent: Gmail asked me to slow down. Try again in a minute."
            else -> "Not sent: Gmail rejected the message."
        }
        else -> "I couldn't confirm Gmail's response. It may have accepted the message. I won't send it again automatically; check your Sent folder first."
    }

    private fun itemAt(index: Int): String? {
        val list = state.turns.lastOrNull { it is MailTurn.MessageList } as? MailTurn.MessageList ?: return null
        return list.items.getOrNull(index)?.messageId
    }

    private fun noListYet() = note("Check for messages first, then pick one.")

    private fun note(text: String, tone: MailTurn.Tone = MailTurn.Tone.NEUTRAL) = add(MailTurn.Note(0, text, tone))

    private fun add(turn: MailTurn) {
        val id = nextTurnId++
        val withId = when (turn) {
            is MailTurn.UserSaid -> turn.copy(id = id)
            is MailTurn.Note -> turn.copy(id = id)
            is MailTurn.MessageList -> turn.copy(id = id)
            is MailTurn.MessageText -> turn.copy(id = id)
            is MailTurn.Summary -> turn.copy(id = id)
            is MailTurn.ConsentRequest -> turn.copy(id = id)
            is MailTurn.Draft -> turn.copy(id = id)
            is MailTurn.ConfirmSend -> turn.copy(id = id)
            is MailTurn.SendResult -> turn.copy(id = id)
        }
        state = state.copy(turns = (state.turns + withId).takeLast(MAX_TURNS))
        publish()
    }

    private fun publish() = onState(state)

    private fun currentGrant(): MailDelegationGrant? =
        if (grantUnavailableForSession) null else grantSource()

    private fun operationIsCurrent(epoch: Long, expectedGrant: MailDelegationGrant?): Boolean {
        if (epoch != operationEpoch || expectedGrant == null) return false
        val liveGrant = currentGrant() ?: return false
        return liveGrant == expectedGrant &&
            MailDelegationPolicy.grantIsLive(liveGrant, sessionSource(), clock())
    }

    private fun invalidateOperations() {
        operationEpoch++
    }

    private fun clearAccountThread(accountEmailForReconciliation: String?) {
        headers.clear()
        messages.clear()
        drafts.clear()
        confirmation = null
        val unresolved = ledger.recoverAfterRestart().map { record ->
            MailTurn.SendResult(
                id = nextTurnId++,
                draftId = record.draftId,
                state = SendState.UNCERTAIN,
                text = "An earlier reply may or may not have been sent. Reconnect the same Google account to check its Sent folder; don't send a duplicate.",
                canCheckSent = accountEmailForReconciliation != null &&
                    record.accountEmail.equals(accountEmailForReconciliation, ignoreCase = true)
            )
        }
        state = state.copy(turns = unresolved, busyLabel = null, pendingConfirmationDraftId = null)
    }

    private fun refreshUncertainSendActions(accountEmail: String) {
        state = state.copy(
            turns = state.turns.map { turn ->
                if (turn is MailTurn.SendResult && turn.state == SendState.UNCERTAIN) {
                    val recordedAccount = ledger.record(turn.draftId)?.accountEmail
                    turn.copy(canCheckSent = recordedAccount.equals(accountEmail, ignoreCase = true))
                } else turn
            }
        )
    }

    companion object {
        const val NEWER_THAN_DAYS = 14
        const val MAX_RESULTS = 10
        const val DISPLAY_CHARS = 6_000
        const val MAX_TURNS = 40
    }
}
