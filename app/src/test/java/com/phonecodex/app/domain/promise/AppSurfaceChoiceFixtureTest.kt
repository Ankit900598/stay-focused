package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.PromiseEnforcementCoordinator
import com.phonecodex.app.domain.enforcement.PromiseEvalInput
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.ScopeAppliesToCopy
import com.phonecodex.app.domain.scope.ScopeCanonicalizer
import com.phonecodex.app.domain.scope.ScopeIdentity
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeKindPresets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stuck app-scope clarification, end to end on Android. Fixtures are real backend
 * compilePromise round-trips written by
 * `node backend/smoke_app_surface_choices.js --write-fixtures`.
 * The card, the saved typed scope / surfaces and the runtime decision must describe
 * the same policy.
 */
class AppSurfaceChoiceFixtureTest {

    private val ig = VideoPlatformRegistry.INSTAGRAM
    private val reel = "https://www.instagram.com/reel/CxyzABC123 audio original"
    private val feed = "Profile Posts Followers Following Edit profile Share profile Home Reels"
    private val dmInbox = "Messages Requests Primary General Search Your note Home Reels Profile"
    private val unknown = "Loading"

    private data class Fixture(val promise: String, val response: JSONObject, val draft: FocusPromise)

    private fun load(name: String): Fixture {
        val stream = javaClass.classLoader!!.getResourceAsStream("scope-contract/$name.json")
        assertNotNull("missing fixture $name; run the backend smoke with --write-fixtures", stream)
        val root = JSONObject(stream!!.bufferedReader().use { it.readText() })
        val responseJson = root.getJSONObject("response").toString()
        val parsed = CompiledPromiseResponseParser.parse(responseJson)
        assertNotNull("parse failed", parsed)
        val draft = PromiseClarificationLaw.apply(CompiledPromiseMapper.toFocusPromise(parsed!!))
        return Fixture(root.getString("promise"), JSONObject(responseJson), draft)
    }

