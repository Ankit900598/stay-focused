package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.EnforcementScopeLaw
import com.phonecodex.app.domain.enforcement.ShortFormLanguage
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.ScopeCanonicalizer
import com.phonecodex.app.domain.scope.ScopeIdentity
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeKindPresets

/**
 * On-device Promise Compiler preview.
 *
 * Separates session duration from content-duration / surface rules. Ambiguous promises return
 * [FocusPromise.clarificationQuestion] so Start cannot run.
 *
 * Azure swap: map Promise Compiler JSON → [FocusPromise]; confirmation UI stays unchanged.
 */
class FocusPromiseParser {

    fun parse(
        rawText: String,
        namedPackageHint: String? = null,
        fallbackDurationMinutes: Int = DEFAULT_DURATION_MINUTES
    ): FocusPromise {
        val trimmed = rawText.trim()
        val normalized = trimmed.lowercase()
        val fallbackDuration = fallbackDurationMinutes.coerceAtLeast(1)
        val hintedPackage = VideoPlatformRegistry.canonicalizeNamedPackage(namedPackageHint.orEmpty())
            ?: namedPackageHint?.trim()?.takeIf { VideoPlatformRegistry.isVideoOrStreamingPackage(it) }

        val contentLengthHits = findContentLengthHits(normalized)
        var contentRules = buildContentRules(normalized, contentLengthHits, hintedPackage)
        contentRules = applyOnlyLongVideoSemantics(normalized, contentRules, contentLengthHits)

        val parsedSessionDuration = parseSessionDuration(normalized, contentLengthHits, fallbackDuration)
        val sessionDurationMinutes = parsedSessionDuration.minutes
        val suggestedAppRules = parseSuggestedAppRules(normalized, contentRules, hintedPackage)
        val contentBrands = inferContentBrands(normalized)
        val scopeKind = inferScopeKind(normalized, contentBrands)
        val scopePackages = inferScopePackages(normalized, contentRules, suggestedAppRules, hintedPackage)
        val strictness = parseStrictness(normalized)
        val lockAttempt = parseLockAttemptThreshold(normalized)
        val clarification = detectClarification(normalized, contentRules, contentLengthHits)
        val warnings = buildWarnings(
            normalized,
            parsedSessionDuration,
            contentLengthHits,
            fallbackDuration
        )
        val typedScope = youtubeShortsOnlyScope(normalized, contentRules, suggestedAppRules, contentLengthHits)

        val summaries = buildHumanSummaries(
            normalized = normalized,
            contentRules = contentRules,
            suggestedAppRules = suggestedAppRules
        )

        return PromiseClarificationLaw.apply(
            FocusPromise(
                rawText = trimmed,
                sessionDurationMinutes = sessionDurationMinutes,
                lockAttemptThreshold = lockAttempt.threshold,
                lockAttemptThresholdInvalid = lockAttempt.invalid,
                sessionDurationWasExplicit = parsedSessionDuration.wasExplicit,
                allowedKeywords = findKeywords(normalized, ALLOW_KEYWORDS),
                blockedKeywords = findKeywords(normalized, BLOCK_KEYWORDS),
                strictness = strictness,
                suggestedAppRules = suggestedAppRules,
                contentRules = contentRules,
                scopePackages = scopePackages,
                contentBrands = contentBrands,
                surfaceScope = contentBrands,
                contentScope = contentBrands,
                scopeKind = scopeKind,
                clarificationQuestion = clarification,
                warnings = warnings,
                allowedSummaries = summaries.allowed,
                blockedSummaries = summaries.blocked,
                conditionalSummaries = summaries.conditional,
                scopeContractV1 = typedScope != null,
                enforcementScope = typedScope,
                legacyContractGap = LegacyScopeContractGuard.gap(trimmed, scopeContractV1 = typedScope != null)
            )
        )
    }

    private data class ContentLengthHit(
        val range: IntRange,
        val minutes: Int,
        val operator: String
    )

    private data class ParsedSessionDuration(
        val minutes: Int,
        val wasExplicit: Boolean
    )

    private data class HumanSummaries(
        val allowed: List<String>,
        val blocked: List<String>,
        val conditional: List<String>
    )

    private fun findContentLengthHits(normalized: String): List<ContentLengthHit> {
        val hits = mutableListOf<ContentLengthHit>()

        fun addHit(range: IntRange, minutes: Int, operator: String) {
            val covered = hits.any { it.range.first <= range.first && range.last <= it.range.last }
            if (!covered) hits += ContentLengthHit(range, minutes, operator)
        }

        CONTENT_COMPARISON_PATTERN.findAll(normalized).forEach { match ->
            val opWord = match.groupValues[1]
            val amount = match.groupValues[2].toIntOrNull() ?: return@forEach
            val unit = match.groupValues[3]
            val minutes = toMinutes(amount, unit) ?: return@forEach
            val operator = when {
                opWord.startsWith("long") || opWord.startsWith("great") ||
                    opWord.startsWith("more") -> "gt"
                opWord.startsWith("shorter") || opWord.startsWith("less") -> "lt"
                else -> "gt"
            }
            addHit(match.range, minutes, operator)
        }

        LENGTH_IS_PATTERN.findAll(normalized).forEach { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return@forEach
            val unit = match.groupValues[2]
            val minutes = toMinutes(amount, unit) ?: return@forEach
            addHit(match.range, minutes, "gt")
        }

        CONTENT_GT_SYMBOL_PATTERN.findAll(normalized).forEach { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return@forEach
            val unit = match.groupValues[2]
            val minutes = toMinutes(amount, unit) ?: return@forEach
            addHit(match.range, minutes, "gt")
        }

        return hits
    }

