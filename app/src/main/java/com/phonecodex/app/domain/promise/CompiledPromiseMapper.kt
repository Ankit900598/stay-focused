package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.EnforcementScopeLaw
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ClarificationOption
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.scope.ScopeAppliesToCopy
import com.phonecodex.app.domain.scope.ScopeCanonicalizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed DTO from backend POST /compile-promise (Android-friendly shape).
 */
data class CompiledPromiseResponse(
    val rawText: String,
    val sessionDurationMinutes: Int,
    val strictness: String,
    val allowedSummaries: List<String>,
    val blockedSummaries: List<String>,
    val conditionalSummaries: List<String>,
    val contentRules: List<CompiledContentRuleDto>,
    val clarificationQuestion: String?,
    val cautionMessages: List<String>,
    val source: String,
    val confidence: Double,
    val suggestedAppRules: List<CompiledAppRuleDto>,
    val confirmationPreview: List<String> = emptyList(),
    val timeWindowKind: String? = null,
    val quotaBoundary: String? = null,
    val scopePackages: List<String> = emptyList(),
    val contentBrands: List<String> = emptyList(),
    val surfaceScope: List<String> = emptyList(),
    val contentScope: List<String> = emptyList(),
    val scopeKind: String? = null,
    val interpretationNotes: List<String> = emptyList(),
    val clarificationOptions: List<ClarificationOptionDto> = emptyList(),
    val clarificationRequired: Boolean = false,
    val canStartCommitment: Boolean? = null,
    val requiresStrongerConfirmation: Boolean = false,
    val isPermanentCommitment: Boolean = false,
    val userFacingConfirmation: UserFacingConfirmationDto? = null,
    /**
     * Reliability design 2026-09-25 §Fix 1 (clarification carry-through).
     * Verbatim JSON string of the top-level `clarificationOptions` array as the
     * backend returned it, including any `internalPolicyPreview` payload.
     * The mapper strips this to a UI-safe shape; this raw copy is kept so the
     * NEXT compile call can echo the selected option + full snapshot back to
     * the backend, letting it verify deterministically instead of guessing.
     */
    val clarificationOptionsRawJson: String? = null,
    /** Backend verdict for a clarification pick (e.g. snapshot_verified, unrecognized_fail_closed). */
    val optionVerification: String? = null,
    /** `v1` = typed scope required (prompt v09); `legacy` or absent = old scope fields only. */
    val scopeContract: String? = null,
    /** Raw top-level `scope` object; parsed + canonicalized by the mapper, never trusted as-is. */
    val scopeJson: String? = null,
    /** `meta.promptVersion` as the backend reported it (e.g. hosted `promise_compiler_v07`). */
    val compilerPromptVersion: String? = null
)

data class ClarificationOptionDto(
    val id: String,
    val label: String,
    val description: String,
    val recommended: Boolean,
    val policyPreview: String?,
    val scopeJson: String? = null
)

data class UserFacingConfirmationDto(
    val understood: String?,
    val allowed: List<String>,
    val blocked: List<String>,
    val time: String?,
    val appliesTo: String?,
    val checkThis: List<String>,
    val safetyNotes: List<String>
)

data class CompiledAppRuleDto(
    val packageName: String,
    val label: String,
    val behavior: String,
    /** Surface the rule applies to inside the app ("" / "all" = whole app, "shorts", …). */
    val scope: String = ""
)

data class CompiledContentRuleDto(
    val appLabel: String?,
    val packageName: String?,
    val surface: String,
    val contentType: String,
    val operator: String?,
    val value: Int?,
    val unit: String?,
    val action: String,
    val description: String? = null
)

object CompiledPromiseResponseParser {

