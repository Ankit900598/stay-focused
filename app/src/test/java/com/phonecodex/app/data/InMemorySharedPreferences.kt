package com.phonecodex.app.data

import android.content.SharedPreferences

/**
 * JVM stand-in for Android SharedPreferences with the same editor semantics:
 * `clear()` runs first, then per-key edits (last write wins, `remove` included),
 * string sets are copied in and out, and typed getters throw ClassCastException
 * on a wrong-type value just like the platform implementation.
 */
class InMemorySharedPreferences : SharedPreferences {

    private val values = linkedMapOf<String, Any>()

    fun snapshot(): Map<String, Any?> = values.mapValues { (_, v) -> if (v is Set<*>) v.toSet() else v }

    override fun getAll(): Map<String, *> = snapshot()

    override fun getString(key: String, defValue: String?): String? =
        if (key in values) values[key] as String else defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        if (key in values) (values[key] as Set<String>).toSet() else defValues

    override fun getInt(key: String, defValue: Int): Int = if (key in values) values[key] as Int else defValue

    override fun getLong(key: String, defValue: Long): Long = if (key in values) values[key] as Long else defValue

    override fun getFloat(key: String, defValue: Float): Float = if (key in values) values[key] as Float else defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        if (key in values) values[key] as Boolean else defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            pending.clear()
            clearAll = false
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
