package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.EnforcementScopeLaw
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise

/**
 * Extracts structured clock fields from a confirmed [FocusPromise]
 * so enforcement does not re-parse English.
 */
object PromiseContentRuleSupport {

    /**
     * Per-video block threshold in minutes (clock B): block when individual
     * video length is greater than this value. Null = no structured length gate.
     */
    fun maxVideoLengthBlockMinutes(draft: FocusPromise): Int? =
        videoLengthBounds(draft).maxBlockMinutes

    /**
     * Resolved per-video length gate for a confirmed promise.
     *
     * When the promise text names only one side ("shorter than 30" with a block
     * cue, or "allow only longer than 30"), that side wins and a contradictory
     * structured rule on the other side is dropped. min == max survives only
     * for an explicit "exactly N minutes"; otherwise min >= max keeps min,
     * because min == max would block every video except one exact length.
     */
    fun videoLengthBounds(draft: FocusPromise): PromiseIntentRules.MediaLengthIntent {
        val intent = PromiseIntentRules.mediaLengthIntent(draft.rawText)
        if (intent.exact) return intent
        val ruleMin = minVideoLengthBlockMinutes(draft.contentRules)
            ?: minFromAllowOnlyLongerRules(draft.contentRules)
        val ruleMax = maxVideoLengthBlockMinutes(draft.contentRules)
        val textMin = intent.minBlockMinutes
        val textMax = intent.maxBlockMinutes
        var min: Int?
        var max: Int?
        when {
            textMin != null && textMax == null -> {
                min = textMin
                max = null
            }
            textMax != null && textMin == null -> {
                min = null
                max = ruleMax ?: textMax
            }
            textMin != null && textMax != null -> {
                min = textMin
                max = ruleMax ?: textMax
            }
            else -> {
                min = ruleMin
                max = ruleMax
            }
        }
        if (min != null && max != null && min >= max) max = null
        return PromiseIntentRules.MediaLengthIntent(min, max)
    }

    fun maxVideoLengthBlockMinutes(rules: List<ContentRule>): Int? {
        val blockGt = rules.firstOrNull { rule ->
            rule.action == ContentRuleAction.BLOCK &&
                rule.unit.equals("minutes", ignoreCase = true) &&
                rule.value != null &&
                rule.value!! > 0 &&
                (rule.operator == "gt" || rule.operator == "gte") &&
                isVideoish(rule)
        }
        if (blockGt != null) {
            // gt 40 → block when > 40; gte 40 → treat as block when >= 40 → use 39 for ">"
            return if (blockGt.operator == "gte") {
                (blockGt.value!! - 1).coerceAtLeast(0)
            } else {
                blockGt.value
            }
        }
        return null
    }

    /**
     * Per-video minimum length in minutes (clock B): block when individual
     * video length is shorter than this value. Null = no structured min gate.
     */
    fun minVideoLengthBlockMinutes(draft: FocusPromise): Int? =
        videoLengthBounds(draft).minBlockMinutes

    fun minVideoLengthBlockMinutes(rules: List<ContentRule>): Int? {
        val blockLt = rules.firstOrNull { rule ->
            rule.action == ContentRuleAction.BLOCK &&
                rule.unit.equals("minutes", ignoreCase = true) &&
                rule.value != null &&
                rule.value!! > 0 &&
                (rule.operator == "lt" || rule.operator == "lte") &&
                isVideoish(rule)
        } ?: return null
        // lt 30 → block when < 30; lte 30 → block when <= 30 → treat as min 31 for "<"
        return if (blockLt.operator == "lte") {
            blockLt.value!! + 1
        } else {
            blockLt.value
        }
    }

    fun hasShortFormBlock(rules: List<ContentRule>): Boolean =
        rules.any {
            it.action == ContentRuleAction.BLOCK &&
                (
                    it.contentType == "short_form_video" ||
                        it.surface.equals("shorts", ignoreCase = true) ||
                        it.surface.equals("reels", ignoreCase = true)
                    )
        }

    /**
     * Daily short-form count quota from structured contentRules (unit=count),
     * else null so callers can fall back to goal-text parse.
     */
    fun shortFormDailyQuotaLimit(draft: FocusPromise): Int? {
        val fromRules = draft.contentRules.firstOrNull { rule ->
            rule.unit.equals("count", ignoreCase = true) &&
                rule.value != null &&
                rule.value!! > 0 &&
                (
                    rule.contentType == "short_form_video" ||
                        rule.surface.equals("shorts", ignoreCase = true) ||
                        rule.surface.equals("reels", ignoreCase = true)
                    ) &&
                (
                    rule.action == ContentRuleAction.ALLOW ||
                        rule.action == ContentRuleAction.WARN ||
                        rule.operator == "lte" ||
                        rule.operator == "lt"
                    )
        }?.value
        return fromRules
            ?: com.phonecodex.app.domain.enforcement.ShortFormQuotaGate.parseDailyShortFormLimit(
                draft.rawText
            )
    }

