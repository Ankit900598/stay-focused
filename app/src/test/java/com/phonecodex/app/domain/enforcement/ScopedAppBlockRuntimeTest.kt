package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.BlockedSurfaceCodec
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.FocusPromiseParser
import com.phonecodex.app.domain.promise.PromiseIntentRules
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.ScopeCanonicalizer
import com.phonecodex.app.domain.scope.ScopeIdentity
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeKindPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Runtime law for scoped app promises, through the shared coordinator:
 *   Shorts-only: Shorts player BLOCKs via the structured surface rule (not APP_RULE_BLOCK);
 *   home / long player / unknown surfaces never fall through to a package-wide BLOCK.
 *   APP_ONLY whole-app: home, long player and Shorts all BLOCK.
 */
class ScopedAppBlockRuntimeTest {

    private val yt = VideoPlatformRegistry.YOUTUBE
    private val shortsPlayer = "Home Shorts Subscriptions Remix this short Pause like comment"
    private val longPlayer = "Hide player controls Time duration 1 hour 25 minutes Subscribe"
    private val home =
        "YouTube Home Search Shorts Subscriptions You Explore recommended for you 0:45 1:12"
    private val unknown = "Loading"

    private fun eval(goal: String, settings: StudyWorldSettings, stored: AppRuleBehavior?, text: String) =
        PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = goal,
                settings = settings,
                appRule = PromiseIntentRules.effectiveAppRule(stored, goal, yt),
                packageName = yt,
                screenText = text
            )
        )

    private fun shortsOnly(): Pair<String, StudyWorldSettings> {
        val goal = "block youtube shorts for 1 hour"
        return goal to ConfirmedPromiseBinder.bind(FocusPromiseParser().parse(goal))
    }

    @Test
    fun shortsOnly_shortsPlayerBlocks_viaStructuredSurfaceRule() {
        val (goal, settings) = shortsOnly()
        val r = eval(goal, settings, AppRuleBehavior.AI_DECIDE, shortsPlayer)
        assertEquals(DecisionType.BLOCK, r.decision)
        assertEquals(EnforcementReasonCodes.STRUCTURED_SURFACE_BLOCK, r.reasonCode)
    }

    @Test
    fun shortsOnly_homeLongAndUnknown_neverBroadBlock() {
        val (goal, settings) = shortsOnly()
        for ((name, text) in listOf("home" to home, "long" to longPlayer, "unknown" to unknown)) {
            val r = eval(goal, settings, AppRuleBehavior.AI_DECIDE, text)
            assertNotEquals("$name must not BLOCK: ${r.reasonCode}", DecisionType.BLOCK, r.decision)
            assertNotEquals(name, EnforcementReasonCodes.APP_RULE_BLOCK, r.reasonCode)
        }
    }

    @Test
    fun structuredSurfaceBlock_worksOnLegacyScopePathToo() {
        val (goal, bound) = shortsOnly()
        val legacy = bound.copy(enforcementScope = null)
        assertEquals(
            DecisionType.BLOCK,
            eval(goal, legacy, AppRuleBehavior.AI_DECIDE, shortsPlayer).decision
        )
        assertNotEquals(
            DecisionType.BLOCK,
            eval(goal, legacy, AppRuleBehavior.AI_DECIDE, longPlayer).decision
        )
    }

    @Test
    fun compiledShortsOnlyLegacyPromise_allowsHomeAndLongVideoWithoutGenericWarn() {
        val goal =
            "Block only YouTube Shorts for the next 5 minutes. " +
                "Keep YouTube Home, long videos, Chrome, and other apps available."
        val (_, bound) = shortsOnly()
        val legacy = bound.copy(
            enforcementScope = null,
            enforcementScopePackages = emptyList(),
            enforcementContentBrands = listOf("youtube"),
            enforcementScopeKind = "youtube_content",
            blockedSurfaces = listOf("$yt|${BlockedSurfaceCodec.SHORT_FORM}")
        )

        for ((name, text) in listOf("home" to home, "long" to longPlayer)) {
            val result = PromiseEnforcementCoordinator.evaluate(
                PromiseEvalInput(
                    hasActiveSession = true,
                    goal = goal,
                    settings = legacy,
                    appRule = AppRuleBehavior.AI_DECIDE,
                    packageName = yt,
                    screenText = text
                )
            )
            assertEquals("$name must remain outside a Shorts-only rule", DecisionType.ALLOW, result.decision)
            assertEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, result.reasonCode)
        }
    }

    @Test
    fun compiledShortsOnlyLegacyPromise_unknownSurfaceWaitsAndShortsStillBlock() {
        val goal = "Block only YouTube Shorts for the next 5 minutes. Keep long videos available."
        val (_, bound) = shortsOnly()
        val legacy = bound.copy(
            enforcementScope = null,
            enforcementScopePackages = emptyList(),
            enforcementContentBrands = listOf("youtube"),
            enforcementScopeKind = "youtube_content",
            blockedSurfaces = listOf("$yt|${BlockedSurfaceCodec.SHORT_FORM}")
        )

        val unknown = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(true, goal, legacy, AppRuleBehavior.AI_DECIDE, yt, "Loading")
        )
        assertEquals(DecisionType.ALLOW, unknown.decision)
        assertEquals(EnforcementReasonCodes.SCOPE_EVIDENCE_INDETERMINATE, unknown.reasonCode)

        val short = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(true, goal, legacy, AppRuleBehavior.AI_DECIDE, yt, shortsPlayer)
        )
        assertEquals(DecisionType.BLOCK, short.decision)
        assertEquals(EnforcementReasonCodes.STRUCTURED_SURFACE_BLOCK, short.reasonCode)
    }

    @Test
    fun appWideBlockStillWinsOutsideStructuredShortSurface() {
        val goal = "Block only YouTube Shorts for the next 5 minutes."
        val (_, bound) = shortsOnly()
        val legacy = bound.copy(
            enforcementScope = null,
            enforcementScopePackages = emptyList(),
            enforcementContentBrands = listOf("youtube"),
            enforcementScopeKind = "youtube_content",
            blockedSurfaces = listOf("$yt|${BlockedSurfaceCodec.SHORT_FORM}")
        )
        val result = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(true, goal, legacy, AppRuleBehavior.BLOCK, yt, home)
        )
        assertEquals(DecisionType.BLOCK, result.decision)
        assertEquals(EnforcementReasonCodes.APP_RULE_BLOCK, result.reasonCode)
    }

    @Test
    fun appOnlyWholeApp_blocksHomeLongAndShorts() {
        val goal = "block youtube for one hour"
        val appOnly = ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.APP_ONLY,
                identity = ScopeIdentity.YOUTUBE,
                include = ScopeKindPresets.expand(
                    ScopeKind.APP_ONLY,
                    ScopeIdentity.YOUTUBE,
                    packages = listOf(yt)
                ).include,
                confidence = 0.9,
                evidenceSpans = listOf("youtube")
            )
        )
        val settings = ConfirmedPromiseBinder.bind(FocusPromiseParser().parse(goal))
            .copy(enforcementScope = appOnly)
        // A compiled whole-app promise persists YouTube BLOCK in AppRulesStore at Start.
        for ((name, text) in listOf("home" to home, "long" to longPlayer, "shorts" to shortsPlayer)) {
            val r = eval(goal, settings, AppRuleBehavior.BLOCK, text)
            assertEquals("$name: ${r.reasonCode}", DecisionType.BLOCK, r.decision)
        }
    }

    @Test
    fun blockedSurfacesPersistOnlyFromStructuredRules() {
        val (_, settings) = shortsOnly()
        assertEquals(listOf("$yt|${BlockedSurfaceCodec.SHORT_FORM}"), settings.blockedSurfaces)
    }
}
