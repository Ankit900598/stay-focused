package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences

class AccessibilityDisclosureConsentStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun hasAcceptedCurrentDisclosure(): Boolean = runCatching {
        prefs.getInt(KEY_ACCEPTED_VERSION, 0) == CURRENT_DISCLOSURE_VERSION
    }.getOrDefault(false)

    fun acceptCurrentDisclosure(): Boolean = runCatching {
        prefs.edit()
            .putInt(KEY_ACCEPTED_VERSION, CURRENT_DISCLOSURE_VERSION)
            .commit()
    }.getOrDefault(false)

    companion object {
        const val CURRENT_DISCLOSURE_VERSION = 1
        const val PREFS_NAME = "accessibility_disclosure_consent"
        const val KEY_ACCEPTED_VERSION = "accepted_version"
    }
}
