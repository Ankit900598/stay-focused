package com.phonecodex.app.domain.enforcement.trace

import com.phonecodex.app.domain.enforcement.EnforcementContext
import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.PromiseEnforcementCoordinator
import com.phonecodex.app.domain.enforcement.PromiseEvalInput
import com.phonecodex.app.domain.enforcement.SessionLockLaw
import com.phonecodex.app.domain.enforcement.ShortFormQuotaAction
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.VideoDurationParser
import com.phonecodex.app.domain.guardrail.PermanentGuardrailEvaluator
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.PermanentGuardrail
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog
import com.phonecodex.app.domain.promise.PromiseIntentRules

/**
 * Pure replay of the Accessibility gate chain for one screen snapshot.
 * Order matches [PhoneCodexAccessibilityService]: safe → adult guardrail →
 * lock-unrelated → coordinator (scope/blank/entertainment/media/shorts) →
 * short-form quota. No overlay, no AI, no network.
 */
object EnforcementTraceEvaluator {

    fun evaluate(trace: EnforcementTrace): EnforcementTraceResult {
        val detection = SurfaceDetector.detect(trace.packageName, trace.screenText)
        val parsed = VideoDurationParser.parse(trace.screenText)
        val context = EnforcementContext.fromParts(
            packageName = trace.packageName,
            screenText = trace.screenText,
            detection = detection,
            parsed = parsed
        )
        val snapshot = Snapshot(
            surface = detection.surface.name,
            activity = context.activityState.name,
            durationSource = context.durationSource.name,
            seconds = context.currentPlayerDurationSeconds
        )
        val effectiveAppRule = if (
            trace.appRule == AppRuleBehavior.BLOCK ||
            PromiseIntentRules.explicitlyBlocksKnownPackage(trace.goal, trace.packageName)
        ) {
            AppRuleBehavior.BLOCK
        } else {
            trace.appRule
        }

        if (
            SafeAppsCatalog.isDefaultPackage(trace.packageName) &&
            (
                effectiveAppRule != AppRuleBehavior.BLOCK ||
                    SafeAppsCatalog.isNonOverridableDefaultPackage(trace.packageName)
            )
        ) {
            return snapshot.result(
                TraceDecision.ALLOW,
                EnforcementReasonCodes.EMERGENCY_ALLOW,
                "safe_app"
            )
        }

        if (trace.permanentAdultGuardrail) {
            val match = PermanentGuardrailEvaluator().evaluate(
                packageName = trace.packageName,
                screenText = trace.screenText,
                guardrails = listOf(ADULT_GUARDRAIL)
            )
            if (match != null && match.decision == DecisionType.BLOCK) {
                return snapshot.result(
                    TraceDecision.BLOCK,
                    EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
                    "permanent_guardrail"
                )
            }
        }

        val locked = trace.sessionStatus == SessionStatus.LOCKED
        if (
            locked &&
            effectiveAppRule != AppRuleBehavior.BLOCK &&
            SessionLockLaw.mayUseAppWhileLocked(
                packageName = trace.packageName,
                screenText = trace.screenText,
                goal = trace.goal,
                enforcementScopePackages = trace.settings.enforcementScopePackages,
                contentBrands = trace.settings.enforcementContentBrands
            )
        ) {
            return snapshot.result(
                TraceDecision.ALLOW,
                EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED,
                "session_lock"
            )
        }

        val coord = PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = trace.sessionStatus == SessionStatus.ACTIVE || locked,
                goal = trace.goal,
                settings = trace.settings,
                appRule = effectiveAppRule,
                packageName = trace.packageName,
                screenText = trace.screenText
            )
        )

        var decision = mapCoordinatorDecision(coord.decision, coord.reasonCode)
        var reason = coord.reasonCode
        var stage = coord.stage

        val quotaLimit = trace.settings.shortFormDailyQuotaLimit
        // Phase 2A: quota is only evaluated on IN-SCOPE traces. If the coordinator
        // short-circuited on ScopeGate (unresolved / exempt / out-of-scope allow),
        // the quota gate must NOT count or block that play. Mirrors the live phone
        // path where the AccessibilityService also stops at scope.
        val scopeShortCircuited = reason == EnforcementReasonCodes.SCOPE_UNRESOLVED_NO_ENFORCE ||
            reason == EnforcementReasonCodes.SCOPE_EXEMPT_ALLOW ||
            reason == EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW
        if (
            quotaLimit != null &&
            decision != TraceDecision.BLOCK &&
            decision != TraceDecision.LOCK &&
            !scopeShortCircuited
        ) {
            val quota = ShortFormQuotaGate(
                ledger = SeedLedger(trace.quotaAlreadyCounted)
            ).evaluate(
                packageName = trace.packageName,
                screenText = trace.screenText,
                limit = quotaLimit,
                allowLongEducational = trace.settings.allowLongEducationalVideos
            )
            when (quota.action) {
                ShortFormQuotaAction.BLOCK_ADULT -> {
                    decision = TraceDecision.BLOCK
                    reason = EnforcementReasonCodes.QUOTA_BLOCK_ADULT
                    stage = "short_form_quota"
                }
                ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED -> {
                    decision = TraceDecision.BLOCK
                    reason = EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED
                    stage = "short_form_quota"
                }
                ShortFormQuotaAction.ALLOW_AND_COUNT,
                ShortFormQuotaAction.ALLOW_DUPLICATE -> {
                    decision = TraceDecision.ALLOW
                    reason = EnforcementReasonCodes.QUOTA_ALLOW_COUNT
                    stage = "short_form_quota"
                }
                else -> Unit
            }
        }

        if (
            locked &&
            SessionLockLaw.shouldKeepLockingPackage(
                packageName = trace.packageName,
                screenText = trace.screenText,
                goal = trace.goal,
                enforcementScopePackages = trace.settings.enforcementScopePackages,
                contentBrands = trace.settings.enforcementContentBrands
            ) &&
            decision == TraceDecision.ALLOW
        ) {
            decision = TraceDecision.LOCK
            reason = "SESSION_LOCK_FORBIDDEN_SURFACE"
            stage = "session_lock"
        }

        return snapshot.result(decision, reason, stage)
    }

    fun formatFailure(
        trace: EnforcementTrace,
        actual: EnforcementTraceResult
    ): String =
        "TRACE_FAIL id=${trace.id} pkg=${trace.packageName} " +
            "goal=${trace.goal.take(120)} " +
            "screen=${trace.screenText.replace("\n", " ").take(200)} " +
            "expectedDecision=${trace.expectedDecisions} actualDecision=${actual.decision} " +
            "expectedReason=${trace.expectedReasonCodes.ifEmpty { setOf("-") }} " +
            "actualReason=${actual.reasonCode} " +
            "expectedSurface=${trace.expectedSurface ?: "-"} actualSurface=${actual.surface} " +
            "expectedActivity=${trace.expectedActivity ?: "-"} actualActivity=${actual.activity} " +
            "expectedDuration=${trace.expectedDurationSource ?: "-"} " +
            "actualDuration=${actual.durationSource}"

    private fun mapCoordinatorDecision(
        decision: DecisionType?,
        reasonCode: String
    ): TraceDecision {
        if (
            reasonCode == EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK ||
            reasonCode == EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE
        ) {
            return TraceDecision.WAIT
        }
        return when (decision) {
            DecisionType.BLOCK -> TraceDecision.BLOCK
            DecisionType.WARN -> TraceDecision.WARN
            DecisionType.LOCK -> TraceDecision.LOCK
            DecisionType.ASK -> TraceDecision.WARN
            DecisionType.ALLOW, null -> TraceDecision.ALLOW
        }
    }

    private data class Snapshot(
        val surface: String,
        val activity: String,
        val durationSource: String,
        val seconds: Int?
    ) {
        fun result(
            decision: TraceDecision,
            reasonCode: String,
            stage: String
        ): EnforcementTraceResult =
            EnforcementTraceResult(
                decision = decision,
                reasonCode = reasonCode,
                surface = surface,
                activity = activity,
                durationSource = durationSource,
                currentPlayerDurationSeconds = seconds,
                stage = stage
            )
    }

    private class SeedLedger(
        private val seedCount: Int
    ) : ShortFormQuotaGate.Ledger {
        override fun load(dayKey: String): Pair<Int, Set<String>> =
            seedCount.coerceAtLeast(0) to emptySet()

        override fun save(dayKey: String, count: Int, signatures: Set<String>) = Unit
    }

    private val ADULT_GUARDRAIL = PermanentGuardrail(
        id = "porn_guardrail",
        name = "No adult content",
        enabled = true,
        blockedKeywords = emptySet(),
        blockedPackages = emptySet(),
        createdAtMillis = 1L,
        expiresAtMillis = null
    )
}
