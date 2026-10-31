package com.phonecodex.app.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromiseComposerSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandedComposerPlaceholderIsClearlyAnExampleNotAnExistingPromise() {
        composeRule.setContent {
            PhoneCodexTheme {
                PromiseComposerSection(
                    promiseText = "",
                    onPromiseTextChange = {},
                    onExampleSelected = {},
                    onUnderstandPromise = {},
                    onVoicePromise = {}
                )
            }
        }

        composeRule.onNodeWithText(
            "Example: Keep me on one lecture playlist for 3 hours…"
        ).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Tomorrow I have my OS exam")
                .fetchSemanticsNodes()
                .size
        )
    }
}
