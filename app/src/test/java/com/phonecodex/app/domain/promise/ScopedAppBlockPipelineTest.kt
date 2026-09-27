package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.ScopeCanonicalizer
import com.phonecodex.app.domain.scope.ScopeIdentity
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeKindPresets
import com.phonecodex.app.domain.scope.ScopeSerialization
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Block YouTube Shorts" must become a structured short-form BLOCK plus a narrow scope,
 * never a whole-package YouTube BLOCK. Explicit whole-app blocks stay whole-app; scope/action
 * contradictions cannot start.
 */
class ScopedAppBlockPipelineTest {

    private val yt = VideoPlatformRegistry.YOUTUBE
    private val shortsText = "block youtube shorts for 1 hour"

    private fun typedScope(kind: ScopeKind, evidence: String): EnforcementScope =
        ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = kind,
                identity = ScopeIdentity.YOUTUBE,
                include = ScopeKindPresets.expand(kind, ScopeIdentity.YOUTUBE, packages = listOf(yt)).include,
                confidence = 0.9,
                evidenceSpans = listOf(evidence)
            )
        )

    private fun compiled(
        text: String,
        appRules: JSONArray,
        contentRules: JSONArray = JSONArray(),
        scope: EnforcementScope,
        blockedBullets: List<String> = emptyList()
    ): FocusPromise {
        val json = JSONObject()
            .put("rawText", text)
            .put("sessionDurationMinutes", 60)
            .put("strictness", "STRICT")
            .put("suggestedAppRules", appRules)
            .put("contentRules", contentRules)
            .put("clarificationRequired", false)
            .put("canStartCommitment", true)
            .put("scopeContract", "v1")
            .put("scope", ScopeSerialization.toJson(scope))
            .put(
                "userFacingConfirmation",
                JSONObject().put("understood", "Block YouTube for 1 hour.")
                    .put("blocked", JSONArray(blockedBullets))
                    .put("allowed", JSONArray())
            )
        val parsed = CompiledPromiseResponseParser.parse(json.toString())!!
        return PromiseClarificationLaw.apply(CompiledPromiseMapper.toFocusPromise(parsed))
    }

    private fun appRule(pkg: String, behavior: String, scope: String? = null): JSONObject =
        JSONObject().put("packageName", pkg).put("label", "YouTube").put("behavior", behavior)
            .also { if (scope != null) it.put("scope", scope) }

    private fun FocusPromise.ytBehavior(): AppRuleBehavior? =
        suggestedAppRules.firstOrNull { it.packageName == yt }?.behavior

    private fun FocusPromise.hasYtShortFormBlock(): Boolean = contentRules.any {
        it.packageName == yt && it.contentType == "short_form_video" &&
            it.action == ContentRuleAction.BLOCK && it.operator == null
    }

    @Test
    fun compiledScopedShortsBlock_becomesSurfaceBlock_notWholeApp() {
        val draft = compiled(
            text = shortsText,
            appRules = JSONArray().put(appRule(yt, "BLOCK", scope = "shorts")),
            scope = typedScope(ScopeKind.APP_SHORT_FORM_ONLY, "youtube shorts"),
            blockedBullets = listOf("YouTube")
        )
        assertNotEquals(AppRuleBehavior.BLOCK, draft.ytBehavior())
        assertTrue("structured short-form BLOCK", draft.hasYtShortFormBlock())
        assertFalse("valid narrow promise can start", draft.needsClarification)

        val bound = ConfirmedPromiseBinder.bind(draft)
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, bound.enforcementScope?.kind)
        assertEquals(listOf("$yt|${BlockedSurfaceCodec.SHORT_FORM}"), bound.blockedSurfaces)
    }

    @Test
    fun compiledWholeAppBlock_withNarrowScope_cannotStart() {
        val draft = compiled(
            text = shortsText,
            appRules = JSONArray().put(appRule(yt, "BLOCK")),
            scope = typedScope(ScopeKind.APP_SHORT_FORM_ONLY, "youtube shorts")
        )
        assertTrue(draft.appRuleScopeConflict)
        assertTrue(draft.needsClarification)
        assertEquals(false, draft.canStartCommitment)
    }

    @Test
    fun compiledUnrepresentableAppScope_neverWholeApp_andCannotStart() {
        val draft = compiled(
            text = "block youtube comments for 1 hour",
            appRules = JSONArray().put(appRule(yt, "BLOCK", scope = "comments")),
            scope = typedScope(ScopeKind.APP_ONLY, "youtube")
        )
        assertNotEquals(AppRuleBehavior.BLOCK, draft.ytBehavior())
        assertTrue(draft.needsClarification)
    }

    @Test
    fun compiledFreeTextShortsScope_becomesSurfaceBlock() {
        val draft = compiled(
            text = "Block YouTube Shorts only for the next 10 minutes. Keep YouTube Home, long videos, Chrome, and other apps available.",
            appRules = JSONArray().put(appRule(yt, "BLOCK", scope = "Shorts player only; not the whole app")),
            scope = typedScope(ScopeKind.APP_SHORT_FORM_ONLY, "YouTube Shorts only")
        )
        assertNotEquals(AppRuleBehavior.BLOCK, draft.ytBehavior())
        assertTrue("structured short-form BLOCK", draft.hasYtShortFormBlock())
        assertFalse(draft.needsClarification)
        assertEquals(listOf("$yt|${BlockedSurfaceCodec.SHORT_FORM}"), ConfirmedPromiseBinder.bind(draft).blockedSurfaces)
    }

    @Test
    fun classifyAppScope_freeText_narrowsOnlyPureShortForm() {
        val short = EffectivePromisePolicy.AppScope.SHORT_FORM
        val unknown = EffectivePromisePolicy.AppScope.UNKNOWN
        mapOf(
            "Shorts player only; not the whole app" to short,
            "only the Shorts player, not the entire app" to short,
            "short videos only" to short,
            "Reels tab" to short,
            "whole app except Shorts" to unknown,
            "everything but Shorts" to unknown,
            "Shorts and comments" to unknown,
            "Shorts plus the home feed" to unknown,
            "all of the app, including Shorts" to unknown,
            "comments" to unknown
        ).forEach { (raw, expected) ->
            assertEquals(raw, expected, EffectivePromisePolicy.classifyAppScope(raw))
        }
        assertEquals(EffectivePromisePolicy.AppScope.WHOLE_APP, EffectivePromisePolicy.classifyAppScope(""))
    }

    @Test
    fun compiledExplicitWholeApp_staysWholeApp() {
        val draft = compiled(
            text = "block youtube for 1 hour",
            appRules = JSONArray().put(appRule(yt, "BLOCK", scope = "")),
            scope = typedScope(ScopeKind.APP_ONLY, "youtube")
        )
        assertEquals(AppRuleBehavior.BLOCK, draft.ytBehavior())
        assertFalse(draft.hasYtShortFormBlock())
        assertFalse(draft.needsClarification)
        assertTrue(ConfirmedPromiseBinder.bind(draft).blockedSurfaces.isEmpty())
    }

    @Test
    fun offlineParser_blockYouTubeShorts_isNarrow() {
        val draft = FocusPromiseParser().parse(shortsText)
        assertNotEquals(AppRuleBehavior.BLOCK, draft.ytBehavior())
        assertTrue("structured short-form BLOCK", draft.hasYtShortFormBlock())
        assertFalse(draft.needsClarification)
        val bound = ConfirmedPromiseBinder.bind(draft)
        assertEquals(ScopeKind.APP_SHORT_FORM_ONLY, bound.enforcementScope?.kind)
        assertEquals(listOf("$yt|${BlockedSurfaceCodec.SHORT_FORM}"), bound.blockedSurfaces)
    }

    @Test
    fun offlineParser_blockYouTube_isNotNarrowed() {
        val draft = FocusPromiseParser().parse("block youtube for 1 hour")
        assertFalse(draft.hasYtShortFormBlock())
        assertNotEquals(ScopeKind.APP_SHORT_FORM_ONLY, draft.enforcementScope?.kind)
        assertTrue(ConfirmedPromiseBinder.bind(draft).blockedSurfaces.isEmpty())
    }

    @Test
    fun offlineParser_whatsAppWholeAppBlock_unchanged() {
        val draft = FocusPromiseParser().parse("do not allow whatsapp for 10 minutes")
        assertEquals(
            AppRuleBehavior.BLOCK,
            draft.suggestedAppRules.first { it.packageName == "com.whatsapp" }.behavior
        )
        assertFalse(draft.needsClarification)
    }

    @Test
    fun goalText_surfaceQualifiedAlias_isNotAWholeAppBlock() {
        assertEquals(
            AppRuleBehavior.AI_DECIDE,
            PromiseIntentRules.effectiveAppRule(AppRuleBehavior.AI_DECIDE, shortsText, yt)
        )
        assertEquals(
            AppRuleBehavior.AI_DECIDE,
            PromiseIntentRules.effectiveAppRule(AppRuleBehavior.AI_DECIDE, "no youtube reels today", yt)
        )
        assertEquals(
            AppRuleBehavior.BLOCK,
            PromiseIntentRules.effectiveAppRule(AppRuleBehavior.AI_DECIDE, "no whatsapp for 1 hour", "com.whatsapp")
        )
        assertEquals(
            AppRuleBehavior.BLOCK,
            PromiseIntentRules.effectiveAppRule(AppRuleBehavior.BLOCK, shortsText, yt)
        )
    }

    @Test
    fun blockedSurfaceCodec_roundTrips_andDropsUnknown() {
        val rules = listOf("$yt|short_form", "com.instagram.android|short_form")
        assertEquals(rules.toSet(), BlockedSurfaceCodec.decode(BlockedSurfaceCodec.encode(rules)).toSet())
        assertEquals(
            listOf("$yt|short_form"),
            BlockedSurfaceCodec.decode(setOf("$yt|short_form", "$yt|comments", "garbage", "|short_form"))
        )
        assertTrue(BlockedSurfaceCodec.decode(null).isEmpty())
    }
}
