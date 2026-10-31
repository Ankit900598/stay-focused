package com.phonecodex.app.domain.guardrail

import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.PermanentGuardrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PermanentGuardrailEvaluatorTest {

    private val evaluator = PermanentGuardrailEvaluator()

    @Test
    fun oneWeakPornSignalOnQuietApp_returnsWarnNotBlock() {
        val match = evaluator.evaluate(
            packageName = "com.lonely.notes",
            screenText = "This page mentions adult content warnings",
            guardrails = listOf(pornGuardrail())
        )

        assertNotNull(match)
        assertEquals(DecisionType.WARN, match?.decision)
        assertEquals(listOf("adult content"), match?.matchedWeakSignals)
    }

    @Test
    fun oneWeakPornWordOnChrome_isIgnored() {
        val match = evaluator.evaluate(
            packageName = "com.android.chrome",
            screenText = "Raj Shamani Shorts comment mentions porn once",
            guardrails = listOf(pornGuardrail())
        )

        assertNull(match)
    }

    @Test
    fun twoWeakPornSignals_returnBlock() {
        val match = evaluator.evaluate(
            packageName = "com.android.chrome",
            screenText = "nsfw nude scene description",
            guardrails = listOf(pornGuardrail())
        )

        assertNotNull(match)
        assertEquals(DecisionType.BLOCK, match?.decision)
        assertEquals(listOf("nsfw", "nude"), match?.matchedWeakSignals)
    }

    @Test
    fun youtubeVideoIdSubstringPorn_isNotAGuardrailHit() {
        val match = evaluator.evaluate(
            packageName = "com.android.chrome",
            screenText = "Raj Shamani Shorts https://m.youtube.com/watch?v=abPornXy12Q",
            guardrails = listOf(pornGuardrail())
        )

        assertNull(match)
    }

    @Test
    fun weakWordsInsideNormalWords_doNotMatch() {
        val match = evaluator.evaluate(
            packageName = "com.google.android.apps.docs",
            screenText = "Sussex history assignment and hotel notes",
            guardrails = listOf(pornGuardrail())
        )

        assertNull(match)
    }

    @Test
    fun strongPornSignal_returnsBlock() {
        val match = evaluator.evaluate(
            packageName = "com.android.chrome",
            screenText = "visit xvideos now",
            guardrails = listOf(pornGuardrail())
        )

        assertNotNull(match)
        assertEquals(DecisionType.BLOCK, match?.decision)
        assertEquals(listOf("xvideos"), match?.matchedStrongSignals)
    }

    @Test
    fun noPornSignals_returnsNull() {
        val match = evaluator.evaluate(
            packageName = "com.android.chrome",
            screenText = "DSA lecture notes and compiler design",
            guardrails = listOf(pornGuardrail())
        )

        assertNull(match)
    }

    @Test
    fun disabledGuardrail_doesNotBlockPackageOrContent() {
        val guardrail = pornGuardrail().copy(
            enabled = false,
            blockedPackages = setOf("com.android.chrome")
        )

        assertNull(evaluator.evaluate("com.android.chrome", "visit xvideos now", listOf(guardrail)))
    }

    @Test
    fun expiredGuardrail_doesNotBlockPackageOrContent() {
        val guardrail = pornGuardrail().copy(
            blockedPackages = setOf("com.android.chrome"),
            expiresAtMillis = 1_000L
        )

        assertNotNull(evaluator.evaluate("com.android.chrome", "visit xvideos now", listOf(guardrail), 999L))
        assertNull(evaluator.evaluate("com.android.chrome", "visit xvideos now", listOf(guardrail), 1_000L))
    }

    private fun pornGuardrail(): PermanentGuardrail {
        return PermanentGuardrail(
            id = "porn_guardrail",
            name = "Block Porn",
            enabled = true,
            blockedKeywords = emptySet(),
            blockedPackages = emptySet(),
            createdAtMillis = 0L,
            expiresAtMillis = null
        )
    }
}