    private fun buildContentRules(
        normalized: String,
        lengthHits: List<ContentLengthHit>,
        hintedPackage: String? = null
    ): List<ContentRule> {
        val rules = mutableListOf<ContentRule>()

        lengthHits.forEach { hit ->
            val windowStart = (hit.range.first - 64).coerceAtLeast(0)
            val window = normalized.substring(windowStart, hit.range.last + 1)
            val action = inferLengthAction(window)
            val apps = appsMentionedNear(window, normalized, hintedPackage)

            if (apps.isEmpty()) {
                rules += ContentRule(
                    appLabel = null,
                    packageName = null,
                    surface = "video",
                    contentType = "long_form_video",
                    operator = hit.operator,
                    value = hit.minutes,
                    unit = "minutes",
                    action = action
                )
            } else {
                apps.forEach { app ->
                    rules += ContentRule(
                        appLabel = app.label,
                        packageName = app.packageName,
                        surface = "video",
                        contentType = "long_form_video",
                        operator = hit.operator,
                        value = hit.minutes,
                        unit = "minutes",
                        action = action
                    )
                }
            }
        }

        if (mentionsInstagram(normalized)) {
            val messagesOnly = INSTAGRAM_MESSAGES_ONLY.containsMatchIn(normalized) ||
                ((normalized.contains("only") || normalized.contains("sirf")) &&
                    (normalized.contains("message") || normalized.contains("dm") ||
                        normalized.contains("chat") || normalized.contains("repl")))
            val noReels = normalized.contains("no reel") || normalized.contains("no reels") ||
                (normalized.contains("reel") &&
                    (normalized.contains("block") || normalized.contains("don't") ||
                        normalized.contains("dont") || normalized.contains("without") ||
                        normalized.contains("mat ")))
            if (messagesOnly) {
                rules += ContentRule(
                    appLabel = "Instagram",
                    packageName = PKG_INSTAGRAM,
                    surface = "messages",
                    contentType = "social_dm",
                    operator = null,
                    value = null,
                    unit = null,
                    action = ContentRuleAction.ALLOW
                )
            }
            if (noReels || messagesOnly) {
                rules += ContentRule(
                    appLabel = "Instagram",
                    packageName = PKG_INSTAGRAM,
                    surface = "reels",
                    contentType = "short_form_video",
                    operator = null,
                    value = null,
                    unit = null,
                    action = ContentRuleAction.BLOCK
                )
            }
            // "Only messages" also means no feed; "no reels" alone never widens to the feed.
            if (messagesOnly) {
                rules += ContentRule(
                    appLabel = "Instagram",
                    packageName = PKG_INSTAGRAM,
                    surface = "feed",
                    contentType = "social_feed",
                    operator = null,
                    value = null,
                    unit = null,
                    action = ContentRuleAction.BLOCK
                )
            }
        }

        if ((normalized.contains("youtube") || normalized.contains("neso")) &&
            (normalized.contains("neso") || normalized.contains("playlist") ||
                normalized.contains("channel"))
        ) {
            rules += ContentRule(
                appLabel = "YouTube",
                packageName = PKG_YOUTUBE,
                surface = "playlist",
                contentType = "study",
                operator = null,
                value = null,
                unit = null,
                action = ContentRuleAction.AI_DECIDE
            )
        }

        if (blocksYouTubeShortsOnly(normalized)) {
            rules += EffectivePromisePolicy.shortFormSurfaceRule(PKG_YOUTUBE, "YouTube")
        }

        if (normalized.contains("porn") || normalized.contains("adult content") ||
            normalized.contains("nsfw") || normalized.contains("18+")
        ) {
            rules += ContentRule(
                appLabel = null,
                packageName = null,
                surface = "any",
                contentType = "adult_sexual",
                operator = null,
                value = null,
                unit = null,
                action = ContentRuleAction.BLOCK
            )
        }

        return rules.distinctBy {
            listOf(it.appLabel, it.surface, it.contentType, it.operator, it.value, it.action)
        }
    }

    /**
     * "only allow … longer than N" means shorter videos are blocked — no clarification needed.
     */
    private fun applyOnlyLongVideoSemantics(
        normalized: String,
        rules: List<ContentRule>,
        hits: List<ContentLengthHit>
    ): List<ContentRule> {
        if (hits.isEmpty()) return rules
        val onlyLong = hits.any { hit ->
            val before = normalized.substring(0, hit.range.first).takeLast(72)
            val after = normalized.substring(hit.range.last + 1).trim()
            val trailingOnly = after == "only" || after.startsWith("only ")
            ONLY_ALLOW_CUE.containsMatchIn(before) ||
                trailingOnly ||
                (before.contains("only") &&
                    (before.contains("allow") || before.contains("youtube") ||
                        before.contains("video")))
        }
        val shorterBlockCue = SHORTER_BLOCK_CUE.containsMatchIn(normalized)
        if (!onlyLong && !shorterBlockCue) return rules

        val expanded = rules.toMutableList()
        rules.filter {
            it.action == ContentRuleAction.ALLOW &&
                it.operator == "gt" &&
                it.contentType == "long_form_video" &&
                it.value != null
        }.forEach { allowRule ->
            expanded += allowRule.copy(
                operator = "lte",
                action = ContentRuleAction.BLOCK
            )
        }
        return expanded.distinctBy {
            listOf(it.appLabel, it.surface, it.contentType, it.operator, it.value, it.action)
        }
    }

    private fun inferLengthAction(window: String): ContentRuleAction {
        val explicitDeny =
            window.contains("do not allow") ||
                window.contains("dont allow") ||
                window.contains("don't allow") ||
                window.contains("not allow") ||
                window.contains("never allow")
        val blockCue = explicitDeny ||
            window.contains("block") ||
            window.contains("forbid") ||
            window.contains("ban ") ||
            Regex("""\bno\b""").containsMatchIn(window)
        // "allow" alone must not win over "do not allow" / "not allow".
        val allowCue = !explicitDeny && (
            window.contains("allow") ||
                window.contains("keep me") ||
                window.contains("only")
            )
        return when {
            explicitDeny || (blockCue && !allowCue) -> ContentRuleAction.BLOCK
            allowCue -> ContentRuleAction.ALLOW
            blockCue -> ContentRuleAction.BLOCK
            else -> ContentRuleAction.ALLOW
        }
    }

