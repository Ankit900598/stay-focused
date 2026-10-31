package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.guardrail.PermanentGuardrailEvaluator
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentSignals
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.GuardrailMatch
import com.phonecodex.app.domain.model.PermanentGuardrail
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.PromiseIntentRules
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog
import com.phonecodex.app.domain.scope.ScopeGate
import com.phonecodex.app.domain.scope.ScopeMatch
import com.phonecodex.app.domain.scope.TypedScopeRecord
import com.phonecodex.app.domain.signals.ContentSignalDetector

/** What the law says to do with one screen. WAIT keeps the screen usable while evidence is missing. */
enum class LawVerdict { ALLOW, BLOCK, WARN, WAIT, IGNORE, CONTINUE }

enum class LawStage {
    SAFE_APP,
    UNRELATED_PRODUCTIVITY,
    SYSTEM_NOISE,
    PERMANENT_GUARDRAIL,
    NO_SESSION,
    SESSION_LOCK_UNRELATED,
    SCOPE,
    SCOPE_APP_RULE,
    STRUCTURED_SURFACE,
    BLANK_VIDEO_TREE,
    APP_RULE_BLOCK,
    BLANK_TEXT,
    SHORT_FORM_QUOTA,
    ENTERTAINMENT_BAN,
    MEDIA_LENGTH,
    SHORT_FORM_BAN,
    YOUTUBE_SHORTS_SIGNAL,
    YOUTUBE_STUDY_SIGNAL,
    CHROME_ADULT_SIGNAL,
    CHROME_BROAD_USE,
    STRUCTURED_SURFACE_SCOPE,
    SURFACE_GATE,
    APP_RULE
}

/**
 * Immutable input for one decision. Android-only state (overlay visibility, stores) is
 * resolved by the caller into plain values.
 *
 * [storedDossier] is the persisted classification record for [packageName], merged with
 * the live screen inside the law. Only the category / lifestyle ban reads it, so a caller
 * with a disk-backed store loads it only when [CommitmentPolicyEvaluator.readsStoredDossier]
 * is true; otherwise null gives the identical decision.
 */
data class CommitmentSnapshot(
    val packageName: String,
    val screenText: String,
    /** Null when no commitment is running. Only ACTIVE / LOCKED sessions are passed. */
    val sessionStatus: SessionStatus?,
    val goal: String,
    val settings: StudyWorldSettings,
    val storedAppRule: AppRuleBehavior?,
    val isSafePackage: Boolean,
    val isSystemOverlayNoise: Boolean = false,
    val guardrails: List<PermanentGuardrail> = emptyList(),
    val storedDossier: AppDossier? = null
)

data class CommitmentDecision(
    val verdict: LawVerdict,
    val reasonCode: String,
    val stage: LawStage,
    val policyVersion: String,
    val effectiveAppRule: AppRuleBehavior?,
    val detection: SurfaceDetection? = null,
    val guardrail: GuardrailMatch? = null,
    val scope: ScopeGate.ScopeGateOutcome? = null,
    val blankTree: BlankVideoTreeResult? = null,
    val quota: ShortFormQuotaDecision? = null,
    val media: MediaLengthDecisionDetail? = null,
    val signals: ContentSignals? = null,
    val surfaceGate: SurfaceGateDecision? = null
)

/**
 * The one commitment decision law, shared by [com.phonecodex.app.accessibility.PhoneCodexAccessibilityService]
 * and [com.phonecodex.app.domain.enforcement.trace.EnforcementTraceEvaluator].
 *
 * Precedence (first match wins):
 *  1. non-overridable emergency / system apps
 *  2. own-overlay / system UI noise is ignored
 *  3. permanent guardrails (independent of any session)
 *  4. overridable safe apps and unrelated productivity apps
 *  5. no session -> allow;  locked session -> unrelated apps stay usable
 *  6. typed / legacy scope; outside scope only an app-rule BLOCK still applies
 *  7. structured surface BLOCK, blank video tree, app-rule BLOCK
 *  8. blank text is ignored (keep last decision, never guess)
 *  9. clocks and counters: daily short-form quota, lifestyle ban, per-item media length,
 *     short-form bans, deterministic content signals
 * 10. structured-surface scope, passive-surface pass, remaining app rule
 * 11. CONTINUE: the caller may consult feedback memory and advisory AI, which can never
 *     override any stage above.
 *
 * The quota gate owns the daily counter, so evaluating a counted play records it; every
 * other stage is side-effect free.
 */