    fun parse(body: String): CompiledPromiseResponse? {
        return try {
            val json = JSONObject(body)
            val rawText = json.optString("rawText").orEmpty()
            val sessionDuration = json.optInt("sessionDurationMinutes", -1)
            if (sessionDuration <= 0) return null

            CompiledPromiseResponse(
                rawText = rawText,
                sessionDurationMinutes = sessionDuration,
                strictness = json.optString("strictness", "STRICT"),
                allowedSummaries = stringList(json.optJSONArray("allowedSummaries")),
                blockedSummaries = stringList(json.optJSONArray("blockedSummaries")),
                conditionalSummaries = stringList(json.optJSONArray("conditionalSummaries")),
                contentRules = contentRules(json.optJSONArray("contentRules")),
                clarificationQuestion = json.optString("clarificationQuestion")
                    .takeIf { it.isNotBlank() && it != "null" },
                cautionMessages = stringList(json.optJSONArray("cautionMessages")),
                source = json.optString("source", "azure_promise_compiler"),
                confidence = json.optDouble("confidence", 0.0),
                suggestedAppRules = appRules(json.optJSONArray("suggestedAppRules")),
                confirmationPreview = stringList(json.optJSONArray("confirmationPreview")),
                timeWindowKind = json.optString("timeWindowKind")
                    .takeIf { it.isNotBlank() && it != "null" },
                quotaBoundary = json.optString("quotaBoundary")
                    .takeIf { it.isNotBlank() && it != "null" },
                scopePackages = stringList(json.optJSONArray("scopePackages")),
                contentBrands = stringList(json.optJSONArray("contentBrands")),
                surfaceScope = stringList(json.optJSONArray("surfaceScope")),
                contentScope = stringList(json.optJSONArray("contentScope")),
                scopeKind = json.optString("scopeKind")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: json.optString("optionScopeKind")
                        .takeIf { it.isNotBlank() && it != "null" },
                interpretationNotes = stringList(json.optJSONArray("interpretationNotes")),
                clarificationOptions = clarificationOptions(json.optJSONArray("clarificationOptions")),
                clarificationRequired = json.optBoolean("clarificationRequired", false),
                canStartCommitment = if (json.has("canStartCommitment")) {
                    json.optBoolean("canStartCommitment")
                } else {
                    null
                },
                requiresStrongerConfirmation = json.optBoolean("requiresStrongerConfirmation", false),
                isPermanentCommitment = json.optBoolean("isPermanentCommitment", false),
                userFacingConfirmation = userFacingConfirmation(json.optJSONObject("userFacingConfirmation")),
                clarificationOptionsRawJson = json.optJSONArray("clarificationOptions")?.toString(),
                optionVerification = json.optString("optionVerification")
                    .takeIf { it.isNotBlank() && it != "null" },
                scopeContract = json.optString("scopeContract")
                    .takeIf { it.isNotBlank() && it != "null" },
                scopeJson = json.optJSONObject("scope")?.toString(),
                compilerPromptVersion = json.optJSONObject("meta")?.optString("promptVersion")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i)?.trim().orEmpty()
            if (value.isNotEmpty() && value != "null") out += value
        }
        return out
    }

