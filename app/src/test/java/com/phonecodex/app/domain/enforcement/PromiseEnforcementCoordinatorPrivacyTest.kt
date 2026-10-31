package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.StudyWorldSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseEnforcementCoordinatorPrivacyTest {

    @Test
    fun evalPreludeOmitsPromiseAndScreenContents() {
        val privateGoal = "Block messages about one-time code 123456"
        val privateScreenText = "Your verification code is 987654"
        val prelude = PromiseEnforcementCoordinator.evalPrelude(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = privateGoal,
                settings = StudyWorldSettings(
                    durationMinutes = 60,
                    lockAttemptThreshold = 3,
                    blockAttemptCooldownMinutes = 2
                ),
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = "com.example.messages",
                screenText = privateScreenText
            )
        )

        assertFalse(prelude.contains("123456"))
        assertFalse(prelude.contains("987654"))
        assertTrue(prelude.contains("goalChars=${privateGoal.length}"))
        assertTrue(prelude.contains("pkg=com.example.messages"))
    }
}
