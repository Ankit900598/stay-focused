package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.scope.EnforcementScope
import com.phonecodex.app.domain.scope.LegacyScopeAdapter
import com.phonecodex.app.domain.scope.ScopeAppliesToCopy
import com.phonecodex.app.domain.scope.ScopeGate
import com.phonecodex.app.domain.scope.ScopeKind
import com.phonecodex.app.domain.scope.ScopeSurfaceClass
import com.phonecodex.app.domain.scope.StoredScopeCodec
import com.phonecodex.app.domain.model.StudyWorldSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 scope contract end to end on Android:
 * backend JSON -> parser -> mapper -> clarification law -> binder -> persisted string.
 */
class TypedScopePipelineTest {

    private val promise = "No Google video shorter than 30 min for 1 hour"

    private fun clause(surfaces: List<String>, host: String = "BROWSER", identity: String = "VIDEO_ANY") =
        JSONObject()
            .put("id", "i1")
            .put("host", host)
            .put("identity", identity)
            .put("surfaces", JSONArray(surfaces))
            .put("packages", JSONArray(listOf("com.android.chrome")))
            .put("packageMatch", "PINNED")

    private fun scopeJson(
        kind: String,
        identity: String = "VIDEO_ANY",
        clauses: List<JSONObject> = listOf(clause(listOf("ANY"))),
        appliesToText: String? = "Chrome in .",
        evidence: List<String> = listOf("Google video")
    ): JSONObject = JSONObject()
        .put("version", 1)
        .put("kind", kind)
        .put("identity", identity)
        .put("include", JSONArray(clauses))
        .put("exempt", JSONArray())
        .put("confidence", 0.9)
        .put("appliesToText", appliesToText)
        .put("evidenceSpans", JSONArray(evidence))

    private fun body(
        scope: JSONObject?,
        contract: String? = "v1",
        options: JSONArray? = null,
        canStart: Boolean = true,
        modelAppliesTo: String = "Chrome in ."
    ): String {
        val obj = JSONObject()
            .put("rawText", promise)
            .put("sessionDurationMinutes", 60)
            .put("strictness", "STRICT")
            .put(
                "contentRules",
                JSONArray().put(
                    JSONObject()
                        .put("action", "BLOCK")
                        .put("operator", "lt")
                        .put("value", 30)
                        .put("unit", "minutes")
                        .put("contentType", "video")
                        .put("surface", "any")
                )
            )
            .put("canStartCommitment", canStart)
            .put("userFacingConfirmation", JSONObject().put("appliesTo", modelAppliesTo))
        if (contract != null) obj.put("scopeContract", contract)
        if (scope != null) obj.put("scope", scope)
        if (options != null) obj.put("clarificationOptions", options)
        return obj.toString()
    }

    private fun draft(json: String): FocusPromise {
        val parsed = CompiledPromiseResponseParser.parse(json)
        assertNotNull("parse failed", parsed)
        return PromiseClarificationLaw.apply(CompiledPromiseMapper.toFocusPromise(parsed!!))
    }

    @Test
    fun validBrowserVideoScope_isCanonicalizedSavedAndPersisted() {
        val d = draft(body(scopeJson("BROWSER_VIDEO")))
        val scope = d.enforcementScope
        assertNotNull(scope)
        assertTrue(d.scopeContractV1)
        assertEquals(ScopeKind.BROWSER_VIDEO, scope!!.kind)
        val clause = scope.include.single()
        assertEquals(setOf(ScopeSurfaceClass.PLAYER_ANY), clause.surfaces)
        assertTrue("model package list must be discarded", clause.packages.isEmpty())
        assertFalse(d.needsClarification)

        val bound = ConfirmedPromiseBinder.bind(d)
        assertEquals(scope, bound.enforcementScope)
        val stored = StoredScopeCodec.encode(bound.enforcementScope)
        val decoded = StoredScopeCodec.decode(stored)
        assertFalse(decoded.corrupt)
        assertEquals(scope, decoded.scope)
    }

    @Test
    fun unknownMissingOrMalformedV1Scope_cannotStart_evenIfServerSaysCanStart() {
        val bad = listOf(
            "missing" to body(scope = null),
            "unknown kind" to body(scopeJson("CHROME_ONLY")),
            "bad version" to body(scopeJson("BROWSER_VIDEO").put("version", 2)),
            "not verbatim evidence" to body(scopeJson("BROWSER_VIDEO", evidence = listOf("all browsers"))),
            "malformed surface" to body(scopeJson("BROWSER_VIDEO", clauses = listOf(clause(listOf("EVERYWHERE"))))),
            "generic browser-only video" to body(scopeJson("BROWSER_ONLY")),
            "unresolved" to body(scopeJson("UNRESOLVED").put("unresolvedReason", "HOST_AMBIGUOUS"))
        )
        for ((name, json) in bad) {
            val d = draft(json)
            assertTrue("$name: typed scope must block start", d.typedScopeBlocksStart)
            assertTrue("$name: needs clarification", d.needsClarification)
            assertEquals("$name: canStart", false, d.canStartCommitment)
            assertNull("$name: nothing saved", ConfirmedPromiseBinder.bind(d).enforcementScope)
        }
    }

