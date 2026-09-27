package com.phonecodex.app.domain.enforcement.trace

import com.phonecodex.app.domain.enforcement.CommitmentDecision
import com.phonecodex.app.domain.enforcement.CommitmentPolicyEvaluator
import com.phonecodex.app.domain.enforcement.CommitmentSnapshot
import com.phonecodex.app.domain.enforcement.EnforcementContext
import com.phonecodex.app.domain.enforcement.LawStage
import com.phonecodex.app.domain.enforcement.LawVerdict
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.VideoDurationParser
import com.phonecodex.app.domain.model.PermanentGuardrail
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog

/**
 * Offline replay of one screen snapshot through [CommitmentPolicyEvaluator], the same law
 * the AccessibilityService runs. Replay supplies the default safe-app catalog, an optional
 * adult guardrail, a seeded quota counter and an optional stored app dossier.
 * No overlay, no AI, no network.
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
        val law = CommitmentPolicyEvaluator.evaluate(
            CommitmentSnapshot(
                packageName = trace.packageName,
                screenText = trace.screenText,
                sessionStatus = trace.sessionStatus.takeIf {
                    it == SessionStatus.ACTIVE || it == SessionStatus.LOCKED
                },
                goal = trace.goal,
                settings = trace.settings,
                storedAppRule = trace.appRule,
                isSafePackage = SafeAppsCatalog.isDefaultPackage(trace.packageName),
                guardrails = if (trace.permanentAdultGuardrail) listOf(ADULT_GUARDRAIL) else emptyList(),
                storedDossier = trace.storedDossier
            ),
            ShortFormQuotaGate(ledger = SeedLedger(trace.quotaAlreadyCounted))
        )
        return snapshot.result(traceDecision(law), law.reasonCode, law.stage.name.lowercase())
    }

    /** Replay vocabulary for a law verdict. A counted quota play is allowed with a notice. */
    fun traceDecision(law: CommitmentDecision): TraceDecision = when (law.verdict) {
        LawVerdict.ALLOW, LawVerdict.CONTINUE -> TraceDecision.ALLOW
        LawVerdict.BLOCK -> TraceDecision.BLOCK
        LawVerdict.WARN ->
            if (law.stage == LawStage.SHORT_FORM_QUOTA) TraceDecision.ALLOW else TraceDecision.WARN
        LawVerdict.WAIT, LawVerdict.IGNORE -> TraceDecision.WAIT
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
        override fun load(dayKey: String): ShortFormQuotaGate.LedgerRead =
            ShortFormQuotaGate.LedgerRead.Counted(seedCount.coerceAtLeast(0), emptySet())

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
