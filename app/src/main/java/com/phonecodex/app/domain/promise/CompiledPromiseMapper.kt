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
    val userFacingConfirmation: UserFacingConfirmationDto? = null
)

data class ClarificationOptionDto(
    val id: String,
    val label: String,
    val description: String,
    val recommended: Boolean,
    val policyPreview: String?
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
    val behavior: String
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
                userFacingConfirmation = userFacingConfirmation(json.optJSONObject("userFacingConfirmation"))
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
                policyPreview = preview
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
            out += CompiledAppRuleDto(packageName, label, behavior)
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
        return PromiseClarificationLaw.clearGhostClarification(
            FocusPromise(
                rawText = raw,
            sessionDurationMinutes = response.sessionDurationMinutes.coerceAtLeast(1),
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = parseStrictness(response.strictness),
            suggestedAppRules = response.suggestedAppRules.mapNotNull(::mapAppRule),
            contentRules = response.contentRules.mapNotNull(::mapContentRule),
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
                lines = response.cautionMessages + response.interpretationNotes + checkThis + safetyNotes,
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
            clarificationOptions = response.clarificationOptions.map(::mapClarificationOption),
            clarificationRequired = response.clarificationRequired,
            canStartCommitment = response.canStartCommitment,
            requiresStrongerConfirmation = response.requiresStrongerConfirmation,
            isPermanentCommitment = response.isPermanentCommitment,
            understoodSummary = ufc?.understood,
            userFacingTime = ufc?.time,
            userFacingAppliesTo = ufc?.appliesTo,
            checkThisNotes = checkThis,
            safetyNotes = safetyNotes
        )
        )
    }

    private fun mapClarificationOption(dto: ClarificationOptionDto): ClarificationOption =
        ClarificationOption(
            id = dto.id,
            label = dto.label,
            description = dto.description,
            recommended = dto.recommended,
            policyPreview = dto.policyPreview
        )

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
