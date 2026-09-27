package com.phonecodex.app.domain.promise

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationChoiceCarryTest {

    private val promise = "do not allow videos shorter than 30 minutes for next 1 hour"
    private val options = """
        [
          {"id":"A","label":"All video apps and browsers","internalPolicyPreview":{"scopeKind":"all_video_apps"}},
          {"id":"B","label":"Browser video only","internalPolicyPreview":{"scopeKind":"chrome_browser"}},
          {"id":"C","label":"YouTube app only","internalPolicyPreview":{"scopeKind":"youtube_app_only"}}
        ]
    """.trimIndent()

    @Test
    fun pick_sendsTheShownOptionAndTheWholeSnapshot() {
        val snapshot = ClarificationChoiceCarry.snapshotFrom(promise, options)
        val payload = ClarificationChoiceCarry.payloadFor("A", promise, snapshot)
        assertNotNull(payload.selectedOptionJson)
        val carried = JSONObject(payload.selectedOptionJson!!)
        assertEquals("All video apps and browsers", carried.getString("label"))
        assertEquals(options, payload.snapshotJson)
        assertEquals(promise, payload.snapshotPromiseText)
    }

    @Test
    fun optionOrder_doesNotMatter_idIsTheKey() {
        val reordered = """[{"id":"C","label":"YouTube app only"},{"id":"a","label":"All video apps and browsers"}]"""
        val found = ClarificationChoiceCarry.findUniqueOption(reordered, "A")
        assertEquals("All video apps and browsers", JSONObject(found!!).getString("label"))
    }

    @Test
    fun duplicateIds_neverCarryAnOption() {
        val dup = """[{"id":"A","label":"All video apps"},{"id":"A","label":"Browser video only"}]"""
        assertNull(ClarificationChoiceCarry.findUniqueOption(dup, "A"))
    }

    @Test
    fun missingIdOrMalformedJson_carriesNothing() {
        assertNull(ClarificationChoiceCarry.findUniqueOption(options, "D"))
        assertNull(ClarificationChoiceCarry.findUniqueOption("not json", "A"))
        assertNull(ClarificationChoiceCarry.snapshotFrom(promise, "[]"))
        assertNull(ClarificationChoiceCarry.snapshotFrom(promise, null))
    }

    @Test
    fun editedPromise_dropsTheOldSnapshotSoTheBackendFailsClosed() {
        val snapshot = ClarificationChoiceCarry.snapshotFrom(promise, options)
        val payload = ClarificationChoiceCarry.payloadFor(
            "A",
            "do not allow videos shorter than 45 minutes for next 2 hours",
            snapshot
        )
        assertEquals("A", payload.selectedOptionId)
        assertNull(payload.selectedOptionJson)
        assertNull(payload.snapshotJson)
        assertNull(payload.snapshotPromiseText)
    }

    @Test
    fun whitespaceOrCaseOnlyDifference_isTheSamePromise() {
        val snapshot = ClarificationChoiceCarry.snapshotFrom(promise, options)
        val payload = ClarificationChoiceCarry.payloadFor("B", "  ${promise.uppercase()} ", snapshot)
        assertNotNull(payload.snapshotJson)
        assertEquals("Browser video only", JSONObject(payload.selectedOptionJson!!).getString("label"))
    }

    @Test
    fun staleResponse_afterNewerRequest_isDiscardedByTheGate() {
        val gate = PromiseUnderstandingRequestGate()
        val firstPick = gate.begin()
        val newerRequest = gate.begin()
        assertFalse("old pick response must not apply", gate.isCurrent(firstPick))
        assertTrue(gate.isCurrent(newerRequest))
        gate.invalidate()
        assertFalse("editing the promise invalidates in-flight picks", gate.isCurrent(newerRequest))
    }
}
