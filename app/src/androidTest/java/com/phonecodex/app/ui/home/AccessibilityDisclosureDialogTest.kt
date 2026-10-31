package com.phonecodex.app.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecodex.app.R
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityDisclosureDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disclosureExplainsScreenTextAndRequiresContinueAction() {
        var continued = false
        var dismissed = false
        composeRule.setContent {
            PhoneCodexTheme {
                AccessibilityDisclosureDialog(
                    onDismiss = { dismissed = true },
                    onContinue = { continued = true }
                )
            }
        }

        composeRule.onNodeWithText("Before enabling Accessibility").assertIsDisplayed()
        composeRule.onNodeWithText("private messages or other sensitive content", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("image experiment", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("may lead to a warning or block", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Agree and open Settings").assertIsDisplayed().performClick()

        val serviceDescription = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.accessibility_service_description)
        assertTrue(serviceDescription.contains("may lead to a warning or block"))
        assertFalse(serviceDescription.contains("AI is advisory"))

        assertTrue(continued)
        assertFalse(dismissed)
    }

    @Test
    fun notNowDismissesWithoutContinuing() {
        var continued = false
        var dismissed = false
        composeRule.setContent {
            PhoneCodexTheme {
                AccessibilityDisclosureDialog(
                    onDismiss = { dismissed = true },
                    onContinue = { continued = true }
                )
            }
        }

        composeRule.onNodeWithText("Not now").assertIsDisplayed().performClick()

        assertTrue(dismissed)
        assertFalse(continued)
    }

    @Test
    fun consentPersistenceFailureIsShownAndDoesNotHideTheDisclosure() {
        composeRule.setContent {
            PhoneCodexTheme {
                AccessibilityDisclosureDialog(
                    onDismiss = {},
                    onContinue = {},
                    consentSaveFailed = true
                )
            }
        }

        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.accessibility_disclosure_save_failed
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Agree and open Settings").assertIsDisplayed()
    }
}
