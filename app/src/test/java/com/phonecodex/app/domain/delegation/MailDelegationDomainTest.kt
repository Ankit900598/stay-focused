package com.phonecodex.app.domain.delegation

import com.phonecodex.app.data.InMemorySharedPreferences
import com.phonecodex.app.data.MailDelegationStore
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.promise.FocusPromiseParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailDelegationDomainTest {

    private val prof = EmailAddress.parseUserEntry("Prof.Sharma@IITD.ac.in")!!

    // --- Exact sender matching ---------------------------------------------------------

    @Test
    fun userEntry_normalizesCase_andRejectsNonAddresses() {
        assertEquals("prof.sharma@iitd.ac.in", prof.value)
        assertEquals("iitd.ac.in", prof.domain)
        assertEquals(prof, EmailAddress.parseUserEntry("  <prof.sharma@iitd.ac.in> "))
        for (bad in listOf(
            "", "prof", "prof@", "@iitd.ac.in", "prof@iitd", "a@b@c.com", "prof sharma@iitd.ac.in",
            "prof@iitd.ac.in, other@x.com", "prof@1.2.3.4", "prof@iitd..ac.in", "pröf@iitd.ac.in",
            "prof@iitd.ac.in\r\nBcc: evil@x.com", "Prof <prof@iitd.ac.in>"
        )) {
            assertNull(bad, EmailAddress.parseUserEntry(bad))
        }
    }

    @Test
    fun exactMatch_rejectsLookalikes_plusAddressing_andSubdomains() {
        for (lookalike in listOf(
            "prof.sharma@iitd.ac.in.evil.com", "prof.sharma@iltd.ac.in", "profsharma@iitd.ac.in",
            "prof.sharma+x@iitd.ac.in", "prof.sharma@mail.iitd.ac.in", "prof.sharma@iitd.ac"
        )) {
            assertFalse(lookalike, prof.matchesExactly(EmailAddress.parseUserEntry(lookalike)))
        }
        assertTrue(prof.matchesExactly(EmailAddress.parseUserEntry("PROF.SHARMA@iitd.ac.in")))
        assertFalse(prof.matchesExactly(null))
    }

    @Test
    fun fromHeader_usesAngleAddress_neverAnAddressInTheDisplayName() {
        assertEquals(prof, EmailAddress.fromHeader("Prof. Sharma <prof.sharma@iitd.ac.in>"))
        assertEquals(prof, EmailAddress.fromHeader("\"Sharma, Prof\" <prof.sharma@iitd.ac.in>"))
        assertEquals(prof, EmailAddress.fromHeader("prof.sharma@iitd.ac.in (Prof Sharma)"))
        // Spoof tricks: the real mailbox is the attacker's.
        assertEquals("attacker@evil.com", EmailAddress.fromHeader("\"prof.sharma@iitd.ac.in\" <attacker@evil.com>")?.value)
        assertEquals("attacker@evil.com", EmailAddress.fromHeader("(prof.sharma@iitd.ac.in) attacker@evil.com")?.value)
        assertNull(EmailAddress.fromHeader("prof.sharma@iitd.ac.in <attacker@evil.com>"))
        assertNull(EmailAddress.fromHeader("<prof.sharma@iitd.ac.in>, <attacker@evil.com>"))
        assertNull(EmailAddress.fromHeader("A <prof.sharma@iitd.ac.in> B <attacker@evil.com>"))
        assertNull(EmailAddress.fromHeader("\"unterminated <prof.sharma@iitd.ac.in>"))
        assertNull(EmailAddress.fromHeader(null))
    }

    // --- Delegation intent + offer ------------------------------------------------------

    private val flagship =
        "Keep Gmail closed for three hours, but let me ask Strlix to check messages from my professor, " +
            "summarize a selected message, prepare a reply, and send it only after I approve the exact recipient and text."

    @Test
    fun flagshipPromise_splitsCommitmentFromDelegatedTask() {
        val r = MailDelegationIntent.detect(flagship)
        assertTrue(r.requested)
        assertFalse(r.forbidsEveryForm)
        assertEquals("Keep Gmail closed for three hours", r.commitmentText)
        assertEquals("my professor", r.senderHint)
        assertNull("a hint is never an address", r.senderAddressInText)
        assertTrue(r.wantsSummary && r.wantsReplyDraft && r.wantsSend)
        assertFalse(r.wantsProactiveAlerts)
    }

    @Test
    fun addressTypedInPromise_isOnlyPrefill() {
        val r = MailDelegationIntent.detect("Block Gmail for 2 hours. Let Strlix check mail from prof.sharma@iitd.ac.in")
        assertTrue(r.requested)
        assertEquals(prof, r.senderAddressInText)
        assertEquals("Block Gmail for 2 hours", r.commitmentText)
    }

    @Test
    fun draftingReplyDoesNotImplyPermissionToSend() {
        val request = MailDelegationIntent.detect(
            "Keep Gmail closed for one hour. Let me ask Strlix to draft a reply to messages from my professor."
        )
        assertTrue(request.wantsReplyDraft)
        assertFalse(request.wantsSend)
    }

    @Test
    fun genericSocialMessagesAreNotSilentlyReinterpretedAsGmail() {
        val cases = listOf(
            "Block WhatsApp for 3 weeks, but let me ask Strlix to check messages from my professor.",
            "Block Instagram for an hour. Let Strlix summarize messages from my manager.",
            "Block Gmail and WhatsApp for 3 weeks, but let Strlix check messages from my professor."
        )
        for (text in cases) {
            val request = MailDelegationIntent.detect(text)
            assertFalse(text, request.requested)
            assertEquals(text, text, request.commitmentText)
            assertFalse(text, request.wantsProactiveAlerts)
        }
    }

    @Test
    fun explicitGmailInTaskClauseRemainsRecognizedAlongsideOtherApps() {
        val request = MailDelegationIntent.detect(
            "Block WhatsApp for 3 weeks, but let me ask Strlix to summarize Gmail messages from my professor."
        )
        assertTrue(request.requested)
        assertTrue(request.commitmentText.contains("Block WhatsApp"))
    }

    @Test
    fun forbidEveryForm_neverOffersConnector() {
        for (text in listOf(
            "Block Gmail for 3 hours. No Gmail in any form, not even through Strlix.",
            "No gmail at all for 2 hours, don't let Strlix read my email",
            "Keep Gmail closed for 3 hours, but let Strlix check mail from my professor. Actually no exceptions."
        )) {
            val r = MailDelegationIntent.detect(text)
            assertTrue(text, r.forbidsEveryForm)
            assertFalse(text, r.requested)
            val draft = FocusPromiseParser().parse(r.commitmentText)
            assertEquals(text, MailHelperOffer.FORBIDDEN_BY_USER, MailDelegationOfferLaw.decide(r, MailDelegationOfferLaw.draftClosesGmail(draft)))
        }
    }

    @Test
    fun offerLaw_table() {
        fun offer(text: String): MailHelperOffer {
            val r = MailDelegationIntent.detect(text)
            val draft = MailDelegationOfferLaw.withDeterministicGmailBlock(FocusPromiseParser().parse(r.commitmentText))
            return MailDelegationOfferLaw.decide(r, MailDelegationOfferLaw.draftClosesGmail(draft))
        }
        assertEquals(MailHelperOffer.REQUESTED, offer(flagship))
        assertEquals(MailHelperOffer.OPTIONAL, offer("Block Gmail for 2 hours"))
        assertEquals(MailHelperOffer.NONE, offer("Block Instagram for 1 hour"))
        assertEquals(MailHelperOffer.GMAIL_NOT_CLOSED, offer("Block Instagram for 1 hour but let Strlix check mail from my professor"))
    }

    @Test
    fun proactiveAlertRequest_isDetected_notGranted() {
        val r = MailDelegationIntent.detect("Block Gmail for 2 hours and tell me when an email from my professor arrives")
        assertTrue(r.wantsProactiveAlerts)
        val grant = MailDelegationGrant("s", prof, aiAssistConsent = false, allowSend = false, createdAtMillis = 0)
        assertFalse("this version has no alert path", grant.proactiveAlerts)
    }

    @Test
    fun proactiveAlertForWhatsAppIsNotMislabelledAsGmailBecauseItWasAlsoBlocked() {
        val request = MailDelegationIntent.detect(
            "Block Gmail and WhatsApp for 2 hours, and tell me when WhatsApp messages arrive."
        )
        assertFalse(request.wantsProactiveAlerts)
    }

    @Test
    fun deterministicGmailBlock_addedWhenCompilerOmitsIt() {
        val draft = FocusPromiseParser().parse("Keep Gmail closed for three hours")
            .let { it.copy(suggestedAppRules = it.suggestedAppRules.filterNot { r -> r.packageName == MailDelegationOfferLaw.GMAIL_PACKAGE }) }
        val fixed = MailDelegationOfferLaw.withDeterministicGmailBlock(draft)
        assertEquals(
            AppRuleBehavior.BLOCK,
            fixed.suggestedAppRules.single { it.packageName == MailDelegationOfferLaw.GMAIL_PACKAGE }.behavior
        )
        val unrelated = FocusPromiseParser().parse("Block Instagram for 1 hour")
        assertEquals(unrelated, MailDelegationOfferLaw.withDeterministicGmailBlock(unrelated))
    }

    // --- Authorization -----------------------------------------------------------------

    private fun session(id: String = "s1", status: SessionStatus = SessionStatus.ACTIVE, deadline: Long = 10_000L) =
        FocusSession(id, "study", "Keep Gmail closed", 0L, deadline, status, 0, StrictnessLevel.STRICT)

    private fun grant(sessionId: String = "s1", ai: Boolean = true, send: Boolean = true, account: String? = "me@gmail.com") =
        MailDelegationGrant(sessionId, prof, ai, send, 0L, account)

    private fun reply(body: String = "Thanks, will do.", to: EmailAddress = prof) =
        OutgoingReply("d1", to, "Re: Assignment", body, "t1", null, null)

    @Test
    fun sessionGate_everyActionNeedsTheSameLiveCommitment() {
        val now = 5_000L
        val g = grant()
        assertTrue(MailDelegationPolicy.authorizeCheck(g, session(), now).allowed)
        assertEquals(MailDenial.NO_ACTIVE_COMMITMENT, (MailDelegationPolicy.authorizeCheck(g, null, now) as MailAuthorization.Denied).reason)
        assertEquals(MailDenial.COMMITMENT_ENDED, (MailDelegationPolicy.authorizeCheck(g, session(status = SessionStatus.ENDED), now) as MailAuthorization.Denied).reason)
        assertEquals(MailDenial.COMMITMENT_ENDED, (MailDelegationPolicy.authorizeCheck(g, session(), 10_000L) as MailAuthorization.Denied).reason)
        assertEquals(MailDenial.GRANT_FOR_OTHER_COMMITMENT, (MailDelegationPolicy.authorizeCheck(grant("old"), session(), now) as MailAuthorization.Denied).reason)
        assertEquals(MailDenial.NO_GRANT, (MailDelegationPolicy.authorizeCheck(null, session(), now) as MailAuthorization.Denied).reason)
        assertEquals(MailDenial.ACCOUNT_NOT_LINKED, (MailDelegationPolicy.authorizeCheck(grant(account = null), session(), now) as MailAuthorization.Denied).reason)
        assertTrue(MailDelegationPolicy.authorizeCheck(g, session(status = SessionStatus.LOCKED), now).allowed)
    }

    @Test
    fun readAndAi_requireExactSender_andConsent() {
        val now = 5_000L
        val other = EmailAddress.parseUserEntry("prof.sharma@iitd.ac.in.evil.com")
        assertTrue(MailDelegationPolicy.authorizeRead(grant(), session(), now, prof).allowed)
        assertEquals(MailDenial.WRONG_SENDER, (MailDelegationPolicy.authorizeRead(grant(), session(), now, other) as MailAuthorization.Denied).reason)
        assertEquals(MailDenial.WRONG_SENDER, (MailDelegationPolicy.authorizeRead(grant(), session(), now, null) as MailAuthorization.Denied).reason)
        assertEquals(MailDenial.AI_CONSENT_MISSING, (MailDelegationPolicy.authorizeAiAssist(grant(ai = false), session(), now, prof) as MailAuthorization.Denied).reason)
        assertTrue(MailDelegationPolicy.authorizeAiAssist(grant(), session(), now, prof).allowed)
    }

    @Test
    fun sentReconciliation_isBoundToCurrentSenderAndGoogleAccount() {
        val now = 5_000L
        val record = SendRecord(
            draftId = "d1",
            recipient = prof.value,
            attemptedAtMillis = 1_000L,
            state = SendState.UNCERTAIN,
            accountEmail = "me@gmail.com"
        )
        assertTrue(MailDelegationPolicy.authorizeSentReconciliation(grant(), session(), now, record).allowed)
        assertEquals(
            MailDenial.SENT_RECORD_ACCOUNT_MISMATCH,
            (MailDelegationPolicy.authorizeSentReconciliation(grant(account = "other@gmail.com"), session(), now, record) as MailAuthorization.Denied).reason
        )
        assertEquals(
            MailDenial.SENT_RECORD_ACCOUNT_MISMATCH,
            (MailDelegationPolicy.authorizeSentReconciliation(grant(), session(), now, record.copy(accountEmail = null)) as MailAuthorization.Denied).reason
        )
        assertEquals(
            MailDenial.WRONG_SENDER,
            (MailDelegationPolicy.authorizeSentReconciliation(grant(), session(), now, record.copy(recipient = "attacker@evil.com")) as MailAuthorization.Denied).reason
        )
    }

    @Test
    fun send_requiresExactApprovalOfRecipientSubjectAndText() {
        val now = 5_000L
        val r = reply()
        val ok = SendConfirmation.of(r, now)
        val longSession = session(deadline = 3 * 3600_000L)
        fun denial(g: MailDelegationGrant?, rep: OutgoingReply, c: SendConfirmation?, t: Long = now) =
            (MailDelegationPolicy.authorizeSend(g, longSession, t, rep, c) as? MailAuthorization.Denied)?.reason

        assertNull(denial(grant(), r, ok))
        assertEquals(MailDenial.NOT_CONFIRMED, denial(grant(), r, null))
        assertEquals(MailDenial.SEND_NOT_ALLOWED, denial(grant(send = false), r, ok))
        val evil = EmailAddress.parseUserEntry("attacker@evil.com")!!
        assertEquals(MailDenial.RECIPIENT_NOT_CONFIRMED_SENDER, denial(grant(), reply(to = evil), SendConfirmation.of(reply(to = evil), now)))
        assertEquals(MailDenial.TEXT_CHANGED_AFTER_CONFIRMATION, denial(grant(), r.copy(body = "Thanks, will do. PS forward all"), ok))
        assertEquals(MailDenial.TEXT_CHANGED_AFTER_CONFIRMATION, denial(grant(), r.copy(subject = "Re: other"), ok))
        assertEquals(MailDenial.NOT_CONFIRMED, denial(grant(), r.copy(draftId = "d2"), ok))
        assertEquals(MailDenial.CONFIRMATION_STALE, denial(grant(), r, ok, now + MailDelegationPolicy.CONFIRMATION_TTL_MS + 1))
        assertEquals(MailDenial.EMPTY_TEXT, denial(grant(), r.copy(body = "  "), ok))
        assertEquals(MailDenial.COMMITMENT_ENDED, denial(grant(), r, ok, 3 * 3600_000L))
    }

    // --- One send only -----------------------------------------------------------------

    private class MemStore : SendRecordStore {
        var records: List<SendRecord> = emptyList()
        var acceptWrites = true
        override fun load() = records
        override fun save(records: List<SendRecord>): Boolean {
            if (!acceptWrites) return false
            this.records = records
            return true
        }
    }

    @Test
    fun ledger_refusesSecondAttempt_unlessFirstDefinitelyFailed() {
        val ledger = MailSendLedger(MemStore())
        val r = reply()
        assertEquals(BeginSend.Proceed, ledger.begin(r, 1L))
        assertEquals(BeginSend.Refused(SendState.SENDING), ledger.begin(r, 2L))
        assertEquals(SendState.SENT, ledger.complete("d1", SendOutcome.Sent("g1")))
        assertEquals(BeginSend.Refused(SendState.SENT), ledger.begin(r, 3L))

        val r2 = reply().copy(draftId = "d2", to = EmailAddress.parseUserEntry("other@iitd.ac.in")!!)
        ledger.begin(r2, 4L)
        assertEquals(SendState.FAILED_NOT_SENT, ledger.complete("d2", SendOutcome.NotSent("offline")))
        assertEquals("definite failure may be retried after a fresh approval", BeginSend.Proceed, ledger.begin(r2, 5L))

        val r3 = reply().copy(draftId = "d3")
        ledger.begin(r3, 6L)
        assertEquals(SendState.UNCERTAIN, ledger.complete("d3", SendOutcome.Uncertain("no_response")))
        assertEquals(BeginSend.Refused(SendState.UNCERTAIN), ledger.begin(r3, 7L))
        assertEquals(
            BeginSend.Refused(SendState.UNCERTAIN),
            ledger.begin(r3.copy(draftId = "d4"), 8L)
        )
    }

    @Test
    fun ledgerFailsClosed_whenHistoryCannotBeReadOrDurablyWritten() {
        val unreadable = object : SendRecordStore {
            override fun load(): List<SendRecord> = error("corrupt")
            override fun save(records: List<SendRecord>) = false
        }
        assertEquals(BeginSend.StorageUnavailable, MailSendLedger(unreadable).begin(reply(), 1L))

        val unwritable = MemStore().apply { acceptWrites = false }
        assertEquals(BeginSend.StorageUnavailable, MailSendLedger(unwritable).begin(reply(), 1L))
        assertTrue(unwritable.records.isEmpty())
    }

    @Test
    fun ledgerCompletionWithoutItsDurableStartRecord_remainsUncertain() {
        val store = MemStore()
        val ledger = MailSendLedger(store)
        assertEquals(BeginSend.Proceed, ledger.begin(reply(), 1L))
        store.records = emptyList()
        assertEquals(SendState.UNCERTAIN, ledger.complete("d1", SendOutcome.Sent("g1")))
    }

    @Test
    fun ledgerUncertainRecord_blocksAnotherDraftToSameRecipient() {
        val store = MemStore().apply {
            records = listOf(
                SendRecord(
                    draftId = "legacy-draft",
                    recipient = prof.value,
                    attemptedAtMillis = 1L,
                    state = SendState.UNCERTAIN,
                    accountEmail = "me@gmail.com"
                )
            )
        }
        assertEquals(
            BeginSend.Refused(SendState.UNCERTAIN),
            MailSendLedger(store).begin(reply().copy(draftId = "new-draft"), 2L, accountEmail = "me@gmail.com")
        )
    }

    @Test
    fun ledger_restartKeepsUncertainWhenSentSearchFindsNothing() {
        val store = MemStore()
        MailSendLedger(store).begin(reply(), 1_000L)
        val afterRestart = MailSendLedger(store)
        assertEquals(listOf("d1"), afterRestart.recoverAfterRestart().map { it.draftId })
        assertEquals(SendState.UNCERTAIN, afterRestart.stateOf("d1"))
        assertEquals(
            SendState.UNCERTAIN,
            afterRestart.resolveUncertain("d1", foundInSent = false)
        )
        assertEquals(BeginSend.Refused(SendState.UNCERTAIN), afterRestart.begin(reply().copy(draftId = "d2"), Long.MAX_VALUE))

        val s2 = MemStore()
        MailSendLedger(s2).apply { begin(reply(), 1L); complete("d1", SendOutcome.Uncertain("server_502")) }
        assertEquals(SendState.SENT, MailSendLedger(s2).resolveUncertain("d1", foundInSent = true, sentMessageId = "g9"))
        assertEquals("g9", s2.records.single().gmailMessageId)
    }

    // --- Persistence + restoration -----------------------------------------------------

    @Test
    fun store_persistsMetadataOnly_andRetainsOnlyUnresolvedSendsAfterCommitment() {
        val prefs = InMemorySharedPreferences()
        val store = MailDelegationStore(prefs)
        store.saveGrant(grant())
        val ledger = MailSendLedger(store)
        ledger.begin(reply(body = "SECRET BODY TEXT"), 1L, accountEmail = "me@gmail.com")
        val dump = prefs.all.values.joinToString("|")
        assertFalse("reply text is never persisted", dump.contains("SECRET BODY TEXT"))
        assertFalse("reply text hashes are never persisted", dump.contains("bodySha256"))
        assertFalse("reply text hashes are never persisted", dump.contains(sha256("SECRET BODY TEXT")))
        assertFalse("reply subject is not needed for recovery", dump.contains("Re: Assignment 3"))
        assertTrue(dump.contains(prof.value))
        assertTrue(dump.contains("me@gmail.com"))

        assertNotNull(store.liveGrant(session(), 5_000L))
        assertNull("new commitment, old grant", store.liveGrant(session(id = "s2"), 5_000L))
        assertNull("grant erased on first stale read", store.grant())
        assertEquals(listOf(SendState.SENDING), store.load().map { it.state })
        assertTrue(ledger.retainUnresolved())
        assertEquals(listOf(SendState.SENDING), store.load().map { it.state })

        ledger.complete("d1", SendOutcome.Sent("g1"))
        assertTrue("resolved records are pruned at teardown", ledger.retainUnresolved())
        assertTrue(store.load().isEmpty())

        store.saveGrant(grant())
        assertNull(store.liveGrant(null, 5_000L))
        store.saveGrant(grant())
        assertNull("deadline passed", store.liveGrant(session(), 10_001L))
    }

    @Test
    fun storeMigratesLegacyReplyHashesBeforeReturningSendHistory() {
        val prefs = InMemorySharedPreferences()
        val legacyHash = sha256("Thanks, I will submit by Friday.")
        val legacy = JSONArray().put(
            JSONObject()
                .put("draftId", "d1")
                .put("recipient", prof.value)
                .put("bodySha256", legacyHash)
                .put("attemptedAt", 1L)
                .put("state", SendState.UNCERTAIN.name)
                .put("gmailMessageId", JSONObject.NULL)
                .put("accountEmail", "me@gmail.com")
        )
        assertTrue(prefs.edit().putString("send_records_v1", legacy.toString()).commit())

        val record = MailDelegationStore(prefs).load().single()
        val persisted = prefs.getString("send_records_v1", "")!!
        assertEquals("d1", record.draftId)
        assertEquals(SendState.UNCERTAIN, record.state)
        assertFalse(persisted.contains("bodySha256"))
        assertFalse(persisted.contains(legacyHash))
    }
}