    private fun decide(goal: String, settings: StudyWorldSettings, draft: FocusPromise, text: String) =
        PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = goal,
                settings = settings,
                appRule = PromiseIntentRules.effectiveAppRule(
                    draft.suggestedAppRules.firstOrNull { it.packageName == ig }?.behavior,
                    goal,
                    ig
                ),
                packageName = ig,
                screenText = text
            )
        )

    @Test
    fun turn1_offersTwoServerChoices_noneRecommended_cannotStart() {
        val f = load("app_surface_ig_turn1")
        assertTrue(f.draft.needsClarification)
        assertEquals(false, f.draft.canStartCommitment)
        val options = f.response.getJSONArray("clarificationOptions")
        assertEquals(2, options.length())
        assertEquals(listOf("A", "B"), f.draft.clarificationOptions.map { it.id })
        assertTrue("no preselected choice", f.draft.clarificationOptions.none { it.recommended })
        for (i in 0 until options.length()) {
            val o = options.getJSONObject(i)
            val scope = ScopeCanonicalizer.fromCompilerJson(o.getJSONObject("scope").toString(), f.promise)
            assertEquals(o.getString("label"), ScopeAppliesToCopy.optionLabel(scope))
            assertEquals(o.getString("label"), f.draft.clarificationOptions[i].label)
        }
        assertEquals(
            listOf(ScopeKind.APP_SHORT_FORM_ONLY, ScopeKind.APP_ONLY),
            (0 until options.length()).map {
                ScopeCanonicalizer.fromCompilerJson(
                    options.getJSONObject(it).getJSONObject("scope").toString(),
                    f.promise
                ).kind
            }
        )
    }

    @Test
    fun pickReelsOnly_savesShortFormSurface_andOnlyTheReelPlayerBlocks() {
        val f = load("app_surface_ig_pick_a")
        assertFalse(f.draft.needsClarification)
        assertEquals(true, f.draft.canStartCommitment)
        assertEquals(null, EffectivePromisePolicy.conflictReason(f.draft))
        val bound = ConfirmedPromiseBinder.bind(f.draft)
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, bound.enforcementScope?.kind)
        assertEquals(ScopeIdentity.INSTAGRAM, bound.enforcementScope?.identity)
        assertEquals(listOf("$ig|${BlockedSurfaceCodec.SHORT_FORM}"), bound.blockedSurfaces)
        assertFalse(f.draft.suggestedAppRules.any { it.packageName == ig && it.behavior == AppRuleBehavior.BLOCK })
        assertEquals(ScopeAppliesToCopy.forScope(bound.enforcementScope), f.draft.userFacingAppliesTo)
        assertTrue(f.draft.blockedSummaries.any { it.contains("Instagram Reels", ignoreCase = true) })

        val r = decide(f.promise, bound, f.draft, reel)
        assertEquals(DecisionType.BLOCK, r.decision)
        assertEquals(EnforcementReasonCodes.STRUCTURED_SURFACE_BLOCK, r.reasonCode)
        for ((name, text) in listOf("feed" to feed, "dm inbox" to dmInbox, "unknown" to unknown)) {
            val d = decide(f.promise, bound, f.draft, text)
            assertNotEquals("$name must stay open: ${d.reasonCode}", DecisionType.BLOCK, d.decision)
        }
    }

    @Test
    fun pickWholeApp_savesAppOnly_andEveryInstagramScreenBlocks() {
        val f = load("app_surface_ig_pick_b")
        assertEquals(true, f.draft.canStartCommitment)
        assertEquals(null, EffectivePromisePolicy.conflictReason(f.draft))
        val bound = ConfirmedPromiseBinder.bind(f.draft)
        assertEquals(ScopeKind.APP_ONLY, bound.enforcementScope?.kind)
        assertTrue(f.draft.suggestedAppRules.any { it.packageName == ig && it.behavior == AppRuleBehavior.BLOCK })
        assertTrue(bound.blockedSurfaces.isEmpty())
        for ((name, text) in listOf("reel" to reel, "feed" to feed, "dm inbox" to dmInbox)) {
            assertEquals(name, DecisionType.BLOCK, decide(f.promise, bound, f.draft, text).decision)
        }
    }

    @Test
    fun messagesOnly_isNeverStartable_andNeverSocialMessagingOnly() {
        val f = load("app_surface_ig_messages_only")
        assertTrue(f.draft.needsClarification)
        assertEquals(false, f.draft.canStartCommitment)
        assertTrue("no fake choices", f.draft.clarificationOptions.isEmpty())
        assertTrue(f.draft.warnings.any { it.contains("messages", ignoreCase = true) })
    }

    // --- Picks never delete independent rules -------------------------------------------

    private val yt = VideoPlatformRegistry.YOUTUBE

    private fun FocusPromise.rules(
        pkg: String?,
        type: String,
        action: ContentRuleAction,
        operator: String? = null
    ) = contentRules.filter {
        it.packageName == pkg && it.contentType == type && it.action == action && it.operator == operator
    }

    private fun assertRichIndependentRulesKept(draft: FocusPromise) {
        assertEquals(1, draft.rules(ig, "adult_sexual", ContentRuleAction.BLOCK).size)
        assertEquals(1, draft.rules(null, "short_form_video", ContentRuleAction.BLOCK).size)
        assertEquals(1, draft.rules(yt, "short_form_video", ContentRuleAction.BLOCK).size)
        assertEquals(20, draft.rules(ig, "long_form_video", ContentRuleAction.BLOCK, "gt").single().value)
        assertEquals(30, draft.contentRules.single { it.contentType == "usage_quota" }.value)
        assertFalse(
            "YouTube never gains a whole-app BLOCK",
            draft.suggestedAppRules.any { it.packageName == yt && it.behavior == AppRuleBehavior.BLOCK }
        )
    }

    private fun scopeGapWarning(draft: FocusPromise): String =
        draft.warnings.single { it.contains("can't also enforce", ignoreCase = true) }

    private val igAdultRule = ContentRule(
        appLabel = "Instagram",
        packageName = VideoPlatformRegistry.INSTAGRAM,
        surface = "adult",
        contentType = "adult_sexual",
        operator = null,
        value = null,
        unit = null,
        action = ContentRuleAction.BLOCK
    )

    @Test
    fun richPickReelsOnly_keepsEveryRule_butScopeCannotReachThem_soCannotStart() {
        val f = load("app_surface_ig_rich_pick_a")
        assertRichIndependentRulesKept(f.draft)
        assertEquals(1, f.draft.rules(ig, "short_form_video", ContentRuleAction.BLOCK).size)
        assertEquals(1, f.draft.rules(ig, "social_dm", ContentRuleAction.ALLOW).size)
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, f.draft.enforcementScope?.kind)
        assertEquals(EffectivePromisePolicy.APP_SCOPE_MISSES_RULES, EffectivePromisePolicy.conflictReason(f.draft))
        assertEquals(false, f.draft.canStartCommitment)
        assertTrue(f.draft.clarificationOptions.isEmpty())
        val warning = scopeGapWarning(f.draft)
        for (named in listOf("YouTube Shorts", "short-form videos everywhere", "Instagram adult content", "longer than 20 minutes")) {
            assertTrue("warning names $named: $warning", warning.contains(named, ignoreCase = true))
        }
        assertTrue(f.draft.blockedSummaries.any { it.contains("Instagram adult content", ignoreCase = true) })
        assertFalse("feed not claimed open", f.draft.allowedSummaries.any { it.contains("feed", ignoreCase = true) })
    }

    @Test
    fun richPickWholeApp_dropsOnlyTheContradictingAllow_andNamesOtherAppRules() {
        val f = load("app_surface_ig_rich_pick_b")
        assertRichIndependentRulesKept(f.draft)
        assertTrue(f.draft.suggestedAppRules.any { it.packageName == ig && it.behavior == AppRuleBehavior.BLOCK })
        assertTrue(f.draft.contentRules.none { it.packageName == ig && it.action == ContentRuleAction.ALLOW })
        assertTrue(f.draft.rules(ig, "short_form_video", ContentRuleAction.BLOCK).isEmpty())
        assertEquals(ScopeKind.APP_ONLY, f.draft.enforcementScope?.kind)
        assertEquals(EffectivePromisePolicy.APP_SCOPE_MISSES_RULES, EffectivePromisePolicy.conflictReason(f.draft))
        assertEquals(false, f.draft.canStartCommitment)
        val warning = scopeGapWarning(f.draft)
        assertTrue(warning, warning.contains("YouTube Shorts", ignoreCase = true))
        assertFalse("All of Instagram covers the Instagram adult rule: $warning", warning.contains("Instagram adult", ignoreCase = true))
        assertFalse(f.draft.allowedSummaries.any { it.contains("Instagram", ignoreCase = true) })
    }

    @Test
    fun richEditedPromisePick_failsClosed_withEveryRuleIntact() {
        val f = load("app_surface_ig_rich_stale")
        assertEquals(false, f.draft.canStartCommitment)
        assertRichIndependentRulesKept(f.draft)
        assertEquals(1, f.draft.rules(ig, "social_dm", ContentRuleAction.ALLOW).size)
    }

    @Test
    fun soloPickWholeApp_keepsInstagramAdultAndLengthRules_andStarts() {
        val f = load("app_surface_ig_solo_pick_b")
        assertEquals(true, f.draft.canStartCommitment)
        assertEquals(null, EffectivePromisePolicy.conflictReason(f.draft))
        assertEquals(1, f.draft.rules(ig, "adult_sexual", ContentRuleAction.BLOCK).size)
        assertEquals(1, f.draft.rules(ig, "long_form_video", ContentRuleAction.BLOCK, "gt").size)
        assertTrue(f.draft.contentRules.none { it.packageName == ig && it.action == ContentRuleAction.ALLOW })
        val bound = ConfirmedPromiseBinder.bind(f.draft)
        assertEquals(ScopeKind.APP_ONLY, bound.enforcementScope?.kind)
        assertEquals(20, bound.maxVideoLengthBlockMinutes)
        assertTrue(f.draft.blockedSummaries.any { it.contains("adult", ignoreCase = true) })
        assertFalse(f.draft.allowedSummaries.any { it.contains("Instagram", ignoreCase = true) })
        for ((name, text) in listOf("reel" to reel, "feed" to feed, "dm inbox" to dmInbox)) {
            assertEquals(name, DecisionType.BLOCK, decide(f.promise, bound, f.draft, text).decision)
        }
    }

    @Test
    fun dedupePickReelsOnly_collapsesReelsRules_keepsMessagesAndOtherApp_andStarts() {
        val f = load("app_surface_ig_dedupe_pick_a")
        assertEquals(true, f.draft.canStartCommitment)
        assertEquals(null, EffectivePromisePolicy.conflictReason(f.draft))
        assertEquals(1, f.draft.rules(ig, "short_form_video", ContentRuleAction.BLOCK).size)
        assertEquals(1, f.draft.rules(ig, "social_dm", ContentRuleAction.ALLOW).size)
        assertTrue(f.draft.suggestedAppRules.any { it.packageName == "com.whatsapp" && it.behavior == AppRuleBehavior.ALLOW })
        val bound = ConfirmedPromiseBinder.bind(f.draft)
        assertEquals(listOf("$ig|${BlockedSurfaceCodec.SHORT_FORM}"), bound.blockedSurfaces)
        assertEquals(DecisionType.BLOCK, decide(f.promise, bound, f.draft, reel).decision)
        for ((name, text) in listOf("feed" to feed, "dm inbox" to dmInbox)) {
            assertNotEquals(name, DecisionType.BLOCK, decide(f.promise, bound, f.draft, text).decision)
        }
    }

    @Test
    fun singleAppScope_withRestrictionItCannotReach_isAConflict() {
        val reelsOnly = load("app_surface_ig_pick_a").draft
        val withYouTube = reelsOnly.copy(
            contentRules = reelsOnly.contentRules + EffectivePromisePolicy.shortFormSurfaceRule(yt, "YouTube")
        )
        assertEquals(EffectivePromisePolicy.APP_SCOPE_MISSES_RULES, EffectivePromisePolicy.conflictReason(withYouTube))
        assertEquals(false, PromiseClarificationLaw.apply(withYouTube).canStartCommitment)
        val withIgAdult = reelsOnly.copy(contentRules = reelsOnly.contentRules + igAdultRule)
        assertEquals(EffectivePromisePolicy.APP_SCOPE_MISSES_RULES, EffectivePromisePolicy.conflictReason(withIgAdult))

        val wholeApp = load("app_surface_ig_pick_b").draft
        assertEquals(null, EffectivePromisePolicy.conflictReason(wholeApp.copy(contentRules = wholeApp.contentRules + igAdultRule)))
    }

    @Test
    fun feedAndReelsScope_hasNoFeedEnforcement_soCannotStart() {
        val base = load("app_surface_ig_pick_a").draft
        val feedAndReels = ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.SOCIAL_FEED_AND_REELS_ONLY,
                identity = ScopeIdentity.INSTAGRAM,
                include = ScopeKindPresets.expand(ScopeKind.SOCIAL_FEED_AND_REELS_ONLY, ScopeIdentity.INSTAGRAM).include,
                confidence = 0.9,
                evidenceSpans = listOf("Instagram")
            )
        )
        val draft = base.copy(enforcementScope = feedAndReels)
        assertEquals(EffectivePromisePolicy.FEED_SURFACE_UNSUPPORTED, EffectivePromisePolicy.conflictReason(draft))
        assertEquals(false, PromiseClarificationLaw.apply(draft).canStartCommitment)
    }

    @Test
    fun socialExceptMessages_withDefaultSafeApp_cannotPromiseUnenforcedBlock() {
        val base = load("app_surface_ig_pick_a").draft
        for (identity in listOf(ScopeIdentity.WHATSAPP, ScopeIdentity.SOCIAL_ANY)) {
            val expansion = ScopeKindPresets.expand(ScopeKind.SOCIAL_MESSAGING_ONLY, identity)
            val scope = ScopeCanonicalizer.canonicalize(
                EnforcementScope(
                    version = EnforcementScope.CURRENT_VERSION,
                    kind = ScopeKind.SOCIAL_MESSAGING_ONLY,
                    identity = identity,
                    include = expansion.include,
                    exempt = expansion.exempt,
                    confidence = 0.9,
                    evidenceSpans = listOf("messages stay open")
                )
            )
            assertTrue("$identity scope must be valid", scope.isEnforceable)
            val draft = base.copy(enforcementScope = scope)
            assertEquals(
                "$identity must not be startable on an unenforced surface",
                EffectivePromisePolicy.SOCIAL_SAFE_APP_SURFACE_UNSUPPORTED,
                EffectivePromisePolicy.conflictReason(draft)
            )
            val gated = PromiseClarificationLaw.apply(draft)
            assertFalse(gated.canStartCommitment == true)
            assertTrue(gated.clarificationQuestion.orEmpty().contains("can't reliably"))
        }
    }
}
