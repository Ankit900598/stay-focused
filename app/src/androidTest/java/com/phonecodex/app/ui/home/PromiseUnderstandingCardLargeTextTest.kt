package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromiseUnderstandingCardLargeTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun permanentPromiseAtLargeTextRequiresAcknowledgementAndExplicitStart() {
        val acknowledged = mutableStateOf(false)
        var startCalls = 0
        val draft = FocusPromise(
            rawText = "Keep social media blocked for one year",
            sessionDurationMinutes = 365 * 24 * 60,
            allowedKeywords = emptyList(),
            blockedKeywords = emptyList(),
            strictness = StrictnessLevel.LOCKED,
            suggestedAppRules = emptyList(),
            allowedSummaries = (1..7).map { "Allowed essential activity $it" },
            blockedSummaries = (1..7).map { "Blocked distracting category $it" },
            conditionalSummaries = listOf("Ask before allowing an uncertain surface"),
            checkThisNotes = listOf("Review the selected apps and duration carefully"),
            safetyNotes = listOf("Emergency access remains available"),
            requiresStrongerConfirmation = true,
            isPermanentCommitment = true,
            canStartCommitment = false
        )

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.5f)
            ) {
                PhoneCodexTheme(darkTheme = true) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .testTag("confirmation_scroll")
                    ) {
                        PromiseUnderstandingCard(
                            understanding = buildPromiseUnderstanding(
                                draft = draft,
                                source = UnderstandingSource.PROMISE_COMPILER,
                                strongerConfirmAcknowledged = acknowledged.value
                            ),
                            startBlockedMessage = null,
                            onStartCommitment = { startCalls += 1 },
                            onEditPromise = {},
                            onSelectClarificationOption = {},
                            onStrongerConfirmChange = { acknowledged.value = it }
                        )
                    }
                }
            }
        }

        assertTrue(composeRule.onAllNodesWithTag("start_commitment").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("stronger_confirm_checkbox")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(0, startCalls)

        composeRule.onNodeWithText("Change my promise")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("start_commitment")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, startCalls)

        composeRule.onNodeWithTag("start_commitment").performClick()
        assertEquals(1, startCalls)
    }
}
