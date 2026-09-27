package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate.LedgerRead

class ShortFormQuotaStore(private val prefs: SharedPreferences) : ShortFormQuotaGate.Ledger {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /**
     * A day with no record is a real zero; a record of the wrong type or shape is not.
     * [save] only ever writes what the gate holds: `count` distinct, non-blank fingerprints
     * joined by the separator, so anything else is unreadable.
     */
    override fun load(dayKey: String): LedgerRead {
        val countKey = countKey(dayKey)
        val sigKey = sigKey(dayKey)
        val hasCount = prefs.contains(countKey)
        val hasSig = prefs.contains(sigKey)
        if (!hasCount && !hasSig) return LedgerRead.Counted(0, emptySet())
        if (!hasCount) return LedgerRead.Unreadable("signatures without count")

        val count = try {
            prefs.getInt(countKey, 0)
        } catch (_: ClassCastException) {
            return LedgerRead.Unreadable("count has wrong type")
        }
        val raw = try {
            prefs.getString(sigKey, null)
        } catch (_: ClassCastException) {
            return LedgerRead.Unreadable("signatures have wrong type")
        }
        if (count < 0) return LedgerRead.Unreadable("negative count")
        if (raw == null) {
            return if (count == 0) LedgerRead.Counted(0, emptySet()) else LedgerRead.Unreadable("count without signatures")
        }

        val parts = if (raw.isEmpty()) emptyList() else raw.split(SEPARATOR)
        val signatures = parts.toSet()
        return when {
            parts.any { it.isBlank() } -> LedgerRead.Unreadable("blank signature")
            signatures.size != parts.size -> LedgerRead.Unreadable("duplicate signatures")
            signatures.size != count -> LedgerRead.Unreadable("count=$count signatures=${signatures.size}")
            else -> LedgerRead.Counted(count, signatures)
        }
    }

    override fun save(dayKey: String, count: Int, signatures: Set<String>) {
        prefs.edit()
            .putInt(countKey(dayKey), count)
            .putString(sigKey(dayKey), signatures.joinToString(SEPARATOR.toString()))
            .apply()
    }

    private fun countKey(dayKey: String) = "count.$dayKey"

    private fun sigKey(dayKey: String) = "sig.$dayKey"

    companion object {
        private const val PREFS_NAME = "phonecodex_short_form_quota"
        private const val SEPARATOR = '\u001E'
    }
}
