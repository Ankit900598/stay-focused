package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.diagnostics.BackendHealthProbe

import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.ui.home.UnderstandingSource
import com.phonecodex.app.ui.home.buildPromiseUnderstanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompiledPromiseMapperTest {

    @Test
    fun mapsFixtureJsonToFocusPromise() {
        val json = """
            {
              "rawText": "only allow YouTube videos longer than 40 min for next 1 hour",
              "sessionDurationMinutes": 60,
              "strictness": "STRICT",
              "allowedSummaries": ["videos over 40 min → ALLOW"],
              "blockedSummaries": ["shorts and reels → BLOCK"],
              "conditionalSummaries": ["Entertainment budget: 40 minutes per session"],
              "contentRules": [
                {
                  "appLabel": "youtube",
                  "packageName": "com.google.android.youtube",
                  "surface": "video",
                  "contentType": "long_form_video",
                  "operator": "gt",
                  "value": 40,
                  "unit": "minutes",
                  "action": "ALLOW"
                },
                {
                  "appLabel": null,
                  "packageName": null,
                  "surface": "shorts",
                  "contentType": "short_form_video",
                  "operator": null,
                  "value": null,
                  "unit": null,
                  "action": "BLOCK"
                }
              ],
              "clarificationQuestion": null,
              "cautionMessages": [],
              "source": "azure_promise_compiler",
              "confidence": 0.91,
              "suggestedAppRules": [
                {
                  "packageName": "com.google.android.youtube",
                  "label": "YouTube",
                  "behavior": "ALLOW"
                },
                {
                  "packageName": "com.instagram.android",
                  "label": "Instagram",
                  "behavior": "BLOCK"
                }
              ]
            }
        """.trimIndent()

        val parsed = CompiledPromiseResponseParser.parse(json)
        assertNotNull(parsed)
        val draft = CompiledPromiseMapper.toFocusPromise(parsed!!)

        assertEquals(60, draft.sessionDurationMinutes)
        assertEquals(StrictnessLevel.STRICT, draft.strictness)
        assertNull(draft.clarificationQuestion)
        assertEquals(2, draft.suggestedAppRules.size)
        assertEquals(2, draft.contentRules.size)
        assertTrue(draft.allowedSummaries.any { it.contains("40") })
        assertTrue(draft.contentRules.any { it.action == ContentRuleAction.ALLOW && it.value == 40 })
        assertFalse(draft.needsClarification)
    }

    @Test
    fun clarificationQuestionDisablesStart() {
        val response = CompiledPromiseResponse(
            rawText = "make me strict today",
            sessionDurationMinutes = 1440,
            strictness = "STRICT",
            allowedSummaries = emptyList(),
            blockedSummaries = emptyList(),
            conditionalSummaries = emptyList(),
            contentRules = emptyList(),
            clarificationQuestion = "What apps or content should I restrict today?",
            cautionMessages = emptyList(),
            source = "azure_promise_compiler",
            confidence = 0.35,
            suggestedAppRules = emptyList(),
            clarificationRequired = true,
            canStartCommitment = false
        )

        val draft = CompiledPromiseMapper.toFocusPromise(response)
        val ui = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )

        assertTrue(draft.needsClarification)
        assertFalse(ui.canStart)
        assertEquals(UnderstandingSource.PROMISE_COMPILER, ui.source)
        assertEquals(response.clarificationQuestion, ui.clarificationQuestion)
        assertEquals("Needs one clarification", ui.statusLabel)
    }

    @Test
    fun parsesClarificationOptionsAndUserFacingConfirmation() {
        val json = """
            {
              "rawText": "google video 30 min",
              "sessionDurationMinutes": 60,
              "strictness": "STRICT",
              "allowedSummaries": [],
              "blockedSummaries": [],
              "conditionalSummaries": [],
              "contentRules": [],
              "clarificationQuestion": "When you said Google video, what did you mean?",
              "clarificationRequired": true,
              "canStartCommitment": false,
              "requiresStrongerConfirmation": false,
              "clarificationOptions": [
                {
                  "id": "A",
                  "label": "Videos inside Chrome / browser",
                  "description": "Only browser video surfaces",
                  "recommended": true,
                  "resultingPolicyPreview": "Chrome video only"
                },
                {
                  "id": "B",
                  "label": "All video apps on my phone",
                  "description": "Every video app",
                  "recommended": false,
                  "policyPreview": "All video apps"
                }
              ],
              "userFacingConfirmation": {
                "understood": "I understood: Google video for 30 minutes",
                "allowed": ["browser videos up to 30 min"],
                "blocked": ["other video apps"],
                "time": "Next 1 hour",
                "appliesTo": "Video apps",
                "checkThis": ["Pick what Google video means"],
                "safetyNotes": []
              },
              "cautionMessages": [],
              "source": "azure_promise_compiler",
              "confidence": 0.7,
              "suggestedAppRules": []
            }
        """.trimIndent()

        val parsed = CompiledPromiseResponseParser.parse(json)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.clarificationOptions.size)
        assertTrue(parsed.clarificationOptions.first().recommended)
        assertEquals("Chrome video only", parsed.clarificationOptions.first().policyPreview)
        assertNotNull(parsed.userFacingConfirmation)
        assertEquals("Next 1 hour", parsed.userFacingConfirmation!!.time)

        val draft = CompiledPromiseMapper.toFocusPromise(parsed)
        assertEquals(2, draft.clarificationOptions.size)
        assertTrue(draft.clarificationRequired)
        assertEquals(false, draft.canStartCommitment)
        assertEquals("I understood: Google video for 30 minutes", draft.understoodSummary)
        assertEquals("Next 1 hour", draft.userFacingTime)

        val uiBlocked = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )
        assertFalse(uiBlocked.canStart)
        assertEquals(2, uiBlocked.clarificationOptions.size)

        val afterPick = draft.copy(
            clarificationRequired = false,
            canStartCommitment = true,
            clarificationOptions = draft.clarificationOptions
        )
        val uiReady = buildPromiseUnderstanding(
            draft = afterPick,
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = "A"
        )
        assertTrue(uiReady.canStart)
    }

    @Test
    fun permanentRequiresStrongerConfirmationBeforeStart() {
        val draft = CompiledPromiseMapper.toFocusPromise(
            CompiledPromiseResponse(
                rawText = "no porn for 1 year rest normal",
                sessionDurationMinutes = 365 * 24 * 60,
                strictness = "LOCKED",
                allowedSummaries = emptyList(),
                blockedSummaries = listOf("adult content"),
                conditionalSummaries = emptyList(),
                contentRules = emptyList(),
                clarificationQuestion = null,
                cautionMessages = emptyList(),
                source = "azure_promise_compiler",
                confidence = 0.95,
                suggestedAppRules = emptyList(),
                clarificationRequired = false,
                canStartCommitment = false,
                requiresStrongerConfirmation = true,
                isPermanentCommitment = true
            )
        )

        val blocked = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )
        assertFalse(blocked.canStart)
        assertTrue(blocked.requiresStrongerConfirmation)
        assertNotNull(blocked.strongerConfirmLabel)

        val ready = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER,
            strongerConfirmAcknowledged = true
        )
        assertTrue(ready.canStart)
    }

    @Test
    fun fallbackUsesLocalParserAndCaution() {
        val text = "instagram only messages no reels for 7 days"
        val resolved = PromiseUnderstandingResolver.resolve(
            text = text,
            compiledResult = Result.failure(PromiseCompilerException("offline", 503))
        )

        assertFalse(resolved.fromCompiler)
        assertTrue(
            resolved.draft.warnings.contains(CompiledPromiseMapper.LOCAL_FALLBACK_CAUTION)
        )
        assertEquals(7 * 24 * 60, resolved.draft.sessionDurationMinutes)
        val ui = buildPromiseUnderstanding(
            draft = resolved.draft,
            source = UnderstandingSource.LOCAL_PREVIEW
        )
        assertTrue(ui.cautions.any { it.contains("Basic offline preview", ignoreCase = true) })
        assertEquals("Basic offline preview", ui.statusLabel)
        assertTrue(ui.statusDetail.contains(BackendHealthProbe.reachabilityHint(), ignoreCase = true))
    }

    @Test
    fun networkSuccessUsesCompilerSource() {
        val fixture = CompiledPromiseResponse(
            rawText = "no porn for 1 year rest normal",
            sessionDurationMinutes = 365 * 24 * 60,
            strictness = "LOCKED",
            allowedSummaries = emptyList(),
            blockedSummaries = listOf("adult content → BLOCK"),
            conditionalSummaries = emptyList(),
            contentRules = listOf(
                CompiledContentRuleDto(
                    appLabel = null,
                    packageName = null,
                    surface = "adult",
                    contentType = "adult_sexual",
                    operator = null,
                    value = null,
                    unit = null,
                    action = "BLOCK"
                )
            ),
            clarificationQuestion = null,
            cautionMessages = emptyList(),
            source = "azure_promise_compiler",
            confidence = 0.95,
            suggestedAppRules = emptyList()
        )

        val resolved = PromiseUnderstandingResolver.resolve(
            text = fixture.rawText,
            compiledResult = Result.success(fixture)
        )

        assertTrue(resolved.fromCompiler)
        assertFalse(resolved.draft.warnings.contains(CompiledPromiseMapper.LOCAL_FALLBACK_CAUTION))
        assertEquals(365 * 24 * 60, resolved.draft.sessionDurationMinutes)
        assertFalse(resolved.draft.needsClarification)
    }

    @Test
    fun hinglishAndAmbiguousCases_localParserStillCoveredViaFallback() {
        val cases = listOf(
            "allow youtube videos greater than 40 min",
            "only allow YouTube videos longer than 40 min for next 1 hour",
            "instagram only messages no reels for 7 days",
            "no porn for 1 year rest normal",
            "make me strict today",
            "kal OS exam hai only Neso playlist 3 hours"
        )
        val parser = FocusPromiseParser()
        cases.forEach { text ->
            val resolved = PromiseUnderstandingResolver.resolve(
                text = text,
                compiledResult = Result.failure(Exception("no backend")),
                parser = parser
            )
            assertEquals(text, resolved.draft.rawText)
            assertFalse(resolved.fromCompiler)
            val ui = buildPromiseUnderstanding(
                resolved.draft,
                source = UnderstandingSource.LOCAL_PREVIEW
            )
            if (resolved.draft.needsClarification) {
                assertFalse("Start must stay blocked for: $text", ui.canStart)
            }
        }
    }

    @Test
    fun rematerializedGoogleVideoOptions_mapDifferentScopePackages() {
        fun dto(
            optionId: String,
            scope: List<String>,
            packagesOnRules: List<String>,
            appliesTo: String
        ): CompiledPromiseResponse {
            val rules = packagesOnRules.map { pkg ->
                CompiledContentRuleDto(
                    appLabel = if (pkg.contains("chrome")) "Chrome" else "YouTube",
                    packageName = pkg,
                    surface = "video",
                    contentType = "long_form_video",
                    operator = "gt",
                    value = 30,
                    unit = "minutes",
                    action = "BLOCK",
                    description = "videos gt 30 min"
                )
            }
            return CompiledPromiseResponse(
                rawText = "block Google video less than 30 min for 1 hour",
                sessionDurationMinutes = 60,
                strictness = "STRICT",
                allowedSummaries = emptyList(),
                blockedSummaries = listOf("videos longer than 30 min"),
                conditionalSummaries = listOf("Applies to: $appliesTo"),
                contentRules = rules,
                clarificationQuestion = null,
                cautionMessages = listOf("Using your choice: $optionId"),
                source = "azure_promise_compiler",
                confidence = 0.9,
                suggestedAppRules = packagesOnRules.map { pkg ->
                    CompiledAppRuleDto(
                        packageName = pkg,
                        label = if (pkg.contains("chrome")) "Chrome" else "YouTube",
                        behavior = "ALLOW"
                    )
                },
                scopePackages = scope,
                clarificationOptions = emptyList(),
                clarificationRequired = false,
                canStartCommitment = true,
                userFacingConfirmation = UserFacingConfirmationDto(
                    understood = "Length rules for $appliesTo",
                    allowed = emptyList(),
                    blocked = listOf("videos longer than 30 min"),
                    time = "About 1 hour",
                    appliesTo = appliesTo,
                    checkThis = emptyList(),
                    safetyNotes = emptyList()
                )
            )
        }

        val youtubeOnly = CompiledPromiseMapper.toFocusPromise(
            dto(
                optionId = "C",
                scope = listOf("com.google.android.youtube"),
                packagesOnRules = listOf("com.google.android.youtube"),
                appliesTo = "YouTube only"
            )
        )
        val allVideo = CompiledPromiseMapper.toFocusPromise(
            dto(
                optionId = "B",
                scope = listOf(
                    "com.google.android.youtube",
                    "com.android.chrome",
                    "org.schabi.newpipe"
                ),
                packagesOnRules = listOf(
                    "com.google.android.youtube",
                    "com.android.chrome",
                    "org.schabi.newpipe"
                ),
                appliesTo = "Major video apps and browser video"
            )
        )

        assertEquals(listOf("com.google.android.youtube"), youtubeOnly.scopePackages)
        assertTrue(allVideo.scopePackages.size > youtubeOnly.scopePackages.size)
        assertTrue(allVideo.scopePackages.contains("com.android.chrome"))
        assertFalse(youtubeOnly.scopePackages.contains("com.android.chrome"))
        assertEquals("YouTube only", youtubeOnly.userFacingAppliesTo)
        assertNotEquals(youtubeOnly.scopePackages, allVideo.scopePackages)

        val ytUi = buildPromiseUnderstanding(
            draft = youtubeOnly,
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = "C"
        )
        assertTrue(ytUi.canStart)
        assertFalse(ytUi.appliesToLabel.orEmpty().contains("com."))
        assertFalse(ytUi.blockedLabel.contains("com."))
        assertFalse(ytUi.allowedLabel.contains("com."))
    }

    @Test
    fun youtubeContentDto_mapsSurfaceScopeSeparateFromPackageScope() {
        val draft = CompiledPromiseMapper.toFocusPromise(
            CompiledPromiseResponse(
                rawText = "allow YouTube video longer than 40 min only",
                sessionDurationMinutes = 60,
                strictness = "STRICT",
                allowedSummaries = listOf("YouTube videos longer than 40 min"),
                blockedSummaries = listOf("YouTube videos under 40 min"),
                conditionalSummaries = emptyList(),
                contentRules = listOf(
                    CompiledContentRuleDto(
                        appLabel = "YouTube",
                        packageName = "com.google.android.youtube",
                        surface = "video",
                        contentType = "long_form_video",
                        operator = "gt",
                        value = 40,
                        unit = "minutes",
                        action = "ALLOW",
                        description = "YouTube videos longer than 40 min"
                    )
                ),
                clarificationQuestion = null,
                cautionMessages = emptyList(),
                source = "azure_promise_compiler",
                confidence = 0.9,
                suggestedAppRules = listOf(
                    CompiledAppRuleDto("com.google.android.youtube", "YouTube", "AI_DECIDE")
                ),
                scopePackages = listOf("com.google.android.youtube"),
                contentBrands = listOf("youtube"),
                surfaceScope = listOf("youtube"),
                contentScope = listOf("youtube"),
                scopeKind = "youtube_content",
                clarificationRequired = false,
                canStartCommitment = true,
                userFacingConfirmation = UserFacingConfirmationDto(
                    understood = "YouTube videos 40 min or longer",
                    allowed = listOf("YouTube videos longer than 40 min"),
                    blocked = listOf("shorter YouTube videos"),
                    time = "About 1 hour",
                    appliesTo = "YouTube videos anywhere",
                    checkThis = emptyList(),
                    safetyNotes = emptyList()
                )
            )
        )
        assertEquals(listOf("youtube"), draft.contentBrands)
        assertEquals(listOf("youtube"), draft.surfaceScope)
        assertEquals("youtube_content", draft.scopeKind)
        val stored = ConfirmedPromiseBinder.bind(draft)
        assertTrue(stored.enforcementContentBrands.contains("youtube"))
        assertTrue(
            "content-brand YouTube must not lock clocks to the official package",
            stored.enforcementScopePackages.isEmpty()
        )
    }

    @Test
    fun youtubeContentScopeScrubsContradictoryAppOnlyConfirmationNotes() {
        val draft = CompiledPromiseMapper.toFocusPromise(
            CompiledPromiseResponse(
                rawText = "for 1 hour allow only YouTube video longer than 40 minutes",
                sessionDurationMinutes = 60,
                strictness = "STRICT",
                allowedSummaries = listOf("YouTube videos longer than 40 minutes"),
                blockedSummaries = listOf("YouTube videos 40 minutes or shorter"),
                conditionalSummaries = emptyList(),
                contentRules = listOf(
                    CompiledContentRuleDto(
                        appLabel = "YouTube",
                        packageName = null,
                        surface = "video",
                        contentType = "long_form_video",
                        operator = "gt",
                        value = 40,
                        unit = "minutes",
                        action = "ALLOW",
                        description = "YouTube videos longer than 40 minutes"
                    )
                ),
                clarificationQuestion = null,
                cautionMessages = emptyList(),
                source = "azure_promise_compiler",
                confidence = 0.9,
                suggestedAppRules = emptyList(),
                contentBrands = listOf("youtube"),
                surfaceScope = listOf("youtube"),
                contentScope = listOf("youtube"),
                scopeKind = "youtube_content",
                clarificationRequired = false,
                canStartCommitment = true,
                userFacingConfirmation = UserFacingConfirmationDto(
                    understood = "Only YouTube videos longer than 40 minutes",
                    allowed = listOf("YouTube videos longer than 40 minutes"),
                    blocked = listOf(
                        "Shorter YouTube videos, other apps and video services, except emergency calls."
                    ),
                    time = "About 1 hour",
                    appliesTo = "YouTube videos anywhere",
                    checkThis = listOf(
                        "Only YouTube is allowed for video playback, not browser video or alternative players.",
                        "I treated only YouTube as excluding browser video and other YouTube clients.",
                        "Exactly 40 minutes is not allowed."
                    ),
                    safetyNotes = listOf("This is not YouTube app only.")
                )
            )
        )

        val ui = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )
        val text = ui.userFacingTextBlob().lowercase()

        assertFalse(text.contains("not browser"))
        assertFalse(text.contains("excluding browser"))
        assertFalse(text.contains("alternative player"))
        assertFalse(text.contains("youtube app only"))
        assertFalse(text.contains("other apps"))
        assertFalse(text.contains("other apps and video services"))
        assertTrue(text.contains("youtube content applies across"))
        assertTrue(text.contains("exactly 40 minutes is not allowed"))
    }

    @Test
    fun explicitYoutubeAppOnlyKeepsAppOnlyConfirmationNotes() {
        val draft = CompiledPromiseMapper.toFocusPromise(
            CompiledPromiseResponse(
                rawText = "YouTube app only: only allow videos longer than 40 min for 1 hour",
                sessionDurationMinutes = 60,
                strictness = "STRICT",
                allowedSummaries = listOf("YouTube app videos longer than 40 minutes"),
                blockedSummaries = listOf("Other YouTube clients do not count"),
                conditionalSummaries = emptyList(),
                contentRules = emptyList(),
                clarificationQuestion = null,
                cautionMessages = emptyList(),
                source = "azure_promise_compiler",
                confidence = 0.9,
                suggestedAppRules = emptyList(),
                scopePackages = listOf("com.google.android.youtube"),
                scopeKind = "youtube_app_only",
                clarificationRequired = false,
                canStartCommitment = true,
                userFacingConfirmation = UserFacingConfirmationDto(
                    understood = "YouTube app only",
                    allowed = listOf("Official YouTube app video"),
                    blocked = listOf("Browser YouTube is outside this promise"),
                    time = "About 1 hour",
                    appliesTo = "YouTube app only",
                    checkThis = listOf("YouTube app only means not browser video or alternative players."),
                    safetyNotes = emptyList()
                )
            )
        )

        val ui = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER
        )
        assertTrue(ui.userFacingTextBlob().lowercase().contains("not browser video"))
    }

    @Test
    fun shortsAllSurfacesRematerialize_expandsScopeOnFocusPromise() {
        val draft = CompiledPromiseMapper.toFocusPromise(
            CompiledPromiseResponse(
                rawText = "shorts later maybe",
                sessionDurationMinutes = 60,
                strictness = "STRICT",
                allowedSummaries = emptyList(),
                blockedSummaries = listOf("short-form after pick"),
                conditionalSummaries = emptyList(),
                contentRules = listOf(
                    CompiledContentRuleDto(
                        appLabel = null,
                        packageName = null,
                        surface = "shorts",
                        contentType = "short_form_video",
                        operator = null,
                        value = null,
                        unit = null,
                        action = "BLOCK",
                        description = "all short-form video"
                    )
                ),
                clarificationQuestion = null,
                cautionMessages = emptyList(),
                source = "azure_promise_compiler",
                confidence = 0.8,
                suggestedAppRules = listOf(
                    CompiledAppRuleDto("com.google.android.youtube", "YouTube", "ALLOW"),
                    CompiledAppRuleDto("com.instagram.android", "Instagram", "ALLOW"),
                    CompiledAppRuleDto("com.zhiliaoapp.musically", "TikTok", "ALLOW")
                ),
                scopePackages = listOf(
                    "com.google.android.youtube",
                    "com.instagram.android",
                    "com.zhiliaoapp.musically",
                    "com.android.chrome"
                ),
                clarificationRequired = false,
                canStartCommitment = true,
                userFacingConfirmation = UserFacingConfirmationDto(
                    understood = "All short-form surfaces",
                    allowed = emptyList(),
                    blocked = listOf("extra shorts"),
                    time = "About 1 hour",
                    appliesTo = "Short-form video apps and browser short videos",
                    checkThis = emptyList(),
                    safetyNotes = emptyList()
                )
            )
        )
        assertTrue(draft.scopePackages.size >= 4)
        assertTrue(draft.scopePackages.contains("com.instagram.android"))
        val scope = PromiseContentRuleSupport.enforcementScopePackages(draft)
        assertTrue(scope.contains("com.instagram.android"))
        assertEquals(false, draft.clarificationRequired)
        assertEquals(true, draft.canStartCommitment)
    }
}

