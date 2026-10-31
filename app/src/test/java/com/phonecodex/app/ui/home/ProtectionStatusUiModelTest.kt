package com.phonecodex.app.ui.home

import com.phonecodex.app.domain.diagnostics.BackendProbeKind
import com.phonecodex.app.domain.protection.ProtectionPrimaryAction
import com.phonecodex.app.domain.protection.ProtectionReliabilityGate
import com.phonecodex.app.domain.protection.ProtectionReliabilityInput
import com.phonecodex.app.domain.protection.ProtectionReliabilityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStatusUiModelTest {

    private val now = 2_000_000L

    @Test
    fun needsSetup_cardIsBlockingAndPrimaryOpensAccessibility() {
        val model = evaluate(
            accessibilityEnabled = false,
            accessibilityAlive = false,
            lastEventMillis = 0L
        )
        assertEquals(ProtectionReliabilityLevel.NEEDS_SETUP, model.level)
        assertEquals("Needs setup", protectionStatusPillText(model.level))
        assertEquals(ProtectionPrimaryAction.OPEN_ACCESSIBILITY, model.primaryAction)
        assertEquals("Turn on Accessibility", model.primaryActionLabel)
        assertTrue(model.detailsExpandedByDefault)
        assertEquals(
            "Turn on Accessibility first. You are not protected yet.",
            model.idleGreeting
        )
        assertFalse(model.idleGreeting.contains("Ready"))
    }

    @Test
    fun notProtecting_doesNotUseReadyGreeting() {
        val model = evaluate(
            accessibilityEnabled = true,
            accessibilityAlive = true,
            lastEventMillis = now - 2_000L
        )
        assertEquals(ProtectionReliabilityLevel.NOT_PROTECTING, model.level)
        assertEquals("Not protecting", protectionStatusPillText(model.level))
        assertEquals(ProtectionReliabilityGate.GREETING_NOT_PROTECTING, model.idleGreeting)
        assertTrue(model.detailsExpandedByDefault)
    }

    @Test
    fun accessibilityEnabledButServiceDead_showsReconnectNotTurnOn() {
        val model = evaluate(
            accessibilityEnabled = true,
            accessibilityAlive = false,
            lastEventMillis = now - 3_000_000L,
            hasActiveCommitment = true
        )
        assertEquals(ProtectionReliabilityLevel.NOT_PROTECTING, model.level)
        assertEquals("Reconnect Accessibility", model.primaryActionLabel)
        assertTrue(model.summary.contains("not connected"))
    }

    @Test
    fun protected_collapsesDetailsAndAllowsReadyGreeting() {
        val model = evaluate(
            accessibilityEnabled = true,
            accessibilityAlive = true,
            lastEventMillis = now - 2_000L,
            hasActiveCommitment = true
        )
        assertTrue(model.isProtected)
        assertEquals("Protected", protectionStatusPillText(model.level))
        assertEquals(ProtectionReliabilityGate.GREETING_READY, model.idleGreeting)
        assertFalse(model.detailsExpandedByDefault)
        assertEquals(ProtectionPrimaryAction.NONE, model.primaryAction)
    }

    @Test
    fun accessibilityServiceHealth_distinguishesDisabledAndEnabledButUnresponsive() {
        assertEquals("Disabled", accessibilityServiceHealthText(enabled = false, alive = false))
        assertEquals(
            "Enabled, not responding",
            accessibilityServiceHealthText(enabled = true, alive = false)
        )
        assertEquals("Connected", accessibilityServiceHealthText(enabled = true, alive = true))
        assertEquals("Disabled", accessibilityServiceHealthText(enabled = false, alive = true))
    }

    @Test
    fun bootstrapPending_usesNeutralStatusAndNoTurnOnOrReconnectPrompt() {
        val model = ProtectionReliabilityGate.evaluate(
            ProtectionReliabilityInput(
                accessibilityEnabled = false,
                accessibilityAlive = false,
                lastEventMillis = 0L,
                lastEventPackage = null,
                lastEventReason = null,
                hasActiveCommitment = false,
                permanentGuardrailOn = false,
                backendKind = BackendProbeKind.IDLE,
                nowMillis = now,
                statusInitialized = false
            )
        )

        assertEquals(ProtectionReliabilityLevel.CHECKING, model.level)
        assertEquals("Checking", protectionStatusPillText(model.level))
        assertEquals(ProtectionPrimaryAction.NONE, model.primaryAction)
        assertEquals(ProtectionReliabilityGate.GREETING_CHECKING, model.idleGreeting)
        assertFalse(model.detailsExpandedByDefault)
    }

    @Test
    fun homeList_showsProtectionStatusBeforeComposer() {
        val items = buildHomeListItems(
            hasStoredSession = false,
            hasPromiseUnderstanding = false,
            showAdvancedControls = false
        )
        assertEquals(HomeListItem.ProtectionStatus, items[1])
        assertTrue(items.indexOf(HomeListItem.ProtectionStatus) < items.indexOf(HomeListItem.PromiseComposer))
    }

    @Test
    fun noTimedCommitmentWarning_isHiddenWhenPermanentCommitmentExists() {
        assertFalse(shouldShowNoActiveCommitmentWarning(false, 1))
        assertFalse(shouldShowNoActiveCommitmentWarning(true, 0))
        assertTrue(shouldShowNoActiveCommitmentWarning(false, 0))
    }

    private fun evaluate(
        accessibilityEnabled: Boolean,
        accessibilityAlive: Boolean,
        lastEventMillis: Long,
        hasActiveCommitment: Boolean = false
    ) = ProtectionReliabilityGate.evaluate(
        ProtectionReliabilityInput(
            accessibilityEnabled = accessibilityEnabled,
            accessibilityAlive = accessibilityAlive,
            lastEventMillis = lastEventMillis,
            lastEventPackage = "com.android.chrome",
            lastEventReason = "window_changed",
            hasActiveCommitment = hasActiveCommitment,
            permanentGuardrailOn = false,
            backendKind = BackendProbeKind.IDLE,
            nowMillis = now
        )
    )
}