    private fun clarificationOptions(array: JSONArray?): List<ClarificationOptionDto> {
        if (array == null) return emptyList()
        val out = mutableListOf<ClarificationOptionDto>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val label = obj.optString("label").trim()
            if (id.isEmpty() || label.isEmpty()) continue
            val preview = obj.optString("policyPreview")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: obj.optString("resultingPolicyPreview")
                    .takeIf { it.isNotBlank() && it != "null" }
            out += ClarificationOptionDto(
                id = id,
                label = label,
                description = obj.optString("description").trim(),
                recommended = obj.optBoolean("recommended", false),
                policyPreview = preview,
                scopeJson = obj.optJSONObject("scope")?.toString()
            )
        }
        return out
    }

    private fun userFacingConfirmation(obj: JSONObject?): UserFacingConfirmationDto? {
        if (obj == null) return null
        val understood = obj.optString("understood")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString("understoodSummary")
                .takeIf { it.isNotBlank() && it != "null" }
        val allowed = stringList(obj.optJSONArray("allowed")).ifEmpty {
            stringList(obj.optJSONArray("allowedBullets"))
        }
        val blocked = stringList(obj.optJSONArray("blocked")).ifEmpty {
            stringList(obj.optJSONArray("blockedBullets"))
        }
        val time = obj.optString("time")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString("timeWindowText")
                .takeIf { it.isNotBlank() && it != "null" }
        val appliesTo = obj.optString("appliesTo")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString("appliesToText")
                .takeIf { it.isNotBlank() && it != "null" }
        return UserFacingConfirmationDto(
            understood = understood,
            allowed = allowed,
            blocked = blocked,
            time = time,
            appliesTo = appliesTo,
            checkThis = stringList(obj.optJSONArray("checkThis")),
            safetyNotes = stringList(obj.optJSONArray("safetyNotes"))
        )
    }

    private fun appRules(array: JSONArray?): List<CompiledAppRuleDto> {
        if (array == null) return emptyList()
        val out = mutableListOf<CompiledAppRuleDto>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val packageName = obj.optString("packageName").trim()
            val label = obj.optString("label").trim().ifBlank { packageName }
            val behavior = obj.optString("behavior").trim().uppercase()
            if (packageName.isEmpty() || behavior.isEmpty()) continue
            val scope = if (obj.isNull("scope")) "" else obj.optString("scope").trim()
            out += CompiledAppRuleDto(packageName, label, behavior, scope)
        }
        return out
    }

    private fun contentRules(array: JSONArray?): List<CompiledContentRuleDto> {
        if (array == null) return emptyList()
        val out = mutableListOf<CompiledContentRuleDto>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val action = obj.optString("action").trim().uppercase()
            if (action.isEmpty()) continue
            out += CompiledContentRuleDto(
                appLabel = obj.optString("appLabel").takeIf { it.isNotBlank() && it != "null" },
                packageName = obj.optString("packageName")
                    .takeIf { it.isNotBlank() && it != "null" },
                surface = obj.optString("surface", "any").ifBlank { "any" },
                contentType = obj.optString("contentType", "other").ifBlank { "other" },
                operator = obj.optString("operator").takeIf { it.isNotBlank() && it != "null" },
                value = if (obj.has("value") && !obj.isNull("value")) obj.optInt("value") else null,
                unit = obj.optString("unit").takeIf { it.isNotBlank() && it != "null" },
                action = action,
                description = obj.optString("description")
                    .takeIf { it.isNotBlank() && it != "null" }
            )
        }
        return out
    }
}

/**
 * Pure mapper: compiled DTO → [FocusPromise] for confirmation UI.
 */
object CompiledPromiseMapper {

    const val LOCAL_FALLBACK_CAUTION =
        "Basic offline preview — AI compiler unavailable. Confirm carefully before Start."

