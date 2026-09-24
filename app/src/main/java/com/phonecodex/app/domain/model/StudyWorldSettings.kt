package com.phonecodex.app.domain.model

import com.phonecodex.app.domain.scope.EnforcementScope

data class StudyWorldSettings(
    val durationMinutes: Int,
    val lockAttemptThreshold: Int,
    val blockAttemptCooldownMinutes: Int,
    /**
     * Clock B: block individual videos longer than this many minutes.
     * Null means no structured max-length gate (goal-text heuristics may still apply).
     */
    val maxVideoLengthBlockMinutes: Int? = null,
    /**
     * Clock B: block individual videos shorter than this many minutes.
     * Null means no structured min-length gate (goal-text heuristics may still apply).
     */
    val minVideoLengthBlockMinutes: Int? = null,
    /**
     * Daily short-form play quota (clock C / count). Null = no daily short-form quota.
     * Boundary: plays 1..N ALLOW; N+1+ BLOCK until local day ends.
     */
    val shortFormDailyQuotaLimit: Int? = null,
    /** When true, long educational players are allowed outside the short-form quota. */
    val allowLongEducationalVideos: Boolean = true,
    /**
     * `session_fixed` | `calendar_day` | null (unspecified / legacy).
     * calendar_day → do not invent a second 60m timer; duration is typically 1440.
     */
    val timeWindowKind: String? = null,
    /**
     * Package-exclusive clock scope ("YouTube app only", Chrome-only rematerialize).
     * Empty = not package-locked. Combined with [enforcementContentBrands].
     */
    val enforcementScopePackages: List<String> = emptyList(),
    /**
     * Content brands (youtube, …). YouTube brand matches the YouTube surface
     * in any package — official app, browser host, NewPipe, embeds.
     */
    val enforcementContentBrands: List<String> = emptyList(),
    /** Persisted compiler scope kind; used to keep app-only vs content-brand honest. */
    val enforcementScopeKind: String? = null,
    /**
     * Scope Semantics v1 typed scope. Null in Phase 2A -- the three legacy fields
     * above stay authoritative and are adapted by
     * [com.phonecodex.app.domain.scope.LegacyScopeAdapter]. Phase 2B/3 flips this
     * to be the source of truth for the coordinator + AccessibilityService via
     * [com.phonecodex.app.domain.scope.ScopeGate].
     *
     * When non-null [com.phonecodex.app.domain.scope.ScopeGate] uses it directly
     * and bypasses the legacy adapter for this settings object.
     */
    val enforcementScope: EnforcementScope? = null,
    /**
     * Developer opt-in for the 10-day vision counsel experiment.
     * Default OFF. Still requires [VisionExperiment] calendar window.
     * Not a promise clock — [StudyWorldSettingsStore.replaceSettings] must not wipe it.
     */
    val visionExperimentEnabled: Boolean = false
)
