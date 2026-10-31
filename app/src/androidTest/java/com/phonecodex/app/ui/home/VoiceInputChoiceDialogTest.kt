package com.phonecodex.app.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecodex.app.ui.theme.PhoneCodexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceInputChoiceDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disclosesAzureAudioTransferAndWaitsForExplicitChoice() {
        var azureChoiceCount = 0
        var phoneChoiceCount = 0
        composeRule.setContent {
            PhoneCodexTheme {
                VoiceInputChoiceDialog(
                    onDismiss = {},
                    onPhoneRecognizer = { phoneChoiceCount += 1 },
                    onAzureSpeech = { azureChoiceCount += 1 }
                )
            }
        }

        composeRule.onNodeWithText("Choose voice input").assertIsDisplayed()
        composeRule.onNodeWithText("sends this clip through Strlix’s backend to Azure Speech.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Send one clip to Azure").assertIsDisplayed()
        assertEquals(0, azureChoiceCount)
        assertEquals(0, phoneChoiceCount)

        composeRule.onNodeWithText("Send one clip to Azure").performClick()
        assertEquals(1, azureChoiceCount)
        assertEquals(0, phoneChoiceCount)
    }

    @Test
    fun phoneRecognizerIsAnIndependentExplicitChoice() {
        var azureChoiceCount = 0
        var phoneChoiceCount = 0
        composeRule.setContent {
            PhoneCodexTheme {
                VoiceInputChoiceDialog(
                    onDismiss = {},
                    onPhoneRecognizer = { phoneChoiceCount += 1 },
                    onAzureSpeech = { azureChoiceCount += 1 }
                )
            }
        }

        composeRule.onNodeWithText("Use phone speech").performClick()

        assertEquals(0, azureChoiceCount)
        assertEquals(1, phoneChoiceCount)
    }
}
