package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.PromiseIntentRules
import com.phonecodex.app.domain.scope.ScopeGate
import com.phonecodex.app.domain.scope.ScopeMatch

/**
 * Ordered law after safe/emergency and overlay-retain:
 * content gates, then ordinary AppRule. AppRule ALLOW never skips clocks
 * on Chrome / YouTube / NewPipe / movie / social video packages.
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
        val prelude = evalPrelude(input)
        if (!input.hasActiveSession) {
            return PromiseEvalResult(
                decision = DecisionType.ALLOW,
                reasonCode = EnforcementReasonCodes.NO_ACTIVE_SESSION_ALLOW,
                stage = "no_session",
                contentGatesRanBeforeAppRule = false,
                appRuleBehavior = input.appRule?.name,
                prelude = prelude
            )
        }

        // Phase 2A scope preflight -- shared with PhoneCodexAccessibilityService.
        // ScopeGate calls ScopeMatcher(LegacyScopeAdapter.from(settings)) unless
        // an explicit `settings.enforcementScope` is present (Phase 2B/3 path).
        val scopeOutcome = ScopeGate.evaluate(
            packageName = input.packageName,
            screenText = input.screenText,
            goal = input.goal,
            settings = input.settings
        )
        when (val m = scopeOutcome.match) {
            is ScopeMatch.Unresolved -> return decided(
                input,
                DecisionType.ALLOW,
                EnforcementReasonCodes.SCOPE_UNRESOLVED_NO_ENFORCE,
                "scope_unresolved",
                prelude
            )
            is ScopeMatch.Exempt -> return decided(
                input,
                DecisionType.ALLOW,
                EnforcementReasonCodes.SCOPE_EXEMPT_ALLOW,
                "scope_exempt",
                prelude
            )
            ScopeMatch.OutOfScope -> {
                if (!scopeOutcome.monkOverridesScope) {
                    if (input.appRule == AppRuleBehavior.BLOCK) {
                        return decided(
                            input,
                            DecisionType.BLOCK,
                            EnforcementReasonCodes.APP_RULE_BLOCK,
                            "scope_app_rule",
                            prelude
                        )
                    }
                    return decided(
                        input,
                        DecisionType.ALLOW,
                        EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW,
                        "scope",
                        prelude
                    )
                }
                // Monk override: fall through and let downstream gates fail-closed
                // on unknown apps. Preserves EntertainmentBanGate.mustEvaluate semantics.
                @Suppress("UNUSED_EXPRESSION") m
            }
            is ScopeMatch.InScope -> Unit // continue to downstream gates
        }

        val blankTree = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = input.packageName,
            screenText = input.screenText,
            goal = input.goal,
            enforcementScopePackages = input.settings.enforcementScopePackages,
            contentBrands = input.settings.enforcementContentBrands
        )
        when (blankTree.action) {
            BlankVideoTreeAction.BLOCK -> return decided(
                input,
                DecisionType.BLOCK,
                blankTree.reasonCode,
                "blank_video_tree",
                prelude
            )
            BlankVideoTreeAction.WAIT -> return decided(
                input,
                DecisionType.WARN,
                blankTree.reasonCode,
                "blank_video_tree",
                prelude
            )
            BlankVideoTreeAction.NONE -> Unit
        }

        val entertainment = entertainmentBan(input)
        if (entertainment != null) return entertainment

        val media = MediaLengthEnforcement.decideDetailed(
            packageName = input.packageName,
            goal = input.goal,
            screenText = input.screenText,
            structuredMaxBlockMinutes = input.settings.maxVideoLengthBlockMinutes,
            structuredMinBlockMinutes = input.settings.minVideoLengthBlockMinutes,
            enforcementScopePackages = input.settings.enforcementScopePackages,
            contentBrands = input.settings.enforcementContentBrands
        )
        when (media.decision) {
            MediaLengthLocalDecision.BLOCK -> {
                val seconds = media.currentPlayerDurationSeconds ?: 0
                val min = input.settings.minVideoLengthBlockMinutes
                val code = if (min != null && seconds < min * 60) {
                    EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN
                } else if (PromiseIntentRules.extractGoalMinVideoLimitMinutes(input.goal) != null &&
                    (input.settings.maxVideoLengthBlockMinutes == null)
                ) {
                    EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN
                } else {
                    EnforcementReasonCodes.MEDIA_BLOCK_OVER_MAX
                }
                return decided(
                    input,
                    DecisionType.BLOCK,
                    code,
                    "content_media",
                    prelude
                )
            }
            MediaLengthLocalDecision.PASS -> return decided(
                input,
                DecisionType.ALLOW,
                EnforcementReasonCodes.SURFACE_PASS_PASSIVE,
                "content_surface",
                prelude
            )
            MediaLengthLocalDecision.ALLOW -> return decided(
                input,
                DecisionType.ALLOW,
                EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT,
                "content_media",
                prelude
            )
            MediaLengthLocalDecision.WAIT -> {
                val shortBan = shortFormBan(input)
                if (shortBan != null) return shortBan
                val waitCode =
                    if (BlankVideoTreeGate.isNearBlankAccessibilityTree(input.screenText) &&
                        VideoPlatformRegistry.isVideoOrStreamingPackage(input.packageName)
                    ) {
                        EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE
                    } else {
                        EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK
                    }
                return decided(
                    input,
                    DecisionType.ALLOW,
                    waitCode,
                    "content_media_wait",
                    prelude
                )
            }
            MediaLengthLocalDecision.NONE -> {
                val shortBan = shortFormBan(input)
                if (shortBan != null) return shortBan
            }
        }

        val gate = SurfaceEnforcementGate.evaluate(
            packageName = input.packageName,
            screenText = input.screenText,
            goal = input.goal,
            structuredMaxBlockMinutes = input.settings.maxVideoLengthBlockMinutes,
            structuredMinBlockMinutes = input.settings.minVideoLengthBlockMinutes,
            shortFormDailyQuotaLimit = input.settings.shortFormDailyQuotaLimit
        )
        if (gate.isPass) {
            return decided(
                input,
                DecisionType.ALLOW,
                EnforcementReasonCodes.SURFACE_PASS_PASSIVE,
                "content_surface",
                prelude
            )
        }

        return applyAppRuleAfterContent(input, prelude)
    }

    private fun entertainmentBan(input: PromiseEvalInput): PromiseEvalResult? {
        return when (
            EntertainmentBanGate.evaluate(
                packageName = input.packageName,
                screenText = input.screenText,
                goal = input.goal
            )
        ) {
            EntertainmentBanAction.BLOCK -> decided(
                input,
                DecisionType.BLOCK,
                EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK,
                "entertainment_ban",
                evalPrelude(input)
            )
            EntertainmentBanAction.ALLOW_STUDY -> decided(
                input,
                DecisionType.ALLOW,
                EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW,
                "entertainment_ban_study",
                evalPrelude(input)
            )
            EntertainmentBanAction.NONE -> null
        }
    }

    private fun shortFormBan(input: PromiseEvalInput): PromiseEvalResult? {
        val surface = SurfaceDetector.detect(input.packageName, input.screenText)
        if (!surface.isShortFormPlay) return null
        if (PromiseIntentRules.allowsShortForm(input.goal)) return null
        val minFloor = input.settings.minVideoLengthBlockMinutes
            ?: PromiseIntentRules.extractGoalMinVideoLimitMinutes(input.goal)
        val floorBlocksShorts = minFloor != null && minFloor > 1
        val statedBan = PromiseIntentRules.blocksShortForm(input.goal)
        if (!floorBlocksShorts && !statedBan) return null
        val code = if (floorBlocksShorts) {
            EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN
        } else {
            EnforcementReasonCodes.CONTENT_SIGNAL_BLOCK
        }
        return decided(
            input,
            DecisionType.BLOCK,
            code,
            if (floorBlocksShorts) "content_media" else "short_form_ban",
            evalPrelude(input)
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
            "contentGatesBeforeAppRule=true$missingPart"
    }

    private fun applyAppRuleAfterContent(
        input: PromiseEvalInput,
        prelude: String
    ): PromiseEvalResult {
        return when (input.appRule) {
            AppRuleBehavior.ALLOW -> {
                if (VideoPlatformRegistry.enforcesMediaSurfaces(input.packageName)) {
                    PromiseEvalResult(
                        decision = null,
                        reasonCode = EnforcementReasonCodes.APP_RULE_ALLOW_DEFERRED,
                        stage = "app_rule_allow_deferred",
                        contentGatesRanBeforeAppRule = true,
                        appRuleBehavior = input.appRule.name,
                        prelude = prelude
                    )
                } else {
                    decided(
                        input,
                        DecisionType.ALLOW,
                        "APP_RULE_ALLOW",
                        "app_rule",
                        prelude
                    )
                }
            }
            AppRuleBehavior.BLOCK -> decided(
                input,
                DecisionType.BLOCK,
                EnforcementReasonCodes.APP_RULE_BLOCK,
                "app_rule",
                prelude
            )
            AppRuleBehavior.WARN -> decided(
                input,
                DecisionType.WARN,
                "APP_RULE_WARN",
                "app_rule",
                prelude
            )
            AppRuleBehavior.AI_DECIDE, null -> PromiseEvalResult(
                decision = null,
                reasonCode = if (
                    input.hasActiveSession &&
                    !ConfirmedPromiseBinder.hasStructuredMediaRule(input.settings)
                ) {
                    EnforcementReasonCodes.NO_STRUCTURED_MEDIA_RULE
                } else {
                    "CONTINUE"
                },
                stage = "continue",
                contentGatesRanBeforeAppRule = true,
                appRuleBehavior = input.appRule?.name,
                prelude = prelude
            )
        }
    }

    private fun decided(
        input: PromiseEvalInput,
        decision: DecisionType,
        code: String,
        stage: String,
        prelude: String
    ): PromiseEvalResult =
        PromiseEvalResult(
            decision = decision,
            reasonCode = code,
            stage = stage,
            contentGatesRanBeforeAppRule = true,
            appRuleBehavior = input.appRule?.name,
            prelude = prelude
        )
}