    @Test
    fun concreteClocksCannotClearRealScopeAmbiguity() {
        val d = CompiledPromiseMapper.toFocusPromise(
            CompiledPromiseResponseParser.parse(
                body(scopeJson("UNRESOLVED").put("unresolvedReason", "HOST_AMBIGUOUS"))
            )!!
        )
        assertTrue(PromiseClarificationLaw.isConcreteClockPolicy(d))
        val cleared = PromiseClarificationLaw.clearGhostClarification(d.copy(clarificationRequired = true))
        assertTrue(cleared.clarificationRequired)
        val applied = PromiseClarificationLaw.apply(d.copy(canStartCommitment = true))
        assertEquals(false, applied.canStartCommitment)
    }

    @Test
    fun legacyContract_keepsLegacyAdapterPath() {
        for (contract in listOf(null, "legacy")) {
            val d = draft(body(scopeJson("BROWSER_VIDEO"), contract = contract))
            assertFalse(d.scopeContractV1)
            assertNull(d.enforcementScope)
            assertFalse(d.typedScopeBlocksStart)
            val bound = ConfirmedPromiseBinder.bind(d)
            assertNull(bound.enforcementScope)
            assertNull(StoredScopeCodec.encode(bound.enforcementScope))
            val gate = ScopeGate.evaluate("com.android.chrome", "Search", d.rawText, bound)
            assertEquals(LegacyScopeAdapter.from(bound).kind, gate.scopeKind)
        }
    }

    @Test
    fun appliesToCopy_isStableAcrossCompiles_andIgnoresModelProse() {
        val a = draft(body(scopeJson("BROWSER_VIDEO", appliesToText = "Chrome in ."), modelAppliesTo = "Chrome in ."))
        val b = draft(
            body(
                scopeJson("BROWSER_VIDEO", appliesToText = "all of your browsers I guess"),
                modelAppliesTo = "videos in com.android.chrome"
            )
        )
        assertEquals(a.userFacingAppliesTo, b.userFacingAppliesTo)
        val copy = a.userFacingAppliesTo.orEmpty()
        assertEquals(ScopeAppliesToCopy.forScope(a.enforcementScope), copy)
        assertFalse(copy, copy.contains(" in ."))
        assertFalse(copy, ScopeAppliesToCopy.containsPackageId(copy))
        assertFalse(copy, copy.contains("Chrome"))
    }

    @Test
    fun v1OptionLabels_comeFromStructuredScope() {
        val options = JSONArray()
            .put(
                JSONObject().put("id", "A").put("label", "Chrome only")
                    .put("description", "d").put("scope", scopeJson("BROWSER_VIDEO"))
            )
            .put(
                JSONObject().put("id", "B").put("label", "Everything")
                    .put("description", "d").put("scope", scopeJson("VIDEO_CLASS_ANY_HOST", clauses = listOf(clause(listOf("ANY"), host = "ANY_HOST"))))
            )
        val d = draft(
            body(
                scopeJson("UNRESOLVED").put("unresolvedReason", "HOST_AMBIGUOUS"),
                options = options,
                canStart = false
            )
        )
        assertEquals(listOf("Video in any browser", "All video apps and browsers"), d.clarificationOptions.map { it.label })
        assertTrue(d.needsClarification)
    }

    @Test
    fun corruptPersistedScope_isDroppedNotBroadenedOrEmptied() {
        val corrupt = listOf(
            "{not json",
            "{}",
            JSONObject().put("version", 1).put("kind", "UNRESOLVED").toString(),
            JSONObject().put("version", 1).put("kind", "BROWSER_VIDEO").put("include", JSONArray()).toString(),
            JSONObject().put("version", 7).put("kind", "BROWSER_VIDEO").toString()
        )
        for (raw in corrupt) {
            val decoded = StoredScopeCodec.decode(raw)
            assertTrue(raw, decoded.corrupt)
            assertNull(raw, decoded.scope)
        }
        val none = StoredScopeCodec.decode(null)
        assertFalse(none.corrupt)
        assertNull(none.scope)
    }

    @Test
    fun legacyAdaptedScope_isNeverPersistedAsTyped() {
        val legacy = LegacyScopeAdapter.from(
            StudyWorldSettings(durationMinutes = 60, lockAttemptThreshold = 3, blockAttemptCooldownMinutes = 2)
        )
        assertNull(StoredScopeCodec.encode(legacy))
        assertNull(StoredScopeCodec.encode(EnforcementScope.unresolved(com.phonecodex.app.domain.scope.UnresolvedReason.HOST_AMBIGUOUS)))
    }
}