    private data class AppRef(val label: String, val packageName: String)

    private fun appsMentionedNear(
        window: String,
        full: String,
        hintedPackage: String? = null
    ): List<AppRef> {
        val apps = mutableListOf<AppRef>()
        val scope = "$window $full"
        if (scope.contains("youtube") || scope.contains("yt ")) {
            apps += AppRef("YouTube", PKG_YOUTUBE)
        }
        if (scope.contains("chrome") || scope.contains("browser") ||
            (scope.contains("anywhere") &&
                (scope.contains("youtube") || scope.contains("video")))
        ) {
            apps += AppRef("Chrome", PKG_CHROME)
        }
        if (PromiseIntentRules.namesNetMirror(scope)) {
            apps += AppRef("NetMirror", PKG_NETMIRROR)
        }
        if (
            PromiseIntentRules.namesThisAppOnly(full) &&
            hintedPackage != null &&
            VideoPlatformRegistry.isVideoOrStreamingPackage(hintedPackage) &&
            apps.none { it.packageName == hintedPackage }
        ) {
            val label = VideoPlatformRegistry.entry(hintedPackage)?.label ?: "this app"
            apps += AppRef(label, hintedPackage)
        }
        return apps.distinctBy { it.packageName }
    }

    private fun parseSessionDuration(
        normalized: String,
        contentHits: List<ContentLengthHit>,
        fallbackDurationMinutes: Int
    ): ParsedSessionDuration {
        val occupied = contentHits.map { it.range }

        fun overlapsContent(range: IntRange): Boolean =
            occupied.any { content ->
                range.first <= content.last && content.first <= range.last
            }

        SESSION_FOR_PATTERN.findAll(normalized).forEach { match ->
            if (overlapsContent(match.range)) return@forEach
            val amount = parseAmountToken(match.groupValues[1]) ?: return@forEach
            toMinutes(amount, match.groupValues[2])?.let {
                return ParsedSessionDuration(it, wasExplicit = true)
            }
        }

        SESSION_NEXT_PATTERN.findAll(normalized).forEach { match ->
            if (overlapsContent(match.range)) return@forEach
            val amount = parseAmountToken(match.groupValues[1]) ?: return@forEach
            toMinutes(amount, match.groupValues[2])?.let {
                return ParsedSessionDuration(it, wasExplicit = true)
            }
        }

        SESSION_DAY_YEAR_PATTERN.findAll(normalized).forEach { match ->
            if (overlapsContent(match.range)) return@forEach
            val before = normalized.substring(0, match.range.first).takeLast(24)
            if (CONTENT_COMPARE_CUES.containsMatchIn(before)) return@forEach
            val amount = parseAmountToken(match.groupValues[1]) ?: return@forEach
            toMinutes(amount, match.groupValues[2])?.let {
                return ParsedSessionDuration(it, wasExplicit = true)
            }
        }

        if (Regex("""\btoday\b""").containsMatchIn(normalized)) {
            return ParsedSessionDuration(MINUTES_PER_DAY, wasExplicit = true)
        }

        HOUR_PATTERN.findAll(normalized).forEach { match ->
            if (overlapsContent(match.range)) return@forEach
            val before = normalized.substring(0, match.range.first).takeLast(24)
            if (CONTENT_COMPARE_CUES.containsMatchIn(before)) return@forEach
            val hours = parseAmountToken(match.groupValues[1]) ?: return@forEach
            return ParsedSessionDuration(hours * 60, wasExplicit = true)
        }
        MINUTE_PATTERN.findAll(normalized).forEach { match ->
            if (overlapsContent(match.range)) return@forEach
            val before = normalized.substring(0, match.range.first).takeLast(24)
            if (CONTENT_COMPARE_CUES.containsMatchIn(before)) return@forEach
            val minutes = parseAmountToken(match.groupValues[1])
                ?: return ParsedSessionDuration(fallbackDurationMinutes, wasExplicit = false)
            return ParsedSessionDuration(minutes, wasExplicit = true)
        }

        return ParsedSessionDuration(fallbackDurationMinutes, wasExplicit = false)
    }

    private fun parseAmountToken(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        return WORD_AMOUNTS[raw.lowercase()]
    }

    private fun toMinutes(amount: Int, unit: String): Int? {
        val u = unit.lowercase()
        return when {
            u.startsWith("min") -> amount
            u.startsWith("hour") || u.startsWith("hr") -> amount * 60
            u.startsWith("day") -> amount * MINUTES_PER_DAY
            u.startsWith("year") -> amount * MINUTES_PER_YEAR
            else -> null
        }
    }

