package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.scope.ScopeAppliesToCopy
import com.phonecodex.app.domain.scope.ScopeCanonicalizer
import com.phonecodex.app.domain.scope.ScopeKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language contract: fixtures are written by
 * `node backend/smoke_scope_contract_v1.js --write-fixtures`.
 * Backend (JS) and Android (Kotlin) must canonicalize the same model scope to the
 * same typed scope, label options identically, and agree on Start.
 */
class TypedScopeContractFixtureTest {

    private data class Fixture(val promise: String, val modelScope: String, val responseJson: String) {
        val response: JSONObject get() = JSONObject(responseJson)
    }

    private fun load(name: String): Fixture {
        val stream = javaClass.classLoader!!.getResourceAsStream("scope-contract/$name.json")
        assertNotNull("missing fixture $name; run the backend smoke with --write-fixtures", stream)
        val root = JSONObject(stream!!.bufferedReader().use { it.readText() })
        return Fixture(
            promise = root.getString("promise"),
            modelScope = root.getJSONObject("modelScope").toString(),
            responseJson = root.getJSONObject("response").toString()
        )
    }

    private fun draft(f: Fixture): FocusPromise {
        val parsed = CompiledPromiseResponseParser.parse(f.responseJson)
        assertNotNull("parse failed", parsed)
        return PromiseClarificationLaw.apply(CompiledPromiseMapper.toFocusPromise(parsed!!))
    }

    private fun assertSavedKind(name: String, expected: ScopeKind) {
        val f = load(name)
        val d = draft(f)
        assertTrue("$name: v1 contract", d.scopeContractV1)
        assertFalse("$name: should start", d.needsClarification)
        val bound = ConfirmedPromiseBinder.bind(d)
        assertEquals("$name: saved kind", expected, bound.enforcementScope?.kind)

        val backendScope = f.response.getJSONObject("scope").toString()
        val kotlinFromBackend = ScopeCanonicalizer.fromCompilerJson(backendScope, f.promise)
        assertEquals("$name: Kotlin re-canonicalization is a no-op", kotlinFromBackend, bound.enforcementScope)
        assertEquals(
            "$name: appliesTo copy is canonical",
            ScopeAppliesToCopy.forScope(bound.enforcementScope),
            d.userFacingAppliesTo
        )
    }

    @Test
    fun primaryScopes_matchAcrossLanguages() {
        for ((name, kind) in listOf(
            "browser_video_primary" to ScopeKind.BROWSER_VIDEO,
            "all_video_primary" to ScopeKind.VIDEO_CLASS_ANY_HOST
        )) {
            assertSavedKind(name, kind)
            val f = load(name)
            val kotlinFromModel = ScopeCanonicalizer.fromCompilerJson(f.modelScope, f.promise)
            val jsCanonical = ScopeCanonicalizer.fromCompilerJson(f.response.getJSONObject("scope").toString(), f.promise)
            assertEquals("$name: same model scope -> same canonical scope", jsCanonical, kotlinFromModel)
        }
    }

    @Test
    fun picks_saveSnapshotScope() {
        assertSavedKind("pick_a_browser_video", ScopeKind.BROWSER_VIDEO)
        assertSavedKind("pick_b_all_video", ScopeKind.VIDEO_CLASS_ANY_HOST)
        assertSavedKind("pick_c_youtube_app", ScopeKind.APP_ONLY)
    }

    /** Written by `node backend/smoke_v09_clarification_scope.js --write-fixtures` (real compilePromise round-trips). */
    @Test
    fun v09DeterministicPicks_saveBridgedScope() {
        assertSavedKind("v09_google_pick_b_bridged", ScopeKind.VIDEO_CLASS_ANY_HOST)
        assertSavedKind("v09_shorts_pick_a_all_surfaces", ScopeKind.SHORT_FORM_ANY_HOST)
        assertSavedKind("v09_shorts_pick_b_youtube_shorts", ScopeKind.APP_SHORT_FORM_ONLY)
    }

    @Test
    fun v09CompoundFailClosed_cannotStart() {
        val d = draft(load("v09_compound_fail_closed"))
        assertTrue(d.needsClarification)
        assertEquals(false, d.canStartCommitment)
        assertNull(ConfirmedPromiseBinder.bind(d).enforcementScope)
        assertEquals(listOf("A", "B", "C"), d.clarificationOptions.map { it.id })
    }

    @Test
    fun turn1OptionLabels_matchKotlinLabels() {
        val f = load("turn1_options")
        val d = draft(f)
        assertTrue(d.needsClarification)
        val options = f.response.getJSONArray("clarificationOptions")
        assertEquals(3, options.length())
        for (i in 0 until options.length()) {
            val o = options.getJSONObject(i)
            val scope = ScopeCanonicalizer.fromCompilerJson(o.getJSONObject("scope").toString(), f.promise)
            assertEquals("option ${o.getString("id")}", o.getString("label"), ScopeAppliesToCopy.optionLabel(scope))
            assertEquals(o.getString("label"), d.clarificationOptions[i].label)
        }
    }

    @Test
    fun mismatchAndUnresolved_cannotStart() {
        for (name in listOf("typed_carry_mismatch", "unresolved_concrete_clocks", "turn1_options")) {
            val d = draft(load(name))
            assertTrue("$name: needs clarification", d.needsClarification)
            assertEquals("$name: canStart", false, d.canStartCommitment)
            assertNull("$name: nothing saved", ConfirmedPromiseBinder.bind(d).enforcementScope)
        }
    }
}
