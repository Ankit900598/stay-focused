package com.phonecodex.app.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone P0 2026-09-25 (E8): BLOCK overlay stayed after switching Chrome videos because
 * target-package events are retained without re-evaluation. These pin the retain rule
 * and the media-identity diagnostics used to log the stale-overlay candidate.
 */
class StaleOverlayDiagnosticsTest {

    @Test
    fun targetPackageEvents_areRetainedUnderBlockOverlay() {
        assertTrue(OverlayLifecycleGate.shouldRetainForNoise("com.android.chrome", "com.android.chrome"))
        assertTrue(OverlayLifecycleGate.shouldRetainForNoise("com.android.systemui", "com.android.chrome"))
        assertTrue(OverlayLifecycleGate.shouldRetainForNoise(OverlayLifecycleGate.OWN_PACKAGE, "com.android.chrome"))
        assertFalse(OverlayLifecycleGate.shouldRetainForNoise("com.miui.home", "com.android.chrome"))
    }

    @Test
    fun mediaIdentity_readsWatchShortsAndShortLinks() {
        assertEquals("video:dQw4w9WgXcQ", OverlayLifecycleGate.mediaIdentity("m.youtube.com/watch?v=dQw4w9WgXcQ Rick"))
        assertEquals("video:dQw4w9WgXcQ", OverlayLifecycleGate.mediaIdentity("youtube.com/watch?app=desktop&v=dQw4w9WgXcQ"))
        assertEquals("shorts:abcDEF12345", OverlayLifecycleGate.mediaIdentity("youtube.com/shorts/abcDEF12345"))
        assertEquals("video:abcDEF12345", OverlayLifecycleGate.mediaIdentity("youtu.be/abcDEF12345"))
        assertNull(OverlayLifecycleGate.mediaIdentity("Google search results"))
    }

    @Test
    fun targetMediaChanged_onlyWhenBothIdentitiesKnownAndDifferent() {
        val rick = "m.youtube.com/watch?v=dQw4w9WgXcQ 3:33"
        val other = "m.youtube.com/watch?v=jNQXAC9IVRw 0:19"
        assertTrue(OverlayLifecycleGate.targetMediaChanged(rick, other))
        assertFalse(OverlayLifecycleGate.targetMediaChanged(rick, "$rick paused"))
        assertFalse(OverlayLifecycleGate.targetMediaChanged(rick, "loading"))
        assertFalse(OverlayLifecycleGate.targetMediaChanged(null, other))
    }
}
