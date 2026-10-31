package com.phonecodex.app.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecodex.app.data.ScreenTimeReadResult
import com.phonecodex.app.domain.usage.ScreenTimeSummary
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayScreenTimeCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun usageAccessIsRequestedOnlyAfterTheUserTapsTheExplainedAction() {
        var requestCount = 0
        composeRule.setContent {
            PhoneCodexTheme {
                TodayScreenTimeCard(
                    result = ScreenTimeReadResult.NeedsAccess,
                    loading = false,
                    onEnableAccess = { requestCount += 1 },
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithText("Optional Android Usage Access.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Review Usage Access").assertIsDisplayed()
        assertEquals(0, requestCount)

        composeRule.onNodeWithText("Review Usage Access").performClick()
        assertEquals(1, requestCount)
    }

    @Test
    fun availableStateShowsTheAggregateAndOnDevicePrivacyStatement() {
        composeRule.setContent {
            PhoneCodexTheme {
                TodayScreenTimeCard(
                    result = ScreenTimeReadResult.Available(
                        ScreenTimeSummary(
                            appForegroundMillis = 8_040_000L,
                            sampledAtMillis = 1L
                        )
                    ),
                    loading = false,
                    onEnableAccess = {},
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithText("2h 14m").assertIsDisplayed()
        composeRule.onNodeWithText("Calculated on this phone · never sent to Strlix AI")
            .assertIsDisplayed()
    }
}