    fun toFocusPromise(response: CompiledPromiseResponse, fallbackRawText: String = ""): FocusPromise {
        val raw = response.rawText.ifBlank { fallbackRawText }
        val ufc = response.userFacingConfirmation
        val scopeKind = response.scopeKind?.trim()?.takeIf { it.isNotEmpty() }
        val youtubeContentScope = isYouTubeContentScope(
            rawText = raw,
            scopeKind = scopeKind,
            contentBrands = response.contentBrands + response.contentScope + response.surfaceScope
        )
        val allowedSummaries = sanitizeConfirmationLinesForScope(
            lines = ufc?.allowed?.takeIf { it.isNotEmpty() }
            ?: response.allowedSummaries.ifEmpty {
                response.confirmationPreview.filter {
                    it.startsWith("Allowed:", ignoreCase = true)
                }
            },
            youtubeContentScope = youtubeContentScope
        )
        val blockedSummaries = sanitizeConfirmationLinesForScope(
            lines = ufc?.blocked?.takeIf { it.isNotEmpty() }
            ?: response.blockedSummaries.ifEmpty {
                response.confirmationPreview.filter {
                    it.startsWith("Blocked", ignoreCase = true)
                }
            },
            youtubeContentScope = youtubeContentScope
        )
        val checkThis = sanitizeConfirmationNotesForScope(
            notes = ufc?.checkThis.orEmpty(),
            youtubeContentScope = youtubeContentScope
        )
        val safetyNotes = sanitizeConfirmationNotesForScope(
            notes = ufc?.safetyNotes.orEmpty(),
            youtubeContentScope = youtubeContentScope
        )
        val scopeContractV1 = response.scopeContract == SCOPE_CONTRACT_V1
        val typedScope = if (scopeContractV1) {
            ScopeCanonicalizer.fromCompilerJson(response.scopeJson, raw)
        } else {
            null
        }
        val scopeUnresolvedCaution = if (typedScope != null && !typedScope.isEnforceable) {
            listOf(TYPED_SCOPE_UNRESOLVED_CAUTION)
        } else {
            emptyList()
        }
        val appRulePlan = planAppRules(response.suggestedAppRules)
        val legacyGap = LegacyScopeContractGuard.gap(raw, scopeContractV1)
        val legacyGapCaution = if (legacyGap != null) {
            listOf(LegacyScopeContractGuard.START_BLOCKED_COPY)
        } else {
            emptyList()
        }
        return PromiseClarificationLaw.clearGhostClarification(
            FocusPromise(
                rawText = raw,
            sessionDurationMinutes = response.sessionDurationMinutes.coerceAtLeast(1),
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = parseStrictness(response.strictness),
            suggestedAppRules = appRulePlan.appRules,
            contentRules = mergeSurfaceRules(
                response.contentRules.mapNotNull(::mapContentRule),
                appRulePlan.surfaceRules
            ),
            scopePackages = response.scopePackages
                .mapNotNull { canonicalizePackage(it) }
                .distinct(),
            contentBrands = mergeBrandLists(
                response.contentBrands,
                response.contentScope,
                response.surfaceScope
            ),
            surfaceScope = mergeBrandLists(response.surfaceScope, response.contentBrands),
            contentScope = mergeBrandLists(response.contentScope, response.contentBrands),
            scopeKind = scopeKind,
            clarificationQuestion = response.clarificationQuestion?.takeIf { it.isNotBlank() },
            warnings = sanitizeConfirmationLinesForScope(
                lines = legacyGapCaution + response.cautionMessages + response.interpretationNotes + checkThis +
                    safetyNotes + scopeUnresolvedCaution,
                youtubeContentScope = youtubeContentScope
            )
                .filter { it.isNotBlank() }
                .distinct(),
            allowedSummaries = allowedSummaries,
            blockedSummaries = blockedSummaries,
            conditionalSummaries = sanitizeConfirmationLinesForScope(
                lines = (
                response.conditionalSummaries +
                    response.confirmationPreview.filter { line ->
                        !line.startsWith("Allowed:", ignoreCase = true) &&
                            !line.startsWith("Blocked", ignoreCase = true)
                    }
                ),
                youtubeContentScope = youtubeContentScope
            ).distinct(),
            clarificationOptions = response.clarificationOptions.map { dto ->
                mapClarificationOption(dto, if (scopeContractV1) raw else null)
            },
            clarificationRequired = response.clarificationRequired,
            canStartCommitment = response.canStartCommitment,
            choiceVerificationFailed =
                response.optionVerification == PromiseClarificationLaw.CHOICE_FAIL_CLOSED,
            requiresStrongerConfirmation = response.requiresStrongerConfirmation,
            isPermanentCommitment = response.isPermanentCommitment,
            understoodSummary = ufc?.understood,
            userFacingTime = ufc?.time,
            // v1: "Applies to" comes from the typed scope only, never model prose.
            userFacingAppliesTo = if (typedScope != null) {
                ScopeAppliesToCopy.forScope(typedScope)
            } else {
                ufc?.appliesTo
            },
            checkThisNotes = checkThis,
            safetyNotes = safetyNotes,
            scopeContractV1 = scopeContractV1,
            enforcementScope = typedScope,
            unrepresentableAppScopes = appRulePlan.unrepresentableLabels,
            compilerPromptVersion = response.compilerPromptVersion,
            legacyContractGap = legacyGap
        )
        )
    }

