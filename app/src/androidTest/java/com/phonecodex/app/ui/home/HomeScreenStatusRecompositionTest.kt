package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecodex.app.domain.protection.ProtectionReliabilityLevel
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenStatusRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lazyHeaderUpdatesWhenObservedProtectionLevelChanges() {
        composeRule.setContent {
            PhoneCodexTheme {
                var serviceAlive by remember { mutableStateOf(false) }
                val protectionLevel by remember {
                    derivedStateOf {
                        if (serviceAlive) {
                            ProtectionReliabilityLevel.PROTECTED
                        } else {
                            ProtectionReliabilityLevel.NOT_PROTECTING
                        }
                    }
                }

                Column {
                    Button(onClick = { serviceAlive = true }) {
                        Text("Simulate healthy service")
                    }
                    LazyColumn {
                        item {
                            HomeScreenHeader(
                                protectionLevel = protectionLevel,
                                permanentSafetyRuleCount = 1,
                                hasActiveCommitment = false
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Not protecting").assertIsDisplayed()
        composeRule.onNodeWithText("Simulate healthy service").performClick()
        composeRule.onNodeWithText("Protected").assertIsDisplayed()
    }
}
