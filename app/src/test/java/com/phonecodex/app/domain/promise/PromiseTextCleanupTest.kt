package com.phonecodex.app.domain.promise

import org.junit.Assert.assertEquals
import org.junit.Test

class PromiseTextCleanupTest {

    @Test
    fun minutesl_becomesMinutes() {
        assertEquals(
            "for 1 hour allow only YouTube video longer than 40 minutes",
            PromiseTextCleanup.normalizeTyped(
                "for 1 hour allow only YouTube video longer than 40 minutesl"
            )
        )
    }

    @Test
    fun gluedUnitSuffix_isStripped() {
        assertEquals("40minutes", PromiseTextCleanup.normalizeTyped("40minutesl"))
    }

    @Test
    fun hourAndSecondSuffixes() {
        assertEquals("for 1 hour", PromiseTextCleanup.normalizeTyped("for 1 hourx"))
        assertEquals("30 seconds", PromiseTextCleanup.normalizeTyped("30 secondsz"))
    }

    @Test
    fun doesNotRewriteRealWords() {
        assertEquals("hourly review", PromiseTextCleanup.normalizeTyped("hourly review"))
        assertEquals("minimize shorts", PromiseTextCleanup.normalizeTyped("minimize shorts"))
        assertEquals("secondly", PromiseTextCleanup.normalizeTyped("secondly"))
        assertEquals("minutes later", PromiseTextCleanup.normalizeTyped("minutes later"))
        assertEquals("mind the time", PromiseTextCleanup.normalizeTyped("mind the time"))
    }

    @Test
    fun emptyAndExactUnitsStay() {
        assertEquals("", PromiseTextCleanup.normalizeTyped(""))
        assertEquals("40 minutes", PromiseTextCleanup.normalizeTyped("40 minutes"))
        assertEquals("1 hour 2 seconds", PromiseTextCleanup.normalizeTyped("1 hour 2 seconds"))
    }
}
