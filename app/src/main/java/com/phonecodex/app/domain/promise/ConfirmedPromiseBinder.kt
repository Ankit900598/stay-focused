package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.model.StudyWorldSettings
import java.time.Instant
import java.time.ZoneId

/**
 * Confirmed promise → stored Study World clocks.
 * Accessibility must read these fields; it must not re-parse English.
 */
object ConfirmedPromiseBinder {

    fun bind(
        draft: FocusPromise,
        savedDefaults: StudyWorldSettings? = null
    ): StudyWorldSettings {
        val isCalendarDay = PromiseContentRuleSupport.isCalendarDayWindow(draft)
        // Explicit promise duration wins. Otherwise Advanced Controls duration wins over
        // the parser's inferred default (never leave a leftover Study World 30 mid-session
        // when the user set 45/60 in Advanced).
        val duration = when {
            isCalendarDay -> maxOf(draft.sessionDurationMinutes, 24 * 60)
            draft.sessionDurationWasExplicit -> draft.sessionDurationMinutes
            else -> savedDefaults?.durationMinutes ?: draft.sessionDurationMinutes
        }
        return StudyWorldSettings(
            durationMinutes = duration.coerceAtLeast(1),
            // Promise-stated lock count > Advanced custom > strictness default map.
            lockAttemptThreshold = draft.lockAttemptThreshold
                ?: savedDefaults
                ?.takeIf { it.lockAttemptThresholdCustomized }
                ?.lockAttemptThreshold
                ?: lockAttemptThresholdFor(draft.strictness),
            // Block cooldown is always an Advanced Controls (or store default) value.
            blockAttemptCooldownMinutes = savedDefaults?.blockAttemptCooldownMinutes
                ?: DEFAULT_BLOCK_ATTEMPT_COOLDOWN_MINUTES,
            maxVideoLengthBlockMinutes =
                PromiseContentRuleSupport.maxVideoLengthBlockMinutes(draft),
            minVideoLengthBlockMinutes =
                PromiseContentRuleSupport.minVideoLengthBlockMinutes(draft),
            shortFormDailyQuotaLimit =
                PromiseContentRuleSupport.shortFormDailyQuotaLimit(draft),
            allowLongEducationalVideos = ShortFormQuotaGate.allowsLongEducational(draft.rawText),
            timeWindowKind = if (isCalendarDay) {
                "calendar_day"
            } else {
                "session_fixed"
            },
            enforcementScopePackages = PromiseContentRuleSupport.enforcementScopePackages(draft),
            enforcementContentBrands = PromiseContentRuleSupport.enforcementContentBrands(draft),
            enforcementScopeKind = PromiseContentRuleSupport.enforcementScopeKind(draft),
            // v1 only; legacy fields above stay as the fallback mirror for the same promise.
            enforcementScope = draft.enforcementScope
                ?.takeIf { draft.scopeContractV1 && it.isEnforceable },
            blockedSurfaces = EffectivePromisePolicy.blockedSurfaces(draft),
            compilerPromptVersion = draft.compilerPromptVersion
        )
    }

    fun audit(draft: FocusPromise, stored: StudyWorldSettings): PromiseStartAudit =
        PromiseStartAudit(
            rawPromise = draft.rawText,
            contentRules = draft.contentRules.map { rule ->
                listOf(
                    rule.action.name,
                    rule.operator.orEmpty(),
                    rule.value?.toString().orEmpty(),
                    rule.unit.orEmpty(),
                    rule.contentType,
                    rule.surface,
                    rule.packageName.orEmpty()
                ).joinToString(":")
            },
            minVideoLengthBlockMinutes = stored.minVideoLengthBlockMinutes,
            maxVideoLengthBlockMinutes = stored.maxVideoLengthBlockMinutes,
            shortFormDailyQuotaLimit = stored.shortFormDailyQuotaLimit,
            enforcementScopePackages = stored.enforcementScopePackages,
            enforcementContentBrands = stored.enforcementContentBrands,
            enforcementScopeKind = stored.enforcementScopeKind,
            typedScopeKind = stored.enforcementScope?.kind?.name,
            typedScopeCorruptDetail = stored.typedScopeCorruptDetail,
            blockedSurfaces = stored.blockedSurfaces,
            compilerPromptVersion = stored.compilerPromptVersion,
            scopeContract = if (draft.scopeContractV1) "v1" else "legacy",
            confirmedStrictness = draft.strictness.name,
            lockAttemptThreshold = stored.lockAttemptThreshold,
            durationMinutes = stored.durationMinutes,
            timeWindowKind = stored.timeWindowKind
        )

