package com.phonecodex.app.data

import com.phonecodex.app.data.CommitmentRuleRestore.Outcome
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.promise.EffectivePromisePolicy
import com.phonecodex.app.domain.promise.FocusPromiseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Commitment app rules through the real AppRulesStore + SharedPreferences contract.
 * Existing overrides and custom entries have unknown origin, so they are user-owned:
 * a commitment only overlays its own suggestions and end / expiry / restart recovery
 * put back the exact pre-session prefs.
 */
class AppRulesStoreCommitmentTest {

    private val yt = VideoPlatformRegistry.YOUTUBE
    private val ig = VideoPlatformRegistry.INSTAGRAM
    private val wa = "com.whatsapp"
    private val ledgerKey = "commitment_ledger_v1"
    private val quarantineKey = "commitment_ledger_quarantine_v1"
    private val recoveryKey = "commitment_ledger_recovery_note"

    private val prefs = InMemorySharedPreferences()
    private val store = AppRulesStore(prefs)

    private fun seedUserRules() {
        prefs.edit()
            .putString("behavior_$yt", "WARN")
            .putString("behavior_$ig", "BLOCK")
            .putString("behavior_com.foo", "ALLOW")
            .putStringSet("custom_rules", setOf("com.foo|Foo", "com.bar|Bar"))
            .commit()
    }

    private fun shortsOnlyDraft(): FocusPromise =
        FocusPromiseParser().parse("Block YouTube Shorts only for the next 1 hour.")

    private fun whatsAppWholeAppDraft(): FocusPromise =
        FocusPromiseParser().parse("do not allow whatsapp for 10 minutes")

    @Test
    fun firstCommitmentAfterUpdate_keepsLegacyOverridesAndCustomEntries() {
        seedUserRules()
        store.applyCommitmentSuggestions(shortsOnlyDraft().suggestedAppRules)

        val touched = shortsOnlyDraft().suggestedAppRules.map { it.packageName }.toSet()
        assertFalse(ig in touched)
        assertEquals(AppRuleBehavior.BLOCK, store.getBehaviorForPackage(ig))
        assertEquals("ALLOW", prefs.getString("behavior_com.foo", null))
        assertTrue(prefs.getStringSet("custom_rules", null)!!.containsAll(setOf("com.foo|Foo", "com.bar|Bar")))
    }

    @Test
    fun shortsOnly_sessionGetsTemporaryNarrowRule_andEndRestoresExactPrefs() {
        seedUserRules()
        val before = prefs.snapshot()
        val draft = shortsOnlyDraft()
        assertNull("Shorts-only draft must be startable", EffectivePromisePolicy.conflictReason(draft))
        val ytSuggestion = draft.suggestedAppRules.first { it.packageName == yt }.behavior
        assertNotEquals("Shorts-only never becomes a package-wide YouTube BLOCK", AppRuleBehavior.BLOCK, ytSuggestion)

        val started = store.applyCommitmentSuggestions(draft.suggestedAppRules)
        assertNull(started.eventMessage)
        assertEquals(ytSuggestion, store.getBehaviorForPackage(yt))
        assertTrue(store.hasCommitmentLedger())

        val ended = store.restoreCommitmentRules()
        assertEquals(Outcome.RESTORED, ended.outcome)
        assertNull(ended.eventMessage)
        assertEquals(before, prefs.snapshot())
        assertEquals(AppRuleBehavior.WARN, store.getBehaviorForPackage(yt))
    }

    @Test
    fun explicitWholeAppBlock_isActiveDuringSession_andItsCustomEntryIsRemovedAfter() {
        seedUserRules()
        val before = prefs.snapshot()
        store.applyCommitmentSuggestions(whatsAppWholeAppDraft().suggestedAppRules)

        assertEquals(AppRuleBehavior.BLOCK, store.getBehaviorForPackage(wa))
        assertTrue(prefs.getStringSet("custom_rules", null)!!.any { it.startsWith("$wa|") })

        store.restoreCommitmentRules()
        assertEquals(before, prefs.snapshot())
        assertNull(store.getBehaviorForPackage(wa))
    }

    @Test
    fun expiryAndRestartRecovery_restoreExactPrefs_onlyWithoutARunningSession() {
        seedUserRules()
        val before = prefs.snapshot()
        store.applyCommitmentSuggestions(whatsAppWholeAppDraft().suggestedAppRules)
        val during = prefs.snapshot()

        val afterRestart = AppRulesStore(prefs)
        assertEquals(Outcome.SESSION_ACTIVE, afterRestart.restoreStaleCommitmentRules(hasActiveSession = true).outcome)
        assertEquals(during, prefs.snapshot())

        val recovered = afterRestart.restoreStaleCommitmentRules(hasActiveSession = false)
        assertEquals(Outcome.RESTORED, recovered.outcome)
        assertEquals(before, prefs.snapshot())

        val again = afterRestart.restoreStaleCommitmentRules(hasActiveSession = false)
        assertEquals(Outcome.NO_LEDGER, again.outcome)
        assertNull("no repeated report once nothing is left to restore", again.eventMessage)
    }