    private data class AppRulePlan(
        val appRules: List<AppRule>,
        val surfaceRules: List<ContentRule>,
        val unrepresentableLabels: List<String>
    )

    /**
     * A BLOCK scoped to a surface ("YouTube, scope: shorts") is a structured short-form
     * BLOCK on that package, not a package-wide BLOCK. An unknown scope is never widened
     * to the whole app; it is dropped and reported so Start stays closed.
     */
    private fun planAppRules(dtos: List<CompiledAppRuleDto>): AppRulePlan {
        val appRules = mutableListOf<AppRule>()
        val surfaceRules = mutableListOf<ContentRule>()
        val unrepresentable = mutableListOf<String>()
        for (dto in dtos) {
            val rule = mapAppRule(dto) ?: continue
            if (rule.behavior != AppRuleBehavior.BLOCK) {
                appRules += rule
                continue
            }
            when (EffectivePromisePolicy.classifyAppScope(dto.scope)) {
                EffectivePromisePolicy.AppScope.WHOLE_APP -> appRules += rule
                EffectivePromisePolicy.AppScope.SHORT_FORM -> {
                    appRules += rule.copy(behavior = AppRuleBehavior.AI_DECIDE)
                    surfaceRules += EffectivePromisePolicy.shortFormSurfaceRule(rule.packageName, rule.label)
                }
                EffectivePromisePolicy.AppScope.UNKNOWN -> unrepresentable += rule.label
            }
        }
        return AppRulePlan(appRules.distinctBy { it.packageName }, surfaceRules, unrepresentable)
    }

    private fun mergeSurfaceRules(existing: List<ContentRule>, surface: List<ContentRule>): List<ContentRule> =
        existing + surface.filter { add ->
            existing.none {
                EffectivePromisePolicy.isShortFormSurfaceBlock(it) && it.packageName == add.packageName
            }
        }

    /** v1 options are labelled from their structured scope so the card names what gets saved. */
    private fun mapClarificationOption(dto: ClarificationOptionDto, v1PromiseText: String?): ClarificationOption {
        val canonicalLabel = v1PromiseText?.let { promise ->
            ScopeAppliesToCopy.optionLabel(ScopeCanonicalizer.fromCompilerJson(dto.scopeJson, promise))
        }
        return ClarificationOption(
            id = dto.id,
            label = canonicalLabel ?: dto.label,
            description = dto.description,
            recommended = dto.recommended,
            policyPreview = dto.policyPreview
        )
    }

    const val SCOPE_CONTRACT_V1 = "v1"
    const val TYPED_SCOPE_UNRESOLVED_CAUTION =
        "Where this promise applies isn't clear yet. Pick an option or reword it before Start."

    private fun parseStrictness(value: String): StrictnessLevel =
        when (value.trim().uppercase()) {
            "SOFT" -> StrictnessLevel.SOFT
            "SMART" -> StrictnessLevel.SMART
            "LOCKED" -> StrictnessLevel.LOCKED
            else -> StrictnessLevel.STRICT
        }

    private fun mapAppRule(dto: CompiledAppRuleDto): AppRule? {
        val behavior = runCatching {
            AppRuleBehavior.valueOf(dto.behavior.trim().uppercase())
        }.getOrNull() ?: return null
        if (dto.packageName.isBlank()) return null
        val packageName = canonicalizePackage(dto.packageName) ?: return null
        return AppRule(
            packageName = packageName,
            label = dto.label.ifBlank { packageName },
            behavior = behavior
        )
    }

