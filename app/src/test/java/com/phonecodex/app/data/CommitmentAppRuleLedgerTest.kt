package com.phonecodex.app.data

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Commitment-written app rules are session-scoped: end, expiry, restart recovery and
 * repeated commitments restore the pre-session user rules without deleting custom rules.
 */
class CommitmentAppRuleLedgerTest {

    private val yt = "com.google.android.youtube"
    private val ig = "com.instagram.android"
    private val wa = "com.whatsapp"
    private val defaults = setOf(yt, ig)

    private val userState = AppRuleState(
        behaviors = mapOf(ig to "BLOCK", "com.foo" to "WARN"),
        customEntries = setOf("com.foo|Foo")
    )

    private val shortsCommitment = listOf(AppRule(yt, "YouTube", AppRuleBehavior.AI_DECIDE))
    private val whatsappCommitment = listOf(AppRule(wa, "WhatsApp", AppRuleBehavior.BLOCK))

    @Test
    fun apply_keepsIndependentUserRules_andRestoreReturnsExactPriorState() {
        val applied = CommitmentAppRuleLedger.apply(userState, whatsappCommitment, defaults)
        assertEquals("BLOCK", applied.state.behaviors[ig])
        assertEquals("WARN", applied.state.behaviors["com.foo"])
        assertEquals("BLOCK", applied.state.behaviors[wa])
        assertEquals(setOf("com.foo|Foo", "$wa|WhatsApp"), applied.state.customEntries)

        val restored = CommitmentAppRuleLedger.restore(applied.state, applied.ledger)
        assertEquals(userState, restored)
    }

    @Test
    fun restore_keepsManualEditsMadeDuringTheSession() {
        val applied = CommitmentAppRuleLedger.apply(userState, whatsappCommitment, defaults)
        val edited = applied.state.copy(behaviors = applied.state.behaviors + (wa to "ALLOW"))
        val restored = CommitmentAppRuleLedger.restore(edited, applied.ledger)
        assertEquals("ALLOW", restored.behaviors[wa])
        assertEquals(setOf("com.foo|Foo", "$wa|WhatsApp"), restored.customEntries)
        assertEquals("BLOCK", restored.behaviors[ig])
    }

    @Test
    fun repeatedCommitments_restoreToOriginalUserState() {
        val first = CommitmentAppRuleLedger.apply(userState, whatsappCommitment, defaults)
        val second = CommitmentAppRuleLedger.apply(
            CommitmentAppRuleLedger.restore(first.state, first.ledger),
            shortsCommitment,
            defaults
        )
        assertNull("previous commitment's WhatsApp BLOCK must not leak", second.state.behaviors[wa])
        assertEquals("AI_DECIDE", second.state.behaviors[yt])
        assertEquals(userState, CommitmentAppRuleLedger.restore(second.state, second.ledger))
    }

    @Test
    fun ledger_serializationRoundTrips() {
        val applied = CommitmentAppRuleLedger.apply(userState, whatsappCommitment + shortsCommitment, defaults)
        val back = CommitmentAppRuleLedger.decode(CommitmentAppRuleLedger.encode(applied.ledger))
        assertEquals(applied.ledger, back)
        assertNull(CommitmentAppRuleLedger.decode("not json"))
        assertNull(CommitmentAppRuleLedger.decode(null))
    }

    @Test
    fun staleLedger_restoresOnlyWhenNoSessionIsActive() {
        val applied = CommitmentAppRuleLedger.apply(userState, whatsappCommitment, defaults)
        assertEquals(
            applied.state,
            CommitmentAppRuleLedger.reconcile(applied.state, applied.ledger, hasActiveSession = true)
        )
        assertEquals(
            userState,
            CommitmentAppRuleLedger.reconcile(applied.state, applied.ledger, hasActiveSession = false)
        )
    }
}
