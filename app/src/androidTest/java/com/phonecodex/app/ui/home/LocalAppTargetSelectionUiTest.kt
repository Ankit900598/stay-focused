package com.phonecodex.app.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phonecodex.app.data.InstalledAppsReader
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.InstalledApp
import com.phonecodex.app.domain.model.LocalAppTargetSelectionCandidate
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.promise.CompiledPromiseMapper
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.UnresolvedReason
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAppTargetSelectionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exactLocalChoiceStaysSeparateFromStartAndDisclosesOnDeviceLookup() {
        val lookupRequested = mutableStateOf(false)
        val matches = mutableStateOf(emptyList<InstalledApp>())
        var findCalls = 0
        var selected: InstalledApp? = null
        var startCalls = 0

        composeRule.setContent {
            PhoneCodexTheme {
                PromiseUnderstandingCard(
                    understanding = buildPromiseUnderstanding(
                        unresolvedDraft(),
                        source = UnderstandingSource.PROMISE_COMPILER
                    ),
                    startBlockedMessage = null,
                    onStartCommitment = { startCalls += 1 },
                    onEditPromise = {},
                    onSelectClarificationOption = {},
                    onStrongerConfirmChange = {},
                    localAppTargetMatches = matches.value,
                    localAppTargetLookupRequested = lookupRequested.value,
                    onFindLocalAppTarget = {
                        findCalls += 1
                        lookupRequested.value = true
                    },
                    onSelectLocalAppTarget = { selected = it }
                )
            }
        }

        composeRule.onNodeWithText("This lookup stays on your phone", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Find exact app on this phone").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("start_commitment").fetchSemanticsNodes().isEmpty())
        assertEquals(0, findCalls)
        assertEquals(0, startCalls)

        composeRule.onNodeWithTag("local_app_target_find").performClick()
        assertEquals(1, findCalls)
        composeRule.runOnIdle {
            matches.value = listOf(InstalledApp("com.example.bereal", "BeReal"))
        }

        composeRule.onNodeWithTag("local_app_target_select").assertIsDisplayed()
        composeRule.onNodeWithTag("local_app_target_select").performClick()
        composeRule.runOnIdle {
            assertEquals("com.example.bereal", selected?.packageName)
            assertEquals(0, startCalls)
        }
        assertTrue(composeRule.onAllNodesWithTag("start_commitment").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun missingOrDuplicateLabelsNeverOfferAChoiceOrStart() {
        val matches = mutableStateOf(
            listOf(
                InstalledApp("com.example.bereal", "BeReal"),
                InstalledApp("com.example.clone", "BeReal")
            )
        )
        var refreshCalls = 0

        composeRule.setContent {
            PhoneCodexTheme {
                PromiseUnderstandingCard(
                    understanding = buildPromiseUnderstanding(
                        unresolvedDraft(),
                        source = UnderstandingSource.PROMISE_COMPILER
                    ),
                    startBlockedMessage = null,
                    onStartCommitment = {},
                    onEditPromise = {},
                    onSelectClarificationOption = {},
                    onStrongerConfirmChange = {},
                    localAppTargetMatches = matches.value,
                    localAppTargetLookupRequested = true,
                    onRefreshLocalAppTarget = { refreshCalls += 1 },
                    onSelectLocalAppTarget = {}
                )
            }
        }

        composeRule.onNodeWithText("More than one launchable app has this exact name", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("local_app_target_refresh").performClick()
        assertEquals(1, refreshCalls)
        assertTrue(composeRule.onAllNodesWithTag("local_app_target_select").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("start_commitment").fetchSemanticsNodes().isEmpty())

        composeRule.runOnIdle { matches.value = emptyList() }
        composeRule.onNodeWithText("No launchable app has the exact name", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("local_app_target_refresh").performClick()
        assertEquals(2, refreshCalls)
        assertTrue(composeRule.onAllNodesWithTag("local_app_target_select").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("start_commitment").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun launcherQueryReturnsUniquePackagesAndFindsInstalledWhatsAppWithoutOpeningIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apps = InstalledAppsReader(context).getLaunchableApps()

        assertTrue("Launcher query should return visible apps", apps.isNotEmpty())
        assertEquals("Package results should be unique", apps.size, apps.map { it.packageName }.distinct().size)
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            assertTrue("The known installed WhatsApp launcher should be visible to the picker", apps.any {
                it.packageName == "com.whatsapp" && it.label.isNotBlank()
            })
        }
    }

    private fun unresolvedDraft() = FocusPromise(
        rawText = "Block BeReal for 1 hour",
        sessionDurationMinutes = 60,
        allowedKeywords = emptyList(),
        blockedKeywords = emptyList(),
        strictness = StrictnessLevel.STRICT,
        suggestedAppRules = listOf(
            AppRule("com.bereal.app", "BeReal", AppRuleBehavior.BLOCK)
        ),
        warnings = listOf(CompiledPromiseMapper.TYPED_SCOPE_UNRESOLVED_CAUTION),
        clarificationRequired = true,
        canStartCommitment = false,
        scopeContractV1 = true,
        enforcementScope = EnforcementScope.unresolved(UnresolvedReason.MISSING_EVIDENCE),
        localAppTargetSelection = LocalAppTargetSelectionCandidate("BeReal")
    )
}