    private fun mapContentRule(dto: CompiledContentRuleDto): ContentRule? {
        val action = runCatching {
            ContentRuleAction.valueOf(dto.action.trim().uppercase())
        }.getOrNull() ?: return null
        if (isContentFreeAllow(dto, action)) return null
        return ContentRule(
            appLabel = dto.appLabel,
            packageName = dto.packageName?.let { canonicalizePackage(it) ?: it },
            surface = dto.surface.ifBlank { "any" },
            contentType = dto.contentType.ifBlank { "other" },
            operator = dto.operator,
            value = dto.value,
            unit = dto.unit,
            action = action
        )
    }

    /** Compiler filler like `ALLOW::::other:any:` names nothing; BLOCK rules are never dropped. */
    private fun isContentFreeAllow(dto: CompiledContentRuleDto, action: ContentRuleAction): Boolean =
        action == ContentRuleAction.ALLOW &&
            dto.packageName.isNullOrBlank() &&
            dto.appLabel.isNullOrBlank() &&
            dto.operator.isNullOrBlank() &&
            dto.value == null &&
            dto.unit.isNullOrBlank() &&
            dto.contentType.ifBlank { "other" } == "other" &&
            dto.surface.ifBlank { "any" } == "any"

    private fun mergeBrandLists(vararg lists: List<String>): List<String> =
        lists.flatMap { list ->
            list.map { EnforcementScopeLaw.normalizeBrand(it) }.filter { it.isNotEmpty() }
        }.distinct()

    private fun isYouTubeContentScope(
        rawText: String,
        scopeKind: String?,
        contentBrands: List<String>
    ): Boolean {
        if (scopeKind == "youtube_content") return true
        if (scopeKind == "youtube_app_only" || scopeKind == "youtube_only" || scopeKind == "youtube_shorts_only") {
            return false
        }
        val hasYouTubeBrand = contentBrands.any {
            EnforcementScopeLaw.normalizeBrand(it) == "youtube"
        }
        return hasYouTubeBrand && !PromiseIntentRules.namesYouTubeAppOnly(rawText)
    }

    private fun sanitizeConfirmationNotesForScope(
        notes: List<String>,
        youtubeContentScope: Boolean
    ): List<String> {
        val sanitized = notes.filter { note ->
            val lower = note.lowercase()
            !youtubeContentScope || (
                "not browser" !in lower &&
                    "excluding browser" !in lower &&
                    "alternative player" !in lower &&
                    "official app only" !in lower &&
                    "youtube app only" !in lower &&
                    !lower.matches(Regex(".*\\bonly youtube is allowed for video playback.*"))
                )
        }
        return if (youtubeContentScope && sanitized.size != notes.size) {
            (sanitized + "YouTube content applies across the YouTube app, browser pages, and other YouTube clients.")
                .distinct()
        } else {
            sanitized.distinct()
        }
    }

    private fun sanitizeConfirmationLinesForScope(
        lines: List<String>,
        youtubeContentScope: Boolean
    ): List<String> = lines.mapNotNull { line ->
        sanitizeConfirmationLineForScope(line, youtubeContentScope)
    }.distinct()

    private fun sanitizeConfirmationLineForScope(
        line: String,
        youtubeContentScope: Boolean
    ): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!youtubeContentScope) return trimmed
        val withoutOtherVideoServices = trimmed
            .replace(
                Regex(
                    pattern = "\\s*,?\\s*other apps[^.]*",
                    option = RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    pattern = "\\s*,?\\s*other video services[^.]*",
                    option = RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*,\\s*,+\\s*"), ", ")
            .replace(Regex("\\.\\s*,+\\s*"), ". ")
            .replace(Regex("\\s+,\\s*"), ", ")
            .trim()
            .trim(',', ';')
            .trim()
        return withoutOtherVideoServices.takeIf { it.isNotBlank() }
    }

    private fun canonicalizePackage(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return VideoPlatformRegistry.canonicalizeNamedPackage(trimmed) ?: trimmed
    }
}
