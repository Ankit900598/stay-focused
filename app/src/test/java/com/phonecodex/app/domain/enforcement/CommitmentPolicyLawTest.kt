package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.enforcement.trace.EnforcementTrace
import com.phonecodex.app.domain.enforcement.trace.EnforcementTraceEvaluator
import com.phonecodex.app.domain.enforcement.trace.TraceDecision
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.PermanentGuardrail
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.FocusPromiseParser
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One decision law: precedence of [CommitmentPolicyEvaluator] and proof that the live
 * AccessibilityService and offline replay both delegate to it.
 */
class CommitmentPolicyLawTest {

    private val yt = VideoPlatformRegistry.YOUTUBE
    private val chrome = VideoPlatformRegistry.CHROME
    private val min40Goal = "allow only YouTube videos longer than 40 minutes"
    private val min40 = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        minVideoLengthBlockMinutes = 40,
        enforcementContentBrands = listOf(EnforcementScopeLaw.BRAND_YOUTUBE),
        enforcementScopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_CONTENT
    )
    private val adultGuardrail = PermanentGuardrail(
        id = "porn_guardrail",
        name = "No adult content",
        enabled = true,
        blockedKeywords = emptySet(),
        blockedPackages = emptySet(),
        createdAtMillis = 1L,
        expiresAtMillis = null
    )

    private fun law(
        pkg: String,
        text: String,
        goal: String = min40Goal,
        settings: StudyWorldSettings = min40,
        session: SessionStatus? = SessionStatus.ACTIVE,
        rule: AppRuleBehavior? = AppRuleBehavior.AI_DECIDE,
        guardrails: List<PermanentGuardrail> = emptyList(),
        noise: Boolean = false,
        quotaGate: ShortFormQuotaGate = ShortFormQuotaGate(),
        dossier: AppDossier? = null
    ) = CommitmentPolicyEvaluator.evaluate(
        CommitmentSnapshot(
            packageName = pkg,
            screenText = text,
            sessionStatus = session,
            goal = goal,
            settings = settings,
            storedAppRule = rule,
            isSafePackage = SafeAppsCatalog.isDefaultPackage(pkg),
            isSystemOverlayNoise = noise,
            guardrails = guardrails,
            storedDossier = dossier
        ),
        quotaGate
    )

    private val noGamesGoal = "no games for 4 hours"
    private val noGames = ConfirmedPromiseBinder.bind(FocusPromiseParser().parse(noGamesGoal))
    private val cloneApp = "com.xyz.randomchat"
    private val cloneScreen = "Home Discover"
    private val researchedAsGame = AppDossier(packageName = cloneApp, localClass = AppClass.UNKNOWN)
        .withCounsel(AppClass.GAME, confidence = 0.9, summary = "Casual game", source = AppDossier.SOURCE_AZURE_WEB, nowMillis = 1L)

    @Test
    fun persistedDossier_changesTheCategoryDecision_liveAndReplayAlike() {
        val unknown = law(cloneApp, cloneScreen, goal = noGamesGoal, settings = noGames)
        assertNotEquals(LawVerdict.BLOCK, unknown.verdict)

        val researched = law(cloneApp, cloneScreen, goal = noGamesGoal, settings = noGames, dossier = researchedAsGame)
        assertEquals(LawVerdict.BLOCK, researched.verdict)
        assertEquals(LawStage.ENTERTAINMENT_BAN, researched.stage)
        assertEquals(EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, researched.reasonCode)
        assertTrue(CommitmentPolicyEvaluator.readsStoredDossier(cloneApp, noGamesGoal, SessionStatus.ACTIVE))

        val replay = EnforcementTraceEvaluator.evaluate(
            EnforcementTrace(
                id = "dossier_game_fixture",
                packageName = cloneApp,
                screenText = cloneScreen,
                goal = noGamesGoal,
                settings = noGames,
                storedDossier = researchedAsGame,
                expectedDecisions = setOf(TraceDecision.BLOCK)
            )
        )
        assertEquals(researched.reasonCode, replay.reasonCode)
        assertEquals(TraceDecision.BLOCK, replay.decision)
    }

    @Test
    fun dossierForAnotherPackage_isIgnored() {
        val wrongPkg = researchedAsGame.copy(packageName = "com.other.app")
        val r = law(cloneApp, cloneScreen, goal = noGamesGoal, settings = noGames, dossier = wrongPkg)
        assertNotEquals(LawVerdict.BLOCK, r.verdict)
    }

    @Test
    fun whenPreflightSaysNoRead_theStoredDossierCannotChangeTheDecision() {
        val cases = listOf(
            Triple(cloneApp, min40Goal, SessionStatus.ACTIVE),
            Triple(cloneApp, noGamesGoal, null),
            Triple("com.android.contacts", "Monk mode for 4 hours. Only calls and study.", SessionStatus.ACTIVE),
            Triple(yt, min40Goal, SessionStatus.ACTIVE)
        )
        for ((pkg, goal, session) in cases) {
            assertFalse("$pkg/$goal", CommitmentPolicyEvaluator.readsStoredDossier(pkg, goal, session))
            val dossier = researchedAsGame.copy(packageName = pkg)
            val settings = if (goal == min40Goal) min40 else ConfirmedPromiseBinder.bind(FocusPromiseParser().parse(goal))
            val without = law(pkg, cloneScreen, goal = goal, settings = settings, session = session)
            val with = law(pkg, cloneScreen, goal = goal, settings = settings, session = session, dossier = dossier)
            assertEquals("$pkg/$goal", without.copy(policyVersion = ""), with.copy(policyVersion = ""))
        }
    }

    @Test
    fun unreadableQuotaState_blocksOnlyAnActiveShortPlay_withNoCount() {
        val quotaGoal = "I want to watch at most 10 shorts today"
        val quota10 = StudyWorldSettings(
            durationMinutes = 1440,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            shortFormDailyQuotaLimit = 10,
            timeWindowKind = "calendar_day"
        )
        val unreadable = object : ShortFormQuotaGate.Ledger {
            override fun load(dayKey: String) = ShortFormQuotaGate.LedgerRead.Unreadable("count has wrong type")
            override fun save(dayKey: String, count: Int, signatures: Set<String>) = Unit
        }
        fun run(pkg: String, text: String) =
            law(pkg, text, goal = quotaGoal, settings = quota10, quotaGate = ShortFormQuotaGate(ledger = unreadable))

        val short = run(yt, "Shorts Like Dislike Comment Share Remix Subscribe #shorts")
        assertEquals(LawVerdict.BLOCK, short.verdict)
        assertEquals(EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE, short.reasonCode)
        assertEquals(null, short.quota?.count)

        val home = run(yt, "YouTube Home Search Shorts Subscriptions You Explore recommended for you 0:45 1:12")
        assertNotEquals(EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE, home.reasonCode)
        assertNotEquals(LawVerdict.BLOCK, home.verdict)

        val chat = run("com.whatsapp", "WhatsApp Chats Updates Calls")
        assertNotEquals(LawVerdict.BLOCK, chat.verdict)
    }

    @Test
    fun emergencyDialer_survivesAnExplicitBlockRule() {
        val r = law("com.android.phone", "Emergency call Phone keypad", goal = "block phone app", rule = AppRuleBehavior.BLOCK)
        assertEquals(LawVerdict.ALLOW, r.verdict)
        assertEquals(LawStage.SAFE_APP, r.stage)
    }

    @Test
    fun permanentGuardrail_blocksWithoutAnySession() {
        val r = law(chrome, "pornhub.com free porn videos", session = null, guardrails = listOf(adultGuardrail))
        assertEquals(LawVerdict.BLOCK, r.verdict)
        assertEquals(LawStage.PERMANENT_GUARDRAIL, r.stage)
    }

    @Test
    fun noSession_allows_andOwnOverlayNoiseIsIgnored() {
        assertEquals(LawStage.NO_SESSION, law(yt, "YouTube Home", session = null).stage)
        val noise = law("com.example.overlay", "Notification shade", noise = true)
        assertEquals(LawVerdict.IGNORE, noise.verdict)
    }

    @Test
    fun browserShortsUnderMinimumLength_blocksDeterministically() {
        val r = law(chrome, "m.youtube.com/shorts Back Search Like Share The Blueprint for Success #motivation")
        assertEquals(LawVerdict.BLOCK, r.verdict)
        assertEquals(EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN, r.reasonCode)
    }

    @Test
    fun lockedSession_followsThePhoneChain_noReplayOnlyLockUpgrade() {
        val home = "YouTube Home Search Shorts Subscriptions You Explore recommended for you 0:45 1:12"
        val r = law(yt, home, session = SessionStatus.LOCKED)
        assertEquals(LawVerdict.ALLOW, r.verdict)
        assertEquals(LawStage.SESSION_LOCK_UNRELATED, r.stage)
        assertEquals(EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED, r.reasonCode)
    }

    @Test
    fun shortsOnlyPromise_uncertainSurfaceWaits_insteadOfGuessing() {
        val settings = ConfirmedPromiseBinder.bind(FocusPromiseParser().parse("block youtube shorts for 1 hour"))
        val r = law(yt, "Loading", goal = "block youtube shorts for 1 hour", settings = settings)
        assertEquals(LawVerdict.WAIT, r.verdict)
        assertEquals(EnforcementReasonCodes.SCOPE_EVIDENCE_INDETERMINATE, r.reasonCode)
    }

    @Test
    fun appRuleAllow_neverSkipsMediaLaw_andAdvisoryOnlyRunsLast() {
        val longTooShort = "Hide player controls Time duration 12 minutes Subscribe"
        val r = law(yt, longTooShort, rule = AppRuleBehavior.ALLOW)
        assertEquals(LawVerdict.BLOCK, r.verdict)
        assertEquals(LawStage.MEDIA_LENGTH, r.stage)
        val counsel = law("com.example.unknownapp", "Some unknown feed screen", rule = AppRuleBehavior.AI_DECIDE)
        if (counsel.verdict == LawVerdict.CONTINUE) assertEquals(LawStage.APP_RULE, counsel.stage)
        assertEquals(LawStage.APP_RULE, LawStage.entries.last())
    }

    @Test
    fun everyDecisionNamesThePolicyItRan() {
        val r = law(yt, "YouTube Home")
        assertTrue(r.policyVersion, r.policyVersion.startsWith("law=v1 scope=legacy compiler=unknown"))
        val typed = law(
            yt,
            "YouTube Home",
            settings = min40.copy(compilerPromptVersion = "promise_compiler_v07")
        )
        assertTrue(typed.policyVersion, typed.policyVersion.endsWith("compiler=promise_compiler_v07"))
    }

    @Test
    fun replay_isExactlyTheLawVerdict() {
        val cases = listOf(
            Triple(chrome, "m.youtube.com/shorts Back Search Like Share #motivation", SessionStatus.ACTIVE),
            Triple(yt, "Hide player controls Time duration 1 hour 25 minutes Subscribe", SessionStatus.ACTIVE),
            Triple(yt, "YouTube Home Search Shorts Subscriptions", SessionStatus.LOCKED),
            Triple("com.whatsapp", "WhatsApp Chats Updates Calls", SessionStatus.ACTIVE)
        )
        for ((pkg, text, status) in cases) {
            val direct = law(pkg, text, session = status)
            val replay = EnforcementTraceEvaluator.evaluate(
                EnforcementTrace(
                    id = "parity_$pkg",
                    packageName = pkg,
                    screenText = text,
                    goal = min40Goal,
                    settings = min40,
                    sessionStatus = status,
                    expectedDecisions = TraceDecision.entries.toSet()
                )
            )
            assertEquals("$pkg reason", direct.reasonCode, replay.reasonCode)
            assertEquals("$pkg stage", direct.stage.name.lowercase(), replay.stage)
            assertEquals("$pkg decision", EnforcementTraceEvaluator.traceDecision(direct), replay.decision)
        }
    }

    @Test
    fun structural_liveServiceAndReplayDelegateToTheSameLaw() {
        val service = source("accessibility/PhoneCodexAccessibilityService.kt")
        val replay = source("domain/enforcement/trace/EnforcementTraceEvaluator.kt")
        val coordinator = source("domain/enforcement/PromiseEnforcementCoordinator.kt")

        assertEquals(1, Regex("""CommitmentPolicyEvaluator\.evaluate\(""").findAll(service).count())
        assertEquals(1, Regex("""CommitmentPolicyEvaluator\.evaluate\(""").findAll(replay).count())
        assertEquals(1, Regex("""CommitmentPolicyEvaluator\.evaluate\(""").findAll(coordinator).count())

        val decisionGates = listOf(
            "ScopeGate.evaluate(",
            "SessionLockLaw.",
            "BlankVideoTreeGate.evaluate(",
            "EntertainmentBanGate.evaluate(",
            "MediaLengthEnforcement.decideDetailed(",
            "shortFormQuotaGate.evaluate(",
            "PermanentGuardrailEvaluator()",
            "PromiseIntentRules.effectiveAppRule(",
            "PromiseIntentRules.explicitlyBlocksKnownPackage("
        )
        for (gate in decisionGates) {
            assertTrue("service must not decide via $gate", gate !in service)
            assertTrue("replay must not decide via $gate", gate !in replay)
            assertTrue("coordinator must not decide via $gate", gate !in coordinator)
        }

        val evaluator = source("domain/enforcement/CommitmentPolicyEvaluator.kt")
        assertTrue("snapshot dossier must be a value, not a closure", "dossier: () ->" !in evaluator)
        assertTrue("replay must pass the trace dossier fixture", "storedDossier = trace.storedDossier" in replay)
        val load = Regex(
            """storedDossier = if \(CommitmentPolicyEvaluator\.readsStoredDossier\([^)]*\)\) \{\s*appDossierStore\.get\(packageName\)"""
        )
        assertTrue("service loads the dossier only behind the preflight", load.containsMatchIn(service))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/phonecodex/app/$relative"),
            File("app/src/main/java/com/phonecodex/app/$relative")
        )
        return candidates.first { it.exists() }.readText()
    }
}