object CommitmentPolicyEvaluator {

    private val guardrailEvaluator = PermanentGuardrailEvaluator()
    private val contentSignals = ContentSignalDetector()

    fun evaluate(snapshot: CommitmentSnapshot, quotaGate: ShortFormQuotaGate): CommitmentDecision {
        val law = Run(snapshot)
        val pkg = snapshot.packageName
        val text = snapshot.screenText
        val appRule = law.appRule

        if (SafeAppsCatalog.isNonOverridableDefaultPackage(pkg)) {
            return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.EMERGENCY_ALLOW, LawStage.SAFE_APP)
        }

        if (snapshot.isSystemOverlayNoise) {
            return law.decide(LawVerdict.IGNORE, EnforcementReasonCodes.SYSTEM_NOISE_IGNORED, LawStage.SYSTEM_NOISE)
        }

        val guardrail = guardrailEvaluator.evaluate(pkg, text, snapshot.guardrails)
        if (guardrail != null &&
            (guardrail.decision == DecisionType.BLOCK || guardrail.decision == DecisionType.WARN)
        ) {
            return law.decide(
                if (guardrail.decision == DecisionType.BLOCK) LawVerdict.BLOCK else LawVerdict.WARN,
                EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL,
                LawStage.PERMANENT_GUARDRAIL,
                guardrail = guardrail
            )
        }

