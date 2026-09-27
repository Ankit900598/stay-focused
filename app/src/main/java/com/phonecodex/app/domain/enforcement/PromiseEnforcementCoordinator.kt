package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder

/**
 * Session-policy view of [CommitmentPolicyEvaluator] for callers that start after the
 * safe-app / guardrail stages and never count quota plays (unit tests, diagnostics).
 * It adds no law of its own.
 */
data class PromiseEvalInput(
    val hasActiveSession: Boolean,
    val goal: String,
    val settings: StudyWorldSettings,
    val appRule: AppRuleBehavior?,
    val packageName: String,
    val screenText: String
)

data class PromiseEvalResult(
    val decision: DecisionType?,
    val reasonCode: String,
    val stage: String,
    val contentGatesRanBeforeAppRule: Boolean,
    val appRuleBehavior: String?,
    val prelude: String
)

object PromiseEnforcementCoordinator {

    fun evaluate(input: PromiseEvalInput): PromiseEvalResult {
        val law = CommitmentPolicyEvaluator.evaluate(
            CommitmentSnapshot(
                packageName = input.packageName,
                screenText = input.screenText,
                sessionStatus = if (input.hasActiveSession) SessionStatus.ACTIVE else null,
                goal = input.goal,
                settings = input.settings,
                storedAppRule = input.appRule,
                isSafePackage = false
            ),
            ShortFormQuotaGate()
        )
        return PromiseEvalResult(
            decision = when (law.verdict) {
                LawVerdict.ALLOW, LawVerdict.WAIT -> DecisionType.ALLOW
                LawVerdict.BLOCK -> DecisionType.BLOCK
                LawVerdict.WARN -> DecisionType.WARN
                LawVerdict.IGNORE, LawVerdict.CONTINUE -> null
            },
            reasonCode = law.reasonCode,
            stage = law.stage.name.lowercase(),
            contentGatesRanBeforeAppRule = law.stage != LawStage.NO_SESSION,
            appRuleBehavior = input.appRule?.name,
            prelude = evalPrelude(input)
        )
    }

    fun evalPrelude(input: PromiseEvalInput): String {
        val missing = input.hasActiveSession &&
            !ConfirmedPromiseBinder.hasStructuredMediaRule(input.settings)
        val missingPart = if (missing) {
            " code=${EnforcementReasonCodes.NO_STRUCTURED_MEDIA_RULE}"
        } else {
            ""
        }
        return "evalStart goal=${input.goal.take(160)} " +
            "min=${input.settings.minVideoLengthBlockMinutes} " +
            "max=${input.settings.maxVideoLengthBlockMinutes} " +
            "quota=${input.settings.shortFormDailyQuotaLimit} " +
            "scope=${input.settings.enforcementScopePackages} " +
            "brands=${input.settings.enforcementContentBrands} " +
            "pkg=${input.packageName} appRule=${input.appRule} " +
            "policy=${CommitmentPolicyEvaluator.policyVersion(input.settings)} " +
            "contentGatesBeforeAppRule=true$missingPart"
    }
}
