package com.phonecodex.app.domain.safeapps

object SafeAppsCatalog {

    val DEFAULT_PACKAGES: Set<String> = setOf(
        "com.phonecodex.app",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.emergency",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.openai.chatgpt",
        "com.android.settings",
        "com.miui.home",
        "com.android.systemui",
        "com.whatsapp",
        // Productivity — unrelated promises must not WARN these by default.
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.google.android.apps.drive",
        "com.google.android.gm"
    )

    private val DEFAULT_LABELS: Map<String, String> = mapOf(
        "com.phonecodex.app" to "Strlix",
        "com.android.dialer" to "Phone",
        "com.google.android.dialer" to "Phone",
        "com.android.phone" to "Phone",
        "com.android.contacts" to "Contacts",
        "com.google.android.contacts" to "Contacts",
        "com.android.emergency" to "Emergency",
        "com.android.mms" to "Messages",
        "com.openai.chatgpt" to "ChatGPT",
        "com.google.android.apps.messaging" to "Messages",
        "com.android.settings" to "Settings",
        "com.miui.home" to "Home",
        "com.android.systemui" to "System UI",
        "com.whatsapp" to "WhatsApp",
        "com.google.android.apps.docs" to "Google Docs",
        "com.google.android.apps.docs.editors.docs" to "Google Docs",
        "com.google.android.apps.docs.editors.sheets" to "Google Sheets",
        "com.google.android.apps.docs.editors.slides" to "Google Slides",
        "com.google.android.apps.drive" to "Google Drive",
        "com.google.android.gm" to "Gmail"
    )

    fun isDefaultPackage(packageName: String): Boolean {
        return packageName in DEFAULT_PACKAGES
    }

    fun defaultLabel(packageName: String): String {
        return DEFAULT_LABELS[packageName] ?: packageName
    }
}
