package com.phonecodex.app.domain.scope

/**
 * What the persisted typed-scope slot holds for the active promise.
 *
 * ABSENT  -- no typed-scope key: a legacy session. [LegacyScopeAdapter] applies.
 * VALID   -- an enforceable canonical v1 scope. It is the law for this promise.
 * CORRUPT -- the key exists but cannot be read into an enforceable scope. The legacy
 *            mirror is NOT a substitute: for a v1 promise it can be narrower than
 *            what the user confirmed (e.g. Chrome-only vs browser video).
 */
enum class TypedScopeRecord { ABSENT, VALID, CORRUPT }

/**
 * Typed scope <-> the single string [com.phonecodex.app.data.StudyWorldSettingsStore]
 * writes in the same commit as the legacy scope fields.
 *
 * Only an enforceable compiler scope is stored; legacy sessions store nothing and keep
 * using [LegacyScopeAdapter]. A stored value that no longer parses into an enforceable
 * canonical scope is reported as [TypedScopeRecord.CORRUPT]. It never becomes an
 * UNRESOLVED (enforce-nothing) or all-ANY scope, and never silently becomes the legacy
 * mirror: [ScopeGate] turns it into [ScopeMatch.PersistenceCorrupt].
 */
object StoredScopeCodec {

    /** Stable diagnostic reason; grep contract, append-only. */
    const val CORRUPT_REASON = "SCOPE_PERSISTENCE_CORRUPT"

    data class Decoded(
        val record: TypedScopeRecord,
        val scope: EnforcementScope?,
        /** Why the record is corrupt (`blank`, `unreadable_type`, `not_enforceable:<reason>`). */
        val corruptDetail: String? = null
    ) {
        val corrupt: Boolean get() = record == TypedScopeRecord.CORRUPT
    }

    fun encode(scope: EnforcementScope?): String? {
        if (scope == null || !scope.isEnforceable || LegacyScopeAdapter.isLegacyAdapted(scope)) return null
        return ScopeSerialization.toJson(ScopeCanonicalizer.canonicalize(scope)).toString()
    }

    fun decode(raw: String?): Decoded {
        if (raw == null) return Decoded(TypedScopeRecord.ABSENT, scope = null)
        if (raw.isBlank()) return corrupt("blank")
        val parsed = ScopeCanonicalizer.canonicalize(ScopeSerialization.fromJson(raw))
        return if (parsed.isEnforceable) {
            Decoded(TypedScopeRecord.VALID, scope = parsed)
        } else {
            corrupt("not_enforceable:${parsed.unresolvedReason?.name ?: parsed.kind.name}")
        }
    }

    /**
     * Classifies the persisted slot. [keyPresent] must come from the store (`contains`),
     * so an absent key is the only way to reach the legacy path. [read] may throw when
     * the slot holds a non-string value; that is corrupt, not absent.
     */
    fun readRecord(keyPresent: Boolean, read: () -> String?): Decoded {
        if (!keyPresent) return Decoded(TypedScopeRecord.ABSENT, scope = null)
        val raw = try {
            read()
        } catch (_: ClassCastException) {
            return corrupt("unreadable_type")
        } catch (_: RuntimeException) {
            return corrupt("unreadable")
        }
        return if (raw == null) corrupt("null_value") else decode(raw)
    }

    private fun corrupt(detail: String) =
        Decoded(TypedScopeRecord.CORRUPT, scope = null, corruptDetail = detail)
}
