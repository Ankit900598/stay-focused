package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.ScopeIdentity
import com.phonecodex.app.domain.scope.ScopeKind

/**
 * What Start will actually save for a draft, and whether its WHERE (typed scope) and
 * WHAT (app / content rules) agree. Shared by the binder, the confirmation card and the
 * Start gate so the card can never describe a different policy than the one enforced.
 * Mirrors backend `splitScopedAppBlocks` / `applyScopedAppRuleGates`.
 */
object EffectivePromisePolicy {

    enum class AppScope { WHOLE_APP, SHORT_FORM, UNKNOWN }

    private val WHOLE_APP_SCOPES = setOf(
        "", "all", "app", "whole", "whole_app", "whole app", "entire", "entire_app",
        "entire app", "full", "full_app", "any", "everything"
    )
    private val SHORT_FORM_SCOPES = setOf(
        "shorts", "short", "reels", "reel", "short_form", "short-form", "short form",
        "short_form_video", "short-form video", "short videos", "short video",
        "short_videos", "short_video", "spotlight"
    )

    /** Apps that also carry non-short-form surfaces (home, long video, DMs, browsing). */
    private val MIXED_SURFACE_PACKAGES = setOf(
        VideoPlatformRegistry.YOUTUBE,
        VideoPlatformRegistry.INSTAGRAM,
        "com.facebook.katana",
        "com.snapchat.android",
        VideoPlatformRegistry.CHROME,
        VideoPlatformRegistry.NEWPIPE
    )

    private val NARROW_SURFACE_SCOPE_KINDS = setOf(
        ScopeKind.APP_SHORT_FORM_ONLY,
        ScopeKind.SHORT_FORM_ANY_HOST,
        ScopeKind.SOCIAL_FEED_AND_REELS_ONLY
    )

    // Free-text scopes ("Shorts player only; not the whole app") are short-form only when they
    // name a short-form surface and nothing else: any whole-app claim, exclusion ("except",
    // "but") or other surface keeps them UNKNOWN so Start stays closed.
    private val SHORT_FORM_SCOPE_WORD =
        Regex("""\b(shorts?|reels?|short[\s_-]*(?:form|videos?|clips?)|spotlight)\b""")
    private val NEGATED_WHOLE_APP_SCOPE =
        Regex("""\b(?:not|no|never|without|rather than|instead of)\s+(?:the\s+|a\s+)?(?:whole|entire|full|all of)(?:\s+the)?\s+app\b""")
    private val WHOLE_APP_SCOPE_CLAIM = Regex("""\b(whole|entire|full|all|everything|every)\b""")
    private val EXCLUSION_SCOPE_WORD =
        Regex("""\b(except|but|besides|other than|apart from|excluding|outside)\b""")
    private val OTHER_SURFACE_SCOPE_WORD =
        Regex("""\b(feed|home|comments?|messages?|dms?|chats?|stor(?:y|ies)|search|live|long|explore|brows(?:e|ing)|music|subscriptions?|posts?|calls?)\b""")
    // Free-text whole-app scopes ("Entire native app only"): a whole-app claim on the app itself
    // and nothing that narrows, negates, or names another surface.
    private val WHOLE_APP_FREE_TEXT = Regex("""\b(?:whole|entire|full|all of)\b(?:\s+[\w-]+){0,3}?\s+app\b""")
    private val NEGATION_SCOPE_WORD = Regex("""\b(not|no|never|without|rather than|instead of)\b""")

    fun classifyAppScope(raw: String?): AppScope {
        val s = raw.orEmpty().trim().lowercase().replace(Regex("""\s+"""), " ")
        if (s in WHOLE_APP_SCOPES) return AppScope.WHOLE_APP
        if (s in SHORT_FORM_SCOPES) return AppScope.SHORT_FORM
        if (!SHORT_FORM_SCOPE_WORD.containsMatchIn(s)) {
            return if (WHOLE_APP_FREE_TEXT.containsMatchIn(s) &&
                !NEGATION_SCOPE_WORD.containsMatchIn(s) &&
                !EXCLUSION_SCOPE_WORD.containsMatchIn(s) &&
                !OTHER_SURFACE_SCOPE_WORD.containsMatchIn(s)
            ) {
                AppScope.WHOLE_APP
            } else {
                AppScope.UNKNOWN
            }
        }
        val rest = NEGATED_WHOLE_APP_SCOPE.replace(s, " ")
        return if (WHOLE_APP_SCOPE_CLAIM.containsMatchIn(rest) ||
            EXCLUSION_SCOPE_WORD.containsMatchIn(rest) ||
            OTHER_SURFACE_SCOPE_WORD.containsMatchIn(rest)
        ) {
            AppScope.UNKNOWN
        } else {
            AppScope.SHORT_FORM
        }
    }

    fun shortFormSurfaceRule(packageName: String, label: String): ContentRule = ContentRule(
        appLabel = label,
        packageName = packageName,
        surface = "shorts",
        contentType = "short_form_video",
        operator = null,
        value = null,
        unit = null,
        action = ContentRuleAction.BLOCK
    )

