package com.phonecodex.app.data

import com.phonecodex.app.accessibility.CommitmentOverlayCopy
import com.phonecodex.app.accessibility.OverlayKind
import com.phonecodex.app.data.CommitmentRuleRestore.Outcome as Rules
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.RecoveryPolicy
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.recovery.RecoveryPolicyDefaults
import com.phonecodex.app.domain.session.CommitmentExitGate
import com.phonecodex.app.domain.session.EmergencyExitGate
import com.phonecodex.app.domain.session.ExitFriction
import com.phonecodex.app.domain.session.SessionExpiryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 3: every way a commitment ends goes through one teardown that runs exactly once,
 * restores only this commitment's rules, keeps permanent guardrails, and logs the result.
 */
class CommitmentTeardownTest {

    private val sessionPrefs = InMemorySharedPreferences()
    private val rulePrefs = InMemorySharedPreferences()
    private val events = mutableListOf<String>()
    private val sessionStore = SessionStore(sessionPrefs)
    private val appRules = AppRulesStore(rulePrefs)
    private val teardown = CommitmentTeardown(sessionStore, appRules) { events += it }
    private val ig = "com.instagram.android"

    private fun session(
        id: String = "session-1",
        status: SessionStatus = SessionStatus.ACTIVE,
        deadline: Long = 10_000L,
        promise: StrictnessLevel? = StrictnessLevel.LOCKED
    ) = FocusSession(
        id = id,
        worldId = "study",
        goal = "Block Instagram for 1 hour",
        startTimeMillis = 0L,
        deadlineMillis = deadline,
        status = status,
        attemptCount = 0,
        strictness = StrictnessLevel.STRICT,
        promiseStrictness = promise
    )

    private fun startWithInstagramBlock(s: FocusSession = session()) {
        appRules.applyCommitmentSuggestions(listOf(AppRule(ig, "Instagram", AppRuleBehavior.BLOCK)))
        sessionStore.saveActiveSession(s)
    }

    private fun endLines() = events.filter { it.startsWith(CommitmentTeardown.EVENT_PREFIX) }

    @Test
    fun earlyEnd_clearsSession_restoresRules_logsOnce() {
        val before = appRules.getBehaviorForPackage(ig)
        startWithInstagramBlock()
        assertEquals(AppRuleBehavior.BLOCK, appRules.getBehaviorForPackage(ig))

        val r = teardown.end(CommitmentTeardown.Kind.EARLY_END, "session-1", "friction=TYPED_PHRASE")

        assertEquals(CommitmentTeardown.Outcome.ENDED, r.outcome)
        assertEquals(Rules.RESTORED, r.rules)
        assertNull(sessionStore.getStoredSession())
        assertEquals(before, appRules.getBehaviorForPackage(ig))
        assertEquals(1, endLines().size)
        val line = endLines().single()
        assertTrue(line, "kind=EARLY_END outcome=ENDED session=session-1" in line)
        assertTrue(line, "strictness=LOCKED rules=RESTORED guardrails=kept friction=TYPED_PHRASE" in line)
    }

    @Test
    fun duplicateTap_secondCallChangesNothing() {
        startWithInstagramBlock()
        teardown.end(CommitmentTeardown.Kind.EMERGENCY_EXIT, "session-1")
        val rulesAfterFirst = rulePrefs.snapshot()

        val second = teardown.end(CommitmentTeardown.Kind.EMERGENCY_EXIT, "session-1")

        assertEquals(CommitmentTeardown.Outcome.ALREADY_ENDED, second.outcome)
        assertEquals(rulesAfterFirst, rulePrefs.snapshot())
        assertFalse("no spurious 'no ledger' warning", events.any { it == AppRulesStore.NO_LEDGER_MESSAGE })
        assertEquals(listOf("ENDED", "ALREADY_ENDED"), endLines().map { Regex("outcome=(\\w+)").find(it)!!.groupValues[1] })
    }

    @Test
    fun staleTapOrLateExpiry_neverEndsANewerSession() {
        startWithInstagramBlock(session(id = "session-2"))

        val r = teardown.end(CommitmentTeardown.Kind.EXPIRED, expectedSessionId = "session-1")

        assertEquals(CommitmentTeardown.Outcome.ALREADY_ENDED, r.outcome)
        assertEquals("session-2", sessionStore.getStoredSession()?.id)
        assertEquals(AppRuleBehavior.BLOCK, appRules.getBehaviorForPackage(ig))
        assertFalse(events.contains(SessionExpiryPolicy.EXPIRED_EVENT_MESSAGE))
    }

    @Test
    fun lockedSession_endsTheSameWay() {
        startWithInstagramBlock(session(status = SessionStatus.LOCKED))
        val r = teardown.end(CommitmentTeardown.Kind.EMERGENCY_EXIT, "session-1")
        assertEquals(CommitmentTeardown.Outcome.ENDED, r.outcome)
        assertEquals(SessionStatus.LOCKED, r.ended?.status)
        assertNull(sessionStore.getStoredSession())
    }

    @Test
    fun expiry_racingPaths_logExpiredExactlyOnce() {
        startWithInstagramBlock()
        teardown.end(CommitmentTeardown.Kind.EXPIRED, "session-1")
        teardown.end(CommitmentTeardown.Kind.EXPIRED, "session-1")
        CommitmentTeardown(SessionStore(sessionPrefs), AppRulesStore(rulePrefs)) { events += it }
            .end(CommitmentTeardown.Kind.EXPIRED, "session-1")
        assertEquals(1, events.count { it == SessionExpiryPolicy.EXPIRED_EVENT_MESSAGE })
    }

