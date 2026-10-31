package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.InstalledApp
import com.phonecodex.app.domain.model.LocalAppTargetSelectionCandidate
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.scope.ScopeGate
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeMatch
import com.phonecodex.app.domain.scope.UnresolvedReason
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.ui.home.UnderstandingSource
import com.phonecodex.app.ui.home.buildPromiseUnderstanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAppTargetSelectionLawTest {

    private val promise = "Block BeReal for 1 hour"
    private val candidate = LocalAppTargetSelectionCandidate("BeReal")
    private val unresolvedScope = """{"version":1,"kind":"UNRESOLVED","unresolvedReason":"MISSING_EVIDENCE","resolution":"EXCLUSIVE","include":[],"exempt":[]}"""

    @Test
    fun parserAcceptsOnlyKnownSafeCandidateHint() {
        val valid = CompiledPromiseResponseParser.parse(responseJson(candidate = """{"kind":"WHOLE_APP_BLOCK","label":"BeReal"}"""))
        assertEquals(candidate, valid?.localAppTargetSelection)

        val unknownKind = CompiledPromiseResponseParser.parse(responseJson(candidate = """{"kind":"OTHER","label":"BeReal"}"""))
        assertNull(unknownKind?.localAppTargetSelection)

        val packageLeak = CompiledPromiseResponseParser.parse(responseJson(candidate = """{"kind":"WHOLE_APP_BLOCK","label":"com.example.app"}"""))
        assertNull(packageLeak?.localAppTargetSelection)
    }

    @Test
    fun candidateRequiresOnlyMissingTypedAppEvidence() {
        assertEquals(candidate, LocalAppTargetSelectionLaw.candidate(response(), promise))
        assertEquals(candidate, CompiledPromiseMapper.toFocusPromise(response(), promise).localAppTargetSelection)
        assertNull(LocalAppTargetSelectionLaw.candidate(response(scopeContract = "legacy"), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(scopeJson = null), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(scopeJson = unresolvedScope.replace("MISSING_EVIDENCE", "HOST_AMBIGUOUS")), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(promiseLabel = "Discord"), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(clarificationQuestion = "Which app?"), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(clarificationRequired = false), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(canStartCommitment = true), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(requiresStrongerConfirmation = true), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(isPermanentCommitment = true), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(scopeKind = "BROWSER_VIDEO"), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(optionVerification = "unrecognized_fail_closed"), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(scopePackages = listOf("com.example.other")), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(contentBrands = listOf("youtube")), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(surfaceScope = listOf("shorts")), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(contentScope = listOf("video")), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(cautionMessages = listOf("Check this")), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(interpretationNotes = listOf("Extra intent")), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(contentRules = listOf(contentRule())), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(clarificationOptions = listOf(option())), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(suggestedAppRules = listOf(
            CompiledAppRuleDto("com.bereal.app", "BeReal", "BLOCK"),
            CompiledAppRuleDto("com.example.second", "Second", "BLOCK")
        )), promise))
        assertNull(LocalAppTargetSelectionLaw.candidate(response(suggestedAppRules = listOf(
            CompiledAppRuleDto("com.bereal.app", "BeReal", "ALLOW")
        )), promise))
    }

    @Test
    fun exactAppLookupDoesNotUseSubstringsOrGuessDuplicateNames() {
        val apps = listOf(
            InstalledApp("com.example.bereal", "BeReal"),
            InstalledApp("com.example.bereal.beta", "BeReal Beta"),
            InstalledApp("com.example.notbereal", "Not BeReal")
        )
        assertEquals(listOf("com.example.bereal"), LocalAppTargetSelectionLaw.exactMatches(apps, "bereal").map { it.packageName })

        val duplicates = apps + InstalledApp("com.example.bereal.clone", "BeReal")
        assertEquals(2, LocalAppTargetSelectionLaw.exactMatches(duplicates, "BeReal").size)
    }

    @Test
    fun localSelectionBindsExactPackageAndKeepsStartAsSeparateUserAction() {
        val draft = unresolvedDraft()
        assertTrue(draft.needsClarification)
        assertFalse(buildPromiseUnderstanding(draft, source = UnderstandingSource.PROMISE_COMPILER).canStart)

        val selected = LocalAppTargetSelectionLaw.bindSelectedApp(
            draft,
            InstalledApp("com.example.bereal", "BeReal")
        )

        assertNotNull(selected)
        assertEquals("com.example.bereal", selected?.suggestedAppRules?.single()?.packageName)
        assertEquals(AppRuleBehavior.BLOCK, selected?.suggestedAppRules?.single()?.behavior)
        assertEquals(ScopeKind.PACKAGE_PINNED, selected?.enforcementScope?.kind)
        assertEquals(listOf("com.example.bereal"), selected?.enforcementScope?.include?.single()?.packages)
        assertNull(selected?.localAppTargetSelection)
        assertFalse(selected?.needsClarification ?: true)
        val preview = buildPromiseUnderstanding(selected!!, source = UnderstandingSource.PROMISE_COMPILER)
        assertTrue(preview.canStart)
        assertTrue(preview.appliesToLabel.orEmpty().contains("BeReal"))
        assertTrue(preview.blockedLabel.contains("BeReal"))
        assertFalse(preview.userFacingTextBlob().contains("com.example.bereal"))

        val saved = ConfirmedPromiseBinder.bind(selected)
        assertEquals(selected.enforcementScope, saved.enforcementScope)
        assertEquals(listOf("com.example.bereal"), saved.enforcementScopePackages)
        assertTrue(
            ScopeGate.evaluate("com.example.bereal", "", promise, saved).match is ScopeMatch.InScope
        )
        assertEquals(
            ScopeMatch.OutOfScope,
            ScopeGate.evaluate("com.example.other", "", promise, saved).match
        )
    }

    @Test
    fun bindingRejectsUnsafeAppsAndAnyAdditionalGate() {
        val draft = unresolvedDraft()
        assertNull(LocalAppTargetSelectionLaw.bindSelectedApp(draft, InstalledApp("com.android.dialer", "BeReal")))
        assertNull(LocalAppTargetSelectionLaw.bindSelectedApp(draft, InstalledApp("com.example.bereal", "BeReal Beta")))
        assertNull(LocalAppTargetSelectionLaw.bindSelectedApp(
            draft.copy(clarificationQuestion = "Which duration?"),
            InstalledApp("com.example.bereal", "BeReal")
        ))
        assertNull(LocalAppTargetSelectionLaw.bindSelectedApp(
            draft.copy(requiresStrongerConfirmation = true),
            InstalledApp("com.example.bereal", "BeReal")
        ))
        assertNull(LocalAppTargetSelectionLaw.bindSelectedApp(
            draft.copy(scopeKind = "BROWSER_VIDEO"),
            InstalledApp("com.example.bereal", "BeReal")
        ))
        assertNull(LocalAppTargetSelectionLaw.bindSelectedApp(
            draft.copy(legacyContractGap = "legacy mismatch"),
            InstalledApp("com.example.bereal", "BeReal")
        ))
        assertNull(LocalAppTargetSelectionLaw.bindSelectedApp(
            draft.copy(contentRules = listOf(contentModelRule())),
            InstalledApp("com.example.bereal", "BeReal")
        ))
        assertNull(LocalAppTargetSelectionLaw.bindSelectedApp(
            draft.copy(suggestedAppRules = draft.suggestedAppRules + AppRule("com.example.extra", "Extra", AppRuleBehavior.BLOCK)),
            InstalledApp("com.example.bereal", "BeReal")
        ))
    }

    private fun unresolvedDraft() = FocusPromise(
        rawText = promise,
        sessionDurationMinutes = 60,
        allowedKeywords = emptyList(),
        blockedKeywords = emptyList(),
        strictness = StrictnessLevel.STRICT,
        suggestedAppRules = listOf(AppRule("com.bereal.app", "BeReal", AppRuleBehavior.BLOCK)),
        warnings = listOf(CompiledPromiseMapper.TYPED_SCOPE_UNRESOLVED_CAUTION),
        clarificationRequired = true,
        canStartCommitment = false,
        scopeContractV1 = true,
        enforcementScope = EnforcementScope.unresolved(UnresolvedReason.MISSING_EVIDENCE),
        localAppTargetSelection = candidate
    )

    private fun response(
        scopeContract: String? = "v1",
        scopeJson: String? = unresolvedScope,
        promiseLabel: String = "BeReal",
        clarificationQuestion: String? = null,
        clarificationRequired: Boolean = true,
        canStartCommitment: Boolean? = false,
        requiresStrongerConfirmation: Boolean = false,
        isPermanentCommitment: Boolean = false,
        scopeKind: String? = null,
        optionVerification: String? = null,
        scopePackages: List<String> = emptyList(),
        contentBrands: List<String> = emptyList(),
        surfaceScope: List<String> = emptyList(),
        contentScope: List<String> = emptyList(),
        cautionMessages: List<String> = emptyList(),
        interpretationNotes: List<String> = emptyList(),
        contentRules: List<CompiledContentRuleDto> = emptyList(),
        clarificationOptions: List<ClarificationOptionDto> = emptyList(),
        suggestedAppRules: List<CompiledAppRuleDto> = listOf(
            CompiledAppRuleDto("com.bereal.app", promiseLabel, "BLOCK", "all")
        )
    ) = CompiledPromiseResponse(
        rawText = promise,
        sessionDurationMinutes = 60,
        strictness = "STRICT",
        allowedSummaries = emptyList(),
        blockedSummaries = emptyList(),
        conditionalSummaries = emptyList(),
        contentRules = contentRules,
        clarificationQuestion = clarificationQuestion,
        cautionMessages = cautionMessages,
        source = "azure_promise_compiler",
        confidence = 0.9,
        suggestedAppRules = suggestedAppRules,
        clarificationOptions = clarificationOptions,
        scopeKind = scopeKind,
        scopePackages = scopePackages,
        contentBrands = contentBrands,
        surfaceScope = surfaceScope,
        contentScope = contentScope,
        optionVerification = optionVerification,
        interpretationNotes = interpretationNotes,
        clarificationRequired = clarificationRequired,
        canStartCommitment = canStartCommitment,
        requiresStrongerConfirmation = requiresStrongerConfirmation,
        isPermanentCommitment = isPermanentCommitment,
        scopeContract = scopeContract,
        scopeJson = scopeJson,
        localAppTargetSelection = candidate
    )

    private fun contentRule() = CompiledContentRuleDto(
        appLabel = null,
        packageName = null,
        surface = "video",
        contentType = "video",
        operator = "gt",
        value = 40,
        unit = "minutes",
        action = "BLOCK"
    )

    private fun contentModelRule() = ContentRule(
        appLabel = null,
        packageName = null,
        surface = "video",
        contentType = "video",
        operator = "gt",
        value = 40,
        unit = "minutes",
        action = ContentRuleAction.BLOCK
    )

    private fun option() = ClarificationOptionDto("A", "All of BeReal", "", false, null)

    private fun responseJson(candidate: String) = """
        {
          "rawText":"$promise",
          "sessionDurationMinutes":60,
          "localAppTargetSelection":$candidate
        }
    """.trimIndent()
}