    fun isShortFormSurfaceBlock(rule: ContentRule): Boolean =
        rule.action == ContentRuleAction.BLOCK &&
            rule.contentType.equals("short_form_video", ignoreCase = true) &&
            rule.operator == null &&
            rule.value == null &&
            !rule.packageName.isNullOrBlank()

    fun shortFormSurfaceLabel(packageName: String, label: String?): String = when (packageName) {
        VideoPlatformRegistry.YOUTUBE -> "YouTube Shorts"
        VideoPlatformRegistry.INSTAGRAM -> "Instagram Reels"
        "com.facebook.katana" -> "Facebook Reels"
        else -> "${label ?: packageName} short videos"
    }

    fun wholeAppBlockPackages(draft: FocusPromise): Set<String> =
        draft.suggestedAppRules.filter { it.behavior == AppRuleBehavior.BLOCK }
            .map { it.packageName }
            .toSet()

    /** Short-form surface BLOCKs on apps that are not already blocked whole. */
    fun narrowShortFormBlocks(draft: FocusPromise): List<ContentRule> {
        val whole = wholeAppBlockPackages(draft)
        return draft.contentRules
            .filter(::isShortFormSurfaceBlock)
            .filter { it.packageName !in whole }
            .distinctBy { it.packageName }
    }

    /**
     * Persisted surface BLOCKs. A daily short-form quota is a count rule, not a ban,
     * so it never persists a standing short-form BLOCK.
     */
    fun blockedSurfaces(draft: FocusPromise): List<String> {
        if (PromiseContentRuleSupport.shortFormDailyQuotaLimit(draft) != null) return emptyList()
        return draft.contentRules
            .filter(::isShortFormSurfaceBlock)
            .map { BlockedSurfaceCodec.entry(it.packageName!!, BlockedSurfaceCodec.SHORT_FORM) }
            .distinct()
    }

    fun savedWholeAppRuleConflict(draft: FocusPromise, existingAppRules: List<AppRule>): String? {
        val scope = draft.enforcementScope?.takeIf {
            draft.scopeContractV1 && it.isEnforceable && it.kind == ScopeKind.APP_SHORT_FORM_ONLY
        } ?: return null
        val targetedPackages = scope.include.flatMap { it.packages }.toSet()
        if (targetedPackages.isEmpty()) return null

        val suggestedPackages = draft.suggestedAppRules.map { it.packageName }.toSet()
        val savedWholeAppBlockRemains = existingAppRules.any { rule ->
            rule.packageName in targetedPackages &&
                rule.behavior == AppRuleBehavior.BLOCK &&
                rule.packageName !in suggestedPackages
        }
        return if (savedWholeAppBlockRemains) SAVED_WHOLE_APP_BLOCK_CONFLICT else null
    }

    /** Non-null when scope and actions contradict or an app scope cannot be enforced. */
    fun conflictReason(draft: FocusPromise): String? {
        if (draft.unrepresentableAppScopes.isNotEmpty()) return UNREPRESENTABLE_APP_SCOPE
        if (hasUnsupportedCollectionTarget(draft)) {
            return UNSUPPORTED_COLLECTION_TARGET
        }
        if (draft.contentRules.any { it.contentType.equals("study", ignoreCase = true) }) {
            return UNSUPPORTED_STUDY_CONTENT
        }
        val scope = draft.enforcementScope?.takeIf { draft.scopeContractV1 && it.isEnforceable }
        // No structured feed block exists, so the card would promise more than enforcement.
        if (scope?.kind == ScopeKind.SOCIAL_FEED_AND_REELS_ONLY) return FEED_SURFACE_UNSUPPORTED
        if (scope?.kind == ScopeKind.SOCIAL_MESSAGING_ONLY &&
            (scope.identity == ScopeIdentity.SOCIAL_ANY ||
                scope.include.any { clause -> clause.packages.any(SafeAppsCatalog::isDefaultPackage) })
        ) {
            return SOCIAL_SAFE_APP_SURFACE_UNSUPPORTED
        }
        if (scope != null && scopeMissesRestrictions(draft, scope)) return APP_SCOPE_MISSES_RULES
        val whole = wholeAppBlockPackages(draft)
        if (whole.isEmpty()) return null
        if (scope != null && scope.kind in NARROW_SURFACE_SCOPE_KINDS &&
            whole.any { it in MIXED_SURFACE_PACKAGES }
        ) {
            return WHOLE_APP_BLOCK_VS_NARROW_SCOPE
        }
        if (draft.contentRules.any { it.action == ContentRuleAction.ALLOW && it.packageName in whole }) {
            return WHOLE_APP_BLOCK_VS_CONTENT_ALLOW
        }
        return null
    }

    private fun isRestriction(rule: ContentRule): Boolean =
        rule.action == ContentRuleAction.BLOCK ||
            rule.operator != null ||
            rule.contentType.equals("usage_quota", ignoreCase = true)