    private fun detectClarification(
        normalized: String,
        contentRules: List<ContentRule>,
        lengthHits: List<ContentLengthHit>
    ): String? {
        if (INSTA_LESS.containsMatchIn(normalized)) {
            val hasConcrete = contentRules.any {
                it.packageName == PKG_INSTAGRAM &&
                    (it.surface == "messages" || it.surface == "reels" || it.value != null)
            }
            if (!hasConcrete) {
                return "What limit do you want on Instagram — a time cap, Reels only, or messages only?"
            }
        }

        if (STRICT_VAGUE.containsMatchIn(normalized) || MAKE_STRICT.containsMatchIn(normalized)) {
            val hasConcreteApps = hasConcreteRestrictionCue(normalized, contentRules)
            if (!hasConcreteApps) {
                return "What apps or content should I restrict today?"
            }
        }

        if (FOCUS_VAGUE.containsMatchIn(normalized) &&
            !hasConcreteRestrictionCue(normalized, contentRules) &&
            lengthHits.isEmpty()
        ) {
            return "Focus on what — which apps, how long, and what should stay blocked?"
        }

        // Ambiguous "40 min youtube" / "youtube 1 hour" with no role words.
        if (isAmbiguousAppDurationPhrase(normalized, contentRules, lengthHits)) {
            return "I need one choice for that time: (1) focus session length, " +
                "(2) total YouTube watch budget, or (3) max length of a single video. " +
                "Example: “for 1 hour, block YouTube videos longer than 40 min”."
        }

        val allowLong = contentRules.any {
            it.action == ContentRuleAction.ALLOW &&
                it.operator == "gt" &&
                it.contentType == "long_form_video"
        }
        val hasShorterBlock = contentRules.any {
            it.action == ContentRuleAction.BLOCK &&
                (it.operator == "lt" || it.operator == "lte") &&
                it.contentType == "long_form_video"
        }
        if (allowLong && !hasShorterBlock) {
            val mentionsShorterPolicy = normalized.contains("shorter") ||
                normalized.contains("less than") ||
                normalized.contains("under ") ||
                normalized.contains("block short") ||
                normalized.contains("nothing shorter") ||
                normalized.contains("no shorter") ||
                SHORTER_BLOCK_CUE.containsMatchIn(normalized)
            if (!mentionsShorterPolicy) {
                return "Should shorter videos be blocked, or are long videos simply allowed while shorter ones stay fine?"
            }
        }

        if (normalized.length < 12 &&
            lengthHits.isEmpty() &&
            contentRules.isEmpty() &&
            !ALLOW_KEYWORDS.any { normalized.contains(it) }
        ) {
            return "What exactly should I protect — which apps, for how long?"
        }

        return null
    }

