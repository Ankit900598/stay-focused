package com.phonecodex.app.ui.home

import com.phonecodex.app.domain.enforcement.SessionEnforcementCopy
import org.junit.Assert.assertEquals
import org.junit.Test

class PromiseCardDisplayCopyTest {

    @Test
    fun shortFormAfterLimitVariants_renderOnce() {
        // Seen on the phone 2026-09-28 for "Strict: only 2 YouTube Shorts today".
        val line = joinForDisplay(
            listOf(
                "Allowed: first 2 short-form videos today (then block)",
                "After limit: block short-form until tomorrow",
                "After limit: block short-form videos until tomorrow",
                "Time window: today rest of day (not a 1-hour session)"
            )
        )
        assertEquals(
            "Allowed: first 2 short-form videos today (then block), " +
                "After limit: block short-form until tomorrow, " +
                "Time window: today rest of day (not a 1-hour session)",
            line
        )
    }

    @Test
    fun distinctRules_andWholeAppVsVideos_areKept() {
        assertEquals(
            "After limit: block Shorts until tomorrow, After limit: block Reels until tomorrow",
            joinForDisplay(listOf("After limit: block Shorts until tomorrow", "After limit: block Reels until tomorrow"))
        )
        assertEquals("YouTube, YouTube videos", joinForDisplay(listOf("YouTube", "YouTube videos")))
    }

    @Test
    fun trailingPunctuation_neverRendersAsDotComma() {
        assertEquals(
            "Blocks Shorts, Long lectures stay allowed",
            joinForDisplay(listOf("Blocks Shorts.", "Long lectures stay allowed."))
        )
    }

    @Test
    fun shortsRemaining_isSingularForOne() {
        assertEquals("1 short left of 2.", SessionEnforcementCopy.shortsRemaining(1, 2))
        assertEquals("2 shorts left of 3.", SessionEnforcementCopy.shortsRemaining(1, 3))
        assertEquals(
            "0 shorts left. This commitment blocks more short videos.",
            SessionEnforcementCopy.shortsRemaining(2, 2)
        )
    }
}