    /**
     * Anything outside the typed scope is OutOfScope and never reaches the content gates, so
     * a single-app scope cannot carry a restriction for another app, a global rule, or (for
     * the short-form player only) a non-short-form rule in the same app. Short-form count
     * rules stay in scope: the daily quota gate runs on the short-form player.
     */
    private fun scopeMissesRestrictions(draft: FocusPromise, scope: EnforcementScope): Boolean {
        if (scope.kind != ScopeKind.APP_SHORT_FORM_ONLY && scope.kind != ScopeKind.APP_ONLY) return false
        val packages = scope.include.flatMap { it.packages }.toSet()
        if (packages.isEmpty()) return false
        return draft.contentRules.filter(::isRestriction).any { rule ->
            when {
                rule.packageName !in packages -> true
                scope.kind == ScopeKind.APP_ONLY -> false
                else -> !isShortFormPlayerRule(rule)
            }
        }
    }

    private fun isShortFormPlayerRule(rule: ContentRule): Boolean =
        rule.contentType.equals("short_form_video", ignoreCase = true) &&
            (rule.operator == null || rule.unit.equals("count", ignoreCase = true))

    fun conflictQuestion(draft: FocusPromise): String {
        if (conflictReason(draft) == UNSUPPORTED_COLLECTION_TARGET) {
            return "I can't reliably identify and enforce a specific playlist, channel, creator, or series yet. " +
                "I won't start this promise because that target isn't supported on this phone."
        }
        if (conflictReason(draft) == UNSUPPORTED_STUDY_CONTENT) {
            return "I can't reliably tell which videos count as study or restrict a specific playlist/channel yet. " +
                "I won't start this study-only rule. Edit the promise to choose a rule Strlix can enforce."
        }
        if (conflictReason(draft) == SOCIAL_SAFE_APP_SURFACE_UNSUPPORTED) {
            return "I can't reliably restrict these social screens while keeping messages open yet. Edit the promise or choose a whole-app rule."
        }
        val label = draft.unrepresentableAppScopes.firstOrNull()
            ?: draft.suggestedAppRules.firstOrNull { it.behavior == AppRuleBehavior.BLOCK }?.label
            ?: "this app"
        return "Should I block all of $label, or only part of it (like its short videos)?"
    }

    private fun hasUnsupportedCollectionTarget(draft: FocusPromise): Boolean {
        if (draft.contentRules.any { rule ->
                rule.surface.trim().lowercase() in UNSUPPORTED_COLLECTION_SURFACES
            }
        ) {
            return true
        }
        if (!SPECIFIC_COLLECTION_INTENT.containsMatchIn(draft.rawText)) return false
        val hasRelevantStructuredTarget = draft.contentRules.any { rule ->
            rule.surface.equals("playlist", ignoreCase = true) ||
                rule.contentType.equals("study", ignoreCase = true)
        }
        val hasBroadContentScope = draft.enforcementScope?.kind in setOf(
            ScopeKind.CONTENT_ANY_HOST,
            ScopeKind.VIDEO_CLASS_ANY_HOST
        )
        val hasVideoAppRule = draft.suggestedAppRules.any {
            VideoPlatformRegistry.isVideoOrStreamingPackage(it.packageName)
        }
        return hasRelevantStructuredTarget || hasBroadContentScope || hasVideoAppRule
    }

    private val UNSUPPORTED_COLLECTION_SURFACES = setOf("playlist", "channel", "creator", "series")

    private val SPECIFIC_COLLECTION_INTENT = Regex(
        """\b(?:only|just|specific|particular|from|except|excluding|stay on|keep me on|follow)\b""" +
            """(?:(?!\b(?:not|no|never|without|instead of)\b)[\s\S]){0,80}\b(?:playlist|channel|creator|series)\b""",
        RegexOption.IGNORE_CASE
    )

    const val UNREPRESENTABLE_APP_SCOPE = "unrepresentable_app_scope"
    const val WHOLE_APP_BLOCK_VS_NARROW_SCOPE = "whole_app_block_vs_narrow_scope"
    const val WHOLE_APP_BLOCK_VS_CONTENT_ALLOW = "whole_app_block_vs_content_allow"
    const val FEED_SURFACE_UNSUPPORTED = "feed_surface_unsupported"
    const val SOCIAL_SAFE_APP_SURFACE_UNSUPPORTED = "social_safe_app_surface_unsupported"
    const val UNSUPPORTED_STUDY_CONTENT = "unsupported_study_content_category"
    const val APP_SCOPE_MISSES_RULES = "app_surface_choice_misses_rules"
    const val UNSUPPORTED_COLLECTION_TARGET = "unsupported_media_collection_target"
    const val SAVED_WHOLE_APP_BLOCK_CONFLICT =
        "A saved whole-app block conflicts with this short-videos-only promise. Change that app rule to Auto-decide, then tap Understand again, or edit the promise."
}
