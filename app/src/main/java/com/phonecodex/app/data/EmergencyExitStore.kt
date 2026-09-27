package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences

/** When emergency exit last ended a commitment. Kept apart from the session, which it outlives. */
class EmergencyExitStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun lastUsedAtMillis(): Long? =
        if (prefs.contains(KEY_LAST_USED_AT)) prefs.getLong(KEY_LAST_USED_AT, 0L) else null

    fun recordUse(nowMillis: Long) {
        prefs.edit().putLong(KEY_LAST_USED_AT, nowMillis).commit()
    }

    private companion object {
        const val PREFS_NAME = "phonecodex_recovery"
        const val KEY_LAST_USED_AT = "emergency_exit_last_used_at"
    }
}
