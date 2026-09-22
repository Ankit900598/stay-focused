package com.phonecodex.app.domain

import com.phonecodex.app.accessibility.CommitmentOverlayCopy
import com.phonecodex.app.accessibility.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductBrandTest {

    @Test
    fun userVisibleBrandIsStrlixNotPhoneCodex() {
        assertEquals("Strlix", ProductBrand.DISPLAY_NAME)
        assertEquals("Open Strlix", ProductBrand.OPEN_APP)
        assertTrue(ProductBrand.LOCK_COMPANION.contains("open Strlix"))
        assertFalse(ProductBrand.LOCK_COMPANION.contains("PhoneCodex"))
        assertFalse(ProductBrand.OPEN_APP.contains("PhoneCodex"))
    }

    @Test
    fun lockOverlayCopyUsesStrlix() {
        val lock = CommitmentOverlayCopy.companionLine(OverlayKind.LOCK)
        assertEquals(ProductBrand.LOCK_COMPANION, lock)
        assertFalse(lock.contains("PhoneCodex"))
        assertTrue(lock.contains("Strlix"))
    }

    @Test
    fun overlayContentDescriptionUsesStrlix() {
        val desc = ProductBrand.overlayContentDescription("LOCK")
        assertEquals("Strlix LOCK overlay", desc)
        assertFalse(desc.contains("PhoneCodex"))
    }
}