    /**
     * Bare "40 min youtube" / "youtube 1 hour" with no session/budget/length role words.
     * Must ask before enforcing (clock A vs B vs C).
     */
    private fun isAmbiguousAppDurationPhrase(
        normalized: String,
        contentRules: List<ContentRule>,
        lengthHits: List<ContentLengthHit>
    ): Boolean {
        if (lengthHits.isNotEmpty()) return false
        if (SESSION_FOR_PATTERN.containsMatchIn(normalized) ||
            SESSION_NEXT_PATTERN.containsMatchIn(normalized)
        ) {
            return false
        }
        if (CONTENT_COMPARISON_PATTERN.containsMatchIn(normalized) ||
            LENGTH_IS_PATTERN.containsMatchIn(normalized)
        ) {
            return false
        }
        val hasRoleWords =
            normalized.contains("session") ||
                normalized.contains("focus") ||
                normalized.contains("study") ||
                normalized.contains("padhai") ||
                normalized.contains("lecture") ||
                normalized.contains("exam") ||
                normalized.contains("budget") ||
                normalized.contains("total") ||
                normalized.contains("quota") ||
                normalized.contains("block") ||
                normalized.contains("allow") ||
                normalized.contains("longer") ||
                normalized.contains("greater") ||
                normalized.contains("length") ||
                normalized.contains("for next") ||
                normalized.contains("for the next")
        if (hasRoleWords) return false

        // Already have structured surface/length rules — not bare ambiguous.
        if (contentRules.isNotEmpty()) return false

        val hasApp =
            normalized.contains("youtube") ||
                normalized.contains("yt") ||
                normalized.contains("instagram") ||
                normalized.contains("insta") ||
                normalized.contains("tiktok") ||
                normalized.contains("chrome")
        val hasBareDuration =
            Regex("""\b\d+\s*(?:min|mins|minutes?|hrs?|hours?)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
        return hasApp && hasBareDuration
    }

    private fun hasConcreteRestrictionCue(
        normalized: String,
        contentRules: List<ContentRule>
    ): Boolean {
        return normalized.contains("youtube") ||
            normalized.contains("instagram") ||
            normalized.contains("insta") ||
            normalized.contains("chrome") ||
            normalized.contains("whatsapp") ||
            normalized.contains("shorts") ||
            normalized.contains("reel") ||
            normalized.contains("neso") ||
            normalized.contains("porn") ||
            normalized.contains("adult") ||
            contentRules.isNotEmpty()
    }

    private fun buildWarnings(
        normalized: String,
        parsedSessionDuration: ParsedSessionDuration,
        contentHits: List<ContentLengthHit>,
        fallbackDurationMinutes: Int
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (!parsedSessionDuration.wasExplicit &&
            contentHits.isNotEmpty()
        ) {
            warnings += "I used the saved session-time default of $fallbackDurationMinutes minutes " +
                "because I only saw a content length rule, not how long the commitment should run."
        }
        if (parsedSessionDuration.minutes >= MINUTES_PER_YEAR) {
            warnings += "Long commitments still need Accessibility kept on — preview, not a finished permanent lock."
        }
        if (normalized.contains("rest normal") || normalized.contains("everything else normal") ||
            normalized.contains("rest is normal")
        ) {
            warnings += "Other apps stay normal unless a permanent commitment is already on."
        }
        return warnings
    }

    private fun buildHumanSummaries(
        normalized: String,
        contentRules: List<ContentRule>,
        suggestedAppRules: List<AppRule>
    ): HumanSummaries {
        val allowed = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val conditional = mutableListOf<String>()

        contentRules.forEach { rule ->
            val line = humanContentLine(rule, normalized)
            when (rule.action) {
                ContentRuleAction.ALLOW -> allowed += line
                ContentRuleAction.BLOCK -> blocked += line
                ContentRuleAction.WARN, ContentRuleAction.AI_DECIDE -> conditional += line
            }
        }

        // Only surface app labels the user actually named — never dump inferred YouTube/NewPipe.
        suggestedAppRules.forEach { rule ->
            if (!userNamedAppLabel(normalized, rule.label)) return@forEach
            when (rule.behavior) {
                AppRuleBehavior.ALLOW -> allowed += rule.label
                AppRuleBehavior.BLOCK -> {
                    if (blocked.none { it.contains(rule.label, ignoreCase = true) }) {
                        blocked += rule.label
                    }
                }
                AppRuleBehavior.WARN, AppRuleBehavior.AI_DECIDE -> {
                    if (conditional.none { it.contains(rule.label, ignoreCase = true) } &&
                        allowed.none { it.contains(rule.label, ignoreCase = true) } &&
                        blocked.none { it.contains(rule.label, ignoreCase = true) }
                    ) {
                        conditional += "${rule.label} judged live"
                    }
                }
            }
        }

        if (normalized.contains("study") || normalized.contains("exam") ||
            normalized.contains("lecture") || normalized.contains("neso")
        ) {
            if (allowed.none { it.contains("study", ignoreCase = true) }) {
                allowed += "Study / lecture work"
            }
        }

        return HumanSummaries(
            allowed = allowed.distinct(),
            blocked = blocked.distinct(),
            conditional = conditional.distinct()
        )
    }

    private fun userNamedAppLabel(normalized: String, label: String): Boolean {
        val cue = label.lowercase()
        return when (cue) {
            "youtube" -> normalized.contains("youtube") || Regex("""\byt\b""").containsMatchIn(normalized)
            "newpipe" -> normalized.contains("newpipe")
            "chrome" -> normalized.contains("chrome")
            "netmirror" -> PromiseIntentRules.namesNetMirror(normalized)
            "this app" -> PromiseIntentRules.namesThisAppOnly(normalized)
            else -> normalized.contains(cue)
        }
    }

    private fun humanContentLine(rule: ContentRule, normalized: String = ""): String {
        val app = when {
            rule.appLabel != null && userNamedAppLabel(normalized, rule.appLabel!!) ->
                rule.appLabel!!
            rule.contentType.equals("short_form_video", ignoreCase = true) -> "Short-form video"
            rule.contentType.equals("long_form_video", ignoreCase = true) -> "Longer videos"
            rule.contentType.equals("adult_sexual", ignoreCase = true) -> "Adult content"
            rule.appLabel != null && normalized.isBlank() -> rule.appLabel!!
            else -> "Video"
        }
        return when {
            rule.operator != null && rule.value != null -> {
                val op = when (rule.operator) {
                    "gt" -> "longer than"
                    "gte" -> "at least"
                    "lt" -> "shorter than"
                    "lte" -> "up to"
                    else -> rule.operator
                }
                val action = when (rule.action) {
                    ContentRuleAction.ALLOW -> "allowed"
                    ContentRuleAction.BLOCK -> "blocked"
                    ContentRuleAction.WARN -> "warned"
                    ContentRuleAction.AI_DECIDE -> "checked live"
                }
                "$app videos $op ${rule.value} min → $action"
            }
            rule.surface == "messages" -> "$app messages"
            EffectivePromisePolicy.isShortFormSurfaceBlock(rule) ->
                EffectivePromisePolicy.shortFormSurfaceLabel(rule.packageName!!, rule.appLabel)
            rule.surface == "reels" -> "$app Reels"
            rule.surface == "feed" -> "$app feed"
            rule.surface == "playlist" -> "$app playlist / channel"
            rule.contentType == "adult_sexual" -> "Adult content"
            else -> "$app ${rule.surface}"
        }
    }

    private fun inferContentBrands(normalized: String): List<String> {
        if (PromiseIntentRules.namesYouTubeContentBrand(normalized)) {
            return listOf(EnforcementScopeLaw.BRAND_YOUTUBE)
        }
        return emptyList()
    }

    private fun inferScopeKind(normalized: String, contentBrands: List<String>): String? = when {
        PromiseIntentRules.namesYouTubeAppOnly(normalized) ->
            EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
        contentBrands.contains(EnforcementScopeLaw.BRAND_YOUTUBE) ->
            EnforcementScopeLaw.SCOPE_YOUTUBE_CONTENT
        else -> null
    }

    private fun inferScopePackages(
        normalized: String,
        contentRules: List<ContentRule>,
        suggestedAppRules: List<AppRule>,
        hintedPackage: String?
    ): List<String> {
        val youtubeAppOnly = PromiseIntentRules.namesYouTubeAppOnly(normalized)
        if (!PromiseIntentRules.namesNetMirror(normalized) &&
            !PromiseIntentRules.namesThisAppOnly(normalized) &&
            !youtubeAppOnly
        ) {
            return emptyList()
        }
        val named = linkedSetOf<String>()
        if (youtubeAppOnly) {
            named += PKG_YOUTUBE
        }
        if (PromiseIntentRules.namesNetMirror(normalized)) {
            named += PKG_NETMIRROR
        }
        if (PromiseIntentRules.namesThisAppOnly(normalized) &&
            hintedPackage != null &&
            VideoPlatformRegistry.isVideoOrStreamingPackage(hintedPackage)
        ) {
            named += hintedPackage
        }
        contentRules.mapNotNull { it.packageName?.trim()?.takeIf { pkg -> pkg.isNotEmpty() } }
            .forEach { named += it }
        suggestedAppRules
            .filter { userNamedAppLabel(normalized, it.label) }
            .forEach { named += it.packageName }
        return named.toList()
    }

    private fun parseSuggestedAppRules(
        normalized: String,
        contentRules: List<ContentRule>,
        hintedPackage: String? = null
    ): List<AppRule> {
        val suggestions = mutableListOf<AppRule>()

        if (normalized.contains("telegram")) {
            val behavior = if (explicitlyBlocksNamedApp(normalized, "telegram")) {
                AppRuleBehavior.BLOCK
            } else {
                AppRuleBehavior.WARN
            }
            suggestions += AppRule(PKG_TELEGRAM, "Telegram", behavior)
        }
        if (normalized.contains("whatsapp")) {
            val behavior = if (explicitlyBlocksNamedApp(normalized, "whatsapp")) {
                AppRuleBehavior.BLOCK
            } else {
                AppRuleBehavior.WARN
            }
            suggestions += AppRule(PKG_WHATSAPP, "WhatsApp", behavior)
        }
        // Gmail is a safe app by default; only an explicit block creates a rule.
        if (normalized.contains("gmail") && explicitlyBlocksNamedApp(normalized, "gmail")) {
            suggestions += AppRule(PKG_GMAIL, "Gmail", AppRuleBehavior.BLOCK)
        }

        if (PromiseIntentRules.namesYouTubeAppOnly(normalized)) {
            suggestions += AppRule(PKG_YOUTUBE, "YouTube", AppRuleBehavior.BLOCK)
            if (PromiseIntentRules.namesYouTubeMusicAllowed(normalized)) {
                suggestions += AppRule(PKG_YOUTUBE_MUSIC, "YouTube Music", AppRuleBehavior.ALLOW)
            }
        } else {
            val youtubeRules = contentRules.filter { it.packageName == PKG_YOUTUBE }
            if (normalized.contains("youtube") || normalized.contains("lecture") ||
                normalized.contains("neso") || youtubeRules.isNotEmpty()
            ) {
                // Length-threshold YouTube stays AI_DECIDE (content rules decide), not whole-app BLOCK.
                // A Shorts surface BLOCK is also content-level, never a whole-app BLOCK.
                val wholeAppBlock = youtubeRules.any {
                    it.action == ContentRuleAction.BLOCK && it.operator == null &&
                        !EffectivePromisePolicy.isShortFormSurfaceBlock(it)
                }
                val behavior = when {
                    wholeAppBlock -> AppRuleBehavior.BLOCK
                    youtubeRules.any { it.operator != null } -> AppRuleBehavior.AI_DECIDE
                    youtubeRules.any { it.action == ContentRuleAction.AI_DECIDE } ->
                        AppRuleBehavior.AI_DECIDE
                    else -> AppRuleBehavior.AI_DECIDE
                }
                suggestions += AppRule(PKG_YOUTUBE, "YouTube", behavior)
            }
        }

        if (normalized.contains("chrome") || normalized.contains("browser") ||
            (normalized.contains("anywhere") && normalized.contains("youtube"))
        ) {
            val chromeBehavior = if (allowsBroadChromeUse(normalized)) {
                AppRuleBehavior.ALLOW
            } else {
                AppRuleBehavior.AI_DECIDE
            }
            suggestions += AppRule(PKG_CHROME, "Chrome", chromeBehavior)
        }

        if (mentionsInstagram(normalized)) {
            val igRules = contentRules.filter { it.packageName == PKG_INSTAGRAM }
            val igBlocks = igRules.filter { it.action == ContentRuleAction.BLOCK }
            val behavior = when {
                igRules.any { it.surface == "messages" && it.action == ContentRuleAction.ALLOW } ->
                    AppRuleBehavior.AI_DECIDE
                // A Reels surface BLOCK is content-level, never a whole-app BLOCK (same as YouTube Shorts).
                igBlocks.isNotEmpty() && igBlocks.all(EffectivePromisePolicy::isShortFormSurfaceBlock) ->
                    AppRuleBehavior.AI_DECIDE
                igRules.any { it.action == ContentRuleAction.BLOCK } &&
                    igRules.none { it.action == ContentRuleAction.ALLOW } ->
                    AppRuleBehavior.BLOCK
                else -> AppRuleBehavior.BLOCK
            }
            suggestions += AppRule(PKG_INSTAGRAM, "Instagram", behavior)
        }

        val netmirrorNamed = PromiseIntentRules.namesNetMirror(normalized) ||
            (
                PromiseIntentRules.namesThisAppOnly(normalized) &&
                    hintedPackage == PKG_NETMIRROR
                )
        if (netmirrorNamed) {
            val behavior = if (PromiseIntentRules.blocksEntertainmentOrMovies(normalized)) {
                AppRuleBehavior.BLOCK
            } else {
                AppRuleBehavior.AI_DECIDE
            }
            suggestions += AppRule(PKG_NETMIRROR, "NetMirror", behavior)
        } else if (
            PromiseIntentRules.namesThisAppOnly(normalized) &&
            hintedPackage != null &&
            VideoPlatformRegistry.isVideoOrStreamingPackage(hintedPackage) &&
            suggestions.none { it.packageName == hintedPackage }
        ) {
            val label = VideoPlatformRegistry.entry(hintedPackage)?.label ?: "this app"
            val behavior = if (PromiseIntentRules.blocksEntertainmentOrMovies(normalized)) {
                AppRuleBehavior.BLOCK
            } else {
                AppRuleBehavior.AI_DECIDE
            }
            suggestions += AppRule(hintedPackage, label, behavior)
        }

        return suggestions.distinctBy { it.packageName }
    }

    /** "block youtube shorts", "no yt shorts", "youtube shorts are blocked" — not a quota. */
    private fun blocksYouTubeShortsOnly(normalized: String): Boolean =
        YOUTUBE_SHORTS_BLOCK.containsMatchIn(normalized) &&
            ShortFormLanguage.parseLimit(normalized) == null &&
            !PromiseIntentRules.allowsShortForm(normalized)

    /**
     * Typed WHERE for a promise whose only restriction is YouTube Shorts; anything broader
     * keeps the legacy scope path.
     */
    private fun youtubeShortsOnlyScope(
        normalized: String,
        contentRules: List<ContentRule>,
        suggestedAppRules: List<AppRule>,
        lengthHits: List<ContentLengthHit>
    ): EnforcementScope? {
        val onlyYouTubeShorts = contentRules.isNotEmpty() && contentRules.all {
            EffectivePromisePolicy.isShortFormSurfaceBlock(it) && it.packageName == PKG_YOUTUBE
        }
        if (!onlyYouTubeShorts || lengthHits.isNotEmpty()) return null
        if (suggestedAppRules.any { it.behavior == AppRuleBehavior.BLOCK }) return null
        val evidence = YOUTUBE_SHORTS_SPAN.find(normalized)?.value ?: return null
        return ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.APP_SHORT_FORM_ONLY,
                identity = ScopeIdentity.YOUTUBE,
                include = ScopeKindPresets.expand(
                    ScopeKind.APP_SHORT_FORM_ONLY,
                    ScopeIdentity.YOUTUBE,
                    packages = listOf(PKG_YOUTUBE)
                ).include,
                confidence = 0.9,
                evidenceSpans = listOf(evidence)
            )
        ).takeIf { it.isEnforceable }
    }

    private fun mentionsInstagram(normalized: String): Boolean =
        normalized.contains("instagram") || normalized.contains("insta") ||
            normalized.contains("reels")

    /**
     * Cue must be bound to the alias — see PromiseIntentRules.explicitlyBlocksAlias
     * for the rationale (docs/scope-semantics-v1-phase2-review.md §2 F2). Kept in
     * lockstep with that implementation; do not diverge without updating both.
     */
    private fun explicitlyBlocksNamedApp(normalized: String, appName: String): Boolean {
        val a = Regex.escape(appName) + PromiseIntentRules.SURFACE_QUALIFIER_GUARD

        val preCue = Regex(
            """(?<![a-z])(?:""" +
                """no|block|blocks|blocking|ban|bans|banning|forbid|forbids|""" +
                """stop|stops|disable|disables|disabling|blacklist|""" +
                """don't\s+use|dont\s+use|do\s+not\s+use|""" +
                """can't\s+use|cannot\s+use|no\s+using|""" +
                """(?:do\s+not|don't|dont|never)\s+allow""" +
                """(?:\s+(?:me\s+)?(?:to\s+)?(?:use|open|watch|access))?|""" +
                """not\s+allow""" +
                """(?:\s+(?:me\s+)?(?:to\s+)?(?:use|open|watch|access))?""" +
                """)\s+(?:the\s+)?""" + a + """\b""",
            RegexOption.IGNORE_CASE
        )
        if (preCue.containsMatchIn(normalized)) return true

        val postCue = Regex(
            """\b""" + a + """\b\s+(?:is\s+|are\s+|will\s+be\s+|gets?\s+|stays?\s+|remains?\s+|)""" +
                """(?:blocked|banned|forbidden|disabled|blacklisted|off|not\s+allowed|closed|shut)\b""",
            RegexOption.IGNORE_CASE
        )
        if (postCue.containsMatchIn(normalized)) return true

        val exceptRe = Regex("""\bexcept\s+(?:the\s+)?""" + a + """\b""", RegexOption.IGNORE_CASE)
        val exceptMatch = exceptRe.find(normalized) ?: return false
        val before = normalized.substring(0, exceptMatch.range.first).lowercase()
        val lastAllow = listOf("allow", "everything", "permit").maxOf { before.lastIndexOf(it) }
        val lastBlock = listOf(
            " no ", " block", " don't", " dont", " do not", " not allow",
            " ban", " forbid", " stop", " disable"
        ).maxOf { before.lastIndexOf(it) }
        return lastAllow >= 0 && lastAllow > lastBlock
    }

    private fun allowsBroadChromeUse(normalized: String): Boolean =
        normalized.contains("allow everything on chrome") ||
            normalized.contains("chrome allow everything") ||
            normalized.contains("anything on chrome") ||
            normalized.contains("everything in chrome")

    private fun findKeywords(normalizedText: String, keywords: List<String>): List<String> =
        keywords.filter { keyword -> normalizedText.contains(keyword) }

    private fun parseStrictness(normalizedText: String): StrictnessLevel = when {
        normalizedText.contains("monk mode") ||
            normalizedText.contains("hard mode") ||
            normalizedText.contains("fully restricted") ||
            normalizedText.contains("locked") ||
            normalizedText.contains("no escape") -> StrictnessLevel.LOCKED
        normalizedText.contains("strict mode") ||
            normalizedText.contains("no entertainment") ||
            normalizedText.contains("strict") ||
            normalizedText.contains("no distraction") -> StrictnessLevel.STRICT
        normalizedText.contains("soft") || normalizedText.contains("gentle") ->
            StrictnessLevel.SOFT
        normalizedText.contains("smart") -> StrictnessLevel.SMART
        else -> DEFAULT_STRICTNESS
    }

    private data class ParsedLockAttempt(val threshold: Int?, val invalid: Boolean)

    private fun parseLockAttemptThreshold(normalizedText: String): ParsedLockAttempt {
        val lockSentences = normalizedText.split(Regex("[.!?;\\n]+"))
            .filter { Regex("\\b(lock|freeze)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val attemptCount = Regex(
            """\b(\d{1,3})\s+(?:blocked\s+)?(?:attempts?|tries|times)\b""",
            RegexOption.IGNORE_CASE
        )
        val values = lockSentences.flatMap { sentence ->
            attemptCount.findAll(sentence).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        }.distinct()
        val threshold = values.singleOrNull()
        val invalid = values.isNotEmpty() && (values.size > 1 || threshold == null || threshold !in 1..100)
        return ParsedLockAttempt(threshold?.takeIf { it in 1..100 }, invalid)
    }

    companion object {
        internal const val DEFAULT_DURATION_MINUTES = 30
        private const val MINUTES_PER_DAY = 24 * 60
        private const val MINUTES_PER_YEAR = 365 * MINUTES_PER_DAY

        private const val PKG_YOUTUBE = "com.google.android.youtube"
        private const val PKG_YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        private const val PKG_CHROME = "com.android.chrome"
        private const val PKG_INSTAGRAM = "com.instagram.android"
        private const val PKG_TELEGRAM = "org.telegram.messenger"
        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_GMAIL = "com.google.android.gm"
        private const val PKG_NETMIRROR = VideoPlatformRegistry.NETMIRROR
        private const val APP_BLOCK_WINDOW_CHARS = 80

        private val APP_BLOCK_CUE = Regex(
            """\b(no|block|ban|forbid|stop|disable|except)\b|""" +
                """do not allow|dont allow|don't allow|not allow|""" +
                """not use|dont use|don't use|cannot use|can't use""",
            RegexOption.IGNORE_CASE
        )

        private val ALLOW_KEYWORDS = listOf(
            "study", "lecture", "docs", "telegram", "whatsapp", "coding", "code",
            "github", "stackoverflow", "compiler", "architecture", "course", "class",
            "assignment", "project", "exam", "neso", "message", "messages", "dm"
        )

        private val BLOCK_KEYWORDS = listOf(
            "reels", "reel", "shorts", "short", "porn", "adult", "movie", "netflix",
            "prime", "hotstar", "games", "game", "shopping", "amazon", "flipkart",
            "memes", "meme", "news", "entertainment", "feed"
        )

        private val CONTENT_COMPARISON_PATTERN = Regex(
            """(longer|greater|more|shorter|less)\s+than\s+(\d+)\s*(minutes?|mins?|hours?|hrs?)""",
            RegexOption.IGNORE_CASE
        )

        private val LENGTH_IS_PATTERN = Regex(
            """(?:whose\s+)?length\s+is\s+(?:greater|more|longer)\s+than\s+(\d+)\s*(minutes?|mins?|hours?|hrs?)""",
            RegexOption.IGNORE_CASE
        )

        private val CONTENT_GT_SYMBOL_PATTERN = Regex(
            """>\s*(\d+)\s*(minutes?|mins?|hours?|hrs?)""",
            RegexOption.IGNORE_CASE
        )

        private val CONTENT_COMPARE_CUES = Regex(
            """(?:longer|greater|more|shorter|less)\s+than|length\s+is|>\s*$""",
            RegexOption.IGNORE_CASE
        )

        private val ONLY_ALLOW_CUE = Regex(
            """\bonly\s+(?:allow\s+)?""",
            RegexOption.IGNORE_CASE
        )

        private val SHORTER_BLOCK_CUE = Regex(
            """\bshorter\s+videos?\s+block\b""" +
                """|\bblock\s+shorter(?:\s+videos?|\s+ones?)?\b""" +
                """|\bshorter(?:\s+ones?|\s+videos?)?\s+(?:are\s+)?blocked\b""" +
                """|\bnothing\s+shorter\b""" +
                """|\bno\s+shorter\b""",
            RegexOption.IGNORE_CASE
        )

        private val AMOUNT_TOKEN =
            """(\d+|an|a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"""

        private val WORD_AMOUNTS = mapOf(
            "a" to 1,
            "an" to 1,
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
            "ten" to 10,
            "eleven" to 11,
            "twelve" to 12
        )

        private val SESSION_FOR_PATTERN = Regex(
            """\bfor\s+(?:the\s+)?(?:next\s+)?$AMOUNT_TOKEN\s*(minutes?|mins?|hours?|hrs?|days?|years?)\b""",
            RegexOption.IGNORE_CASE
        )

        private val SESSION_NEXT_PATTERN = Regex(
            """\bnext\s+$AMOUNT_TOKEN\s*(minutes?|mins?|hours?|hrs?|days?|years?)\b""",
            RegexOption.IGNORE_CASE
        )

        private val SESSION_DAY_YEAR_PATTERN = Regex(
            """\b(\d+)\s*(days?|years?)\b""",
            RegexOption.IGNORE_CASE
        )

        // "1 hrs", "1hr", "one hours", "an hour" — messy speech/typos must still be session time.
        private val HOUR_PATTERN = Regex(
            """\b$AMOUNT_TOKEN\s*(?:hours?|hrs?)\b""",
            RegexOption.IGNORE_CASE
        )
        private val MINUTE_PATTERN =
            Regex("""\b(\d+)\s*(?:minutes?|mins?)\b""", RegexOption.IGNORE_CASE)

        private val YOUTUBE_SHORTS_SPAN = Regex("""\b(?:youtube|yt)\s+shorts?\b""", RegexOption.IGNORE_CASE)

        private val YOUTUBE_SHORTS_BLOCK = Regex(
            """\b(?:no|block|blocks|blocking|ban|stop|avoid|skip|without|""" +
                """(?:don'?t|do\s+not|never)\s+(?:watch|open|use|allow))\s+""" +
                """(?:any\s+|the\s+)?(?:youtube|yt)\s+shorts?\b""" +
                """|\b(?:youtube|yt)\s+shorts?\s+(?:is\s+|are\s+)?(?:blocked|banned|off|not\s+allowed)\b""",
            RegexOption.IGNORE_CASE
        )

        private val INSTAGRAM_MESSAGES_ONLY = Regex(
            """(?:instagram|insta).{0,40}(?:only|just).{0,20}(?:message|messages|dm|dms|chat|chats|repl)""",
            RegexOption.IGNORE_CASE
        )

        private val INSTA_LESS = Regex(
            """(?:use\s+)?(?:instagram|insta)\s+less|less\s+(?:instagram|insta)""",
            RegexOption.IGNORE_CASE
        )

        private val STRICT_VAGUE = Regex(
            """\b(?:be\s+)?strict(?:\s+mode)?\s+today\b|\btoday\b.{0,12}\bstrict\b|\bstrict\b.{0,12}\btoday\b""",
            RegexOption.IGNORE_CASE
        )

        private val MAKE_STRICT = Regex(
            """\bmake\s+me\s+strict\b|\bstrict\s+today\b|\bmake\s+me\s+strict\s+aaj\b""",
            RegexOption.IGNORE_CASE
        )

        private val FOCUS_VAGUE = Regex(
            """\bfocus\s+mode\b|\bstart\s+focus\b|\bfocus\s+please\b|\bfocus\s+mode\s+chahiye\b""",
            RegexOption.IGNORE_CASE
        )

        private val DEFAULT_STRICTNESS = StrictnessLevel.STRICT
    }
}