        if (snapshot.isSafePackage && appRule != AppRuleBehavior.BLOCK) {
            return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.EMERGENCY_ALLOW, LawStage.SAFE_APP)
        }

        val session = snapshot.sessionStatus
        if (session != null &&
            appRule != AppRuleBehavior.BLOCK &&
            PromiseIntentRules.isUnrelatedProductivityPackage(pkg, snapshot.goal)
        ) {
            return law.decide(
                LawVerdict.ALLOW,
                EnforcementReasonCodes.PRODUCTIVITY_UNRELATED_ALLOW,
                LawStage.UNRELATED_PRODUCTIVITY
            )
        }

        if (session == null) {
            return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.NO_ACTIVE_SESSION_ALLOW, LawStage.NO_SESSION)
        }

        val settings = snapshot.settings
        if (session == SessionStatus.LOCKED &&
            appRule != AppRuleBehavior.BLOCK &&
            SessionLockLaw.mayUseAppWhileLocked(
                packageName = pkg,
                screenText = text,
                goal = snapshot.goal,
                enforcementScopePackages = settings.enforcementScopePackages,
                contentBrands = settings.enforcementContentBrands
            )
        ) {
            return law.decide(
                LawVerdict.ALLOW,
                EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED,
                LawStage.SESSION_LOCK_UNRELATED
            )
        }

        val scope = ScopeGate.evaluate(pkg, text, snapshot.goal, settings)
        if (scope.shortCircuits) {
            return when (scope.match) {
                is ScopeMatch.Unresolved ->
                    law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.SCOPE_UNRESOLVED_NO_ENFORCE, LawStage.SCOPE, scope = scope)
                is ScopeMatch.PersistenceCorrupt ->
                    law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.SCOPE_PERSISTENCE_CORRUPT, LawStage.SCOPE, scope = scope)
                is ScopeMatch.Exempt ->
                    law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.SCOPE_EXEMPT_ALLOW, LawStage.SCOPE, scope = scope)
                is ScopeMatch.Indeterminate ->
                    law.decide(LawVerdict.WAIT, EnforcementReasonCodes.SCOPE_EVIDENCE_INDETERMINATE, LawStage.SCOPE, scope = scope)
                ScopeMatch.OutOfScope ->
                    if (appRule == AppRuleBehavior.BLOCK) {
                        law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.APP_RULE_BLOCK, LawStage.SCOPE_APP_RULE, scope = scope)
                    } else {
                        law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, LawStage.SCOPE, scope = scope)
                    }
                is ScopeMatch.InScope -> error("InScope never short-circuits")
            }
        }

        if (settings.blockedSurfaces.isNotEmpty() &&
            StructuredSurfaceGate.blocks(pkg, settings, law.detection)
        ) {
            return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.STRUCTURED_SURFACE_BLOCK, LawStage.STRUCTURED_SURFACE)
        }

        val blankTree = BlankVideoTreeGate.evaluate(
            hasActiveSession = true,
            packageName = pkg,
            screenText = text,
            goal = snapshot.goal
        )
        when (blankTree.action) {
            BlankVideoTreeAction.BLOCK ->
                return law.decide(LawVerdict.BLOCK, blankTree.reasonCode, LawStage.BLANK_VIDEO_TREE, blankTree = blankTree)
            BlankVideoTreeAction.WAIT ->
                return law.decide(LawVerdict.WARN, blankTree.reasonCode, LawStage.BLANK_VIDEO_TREE, blankTree = blankTree)
            BlankVideoTreeAction.NONE -> Unit
        }

        if (appRule == AppRuleBehavior.BLOCK) {
            return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.APP_RULE_BLOCK, LawStage.APP_RULE_BLOCK)
        }

        if (EnforcementEventGate.shouldSkipContentAndAi(text)) {
            return law.decide(LawVerdict.IGNORE, EnforcementReasonCodes.BLANK_TEXT_KEEP_LAST, LawStage.BLANK_TEXT)
        }

        val quotaLimit = settings.shortFormDailyQuotaLimit
            ?: ShortFormQuotaGate.parseDailyShortFormLimit(snapshot.goal)
        if (quotaLimit != null) {
            val quota = quotaGate.evaluate(
                packageName = pkg,
                screenText = text,
                limit = quotaLimit,
                allowLongEducational = settings.allowLongEducationalVideos
            )
            when (quota.action) {
                ShortFormQuotaAction.ALLOW_AND_COUNT,
                ShortFormQuotaAction.ALLOW_DUPLICATE ->
                    return law.decide(LawVerdict.WARN, EnforcementReasonCodes.QUOTA_ALLOW_COUNT, LawStage.SHORT_FORM_QUOTA, quota = quota)
                ShortFormQuotaAction.ALLOW_LONG_EDUCATIONAL ->
                    return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.QUOTA_SKIP_NOT_PLAY, LawStage.SHORT_FORM_QUOTA, quota = quota)
                ShortFormQuotaAction.BLOCK_ADULT ->
                    return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.QUOTA_BLOCK_ADULT, LawStage.SHORT_FORM_QUOTA, quota = quota)
                ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED ->
                    return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.QUOTA_BLOCK_EXCEEDED, LawStage.SHORT_FORM_QUOTA, quota = quota)
                ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE ->
                    return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE, LawStage.SHORT_FORM_QUOTA, quota = quota)
                ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY,
                ShortFormQuotaAction.NONE -> Unit
            }
        }

        val dossier = if (readsStoredDossier(pkg, snapshot.goal, session)) {
            AppDossier.withLiveScreen(snapshot.storedDossier, pkg, text)
        } else {
            null
        }
        when (EntertainmentBanGate.evaluate(pkg, text, snapshot.goal, dossier)) {
            EntertainmentBanAction.BLOCK ->
                return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.MONK_ENTERTAINMENT_BLOCK, LawStage.ENTERTAINMENT_BAN)
            EntertainmentBanAction.ALLOW_STUDY ->
                return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW, LawStage.ENTERTAINMENT_BAN)
            EntertainmentBanAction.NONE -> Unit
        }

        val media = MediaLengthEnforcement.decideDetailed(
            packageName = pkg,
            goal = snapshot.goal,
            screenText = text,
            structuredMaxBlockMinutes = settings.maxVideoLengthBlockMinutes,
            structuredMinBlockMinutes = settings.minVideoLengthBlockMinutes,
            enforcementScopePackages = settings.enforcementScopePackages,
            contentBrands = settings.enforcementContentBrands,
            typedScope = settings.enforcementScope
        )
        when (media.decision) {
            MediaLengthLocalDecision.BLOCK ->
                return law.decide(
                    LawVerdict.BLOCK,
                    media.blockReasonCode ?: EnforcementReasonCodes.MEDIA_BLOCK_UNDER_MIN,
                    LawStage.MEDIA_LENGTH,
                    media = media
                )
            MediaLengthLocalDecision.PASS ->
                return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.SURFACE_PASS_PASSIVE, LawStage.MEDIA_LENGTH, media = media)
            MediaLengthLocalDecision.ALLOW ->
                return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.MEDIA_ALLOW_WITHIN_LIMIT, LawStage.MEDIA_LENGTH, media = media)
            MediaLengthLocalDecision.WAIT -> {
                law.shortFormBan(quotaLimit)?.let { return it }
                val waitCode =
                    if (BlankVideoTreeGate.isNearBlankAccessibilityTree(text) &&
                        VideoPlatformRegistry.isVideoOrStreamingPackage(pkg)
                    ) {
                        EnforcementReasonCodes.VIDEO_APP_DURATION_UNAVAILABLE
                    } else {
                        EnforcementReasonCodes.MEDIA_WAIT_NO_PLAYER_CLOCK
                    }
                return law.decide(LawVerdict.WAIT, waitCode, LawStage.MEDIA_LENGTH, media = media)
            }
            MediaLengthLocalDecision.NONE -> law.shortFormBan(quotaLimit)?.let { return it }
        }

        val signals = contentSignals.detect(pkg, text)
        val isYouTube = VideoPlatformRegistry.isOfficialYouTube(pkg)
        val isChrome = VideoPlatformRegistry.isChrome(pkg)
        if (isYouTube &&
            law.detection.isShortFormPlay &&
            signals.isYouTubeShorts &&
            PromiseIntentRules.blocksShortForm(snapshot.goal) &&
            !PromiseIntentRules.allowsShortForm(snapshot.goal) &&
            settings.shortFormDailyQuotaLimit == null
        ) {
            return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.CONTENT_SIGNAL_BLOCK, LawStage.YOUTUBE_SHORTS_SIGNAL, signals = signals)
        }
        if (isYouTube && signals.isLikelySearchOrLecture && law.detection.isActivePlayer) {
            return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW, LawStage.YOUTUBE_STUDY_SIGNAL, signals = signals)
        }
        if (isChrome && signals.isChromeAdultOrPorn) {
            return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.ADULT_BLOCK_GUARDRAIL, LawStage.CHROME_ADULT_SIGNAL, signals = signals)
        }
        val hasMediaLaw = settings.minVideoLengthBlockMinutes != null ||
            settings.maxVideoLengthBlockMinutes != null ||
            PromiseIntentRules.hasMediaLengthLimit(snapshot.goal)
        if (isChrome &&
            !hasMediaLaw &&
            !PromiseIntentRules.blocksEntertainmentOrMovies(snapshot.goal) &&
            (signals.isChromeStudyLike || PromiseIntentRules.allowsBroadChromeUse(snapshot.goal))
        ) {
            return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.CONTENT_SIGNAL_ALLOW, LawStage.CHROME_BROAD_USE, signals = signals)
        }

        when (StructuredSurfaceGate.evaluate(pkg, settings, law.detection)) {
            StructuredSurfaceAction.ALLOW_OUTSIDE_SCOPE ->
                return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, LawStage.STRUCTURED_SURFACE_SCOPE)
            StructuredSurfaceAction.WAIT_FOR_EVIDENCE ->
                return law.decide(LawVerdict.WAIT, EnforcementReasonCodes.SCOPE_EVIDENCE_INDETERMINATE, LawStage.STRUCTURED_SURFACE_SCOPE)
            StructuredSurfaceAction.BLOCK ->
                return law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.STRUCTURED_SURFACE_BLOCK, LawStage.STRUCTURED_SURFACE)
            StructuredSurfaceAction.NONE -> Unit
        }

        val gate = SurfaceEnforcementGate.evaluate(
            packageName = pkg,
            screenText = text,
            goal = snapshot.goal,
            structuredMaxBlockMinutes = settings.maxVideoLengthBlockMinutes,
            structuredMinBlockMinutes = settings.minVideoLengthBlockMinutes,
            shortFormDailyQuotaLimit = settings.shortFormDailyQuotaLimit
        )
        if (gate.isPass) {
            return law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.SURFACE_PASS_PASSIVE, LawStage.SURFACE_GATE, surfaceGate = gate)
        }

        return when (appRule) {
            AppRuleBehavior.ALLOW ->
                if (VideoPlatformRegistry.enforcesMediaSurfaces(pkg) ||
                    VideoPlatformRegistry.looksLikeUnknownStreamingPackage(pkg)
                ) {
                    law.decide(LawVerdict.CONTINUE, EnforcementReasonCodes.APP_RULE_ALLOW_DEFERRED, LawStage.APP_RULE)
                } else {
                    law.decide(LawVerdict.ALLOW, EnforcementReasonCodes.APP_RULE_ALLOW, LawStage.APP_RULE)
                }
            AppRuleBehavior.WARN -> law.decide(LawVerdict.WARN, EnforcementReasonCodes.APP_RULE_WARN, LawStage.APP_RULE)
            AppRuleBehavior.BLOCK -> law.decide(LawVerdict.BLOCK, EnforcementReasonCodes.APP_RULE_BLOCK, LawStage.APP_RULE)
            AppRuleBehavior.AI_DECIDE, null -> law.decide(
                LawVerdict.CONTINUE,
                if (ConfirmedPromiseBinder.hasStructuredMediaRule(settings)) {
                    EnforcementReasonCodes.CONTINUE_TO_COUNSEL
                } else {
                    EnforcementReasonCodes.NO_STRUCTURED_MEDIA_RULE
                },
                LawStage.APP_RULE
            )
        }
    }

    /**
     * True when [evaluate] can consult [CommitmentSnapshot.storedDossier] for this package
     * and goal. When false the stored record is never read, so callers skip loading it.
     */
    fun readsStoredDossier(packageName: String, goal: String, sessionStatus: SessionStatus?): Boolean =
        sessionStatus != null &&
            PromiseIntentRules.hasCategoryOrLifestyleBan(goal) &&
            !EntertainmentClassDetector.isMonkExemptPackage(packageName)

    /** Policy identity recorded with every decision so logs and replays name the law they ran. */
    fun policyVersion(settings: StudyWorldSettings): String {
        val scope = when (settings.typedScopeRecord) {
            TypedScopeRecord.VALID -> "typed:${settings.enforcementScope?.kind?.name}"
            TypedScopeRecord.CORRUPT -> "typed:corrupt"
            TypedScopeRecord.ABSENT -> "legacy"
        }
        val compiler = settings.compilerPromptVersion ?: "unknown"
        return "law=v1 scope=$scope compiler=$compiler"
    }

    private class Run(val snapshot: CommitmentSnapshot) {
        val appRule: AppRuleBehavior? = PromiseIntentRules.effectiveAppRule(
            stored = snapshot.storedAppRule,
            goal = snapshot.goal,
            packageName = snapshot.packageName
        )
        private var detected: SurfaceDetection? = null
        val detection: SurfaceDetection
            get() = detected ?: SurfaceDetector.detect(snapshot.packageName, snapshot.screenText).also { detected = it }

        fun shortFormBan(quotaLimit: Int?): CommitmentDecision? {
            if (quotaLimit != null) return null
            if (!detection.isShortFormPlay) return null
            if (PromiseIntentRules.allowsShortForm(snapshot.goal)) return null
            val minFloor = snapshot.settings.minVideoLengthBlockMinutes
                ?: PromiseIntentRules.extractGoalMinVideoLimitMinutes(snapshot.goal)
            val floorBlocksShorts = minFloor != null && minFloor > 1
            if (!floorBlocksShorts && !PromiseIntentRules.blocksShortForm(snapshot.goal)) return null
            return decide(
                LawVerdict.BLOCK,
                if (floorBlocksShorts) {
                    EnforcementReasonCodes.SHORT_FORM_BLOCK_UNDER_MIN
                } else {
                    EnforcementReasonCodes.CONTENT_SIGNAL_BLOCK
                },
                LawStage.SHORT_FORM_BAN
            )
        }

        fun decide(
            verdict: LawVerdict,
            reasonCode: String,
            stage: LawStage,
            guardrail: GuardrailMatch? = null,
            scope: ScopeGate.ScopeGateOutcome? = null,
            blankTree: BlankVideoTreeResult? = null,
            quota: ShortFormQuotaDecision? = null,
            media: MediaLengthDecisionDetail? = null,
            signals: ContentSignals? = null,
            surfaceGate: SurfaceGateDecision? = null
        ): CommitmentDecision = CommitmentDecision(
            verdict = verdict,
            reasonCode = reasonCode,
            stage = stage,
            policyVersion = policyVersion(snapshot.settings),
            effectiveAppRule = appRule,
            detection = detected,
            guardrail = guardrail,
            scope = scope,
            blankTree = blankTree,
            quota = quota,
            media = media,
            signals = signals,
            surfaceGate = surfaceGate
        )
    }
}
