package com.phonecodex.app.ui.home

import com.phonecodex.app.domain.diagnostics.BackendHealthProbe

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ClarificationOption
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.promise.CompiledPromiseMapper
import com.phonecodex.app.domain.promise.CompiledPromiseResponse
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.FocusPromiseParser
import com.phonecodex.app.domain.promise.PromiseClarificationLaw
import com.phonecodex.app.domain.promise.PromiseCompilerException
import com.phonecodex.app.domain.promise.PromiseContentRuleSupport
import com.phonecodex.app.domain.promise.PromiseUnderstandingResolver
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.ScopeCanonicalizer
import com.phonecodex.app.domain.scope.ScopeIdentity
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeKindPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseUnderstandingTest {

    private val parser = FocusPromiseParser()

    @Test
    fun unsafeSocialMessageException_hidesStartEvenWhenServerClaimsReady() {
        val expansion = ScopeKindPresets.expand(ScopeKind.SOCIAL_MESSAGING_ONLY, ScopeIdentity.WHATSAPP)
        val scope = ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.SOCIAL_MESSAGING_ONLY,
                identity = ScopeIdentity.WHATSAPP,
                include = expansion.include,
                exempt = expansion.exempt,
                confidence = 0.9,
                evidenceSpans = listOf("WhatsApp except messages")
            )
        )
        val draft = FocusPromise(
            rawText = "block WhatsApp except messages for one hour",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            canStartCommitment = true,
            scopeContractV1 = true,
            enforcementScope = scope
        )

        assertTrue(draft.needsClarification(strongerConfirmAcknowledged = true))
        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.PROMISE_COMPILER)
        assertFalse(ui.canStart)
        assertTrue(ui.clarificationQuestion.orEmpty().contains("can't reliably"))
    }

    @Test
    fun unsupportedNamedPlaylistCannotStartEvenWhenCompilerClaimsReady() {
        val parsed = parser.parse("Only Neso Academy playlist for 3 hours")
        val backendRules = parsed.contentRules.map { rule ->
            if (rule.contentType.equals("study", ignoreCase = true)) {
                rule.copy(surface = "study")
            } else {
                rule
            }
        }
        val draft = PromiseClarificationLaw.apply(
            parsed.copy(contentRules = backendRules, canStartCommitment = true)
        )

        assertFalse(draft.canStartCommitment == true)
        assertTrue(draft.needsClarification)
        assertTrue(draft.clarificationQuestion.orEmpty().contains("can't reliably"))

        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.PROMISE_COMPILER)
        assertFalse(ui.canStart)
        assertTrue(ui.clarificationQuestion.orEmpty().contains("playlist", ignoreCase = true))
    }

    @Test
    fun namedPlaylistPromiseWithoutStructuredTargetCannotStart() {
        val identity = ScopeIdentity.YOUTUBE
        val expansion = ScopeKindPresets.expand(ScopeKind.CONTENT_ANY_HOST, identity)
        val scope = ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.CONTENT_ANY_HOST,
                identity = identity,
                include = expansion.include,
                confidence = 0.9,
                evidenceSpans = listOf("Only Neso Academy playlist")
            )
        )
        val draft = FocusPromise(
            rawText = "Only Neso Academy playlist on YouTube for 3 hours",
            sessionDurationMinutes = 180,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = listOf(
                AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.AI_DECIDE)
            ),
            allowedSummaries = listOf("Neso Academy playlist only"),
            canStartCommitment = true,
            scopeContractV1 = true,
            enforcementScope = scope
        )

        listOf("playlist", "channel", "creator", "series").forEach { surface ->
            val ui = buildPromiseUnderstanding(
                draft.copy(contentRules = draft.contentRules.map { it.copy(surface = surface) }),
                source = UnderstandingSource.PROMISE_COMPILER
            )

            assertFalse("$surface surface must not silently start", ui.canStart)
            assertTrue(
                ui.clarificationQuestion.orEmpty(),
                ui.clarificationQuestion.orEmpty().contains("playlist", ignoreCase = true)
            )
        }
    }

    @Test
    fun structuredPlaylistSurfaceCannotStartWhenPromiseUsesHinglish() {
        val identity = ScopeIdentity.YOUTUBE
        val expansion = ScopeKindPresets.expand(ScopeKind.CONTENT_ANY_HOST, identity)
        val scope = ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.CONTENT_ANY_HOST,
                identity = identity,
                include = expansion.include,
                confidence = 0.9,
                evidenceSpans = listOf("Neso Academy se padhai")
            )
        )
        val draft = FocusPromise(
            rawText = "Kal exam hai, 3 hours sirf Neso Academy se padhai",
            sessionDurationMinutes = 180,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = listOf(
                AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.AI_DECIDE)
            ),
            contentRules = listOf(
                ContentRule(
                    appLabel = "YouTube",
                    packageName = "com.google.android.youtube",
                    surface = "playlist",
                    contentType = "study",
                    operator = null,
                    value = null,
                    unit = null,
                    action = ContentRuleAction.AI_DECIDE
                )
            ),
            allowedSummaries = listOf("Neso Academy study only"),
            canStartCommitment = true,
            scopeContractV1 = true,
            enforcementScope = scope
        )

        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.PROMISE_COMPILER)

        assertFalse(ui.canStart)
        assertTrue(ui.clarificationQuestion.orEmpty().contains("playlist", ignoreCase = true))
    }

    @Test
    fun v09StudySurfaceForHinglishNamedSourceCannotStart() {
        val identity = ScopeIdentity.YOUTUBE
        val expansion = ScopeKindPresets.expand(ScopeKind.CONTENT_ANY_HOST, identity)
        val scope = ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.CONTENT_ANY_HOST,
                identity = identity,
                include = expansion.include,
                confidence = 0.9,
                evidenceSpans = listOf("Neso Academy se padhai")
            )
        )
        val draft = FocusPromise(
            rawText = "Kal exam hai, 3 hours sirf Neso Academy se padhai",
            sessionDurationMinutes = 180,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = listOf(
                AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.AI_DECIDE)
            ),
            contentRules = listOf(
                ContentRule(
                    appLabel = "YouTube",
                    packageName = "com.google.android.youtube",
                    surface = "study",
                    contentType = "study",
                    operator = null,
                    value = null,
                    unit = null,
                    action = ContentRuleAction.ALLOW
                )
            ),
            allowedSummaries = listOf("Neso Academy study only"),
            canStartCommitment = true,
            scopeContractV1 = true,
            enforcementScope = scope
        )

        val gated = PromiseClarificationLaw.apply(draft)

        assertFalse(gated.canStartCommitment == true)
        assertTrue(gated.clarificationRequired)
        assertTrue(gated.clarificationQuestion.orEmpty().contains("study", ignoreCase = true))

        val ui = buildPromiseUnderstanding(gated, source = UnderstandingSource.PROMISE_COMPILER)

        assertFalse(ui.canStart)
        assertTrue(ui.clarificationQuestion.orEmpty().contains("study", ignoreCase = true))
    }

    @Test
    fun shortsOnlyWithNegatedChannelDoesNotTriggerUnsupportedCollectionGate() {
        val identity = ScopeIdentity.YOUTUBE
        val expansion = ScopeKindPresets.expand(ScopeKind.APP_SHORT_FORM_ONLY, identity)
        val scope = ScopeCanonicalizer.canonicalize(
            EnforcementScope(
                version = EnforcementScope.CURRENT_VERSION,
                kind = ScopeKind.APP_SHORT_FORM_ONLY,
                identity = identity,
                include = expansion.include,
                confidence = 0.9,
                evidenceSpans = listOf("YouTube Shorts only")
            )
        )
        val draft = FocusPromise(
            rawText = "Only block YouTube Shorts, not the whole channel, for 1 hour",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = listOf(
                AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.AI_DECIDE)
            ),
            contentRules = listOf(
                ContentRule(
                    appLabel = "YouTube",
                    packageName = "com.google.android.youtube",
                    surface = "shorts",
                    contentType = "short_form_video",
                    operator = null,
                    value = null,
                    unit = null,
                    action = ContentRuleAction.BLOCK
                )
            ),
            canStartCommitment = true,
            scopeContractV1 = true,
            enforcementScope = scope
        )

        assertFalse(draft.appRuleScopeConflict)
    }

    @Test
    fun clarificationBlocksStart() {
        val draft = FocusPromise(
            rawText = "be strict today",
            sessionDurationMinutes = 1440,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            clarificationQuestion = "What apps or content should I restrict today?"
        )

        val ui = buildPromiseUnderstanding(draft)

        assertFalse(ui.canStart)
        assertEquals(draft.clarificationQuestion, ui.clarificationQuestion)
        assertEquals("1 day", ui.sessionTimeLabel)
        assertEquals("Needs one clarification", ui.statusLabel)
    }

    @Test
    fun contentAndSessionShownSeparately() {
        val draft = FocusPromise(
            rawText = "block YouTube videos longer than 20 min for next 1 hour",
            sessionDurationMinutes = 60,
            allowedKeywords = listOf("study"),
            blockedKeywords = listOf("shorts"),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = listOf(
                AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.BLOCK)
            ),
            contentRules = listOf(
                ContentRule(
                    appLabel = "YouTube",
                    packageName = "com.google.android.youtube",
                    surface = "video",
                    contentType = "long_form_video",
                    operator = "gt",
                    value = 20,
                    unit = "minutes",
                    action = ContentRuleAction.BLOCK
                )
            )
        )

        val ui = buildPromiseUnderstanding(
            draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )

        assertTrue(ui.canStart)
        assertEquals("1 hour", ui.sessionTimeLabel)
        assertTrue(ui.blockedLabel.contains("YouTube"))
        assertTrue(ui.blockedLabel.contains("20"))
    }

    @Test
    fun confirmationAlwaysShowsStructuredRulesEvenWhenCompilerSuppliesSummaries() {
        val draft = FocusPromise(
            rawText = "Block YouTube Shorts for the next hour",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = listOf(
                AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.AI_DECIDE)
            ),
            contentRules = listOf(
                ContentRule(
                    appLabel = "YouTube",
                    packageName = "com.google.android.youtube",
                    surface = "shorts",
                    contentType = "short_form_video",
                    operator = null,
                    value = null,
                    unit = null,
                    action = ContentRuleAction.BLOCK
                )
            ),
            allowedSummaries = listOf("Long lectures remain allowed"),
            blockedSummaries = listOf("YouTube restrictions apply"),
            conditionalSummaries = listOf("Live screen checks apply")
        )

        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.PROMISE_COMPILER)

        assertTrue(ui.blockedLabel, ui.blockedLabel.contains("short-form video"))
        assertTrue(ui.blockedLabel, ui.blockedLabel.contains("BLOCK"))
        assertTrue(ui.conditionalLabel.orEmpty(), ui.conditionalLabel.orEmpty().contains("YouTube judged live"))
    }

    @Test
    fun formatDuration_yearsAndDays() {
        assertEquals("7 days", formatDuration(7 * 24 * 60))
        assertEquals("1 year", formatDuration(365 * 24 * 60))
        assertEquals("1 hour", formatDuration(60))
        assertEquals("45 minutes", formatDuration(45))
    }

    @Test
    fun alwaysBlockedLabels_appearInSeparateAlwaysOnField_notMergedIntoBlocked() {
        // Reliability design 2026-09-25 §Fix 3: Life Rules must render as their own
        // section and never be folded into "I will block". Otherwise a promise like
        // "study for 30 minutes" with an adult guardrail enabled would falsely claim
        // the *promise itself* blocks adult content.
        val draft = FocusPromise(
            rawText = "study for 30 minutes",
            sessionDurationMinutes = 30,
            allowedKeywords = listOf("study"),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList()
        )

        val ui = buildPromiseUnderstanding(draft, alwaysBlockedLabels = listOf("adult content"))

        assertFalse(
            "Life Rule 'adult content' must NOT appear inside blockedLabel",
            ui.blockedLabel.contains("adult content")
        )
        assertEquals(listOf("adult content"), ui.alwaysOnLifeRules)
        assertNotNull(ui.allowedLabel)
    }

    @Test
    fun whatsappPromise_withAdultGuardrail_keepsBlockedAndLifeRulesSeparate() {
        // Repro of the reported bug: "do not allow WhatsApp for 10 minutes" was
        // showing "adult content" as if it were part of the WhatsApp promise.
        val draft = FocusPromise(
            rawText = "do not allow whatsapp for 10 minutes",
            sessionDurationMinutes = 10,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = listOf(
                AppRule("com.whatsapp", "WhatsApp", AppRuleBehavior.BLOCK)
            ),
            blockedSummaries = listOf("WhatsApp")
        )

        val ui = buildPromiseUnderstanding(
            draft,
            alwaysBlockedLabels = listOf("adult content")
        )

        assertTrue(
            "blockedLabel must name WhatsApp (${ui.blockedLabel})",
            ui.blockedLabel.contains("WhatsApp", ignoreCase = true)
        )
        assertFalse(
            "blockedLabel must NOT smuggle Life Rules in (${ui.blockedLabel})",
            ui.blockedLabel.contains("adult", ignoreCase = true)
        )
        assertEquals(listOf("adult content"), ui.alwaysOnLifeRules)
    }

    @Test
    fun prefersHumanSummaries_andSessionTimeLabel() {
        val draft = FocusPromise(
            rawText = "only allow YouTube videos longer than 40 min for next 2 hours",
            sessionDurationMinutes = 120,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            allowedSummaries = listOf("YouTube videos longer than 40 min → allowed"),
            blockedSummaries = listOf("YouTube videos up to 40 min → blocked"),
            conditionalSummaries = listOf("YouTube judged live"),
            clarificationQuestion = null
        )

        val ui = buildPromiseUnderstanding(
            draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )

        assertTrue(ui.canStart)
        assertEquals("2 hours", ui.sessionTimeLabel)
        assertTrue(ui.allowedLabel.contains("longer than 40"))
        assertTrue(ui.blockedLabel.contains("up to 40") || ui.blockedLabel.contains("40"))
        assertEquals("YouTube judged live", ui.conditionalLabel)
        assertEquals(UnderstandingSource.PROMISE_COMPILER, ui.source)
        assertEquals("AI preview ready", ui.statusLabel)
    }

    @Test
    fun azureSuccessNoClarification_canStartTrue() {
        val response = CompiledPromiseResponse(
            rawText = "for 1 hour do not allow to watch any video whose length is greater than 40 min",
            sessionDurationMinutes = 60,
            strictness = "STRICT",
            allowedSummaries = listOf("Videos up to 40 min → allowed"),
            blockedSummaries = listOf("Videos longer than 40 min → blocked"),
            conditionalSummaries = emptyList(),
            contentRules = emptyList(),
            clarificationQuestion = null,
            cautionMessages = emptyList(),
            source = "azure_promise_compiler",
            confidence = 0.9,
            suggestedAppRules = emptyList()
        )

        val resolved = PromiseUnderstandingResolver.resolve(
            text = response.rawText,
            compiledResult = Result.success(response),
            parser = parser
        )
        val ui = buildPromiseUnderstanding(
            draft = resolved.draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )

        assertTrue(resolved.fromCompiler)
        assertNull(resolved.draft.clarificationQuestion)
        assertTrue(ui.canStart)
        assertEquals("AI preview ready", ui.statusLabel)
        assertFalse(ui.statusDetail.contains("offline", ignoreCase = true))
    }

    @Test
    fun azureSuccessWithClarification_canStartFalse() {
        val response = CompiledPromiseResponse(
            rawText = "make me strict",
            sessionDurationMinutes = 60,
            strictness = "STRICT",
            allowedSummaries = emptyList(),
            blockedSummaries = emptyList(),
            conditionalSummaries = emptyList(),
            contentRules = emptyList(),
            clarificationQuestion = "Which apps should stay blocked during this session?",
            cautionMessages = emptyList(),
            source = "azure_promise_compiler",
            confidence = 0.4,
            suggestedAppRules = emptyList()
        )

        val resolved = PromiseUnderstandingResolver.resolve(
            text = response.rawText,
            compiledResult = Result.success(response),
            parser = parser
        )
        val ui = buildPromiseUnderstanding(
            draft = resolved.draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )

        assertTrue(resolved.fromCompiler)
        assertFalse(ui.canStart)
        assertEquals("Needs one clarification", ui.statusLabel)
        assertTrue(ui.statusDetail.contains("Which apps"))
    }

    @Test
    fun azureFailureFallback_showsClearOfflineWarning() {
        val resolved = PromiseUnderstandingResolver.resolve(
            text = "study youtube lectures for 2 hours",
            compiledResult = Result.failure(PromiseCompilerException("offline", 503)),
            parser = parser
        )
        val ui = buildPromiseUnderstanding(
            draft = resolved.draft,
            source = UnderstandingSource.LOCAL_PREVIEW
        )

        assertFalse(resolved.fromCompiler)
        assertTrue(
            resolved.draft.warnings.contains(CompiledPromiseMapper.LOCAL_FALLBACK_CAUTION)
        )
        assertTrue(ui.cautions.any { it.contains("Basic offline preview", ignoreCase = true) })
        assertEquals("Reconnect to verify this promise", ui.statusLabel)
        assertFalse(ui.canStart)
        assertTrue(ui.statusDetail.contains("Start is disabled while offline"))
        assertTrue(ui.statusDetail.contains(BackendHealthProbe.reachabilityHint(), ignoreCase = true))
    }

    @Test
    fun exactLengthPromise_localParserRequiresOnlineVerificationBeforeStart() {
        val text =
            "for 1 hour do not allow to watch any video whose length is greater than 40 min"
        val draft = parser.parse(text)
        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.LOCAL_PREVIEW)

        assertNull(draft.clarificationQuestion)
        assertFalse(draft.needsClarification)
        assertFalse(ui.canStart)
        assertEquals("Reconnect to verify this promise", ui.statusLabel)
        assertTrue(ui.statusDetail.contains("Start is disabled while offline"))
    }

    @Test
    fun clarificationOptionsBlockStartUntilSelected() {
        val draft = FocusPromise(
            rawText = "google video 30 min",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            clarificationQuestion = "What did you mean by Google video?",
            clarificationOptions = listOf(
                ClarificationOption(
                    id = "A",
                    label = "Chrome / browser videos",
                    description = "Only browser",
                    recommended = true
                ),
                ClarificationOption(
                    id = "B",
                    label = "All video apps",
                    description = "Every video app",
                    recommended = false
                )
            ),
            clarificationRequired = true,
            canStartCommitment = false
        )

        val blocked = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )
        assertFalse(blocked.canStart)
        assertEquals(2, blocked.clarificationOptions.size)

        val ready = buildPromiseUnderstanding(
            draft = draft.copy(
                clarificationRequired = false,
                canStartCommitment = true
            ),
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = "A"
        )
        assertTrue(ready.canStart)
        assertEquals("A", ready.selectedClarificationOptionId)
        // Applied pick: no leftover "Need clarification" question or A/B list.
        assertNull(ready.clarificationQuestion)
        assertTrue(ready.clarificationOptions.isEmpty())
        assertFalse(ready.statusDetail.contains("clarif", ignoreCase = true))
    }

    @Test
    fun strongerConfirmBlocksPermanentStart() {
        val draft = FocusPromise(
            rawText = "no porn for 1 year",
            sessionDurationMinutes = 365 * 24 * 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.LOCKED,
            suggestedAppRules = emptyList(),
            requiresStrongerConfirmation = true,
            isPermanentCommitment = true,
            canStartCommitment = false
        )

        val blocked = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )
        assertFalse(blocked.canStart)
        assertTrue(blocked.requiresStrongerConfirmation)

        val ready = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER,
            strongerConfirmAcknowledged = true
        )
        assertTrue(ready.canStart)
        assertEquals("AI preview ready", ready.statusLabel)
    }

    @Test
    fun userFacingConfirmationPreferredForDisplay() {
        val draft = FocusPromise(
            rawText = "10 shorts today",
            sessionDurationMinutes = 1440,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            allowedSummaries = listOf("fallback allowed"),
            blockedSummaries = listOf("fallback blocked"),
            understoodSummary = "I understood: up to 10 shorts today",
            userFacingTime = "Today until midnight",
            userFacingAppliesTo = "Short-video apps",
            canStartCommitment = true
        )

        val ui = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )

        assertEquals("Today until midnight", ui.sessionTimeLabel)
        assertEquals("Short-video apps", ui.appliesToLabel)
        assertEquals("I understood: up to 10 shorts today", ui.understoodSummary)
        assertTrue(ui.canStart)
    }

    @Test
    fun messyShotsQuota_appearsOnConfirmCard() {
        val draft = parser.parse(
            "allow me upto 10 shots and remind me how much sort is left"
        )
        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.LOCAL_PREVIEW)
        assertTrue(ui.understoodSummary!!.contains("10"))
        assertTrue(ui.allowedLabel.contains("10"))
        assertEquals(10, ConfirmedPromiseBinder.bind(draft).shortFormDailyQuotaLimit)
    }

    @Test
    fun ambiguousFortyMinYouTube_requiresClarification() {
        val draft = parser.parse("40 min youtube")
        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.LOCAL_PREVIEW)

        assertNotNull(draft.clarificationQuestion)
        assertTrue(draft.needsClarification)
        assertFalse(ui.canStart)
        assertEquals("Needs one clarification", ui.statusLabel)
        assertTrue(
            draft.clarificationQuestion!!.contains("focus session", ignoreCase = true) ||
                draft.clarificationQuestion!!.contains("watch budget", ignoreCase = true) ||
                draft.clarificationQuestion!!.contains("max length", ignoreCase = true)
        )
    }

    @Test
    fun rematerializedOption_canStartOnlyAfterValidPick_andNoPackageLeak() {
        val blocked = FocusPromise(
            rawText = "block Google video less than 30 min for 1 hour",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            contentRules = emptyList(),
            clarificationQuestion = "When you said Google video, what did you mean?",
            clarificationOptions = listOf(
                ClarificationOption(
                    id = "C",
                    label = "YouTube only",
                    description = "Limit to YouTube",
                    recommended = false,
                    policyPreview = "Only YouTube follows these rules"
                ),
                ClarificationOption(
                    id = "B",
                    label = "All video apps on my phone",
                    description = "All video",
                    recommended = false,
                    policyPreview = "All major video apps follow length rules"
                )
            ),
            clarificationRequired = true,
            canStartCommitment = false
        )
        assertFalse(
            buildPromiseUnderstanding(
                draft = blocked,
                source = UnderstandingSource.PROMISE_COMPILER
            ).canStart
        )

        val afterYt = blocked.copy(
            clarificationRequired = false,
            canStartCommitment = true,
            scopePackages = listOf("com.google.android.youtube"),
            userFacingAppliesTo = "YouTube only",
            understoodSummary = "Length rules for YouTube only"
        )
        val ytUi = buildPromiseUnderstanding(
            draft = afterYt,
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = "C"
        )
        assertTrue(ytUi.canStart)
        assertEquals("YouTube only", ytUi.appliesToLabel)
        assertFalse(ytUi.appliesToLabel.orEmpty().contains("com.google"))
        assertFalse(ytUi.understoodSummary.orEmpty().contains("com."))

        val afterAll = blocked.copy(
            clarificationRequired = false,
            canStartCommitment = true,
            scopePackages = listOf(
                "com.google.android.youtube",
                "com.android.chrome",
                "org.schabi.newpipe"
            ),
            userFacingAppliesTo = "Major video apps and browser video"
        )
        assertTrue(afterAll.scopePackages.size > afterYt.scopePackages.size)
        assertTrue(
            buildPromiseUnderstanding(
                draft = afterAll,
                source = UnderstandingSource.PROMISE_COMPILER,
                selectedClarificationOptionId = "B"
            ).canStart
        )
    }

    @Test
    fun missingOptionPreview_keepsStartBlockedEvenIfIdSelected() {
        val draft = FocusPromise(
            rawText = "be strict for exam mode tonight",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            clarificationQuestion = "What exactly should I restrict?",
            clarificationOptions = listOf(
                ClarificationOption(
                    id = "A",
                    label = "Vague choice",
                    description = "No preview",
                    recommended = true,
                    policyPreview = null
                )
            ),
            clarificationRequired = true,
            canStartCommitment = false
        )
        val ui = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = "A"
        )
        // Server would keep clarificationRequired=true when rematerialize fails.
        assertFalse(ui.canStart)
    }

    /**
     * Ambiguity may never wear confident copy: even if a buggy server sets
     * canStartCommitment=true while clarificationRequired=true, the UI must stay
     * blocked, show the question + options, and must NOT say "AI preview ready".
     */
    @Test
    fun clarificationRequired_overridesServerCanStart_neverConfidentCopy() {
        val draft = FocusPromise(
            rawText = "google video 30 min",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            understoodSummary = "I understood: 30 min of Google video",
            clarificationQuestion = "When you said Google video, what did you mean?",
            clarificationOptions = listOf(
                ClarificationOption("A", "Videos inside Chrome / browser", "Browser only", true),
                ClarificationOption("B", "All video apps", "Every video app", false)
            ),
            clarificationRequired = true,
            canStartCommitment = true // server bug — UI must not trust it
        )

        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.PROMISE_COMPILER)

        assertFalse(ui.canStart)
        assertEquals("Needs one clarification", ui.statusLabel)
        assertFalse(ui.statusLabel.contains("AI preview ready"))
        // Question + options are surfaced so the interpretation is never presented alone.
        assertEquals(draft.clarificationQuestion, ui.clarificationQuestion)
        assertEquals(2, ui.clarificationOptions.size)
        assertTrue(ui.statusDetail.contains("Google video"))
    }

    /**
     * Permanent commitments never unlock on server say-so alone: the stronger-confirm
     * checkbox is always required even when canStartCommitment=true.
     */
    @Test
    fun permanentWithServerCanStartTrue_stillRequiresCheckbox() {
        val draft = FocusPromise(
            rawText = "no porn for 1 year",
            sessionDurationMinutes = 365 * 24 * 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.LOCKED,
            suggestedAppRules = emptyList(),
            requiresStrongerConfirmation = true,
            isPermanentCommitment = true,
            canStartCommitment = true // must not bypass the checkbox
        )

        val unchecked = buildPromiseUnderstanding(draft, source = UnderstandingSource.PROMISE_COMPILER)
        assertFalse(unchecked.canStart)
        assertEquals("Permanent commitment", unchecked.statusLabel)
        assertNotNull(unchecked.strongerConfirmLabel)
        assertTrue(unchecked.strongerConfirmLabel!!.contains("1 year"))

        val checked = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER,
            strongerConfirmAcknowledged = true
        )
        assertTrue(checked.canStart)
    }

    /**
     * "What I understood" content must always be present before Start can render:
     * status, time, allowed and blocked labels are non-blank in every state.
     */
    @Test
    fun understoodSectionFieldsAlwaysNonBlank_inEveryState() {
        val clear = FocusPromise(
            rawText = "study for 30 minutes",
            sessionDurationMinutes = 30,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            canStartCommitment = true
        )
        val clarify = clear.copy(
            clarificationQuestion = "Which apps?",
            clarificationRequired = true,
            canStartCommitment = false
        )
        val permanent = clear.copy(
            sessionDurationMinutes = 365 * 24 * 60,
            requiresStrongerConfirmation = true,
            isPermanentCommitment = true,
            canStartCommitment = false
        )

        for (draft in listOf(clear, clarify, permanent)) {
            for (source in UnderstandingSource.values()) {
                val ui = buildPromiseUnderstanding(draft, source = source)
                assertTrue(ui.statusLabel.isNotBlank())
                assertTrue(ui.statusDetail.isNotBlank())
                assertTrue(ui.sessionTimeLabel.isNotBlank())
                assertTrue(ui.allowedLabel.isNotBlank())
                assertTrue(ui.blockedLabel.isNotBlank())
            }
        }
    }

    @Test
    fun azureDefaultThirty_yieldsToLocalOneHrs() {
        val text = "allow only videos longer than 40 minutes for 1 hrs"
        val compiled = CompiledPromiseResponse(
            rawText = text,
            sessionDurationMinutes = 30,
            strictness = "STRICT",
            allowedSummaries = listOf("videos longer than 40 min"),
            blockedSummaries = listOf("shorter videos"),
            conditionalSummaries = emptyList(),
            contentRules = emptyList(),
            clarificationQuestion = null,
            cautionMessages = emptyList(),
            source = "azure_promise_compiler",
            confidence = 0.4,
            suggestedAppRules = emptyList()
        )
        val resolved = PromiseUnderstandingResolver.resolve(
            text = text,
            compiledResult = Result.success(compiled),
            parser = parser
        )
        assertEquals(60, resolved.draft.sessionDurationMinutes)
        assertEquals(60, ConfirmedPromiseBinder.bind(resolved.draft).durationMinutes)
        assertEquals(
            60,
            PromiseUnderstandingResolver.reconcileSessionDurationMinutes(
                compiledMinutes = 30,
                localMinutes = 60
            )
        )
    }

    @Test
    fun savedDurationFallback_replacesCompilerDefaultWithoutStaleTimeCopy() {
        val text = "Block YouTube videos longer than 30 minutes"
        val compiled = CompiledPromiseResponse(
            rawText = text,
            sessionDurationMinutes = 60,
            strictness = "STRICT",
            allowedSummaries = emptyList(),
            blockedSummaries = listOf("videos longer than 30 minutes"),
            conditionalSummaries = emptyList(),
            contentRules = emptyList(),
            clarificationQuestion = null,
            cautionMessages = listOf("Session duration was unclear, so I defaulted to 60 minutes."),
            source = "azure_promise_compiler",
            confidence = 0.8,
            suggestedAppRules = emptyList(),
            timeWindowKind = "session_fixed_default",
            userFacingConfirmation = com.phonecodex.app.domain.promise.UserFacingConfirmationDto(
                understood = "Block YouTube videos longer than 30 minutes for about an hour.",
                allowed = emptyList(),
                blocked = listOf("videos longer than 30 minutes"),
                time = "About 1 hour (default — edit if wrong)",
                appliesTo = "YouTube videos",
                checkThis = emptyList(),
                safetyNotes = emptyList()
            )
        )

        val resolved = PromiseUnderstandingResolver.resolve(
            text = text,
            compiledResult = Result.success(compiled),
            parser = parser,
            fallbackDurationMinutes = 45
        )
        val ui = buildPromiseUnderstanding(resolved.draft, source = UnderstandingSource.PROMISE_COMPILER)

        assertEquals(45, resolved.draft.sessionDurationMinutes)
        assertEquals(45, ConfirmedPromiseBinder.bind(resolved.draft).durationMinutes)
        assertNull(resolved.draft.userFacingTime)
        assertTrue(ui.sessionTimeLabel.contains("45 minutes"))
        assertTrue(resolved.draft.warnings.any { it.contains("saved default of 45 minutes") })
        assertFalse(resolved.draft.warnings.any { it.contains("defaulted to 60 minutes", ignoreCase = true) })
    }

    @Test
    fun explicitSessionDurationAndContentLengthRemainSeparateFromSavedFallback() {
        val explicitSession = parser.parse(
            "Allow videos longer than 30 minutes for 1 hour",
            fallbackDurationMinutes = 45
        )
        val contentLengthOnly = parser.parse(
            "Block videos longer than 30 minutes",
            fallbackDurationMinutes = 45
        )

        assertEquals(60, explicitSession.sessionDurationMinutes)
        assertTrue(explicitSession.sessionDurationWasExplicit)
        assertEquals(45, contentLengthOnly.sessionDurationMinutes)
        assertFalse(contentLengthOnly.sessionDurationWasExplicit)
    }

    @Test
    fun offlineUnderstandingUsesSavedDurationFallback() {
        val resolved = PromiseUnderstandingResolver.resolve(
            text = "Block YouTube Shorts",
            compiledResult = Result.failure(PromiseCompilerException("offline")),
            parser = parser,
            fallbackDurationMinutes = 45
        )

        assertFalse(resolved.fromCompiler)
        assertEquals(45, resolved.draft.sessionDurationMinutes)
        assertEquals(45, ConfirmedPromiseBinder.bind(resolved.draft).durationMinutes)
    }

    @Test
    fun messyAllowLongerThan50ForOneHours_requiresOnlineVerificationBeforeStart() {
        val text = "allow video longer than 50 min for one hours shorter video block"
        val draft = parser.parse(text)
        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.LOCAL_PREVIEW)
        val stored = ConfirmedPromiseBinder.bind(draft)

        assertFalse(draft.needsClarification)
        assertFalse(draft.clarificationRequired)
        assertTrue(draft.clarificationOptions.isEmpty())
        assertNull(draft.clarificationQuestion)
        assertEquals(60, draft.sessionDurationMinutes)
        assertEquals(50, PromiseContentRuleSupport.minVideoLengthBlockMinutes(draft))
        assertEquals(60, stored.durationMinutes)
        assertEquals(50, stored.minVideoLengthBlockMinutes)
        assertFalse(ui.canStart)
        assertTrue(ui.clarificationOptions.isEmpty())
        assertTrue(ui.clarificationQuestion.isNullOrBlank())
        assertFalse(ui.statusLabel.contains("clarification", ignoreCase = true))
        assertEquals("Reconnect to verify this promise", ui.statusLabel)
        assertTrue(ui.statusDetail.contains("Start is disabled while offline"))
        val disabled = startDisabledMessage(ui)
        assertTrue(disabled.contains("Reconnect", ignoreCase = true))
        assertFalse(
            ui.cautions.any { PromiseClarificationLaw.isUnrecognizedClarificationChoice(it) }
        )
    }

    @Test
    fun ghostCompilerClarify_concreteClocks_noOptions_unlocksStartAndDropsUnrecognizedWarning() {
        val text = "allow video longer than 50 min for one hours shorter video block"
        val response = CompiledPromiseResponse(
            rawText = text,
            sessionDurationMinutes = 60,
            strictness = "STRICT",
            allowedSummaries = listOf("videos longer than 50 min"),
            blockedSummaries = listOf("shorter videos"),
            conditionalSummaries = emptyList(),
            contentRules = listOf(
                com.phonecodex.app.domain.promise.CompiledContentRuleDto(
                    appLabel = null,
                    packageName = null,
                    surface = "video",
                    contentType = "long_form_video",
                    operator = "gt",
                    value = 50,
                    unit = "minutes",
                    action = "ALLOW"
                ),
                com.phonecodex.app.domain.promise.CompiledContentRuleDto(
                    appLabel = null,
                    packageName = null,
                    surface = "video",
                    contentType = "long_form_video",
                    operator = "lte",
                    value = 50,
                    unit = "minutes",
                    action = "BLOCK"
                )
            ),
            clarificationQuestion = null,
            cautionMessages = listOf(ConfirmationUserCopy.UNRECOGNIZED_CLARIFICATION_CHOICE),
            source = "azure_promise_compiler",
            confidence = 0.8,
            suggestedAppRules = emptyList(),
            clarificationOptions = emptyList(),
            clarificationRequired = true,
            canStartCommitment = false,
            userFacingConfirmation = com.phonecodex.app.domain.promise.UserFacingConfirmationDto(
                understood = "For one hour, only videos longer than 50 minutes are allowed; shorter videos are blocked.",
                allowed = listOf("videos longer than 50 minutes"),
                blocked = listOf("shorter videos"),
                time = "1 hour",
                appliesTo = "video apps",
                checkThis = listOf(ConfirmationUserCopy.UNRECOGNIZED_CLARIFICATION_CHOICE),
                safetyNotes = emptyList()
            )
        )
        val resolved = PromiseUnderstandingResolver.resolve(
            text = text,
            compiledResult = Result.success(response),
            parser = parser
        )
        val ui = buildPromiseUnderstanding(
            draft = resolved.draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )
        val stored = ConfirmedPromiseBinder.bind(resolved.draft)

        assertTrue(resolved.fromCompiler)
        assertFalse(resolved.draft.needsClarification)
        assertFalse(resolved.draft.clarificationRequired)
        assertTrue(ui.canStart)
        assertEquals(60, ui.durationMinutes)
        assertEquals(50, stored.minVideoLengthBlockMinutes)
        assertEquals(60, stored.durationMinutes)
        assertFalse(
            ui.cautions.any { PromiseClarificationLaw.isUnrecognizedClarificationChoice(it) }
        )
        assertEquals("", startDisabledMessage(ui))
        assertEquals("AI preview ready", ui.statusLabel)
    }

    /** Free-text clarification (no options) also blocks Start until recompile. */
    @Test
    fun freeTextClarificationWithoutOptions_blocksStartEvenWithoutServerFlag() {
        val draft = FocusPromise(
            rawText = "be strict",
            sessionDurationMinutes = 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            clarificationQuestion = "What exactly should I restrict?",
            clarificationOptions = emptyList(),
            clarificationRequired = false, // server forgot the flag
            canStartCommitment = null
        )
        val ui = buildPromiseUnderstanding(draft, source = UnderstandingSource.PROMISE_COMPILER)
        assertFalse(ui.canStart)
        assertEquals("Needs one clarification", ui.statusLabel)
    }

    @Test
    fun vagueGreetingDoesNotInventAllowedOrBlockedRulesWhileClarificationIsPending() {
        val draft = FocusPromise(
            rawText = "hi",
            sessionDurationMinutes = 0,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.STRICT,
            suggestedAppRules = emptyList(),
            understoodSummary = "I understood: you said hi. Tell me the commitment you want to set.",
            clarificationQuestion = "What would you like to allow or limit?",
            clarificationRequired = true,
            canStartCommitment = false
        )

        val ui = buildPromiseUnderstanding(
            draft = draft,
            alwaysBlockedLabels = listOf("adult content"),
            source = UnderstandingSource.PROMISE_COMPILER
        )

        assertFalse(ui.canStart)
        assertEquals("Not decided until you clarify", ui.allowedLabel)
        assertEquals("Not decided until you clarify", ui.blockedLabel)
        assertEquals(listOf("adult content"), ui.alwaysOnLifeRules)
        assertFalse(ui.userFacingTextBlob().contains("Whatever pulls you away"))
        assertFalse(ui.userFacingTextBlob().contains("Anything that fits this promise"))
    }
}

