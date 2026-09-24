package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.PromiseEnforcementCoordinator
import com.phonecodex.app.domain.enforcement.PromiseEvalInput
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.StudyWorldSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A F4b: the coordinator (unit-test law) and the AccessibilityService
 * (live phone path) MUST share the same [ScopeGate] preflight contract before
 * gate internals run. If the two ever disagree, one of them is enforcing a
 * verdict the other one skipped -- a false-block / false-allow bug that
 * chrome_browser rematerialize used to reproduce weekly.
 *
 * Directly asserting the live service is expensive (it needs Android context).
 * So we do the next-strongest thing: assert that
 *   ScopeGate.evaluate(pkg, text, goal, settings)
 * produces a verdict that, when consumed by the coordinator's short-circuit
 * ladder, yields the exact same (decision, reasonCode) as the free-standing
 * ScopeGate would demand. The live AccessibilityService uses the same
 * ScopeGate call in its `inspectAndEvaluate` -- see the code review anchor
 * in `PhoneCodexAccessibilityService.inspectAndEvaluate` and the ladder in
 * `PromiseEnforcementCoordinator.evaluate`.
 */
class ScopeGateSharedContractTest {

    private val YT = VideoPlatformRegistry.YOUTUBE
    private val CHROME = VideoPlatformRegistry.CHROME

    private fun coordinator(
        pkg: String,
        text: String,
        goal: String,
        settings: StudyWorldSettings,
        appRule: AppRuleBehavior? = AppRuleBehavior.AI_DECIDE
    ) = PromiseEnforcementCoordinator.evaluate(
        PromiseEvalInput(
            hasActiveSession = true,
            goal = goal,
            settings = settings,
            appRule = appRule,
            packageName = pkg,
            screenText = text
        )
    )

    @Test
    fun outOfScopeChrome_underAppOnlyYoutube_coordinatorShortCircuitsWithEnforcementScopeAllow() {
        val settings = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScopePackages = listOf(YT),
            enforcementScopeKind = EnforcementScopeLaw_SCOPE_YOUTUBE_APP_ONLY
        )
        val outcome = ScopeGate.evaluate(
            packageName = CHROME,
            screenText = "https://www.youtube.com/watch?v=abcd Hide player controls 00:15 / 12:00",
            goal = "YouTube app only for 1 hour",
            settings = settings
        )
        // ScopeGate says OutOfScope, monk-override off -> coordinator must ALLOW with
        // ENFORCEMENT_SCOPE_ALLOW. Direct assertion of the shared contract.
        assertTrue(outcome.match is ScopeMatch.OutOfScope)
        assertEquals(false, outcome.monkOverridesScope)

        val res = coordinator(
            pkg = CHROME,
            text = "https://www.youtube.com/watch?v=abcd Hide player controls 00:15 / 12:00",
            goal = "YouTube app only for 1 hour",
            settings = settings
        )
        assertEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, res.reasonCode)
    }

    @Test
    fun unresolvedExplicitScope_coordinatorReturnsScopeUnresolvedNoEnforce() {
        val unresolved = EnforcementScope.unresolved(
            reason = UnresolvedReason.HOST_AMBIGUOUS,
            evidenceSpans = listOf("only YouTube")
        )
        val settings = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScope = unresolved
        )
        val outcome = ScopeGate.evaluate(
            packageName = YT,
            screenText = "Time elapsed 00:15 Time duration 00:59 Subscribe",
            goal = "only YouTube",
            settings = settings
        )
        assertTrue(outcome.match is ScopeMatch.Unresolved)

        val res = coordinator(YT, "Time elapsed 00:15 Time duration 00:59 Subscribe", "only YouTube", settings)
        assertEquals(EnforcementReasonCodes.SCOPE_UNRESOLVED_NO_ENFORCE, res.reasonCode)
    }

    @Test
    fun explicitScope_socialMessagingOnly_dmSurfaceExemptGetsScopeExemptAllow() {
        val exp = ScopeKindPresets.expand(
            kind = ScopeKind.SOCIAL_MESSAGING_ONLY,
            identity = ScopeIdentity.INSTAGRAM
        )
        val scope = EnforcementScope(
            version = EnforcementScope.CURRENT_VERSION,
            kind = ScopeKind.SOCIAL_MESSAGING_ONLY,
            identity = ScopeIdentity.INSTAGRAM,
            include = exp.include,
            exempt = exp.exempt,
            confidence = 0.9,
            evidenceSpans = listOf("IG messages only")
        )
        val settings = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2,
            enforcementScope = scope
        )
        val res = coordinator(
            pkg = "com.instagram.android",
            text = "Type a message",
            goal = "IG messages only",
            settings = settings
        )
        assertEquals(EnforcementReasonCodes.SCOPE_EXEMPT_ALLOW, res.reasonCode)
    }

    @Test
    fun inScopeUnderAdapter_falls_through_to_gates_notScopeShortCircuit() {
        val settings = StudyWorldSettings(
            durationMinutes = 60,
            lockAttemptThreshold = 3,
            blockAttemptCooldownMinutes = 2
        )
        val outcome = ScopeGate.evaluate(
            packageName = YT,
            screenText = "Time elapsed 00:15 Time duration 00:59 Subscribe",
            goal = "only YouTube videos longer than 40 min",
            settings = settings
        )
        assertTrue(outcome.match is ScopeMatch.InScope)
        assertEquals(false, outcome.shortCircuits)
    }

    // Fully-qualified constant referenced across packages; alias avoids a wildcard import.
    private companion object {
        const val EnforcementScopeLaw_SCOPE_YOUTUBE_APP_ONLY =
            com.phonecodex.app.domain.enforcement.EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
    }
}