    /**
     * Home keeps the bound settings after Start. If the read-back of the typed scope is
     * unreadable, carry that into what Home holds so it cannot claim Protected while
     * Accessibility reports SCOPE_PERSISTENCE_CORRUPT. Clocks stay as bound.
     */
    fun reflectStoredScopeIntegrity(bound: StudyWorldSettings, stored: StudyWorldSettings): StudyWorldSettings =
        if (stored.typedScopeCorruptDetail != null) {
            bound.copy(enforcementScope = null, typedScopeCorruptDetail = stored.typedScopeCorruptDetail)
        } else {
            bound
        }

    fun hasStructuredMediaRule(settings: StudyWorldSettings): Boolean =
        settings.minVideoLengthBlockMinutes != null ||
            settings.maxVideoLengthBlockMinutes != null ||
            settings.shortFormDailyQuotaLimit != null

    /** Session clock for Start. Never fall back to leftover Study World 30. */
    fun sessionDurationMillis(settings: StudyWorldSettings): Long =
        settings.durationMinutes.coerceAtLeast(1) * 60_000L

    fun sessionDurationMillis(draft: FocusPromise): Long =
        sessionDurationMillis(bind(draft))

    /** Start clock: a "today" promise ends at the next local midnight, not 24 hours later. */
    fun sessionDurationMillis(
        draft: FocusPromise,
        bound: StudyWorldSettings,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        if (!PromiseContentRuleSupport.isTodayWindow(draft)) return sessionDurationMillis(bound)
        val midnight = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        return (midnight - nowMillis).coerceAtLeast(60_000L)
    }

    private fun lockAttemptThresholdFor(level: StrictnessLevel): Int = when (level) {
        StrictnessLevel.LOCKED, StrictnessLevel.STRICT -> 3
        StrictnessLevel.SOFT, StrictnessLevel.SMART -> 10
    }

    private const val DEFAULT_BLOCK_ATTEMPT_COOLDOWN_MINUTES = 2
}

data class PromiseStartAudit(
    val rawPromise: String,
    val contentRules: List<String>,
    val minVideoLengthBlockMinutes: Int?,
    val maxVideoLengthBlockMinutes: Int?,
    val shortFormDailyQuotaLimit: Int?,
    val enforcementScopePackages: List<String>,
    val enforcementContentBrands: List<String> = emptyList(),
    val enforcementScopeKind: String? = null,
    /** Typed scope kind when a v1 scope was saved; null = legacy adapter path. */
    val typedScopeKind: String? = null,
    val typedScopeCorruptDetail: String? = null,
    val blockedSurfaces: List<String> = emptyList(),
    /** Backend `meta.promptVersion` verbatim; `unknown` when absent. Never inferred. */
    val compilerPromptVersion: String? = null,
    val scopeContract: String = "legacy",
    val confirmedStrictness: String? = null,
    val lockAttemptThreshold: Int? = null,
    val durationMinutes: Int? = null,
    val timeWindowKind: String? = null
) {
    private val typedScopeLabel: String
        get() = when {
            typedScopeCorruptDetail != null -> "corrupt(${typedScopeCorruptDetail})"
            else -> typedScopeKind ?: "legacy"
        }

    fun format(): String =
        "promiseChars=${rawPromise.length} " +
            "typedScope=$typedScopeLabel " +
            "compiler=${compilerPromptVersion ?: "unknown"} scopeContract=$scopeContract " +
            "strictness=$confirmedStrictness lockThreshold=$lockAttemptThreshold " +
            "durationMinutes=$durationMinutes timeWindow=$timeWindowKind " +
            "contentRules=[${contentRules.joinToString(";")}] " +
            "minVideoLengthBlockMinutes=$minVideoLengthBlockMinutes " +
            "maxVideoLengthBlockMinutes=$maxVideoLengthBlockMinutes " +
            "shortFormDailyQuotaLimit=$shortFormDailyQuotaLimit " +
            "enforcementScopePackages=[${enforcementScopePackages.joinToString(",")}] " +
            "enforcementContentBrands=[${enforcementContentBrands.joinToString(",")}] " +
            "enforcementScopeKind=$enforcementScopeKind " +
            "blockedSurfaces=[${blockedSurfaces.joinToString(",")}] " +
            "stored settings after write: " +
            "min=$minVideoLengthBlockMinutes max=$maxVideoLengthBlockMinutes " +
            "quota=$shortFormDailyQuotaLimit scope=$enforcementScopePackages " +
            "brands=$enforcementContentBrands"

    fun missingStructuredMediaLine(): String =
        "code=${EnforcementReasonCodes.NO_STRUCTURED_MEDIA_RULE} " +
            "session active but media settings are null " +
            "(min/max/quota all unset)"
}