    @Test
    fun processRecreation_freshStoresSeeNoSession_andAStaleLedgerIsRecovered() {
        startWithInstagramBlock()
        // Process died after the session was cleared but before rules were restored.
        sessionStore.clearSession()
        val recreated = CommitmentTeardown(SessionStore(sessionPrefs), AppRulesStore(rulePrefs)) { events += it }

        val r = recreated.end(CommitmentTeardown.Kind.EARLY_END, "session-1")

        assertEquals(CommitmentTeardown.Outcome.ALREADY_ENDED, r.outcome)
        assertEquals(Rules.RESTORED, r.rules)
        assertFalse(AppRulesStore(rulePrefs).hasCommitmentLedger())
        assertNull(SessionStore(sessionPrefs).getStoredSession())
    }

    @Test
    fun teardown_neverTouchesPermanentGuardrails() {
        val source = listOf(
            File("src/main/java/com/phonecodex/app/data/CommitmentTeardown.kt"),
            File("app/src/main/java/com/phonecodex/app/data/CommitmentTeardown.kt")
        ).first { it.exists() }.readText()
        assertFalse(source.contains("PermanentGuardrailsStore"))
        assertFalse(source.contains("setGuardrailEnabled"))
    }

    @Test
    fun everyEndPath_usesTheSharedTeardown() {
        val paths = listOf(
            "ui/home/HomeScreenState.kt",
            "ui/home/HomeScreenPollingEffect.kt",
            "accessibility/PhoneCodexAccessibilityService.kt",
            "protection/ProtectionHeartbeatService.kt"
        )
        for (path in paths) {
            val text = listOf(File("src/main/java/com/phonecodex/app/$path"), File("app/src/main/java/com/phonecodex/app/$path"))
                .first { it.exists() }.readText()
            assertTrue("$path ends via CommitmentTeardown", "CommitmentTeardown" in text)
            assertFalse("$path must not hand-roll teardown", ".restoreCommitmentRules()" in text)
            assertFalse("$path must not log recovery it does not perform", "Cooldown unlock requested" in text)
        }
    }

    // --- Emergency exit bounds -------------------------------------------------------------

    private val policy = RecoveryPolicyDefaults.default()

    @Test
    fun emergencyExit_availableOncePer24Hours() {
        val s = session()
        assertEquals(EmergencyExitGate.Availability.AVAILABLE, EmergencyExitGate.availability(s, policy, null, 1_000L))
        val usedAt = 1_000L
        assertEquals(
            EmergencyExitGate.Availability.USED_RECENTLY,
            EmergencyExitGate.availability(s, policy, usedAt, usedAt + EmergencyExitGate.WINDOW_MILLIS - 1)
        )
        assertEquals(
            EmergencyExitGate.Availability.AVAILABLE,
            EmergencyExitGate.availability(s, policy, usedAt, usedAt + EmergencyExitGate.WINDOW_MILLIS)
        )
    }

    @Test
    fun emergencyExit_respectsPolicyAndNeedsASession() {
        val off: RecoveryPolicy = policy.copy(emergencyOverrideAllowed = false)
        assertEquals(EmergencyExitGate.Availability.DISABLED_BY_POLICY, EmergencyExitGate.availability(session(), off, null, 0L))
        assertEquals(EmergencyExitGate.Availability.NO_SESSION, EmergencyExitGate.availability(null, policy, null, 0L))
    }

    @Test
    fun emergencyExitStore_survivesRecreation() {
        val prefs = InMemorySharedPreferences()
        EmergencyExitStore(prefs).recordUse(42L)
        assertEquals(42L, EmergencyExitStore(prefs).lastUsedAtMillis())
        assertNull(EmergencyExitStore(InMemorySharedPreferences()).lastUsedAtMillis())
    }

    @Test
    fun earlyEndFriction_staysDistinctFromEmergencyExit() {
        assertEquals(ExitFriction.TYPED_PHRASE, CommitmentExitGate.frictionFor(session(), nowMillis = 0L))
        assertEquals(ExitFriction.NONE, CommitmentExitGate.frictionFor(session(), nowMillis = 10_000L))
    }

    // --- Strictness semantics ----------------------------------------------------------------

    @Test
    fun confirmedStrictness_drivesUserFacingFriction_worldStrictnessStaysForPolicyEngine() {
        val soft = session(promise = StrictnessLevel.SOFT)
        assertEquals(StrictnessLevel.SOFT, soft.confirmedStrictness)
        assertEquals(StrictnessLevel.STRICT, soft.strictness)
        assertTrue(CommitmentOverlayCopy.allowsContinue(OverlayKind.WARN, soft.confirmedStrictness))
        assertEquals(ExitFriction.NONE, CommitmentExitGate.frictionFor(soft, nowMillis = 0L))

        val legacy = session(promise = null)
        assertEquals("pre-field sessions fall back to world strictness", StrictnessLevel.STRICT, legacy.confirmedStrictness)
        assertFalse(CommitmentOverlayCopy.allowsContinue(OverlayKind.WARN, legacy.confirmedStrictness))
    }

    @Test
    fun confirmedStrictness_survivesProcessRecreation() {
        sessionStore.saveActiveSession(session(promise = StrictnessLevel.SMART))
        assertEquals(StrictnessLevel.SMART, SessionStore(sessionPrefs).getStoredSession()?.confirmedStrictness)
    }
}