    /** "Today" / "rest of the day" ends at local midnight, as the confirmation card says; "daily" or "1 day" does not. */
    fun isTodayWindow(draft: FocusPromise): Boolean {
        val text = draft.rawText.lowercase()
        return TODAY_WINDOW.containsMatchIn(text) && !PromiseIntentRules.hasExplicitSessionDuration(text)
    }

    private val TODAY_WINDOW = Regex("""\b(today|rest of (?:the )?day|for the day)\b""")

    fun isCalendarDayWindow(draft: FocusPromise): Boolean {
        val text = draft.rawText.lowercase()
        if (text.contains("today") ||
            text.contains("per day") ||
            text.contains("daily") ||
            text.contains("rest of the day") ||
            text.contains("for the day")
        ) {
            return true
        }
        if (draft.sessionDurationMinutes >= 24 * 60) return true
        val quota = shortFormDailyQuotaLimit(draft)
        return quota != null && !PromiseIntentRules.hasExplicitSessionDuration(text)
    }

    /**
     * Package-exclusive clock scope. YouTube *content* promises return empty here
     * so Chrome youtube.com / NewPipe are not treated as outside scope.
     */
    fun enforcementScopePackages(draft: FocusPromise): List<String> =
        EnforcementScopeLaw.resolvePackageScope(
            rawPackages = rawNamedPackages(draft),
            contentBrands = enforcementContentBrands(draft),
            scopeKind = draft.scopeKind,
            rawText = draft.rawText
        )

    /**
     * Content brands from DTO aliases or inferred from "YouTube video/content".
     * Empty for "YouTube app only".
     */
    fun enforcementContentBrands(draft: FocusPromise): List<String> =
        EnforcementScopeLaw.resolveContentBrands(
            explicitBrands = draft.contentBrands + draft.contentScope + draft.surfaceScope,
            scopeKind = draft.scopeKind,
            rawText = draft.rawText
        )

    fun enforcementScopeKind(draft: FocusPromise): String? {
        val kind = draft.scopeKind?.trim()?.takeIf { it.isNotEmpty() }
        if (kind != null) {
            return if (EnforcementScopeLaw.isYouTubeAppOnlyScopeKind(kind)) {
                EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
            } else {
                kind
            }
        }
        if (PromiseIntentRules.namesYouTubeAppOnly(draft.rawText)) {
            return EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
        }
        if (enforcementContentBrands(draft).contains(EnforcementScopeLaw.BRAND_YOUTUBE)) {
            return EnforcementScopeLaw.SCOPE_YOUTUBE_CONTENT
        }
        return null
    }

    private fun rawNamedPackages(draft: FocusPromise): List<String> {
        if (draft.scopePackages.isNotEmpty()) {
            return draft.scopePackages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }
        val fromRules = draft.contentRules
            .mapNotNull { it.packageName?.trim()?.takeIf { pkg -> pkg.isNotEmpty() } }
            .distinct()
        if (fromRules.isNotEmpty()) return fromRules
        if (
            PromiseIntentRules.namesThisAppOnly(draft.rawText) ||
            PromiseIntentRules.namesNetMirror(draft.rawText) ||
            PromiseIntentRules.namesYouTubeAppOnly(draft.rawText)
        ) {
            return draft.suggestedAppRules
                .map { it.packageName.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
        return emptyList()
    }

    private fun minFromAllowOnlyLongerRules(rules: List<ContentRule>): Int? {
        val allowGt = rules.firstOrNull { rule ->
            rule.action == ContentRuleAction.ALLOW &&
                rule.unit.equals("minutes", ignoreCase = true) &&
                rule.value != null &&
                rule.value!! > 0 &&
                (rule.operator == "gt" || rule.operator == "gte") &&
                isVideoish(rule)
        } ?: return null
        return allowGt.value
    }

    private fun isVideoish(rule: ContentRule): Boolean {
        val type = rule.contentType.lowercase()
        val surface = rule.surface.lowercase()
        return type.contains("video") ||
            type == "entertainment" ||
            type == "long_form_video" ||
            surface == "video" ||
            surface == "any"
    }
}