    @Test
    fun consecutiveCommitmentsWithoutEnd_stillRestoreOriginalUserPrefs() {
        seedUserRules()
        val before = prefs.snapshot()
        store.applyCommitmentSuggestions(whatsAppWholeAppDraft().suggestedAppRules)
        store.applyCommitmentSuggestions(shortsOnlyDraft().suggestedAppRules)

        assertNull("earlier commitment's WhatsApp BLOCK must not leak", store.getBehaviorForPackage(wa))
        store.restoreCommitmentRules()
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun manualEditDuringSession_survivesRestore() {
        seedUserRules()
        store.applyCommitmentSuggestions(whatsAppWholeAppDraft().suggestedAppRules)
        store.setBehavior(wa, AppRuleBehavior.ALLOW)

        store.restoreCommitmentRules()
        assertEquals(AppRuleBehavior.ALLOW, store.getBehaviorForPackage(wa))
        assertEquals(AppRuleBehavior.BLOCK, store.getBehaviorForPackage(ig))
    }

    @Test
    fun missingLedgerAtEnd_keepsRulesExactly_andReports() {
        seedUserRules()
        val before = prefs.snapshot()

        val ended = store.restoreCommitmentRules()
        assertEquals(Outcome.NO_LEDGER, ended.outcome)
        assertEquals(AppRulesStore.NO_LEDGER_MESSAGE, ended.eventMessage)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun unreadableLedgerAtEnd_keepsCurrentRules_quarantinesRaw_andReportsOnce() {
        seedUserRules()
        store.applyCommitmentSuggestions(whatsAppWholeAppDraft().suggestedAppRules)
        prefs.edit().putString(ledgerKey, "{not json").commit()
        val during = prefs.snapshot() - ledgerKey

        val ended = store.restoreCommitmentRules()
        assertEquals(Outcome.UNREADABLE_LEDGER_RULES_KEPT, ended.outcome)
        assertEquals(AppRulesStore.UNREADABLE_LEDGER_MESSAGE, ended.eventMessage)
        assertEquals("rules are not guessed or reset", during, prefs.snapshot() - quarantineKey - recoveryKey)
        assertEquals("{not json", prefs.getString(quarantineKey, null))
        assertNotNull(prefs.getString(recoveryKey, null))
        assertFalse(store.hasCommitmentLedger())

        assertNull(store.restoreStaleCommitmentRules(hasActiveSession = false).eventMessage)
    }

    @Test
    fun unreadableLedgerDuringActiveSession_isLeftAlone_untilTheSessionEnds() {
        seedUserRules()
        store.applyCommitmentSuggestions(whatsAppWholeAppDraft().suggestedAppRules)
        prefs.edit().putString(ledgerKey, "{broken").commit()
        val during = prefs.snapshot()

        val restart = AppRulesStore(prefs).restoreStaleCommitmentRules(hasActiveSession = true)
        assertEquals(Outcome.SESSION_ACTIVE, restart.outcome)
        assertNull(restart.eventMessage)
        assertEquals(during, prefs.snapshot())
        assertEquals(AppRuleBehavior.BLOCK, store.getBehaviorForPackage(wa))
    }

    @Test
    fun futureVersionOrWrongTypeLedger_isTreatedAsUnreadable_notDiscarded() {
        seedUserRules()
        prefs.edit().putString(ledgerKey, """{"version":2,"prior":{},"written":{},"addedCustom":[]}""").commit()
        assertEquals(Outcome.UNREADABLE_LEDGER_RULES_KEPT, store.restoreCommitmentRules().outcome)
        assertTrue(prefs.getString(quarantineKey, null)!!.contains("\"version\":2"))

        prefs.edit().putInt(ledgerKey, 7).commit()
        assertEquals(Outcome.UNREADABLE_LEDGER_RULES_KEPT, store.restoreCommitmentRules().outcome)
        assertTrue("earlier quarantined ledger is kept", prefs.getString(quarantineKey, null)!!.contains("\"version\":2"))
        assertTrue(prefs.getString(quarantineKey, null)!!.endsWith("7"))
        assertEquals(AppRuleBehavior.BLOCK, store.getBehaviorForPackage(ig))
    }

    @Test
    fun unreadableLedgerAtStart_isReported_andNewCommitmentStillRestoresToPreStartPrefs() {
        seedUserRules()
        prefs.edit().putString(ledgerKey, "garbage").commit()
        val preStart = prefs.snapshot() - ledgerKey

        val started = store.applyCommitmentSuggestions(shortsOnlyDraft().suggestedAppRules)
        assertEquals(Outcome.UNREADABLE_LEDGER_RULES_KEPT, started.outcome)
        assertEquals(AppRulesStore.UNREADABLE_LEDGER_MESSAGE, started.eventMessage)
        assertTrue(store.hasCommitmentLedger())

        store.restoreCommitmentRules()
        assertEquals(preStart, prefs.snapshot() - quarantineKey - recoveryKey)
    }

    @Test
    fun nonStringBehaviorValue_survivesApplyAndRestore() {
        seedUserRules()
        prefs.edit().putInt("behavior_com.baz", 3).commit()
        val before = prefs.snapshot()

        store.applyCommitmentSuggestions(whatsAppWholeAppDraft().suggestedAppRules)
        assertEquals(3, prefs.all["behavior_com.baz"])
        store.restoreCommitmentRules()
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun legacyMigrationFlagFromEarlierBuild_hasNoEffect() {
        seedUserRules()
        prefs.edit().putBoolean("commitment_ledger_migrated_v1", false).commit()
        val before = prefs.snapshot()

        store.applyCommitmentSuggestions(shortsOnlyDraft().suggestedAppRules)
        store.restoreCommitmentRules()
        assertEquals(before, prefs.snapshot())
    }
}
