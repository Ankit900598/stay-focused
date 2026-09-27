package com.phonecodex.app.domain.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityHeartbeatLawTest {

    @Test
    fun shouldWrite_firstEventAlways() {
        assertTrue(
            AccessibilityHeartbeatLaw.shouldWrite(
                nowMillis = 10_000L,
                lastWriteMillis = 0L,
                packageName = "com.android.chrome",
                lastPackageName = "",
                reason = AccessibilityHeartbeatLaw.REASON_WINDOW_CHANGED,
                lastReason = ""
            )
        )
    }

    @Test
    fun shouldWrite_packageChangeEvenInsideThrottle() {
        assertTrue(
            AccessibilityHeartbeatLaw.shouldWrite(
                nowMillis = 10_500L,
                lastWriteMillis = 10_000L,
                packageName = "com.google.android.youtube",
                lastPackageName = "com.android.chrome",
                reason = AccessibilityHeartbeatLaw.REASON_WINDOW_CHANGED,
                lastReason = AccessibilityHeartbeatLaw.REASON_WINDOW_CHANGED
            )
        )
    }

    @Test
    fun shouldWrite_reasonChangeEvenInsideThrottle() {
        assertTrue(
            AccessibilityHeartbeatLaw.shouldWrite(
                nowMillis = 10_500L,
                lastWriteMillis = 10_000L,
                packageName = "com.android.chrome",
                lastPackageName = "com.android.chrome",
                reason = AccessibilityHeartbeatLaw.REASON_CONTENT_CHANGED,
                lastReason = AccessibilityHeartbeatLaw.REASON_WINDOW_CHANGED
            )
        )
    }

    @Test
    fun shouldWrite_samePackageAndReason_throttledUnderTwoSeconds() {
        assertFalse(
            AccessibilityHeartbeatLaw.shouldWrite(
                nowMillis = 11_999L,
                lastWriteMillis = 10_000L,
                packageName = "com.android.chrome",
                lastPackageName = "com.android.chrome",
                reason = AccessibilityHeartbeatLaw.REASON_CONTENT_CHANGED,
                lastReason = AccessibilityHeartbeatLaw.REASON_CONTENT_CHANGED
            )
        )
    }

    @Test
    fun shouldWrite_samePackageAndReason_afterTwoSeconds() {
        assertTrue(
            AccessibilityHeartbeatLaw.shouldWrite(
                nowMillis = 12_000L,
                lastWriteMillis = 10_000L,
                packageName = "com.android.chrome",
                lastPackageName = "com.android.chrome",
                reason = AccessibilityHeartbeatLaw.REASON_CONTENT_CHANGED,
                lastReason = AccessibilityHeartbeatLaw.REASON_CONTENT_CHANGED
            )
        )
    }

    @Test
    fun ageMs_nullWhenNeverWritten() {
        assertNull(AccessibilityHeartbeatLaw.ageMs(nowMillis = 5_000L, lastEventMillis = 0L))
    }

    @Test
    fun isFresh_trueWithinFortyFiveSeconds() {
        assertTrue(AccessibilityHeartbeatLaw.isFresh(ageMs = 45_000L))
        assertFalse(AccessibilityHeartbeatLaw.isFresh(ageMs = 45_001L))
        assertFalse(AccessibilityHeartbeatLaw.isFresh(ageMs = null))
    }

    @Test
    fun serviceLeaseFresh_onlyWhileRecentAndFromCurrentBoot() {
        assertTrue(
            AccessibilityHeartbeatLaw.isServiceLeaseFresh(
                nowElapsedMillis = 100_000L,
                lastLeaseElapsedMillis = 70_000L
            )
        )
        assertFalse(
            AccessibilityHeartbeatLaw.isServiceLeaseFresh(
                nowElapsedMillis = 100_001L,
                lastLeaseElapsedMillis = 70_000L
            )
        )
        assertFalse(
            AccessibilityHeartbeatLaw.isServiceLeaseFresh(
                nowElapsedMillis = 50_000L,
                lastLeaseElapsedMillis = 70_000L
            )
        )
        assertFalse(
            AccessibilityHeartbeatLaw.isServiceLeaseFresh(
                nowElapsedMillis = 100_000L,
                lastLeaseElapsedMillis = 0L
            )
        )
    }

    @Test
    fun formatAge_matchesHomeCopy() {
        assertEquals("no phone event yet", AccessibilityHeartbeatLaw.formatAge(null))
        assertEquals("last phone event just now", AccessibilityHeartbeatLaw.formatAge(400L))
        assertEquals("last phone event 12s ago", AccessibilityHeartbeatLaw.formatAge(12_000L))
        assertEquals("last phone event 3m ago", AccessibilityHeartbeatLaw.formatAge(180_000L))
        assertEquals("last phone event over an hour ago", AccessibilityHeartbeatLaw.formatAge(3_600_000L))
    }
}
