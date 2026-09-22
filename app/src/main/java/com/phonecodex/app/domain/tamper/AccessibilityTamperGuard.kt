package com.phonecodex.app.domain.tamper

data class TamperGuardMatch(
    val matchedSignals: List<String>,
    val reason: String = PHONECODEX_CONTROLS_REASON
) {
    companion object {
        const val PHONECODEX_CONTROLS_REASON =
            "Accessibility controls are locked during active Study World."
    }
}

class AccessibilityTamperGuard {

    fun evaluatePhoneCodexAccessibilityControls(
        packageName: String,
        screenText: String
    ): TamperGuardMatch? {
        if (packageName !in SETTINGS_PACKAGES) return null

        val normalizedText = screenText.lowercase()
        val matchedSignals = PHONECODEX_CONTROL_SIGNALS.filter { signal ->
            normalizedText.contains(signal)
        }
        if (matchedSignals.isEmpty()) return null

        return TamperGuardMatch(matchedSignals = matchedSignals)
    }

    companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"

        val SETTINGS_PACKAGES: Set<String> = setOf(
            SETTINGS_PACKAGE,
            "com.android.settings.intelligence",
            "com.xiaomi.misettings",
            "com.miui.securitycenter",
            "com.miui.permcenter"
        )

        val PHONECODEX_CONTROL_SIGNALS: List<String> = listOf(
            "phonecodex",
            "phone codex",
            "use phonecodex",
            "strlix",
            "use strlix"
        )
    }
}
