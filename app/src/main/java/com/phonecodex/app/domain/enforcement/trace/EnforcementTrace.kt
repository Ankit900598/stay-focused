package com.phonecodex.app.domain.enforcement.trace

import com.phonecodex.app.domain.enforcement.AppDossier
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings

/**
 * Offline replay of one Accessibility snapshot.
 * No Android UI, no AI, no network.
 */
data class EnforcementTrace(
    val id: String,
    val packageName: String,
    val screenText: String,
    val goal: String,
    val settings: StudyWorldSettings,
    val sessionStatus: SessionStatus = SessionStatus.ACTIVE,
    val appRule: AppRuleBehavior? = AppRuleBehavior.AI_DECIDE,
    val permanentAdultGuardrail: Boolean = false,
    val quotaAlreadyCounted: Int = 0,
    /** Persisted classification record for [packageName], as the live store would return it. */
    val storedDossier: AppDossier? = null,
    val expectedDecisions: Set<TraceDecision>,
    val expectedReasonCodes: Set<String> = emptySet(),
    val forbiddenReasonCodes: Set<String> = emptySet(),
    val expectedSurface: String? = null,
    val expectedActivity: String? = null,
    val expectedDurationSource: String? = null,
    val userFacingCopy: String? = null,
    val notes: String = ""
)

enum class TraceDecision {
    ALLOW,
    WARN,
    BLOCK,
    LOCK,
    WAIT
}

data class EnforcementTraceResult(
    val decision: TraceDecision,
    val reasonCode: String,
    val surface: String,
    val activity: String,
    val durationSource: String,
    val currentPlayerDurationSeconds: Int?,
    val stage: String
)
