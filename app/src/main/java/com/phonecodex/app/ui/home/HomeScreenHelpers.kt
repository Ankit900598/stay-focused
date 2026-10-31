package com.phonecodex.app.ui.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.phonecodex.app.data.PermanentGuardrailsStore
import com.phonecodex.app.domain.model.FocusWorld
import com.phonecodex.app.domain.model.PermanentGuardrail
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.model.WorldMode

internal fun launchAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

internal fun launchPrivacyPolicy(context: Context) {
    PrivacyPolicyLink.launch(context)
}

internal fun defaultStudyWorld(): FocusWorld = FocusWorld(
    id = "study",
    name = "Study World",
    mode = WorldMode.STUDY,
    defaultStrictness = StrictnessLevel.STRICT,
    blockedPackages = setOf("com.instagram.android")
)

/**
 * Product-facing name for a guardrail. The stored name stays as-is because the evaluator and
 * event log reference it; only what the user reads changes here.
 */
internal fun guardrailDisplayName(guardrail: PermanentGuardrail): String =
    when (guardrail.id) {
        PermanentGuardrailsStore.PORN_GUARDRAIL_ID -> "Adult Content Guardrail"
        else -> guardrail.name
    }

/** Short phrase used inside the "I understood" card. */
internal fun guardrailBlockedLabel(guardrail: PermanentGuardrail): String =
    when (guardrail.id) {
        PermanentGuardrailsStore.PORN_GUARDRAIL_ID -> "adult content"
        else -> guardrail.name.lowercase()
    }
