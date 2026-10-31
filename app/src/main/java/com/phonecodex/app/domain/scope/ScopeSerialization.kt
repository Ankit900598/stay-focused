package com.phonecodex.app.domain.scope

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON ⇄ [EnforcementScope]. Validate-or-`UNRESOLVED`. Never partial.
 *
 * Boundary rules (docs/scope-semantics-v1.md §3):
 * - Missing / unknown / non-int `version` -> UNRESOLVED(BAD_VERSION).
 * - Unknown `kind` string -> UNRESOLVED(UNKNOWN_KIND).
 * - `evidenceSpans` must all be verbatim substrings of the user promise (call
 *   [fromJsonWithUserPromise] to enforce this); otherwise UNRESOLVED(MISSING_EVIDENCE).
 * - `PACKAGE_PINNED` targets must map to a supported app name actually present in the
 *   promise; package syntax or model-provided labels alone are not evidence.
 * - Any `com.*` / `org.*` / `net.*` / `io.*` inside `appliesToText` ->
 *   UNRESOLVED(PACKAGE_LEAK_IN_APPLIES_TO). Package IDs are internal only.
 * - Malformed clause (unknown enum value, non-string field where a string was expected,
 *   etc.) -> UNRESOLVED(MALFORMED_CLAUSE).
 *
 * The offline parser and the mapper are BOTH required to route through this file. There is
 * no partial parse path.
 */
object ScopeSerialization {

    /**
     * Parse without the user promise reference (evidenceSpans not verified). Prefer
     * [fromJsonWithUserPromise] on the backend/Android boundary.
     */
    fun fromJson(raw: String?): EnforcementScope = fromJsonWithUserPromise(raw, null)

