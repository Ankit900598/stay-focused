package com.phonecodex.app.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecodex.app.domain.diagnostics.BackendProbeKind
import com.phonecodex.app.domain.protection.ProtectionReliabilityGate
import com.phonecodex.app.domain.protection.ProtectionReliabilityInput
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectionReliabilityCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactHomeCardKeepsSetupTruthAndRevealsDiagnosticsOnDemand() {
        val model = ProtectionReliabilityGate.evaluate(
            ProtectionReliabilityInput(
                accessibilityEnabled = false,
                accessibilityAlive = false,
                lastEventMillis = 0L,
                lastEventPackage = null,
                lastEventReason = null,
                hasActiveCommitment = false,
                permanentGuardrailOn = false,
                backendKind = BackendProbeKind.IDLE,
                nowMillis = 2_000_000L
            )
        )
        composeRule.setContent {
            PhoneCodexTheme {
                ProtectionReliabilityCard(
                    model = model,
                    lastCheckSummary = null,
                    checkInProgress = false,
                    onOpenAccessibilitySettings = {},
                    onRunProtectionCheck = {},
                    compact = true,
                    allowDetailsToggle = true
                )
            }
        }

        composeRule.onNodeWithText("Needs setup").assertIsDisplayed()
        composeRule.onNodeWithText(model.summary).assertIsDisplayed()
        composeRule.onNodeWithText("Turn on Accessibility").assertIsDisplayed()
        composeRule.onNodeWithText("Details").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Accessibility").assertIsDisplayed()
        composeRule.onNodeWithText("Run protection check").assertIsDisplayed()
    }

    @Test
    fun bootstrapPending_showsCheckingWithoutFalseReconnectPrompt() {
        val model = ProtectionReliabilityGate.evaluate(
            ProtectionReliabilityInput(
                accessibilityEnabled = false,
                accessibilityAlive = false,
                lastEventMillis = 0L,
                lastEventPackage = null,
                lastEventReason = null,
                hasActiveCommitment = false,
                permanentGuardrailOn = false,
                backendKind = BackendProbeKind.IDLE,
                nowMillis = 2_000_000L,
                statusInitialized = false
            )
        )
        composeRule.setContent {
            PhoneCodexTheme {
                ProtectionReliabilityCard(
                    model = model,
                    lastCheckSummary = null,
                    checkInProgress = false,
                    onOpenAccessibilitySettings = {},
                    onRunProtectionCheck = {},
                    compact = true,
                    allowDetailsToggle = true
                )
            }
        }

        composeRule.onNodeWithText("Checking protection…").assertIsDisplayed()
        composeRule.onNodeWithText(model.summary).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Reconnect Accessibility").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Turn on Accessibility").fetchSemanticsNodes().size
        )
    }
}