    fun fromJsonWithUserPromise(raw: String?, userPromise: String?): EnforcementScope {
        if (raw.isNullOrBlank()) {
            return EnforcementScope.unresolved(UnresolvedReason.MISSING_EVIDENCE)
        }
        val obj = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return EnforcementScope.unresolved(UnresolvedReason.MALFORMED_CLAUSE)
        }
        return fromJsonObject(obj, userPromise)
    }

    fun fromJsonObject(obj: JSONObject, userPromise: String?): EnforcementScope {
        val versionRaw = obj.opt("version")
        val version = when (versionRaw) {
            is Int -> versionRaw
            is Number -> versionRaw.toInt()
            null -> return EnforcementScope.unresolved(UnresolvedReason.BAD_VERSION)
            else -> return EnforcementScope.unresolved(UnresolvedReason.BAD_VERSION)
        }
        if (version != EnforcementScope.CURRENT_VERSION) {
            return EnforcementScope.unresolved(UnresolvedReason.BAD_VERSION)
        }

        val kindRaw = obj.optString("kind", "").trim()
        val kind = parseEnum<ScopeKind>(kindRaw)
            ?: return EnforcementScope.unresolved(
                reason = UnresolvedReason.UNKNOWN_KIND,
                evidenceSpans = readStringList(obj, "evidenceSpans")
            )

        val evidenceSpans = readStringList(obj, "evidenceSpans")
        if (kind != ScopeKind.UNRESOLVED &&
            kind != ScopeKind.PACKAGE_PINNED &&
            userPromise != null &&
            evidenceSpans.isEmpty()
        ) {
            // Contract: compiler must point at the user's words that set scope.
            return EnforcementScope.unresolved(UnresolvedReason.MISSING_EVIDENCE)
        }
        if (userPromise != null && !allSpansAreVerbatim(evidenceSpans, userPromise)) {
            return EnforcementScope.unresolved(UnresolvedReason.MISSING_EVIDENCE, evidenceSpans)
        }

        val resolution = parseEnum<ScopeResolution>(obj.optString("resolution", "").trim())
            ?: ScopeResolution.EXCLUSIVE
        val identity = parseEnum<ScopeIdentity>(obj.optString("identity", "").trim())
        val confidence = obj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)

        val appliesToText = obj.optString("appliesToText", "").ifBlank { null }
        if (appliesToText != null && ScopeAppliesToCopy.containsPackageId(appliesToText)) {
            return EnforcementScope.unresolved(
                reason = UnresolvedReason.PACKAGE_LEAK_IN_APPLIES_TO,
                evidenceSpans = evidenceSpans
            )
        }

        val include = readClauseList(obj, "include")
            ?: return EnforcementScope.unresolved(UnresolvedReason.MALFORMED_CLAUSE, evidenceSpans)
        val exempt = readClauseList(obj, "exempt")
            ?: return EnforcementScope.unresolved(UnresolvedReason.MALFORMED_CLAUSE, evidenceSpans)

        if (kind == ScopeKind.PACKAGE_PINNED && userPromise != null) {
            val packages = include.flatMap { it.packages }.distinct()
            if (packages.isEmpty() || packages.any { !ScopePackageMentionLaw.wasNamedIn(it, userPromise) }) {
                return EnforcementScope.unresolved(UnresolvedReason.MISSING_EVIDENCE, evidenceSpans)
            }
        }

        val unresolvedReason = parseEnum<UnresolvedReason>(obj.optString("unresolvedReason", "").trim())

        if (kind == ScopeKind.UNRESOLVED) {
            return EnforcementScope(
                version = version,
                kind = kind,
                resolution = resolution,
                identity = identity,
                include = emptyList(),
                exempt = emptyList(),
                confidence = confidence,
                appliesToText = null,
                unresolvedReason = unresolvedReason ?: UnresolvedReason.MISSING_EVIDENCE,
                evidenceSpans = evidenceSpans,
                candidateScopes = readCandidateScopes(obj, userPromise)
            )
        }

        if (include.isEmpty()) {
            // A non-UNRESOLVED kind must materialise clauses. Compiler contract breach.
            return EnforcementScope.unresolved(UnresolvedReason.MALFORMED_CLAUSE, evidenceSpans)
        }

        return EnforcementScope(
            version = version,
            kind = kind,
            resolution = resolution,
            identity = identity,
            include = include,
            exempt = exempt,
            confidence = confidence,
            appliesToText = appliesToText,
            unresolvedReason = null,
            evidenceSpans = evidenceSpans,
            candidateScopes = readCandidateScopes(obj, userPromise)
        )
    }

    fun toJson(scope: EnforcementScope): JSONObject {
        val obj = JSONObject()
        obj.put("version", scope.version)
        obj.put("kind", scope.kind.name)
        obj.put("resolution", scope.resolution.name)
        obj.put("identity", scope.identity?.name)
        obj.put("include", scope.include.map { clauseToJson(it) }.let { JSONArray(it) })
        obj.put("exempt", scope.exempt.map { clauseToJson(it) }.let { JSONArray(it) })
        obj.put("confidence", scope.confidence)
        obj.put("appliesToText", scope.appliesToText)
        obj.put("unresolvedReason", scope.unresolvedReason?.name)
        obj.put("evidenceSpans", JSONArray(scope.evidenceSpans))
        if (scope.candidateScopes.isNotEmpty()) {
            obj.put("candidateScopes", JSONArray(scope.candidateScopes.map { toJson(it) }))
        }
        return obj
    }

    // --- clause parsing -----------------------------------------------------

    private fun readClauseList(obj: JSONObject, field: String): List<ScopeClause>? {
        val arr = obj.optJSONArray(field) ?: return emptyList()
        val out = ArrayList<ScopeClause>(arr.length())
        for (i in 0 until arr.length()) {
            val el = arr.optJSONObject(i) ?: return null
            val clause = readClause(el) ?: return null
            out += clause
        }
        return out
    }

    private fun readClause(obj: JSONObject): ScopeClause? {
        val id = obj.optString("id", "").ifBlank { return null }
        val host = parseEnum<ScopeHost>(obj.optString("host", "").trim())
            ?: ScopeHost.ANY_HOST
        val identity = parseEnum<ScopeIdentity>(obj.optString("identity", "").trim())
            ?: ScopeIdentity.ANY
        val surfacesRaw = obj.optJSONArray("surfaces")
        val surfaces = if (surfacesRaw == null || surfacesRaw.length() == 0) {
            setOf(ScopeSurfaceClass.ANY)
        } else {
            val set = LinkedHashSet<ScopeSurfaceClass>()
            for (i in 0 until surfacesRaw.length()) {
                val raw = surfacesRaw.optString(i, "").trim()
                val parsed = parseEnum<ScopeSurfaceClass>(raw) ?: return null
                set += parsed
            }
            set
        }
        val sites = jsonArrayToStringList(obj.optJSONArray("sites"))
        val packages = jsonArrayToStringList(obj.optJSONArray("packages"))
        val packageMatch = parseEnum<PackageMatchMode>(obj.optString("packageMatch", "").trim())
            ?: PackageMatchMode.CLASS_RESOLVED
        return ScopeClause(
            id = id,
            host = host,
            identity = identity,
            surfaces = surfaces,
            sites = sites,
            packages = packages,
            packageMatch = packageMatch
        )
    }

    private fun clauseToJson(clause: ScopeClause): JSONObject {
        val obj = JSONObject()
        obj.put("id", clause.id)
        obj.put("host", clause.host.name)
        obj.put("identity", clause.identity.name)
        obj.put("surfaces", JSONArray(clause.surfaces.map { it.name }))
        obj.put("sites", JSONArray(clause.sites))
        obj.put("packages", JSONArray(clause.packages))
        obj.put("packageMatch", clause.packageMatch.name)
        return obj
    }

    private fun readCandidateScopes(obj: JSONObject, userPromise: String?): List<EnforcementScope> {
        val arr = obj.optJSONArray("candidateScopes") ?: return emptyList()
        val out = ArrayList<EnforcementScope>(arr.length())
        for (i in 0 until arr.length()) {
            val el = arr.optJSONObject(i) ?: continue
            out += fromJsonObject(el, userPromise)
        }
        return out
    }

    // --- helpers ------------------------------------------------------------

    private fun readStringList(obj: JSONObject, field: String): List<String> =
        jsonArrayToStringList(obj.optJSONArray(field))

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    private fun allSpansAreVerbatim(spans: List<String>, userPromise: String): Boolean {
        if (spans.isEmpty()) return true
        val haystack = userPromise
        val hayLower = haystack.lowercase()
        return spans.all { span ->
            val trimmed = span.trim()
            trimmed.isNotEmpty() && (
                haystack.contains(trimmed) || hayLower.contains(trimmed.lowercase())
                )
        }
    }

    private inline fun <reified E : Enum<E>> parseEnum(raw: String): E? {
        if (raw.isBlank()) return null
        return try {
            enumValueOf<E>(raw.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
