package com.phonecodex.app.data

import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.SessionEnforcementCopy
import com.phonecodex.app.domain.enforcement.ShortFormQuotaAction
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate.LedgerRead
import com.phonecodex.app.domain.enforcement.VideoPlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The live SharedPreferences ledger: unreadable state is reported, never read as zero. */
class ShortFormQuotaStoreTest {

    private val zone = ZoneId.of("UTC")
    private val now = 1_790_000_000_000L
    private val dayKey = ShortFormQuotaGate(zoneId = zone).todayKey(now)
    private val countKey = "count.$dayKey"
    private val sigKey = "sig.$dayKey"

    private val yt = VideoPlatformRegistry.YOUTUBE
    private val shortPlay = "Hide player controls Shorts player swipe up for next 0:45 clip one"
    private val otherShort = "Hide player controls Shorts player swipe up for next 0:30 clip two"
    private val ytHome = "Home Shorts Subscriptions Library Shorts shelf recommended"
    private val lecture =
        "Hide player controls Time duration 1 hour 46 minutes Neso Academy OS lecture Subscribe Share"
    private val adultShort = "Play Pause 00:10 / 00:45 porn xxx adult video"

    private fun gate(prefs: InMemorySharedPreferences) =
        ShortFormQuotaGate(zoneId = zone, clock = { now }, ledger = ShortFormQuotaStore(prefs))

    private fun prefs(write: (android.content.SharedPreferences.Editor) -> Unit) =
        InMemorySharedPreferences().also { p -> p.edit().also(write).apply() }

    private fun assertFailClosedOnShortPlay(prefs: InMemorySharedPreferences, label: String) {
        val before = prefs.snapshot()
        val d = gate(prefs).evaluate(yt, shortPlay, limit = 10)
        assertEquals(label, ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE, d.action)
        assertEquals(label, "BLOCK", d.decisionName)
        assertNull("$label must not show a count", d.count)
        assertFalse(label, d.counted)
        assertTrue(label, ShortFormQuotaGate.formatLog(d, yt).contains("count=unreadable/10"))
        assertEquals("$label must not overwrite the record", before, prefs.snapshot())
    }

    @Test
    fun emptyStore_isARealZero_andCountsRoundTrip() {
        val p = InMemorySharedPreferences()
        assertEquals(LedgerRead.Counted(0, emptySet()), ShortFormQuotaStore(p).load(dayKey))
        assertEquals(1, gate(p).evaluate(yt, shortPlay, limit = 10).count)
        val second = gate(p).evaluate(yt, otherShort, limit = 10)
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, second.action)
        assertEquals(2, second.count)
        val stored = ShortFormQuotaStore(p).load(dayKey) as LedgerRead.Counted
        assertEquals(2, stored.count)
        assertEquals(2, stored.signatures.size)
    }

    @Test
    fun wrongTypedCount_failsClosedOnlyOnActiveShortPlay() {
        val p = prefs { it.putString(countKey, "3").putString(sigKey, "a") }
        assertTrue(ShortFormQuotaStore(p).load(dayKey) is LedgerRead.Unreadable)
        assertFailClosedOnShortPlay(p, "count as String")
    }

    @Test
    fun wrongTypedSignatures_failClosedOnlyOnActiveShortPlay() {
        val p = prefs { it.putInt(countKey, 1).putInt(sigKey, 5) }
        assertTrue(ShortFormQuotaStore(p).load(dayKey) is LedgerRead.Unreadable)
        assertFailClosedOnShortPlay(p, "signatures as Int")
    }

    @Test
    fun malformedState_failsClosedOnlyOnActiveShortPlay() {
        val cases = listOf<Pair<String, (android.content.SharedPreferences.Editor) -> Unit>>(
            "signatures without count" to { e -> e.putString(sigKey, "a") },
            "count without signatures" to { e -> e.putInt(countKey, 2) },
            "negative count" to { e -> e.putInt(countKey, -1).putString(sigKey, "") },
            "more signatures than count" to { e -> e.putInt(countKey, 1).putString(sigKey, "a\u001Eb") },
            "positive count with empty signatures" to { e -> e.putInt(countKey, 3).putString(sigKey, "") },
            "partial signatures" to { e -> e.putInt(countKey, 3).putString(sigKey, "a\u001Eb") },
            "blank signature part" to { e -> e.putInt(countKey, 2).putString(sigKey, "a\u001E") },
            "whitespace signature part" to { e -> e.putInt(countKey, 2).putString(sigKey, "a\u001E ") },
            "duplicate signatures" to { e -> e.putInt(countKey, 2).putString(sigKey, "a\u001Ea") }
        )
        for ((label, write) in cases) {
            val p = prefs(write)
            assertTrue(label, ShortFormQuotaStore(p).load(dayKey) is LedgerRead.Unreadable)
            assertFailClosedOnShortPlay(p, label)
        }
    }

    @Test
    fun zeroCountWithEmptySignatures_isARealZero() {
        val p = prefs { it.putInt(countKey, 0).putString(sigKey, "") }
        assertEquals(LedgerRead.Counted(0, emptySet()), ShortFormQuotaStore(p).load(dayKey))
    }

    @Test
    fun titleContainingTheSeparator_stillRoundTripsAsOneSignature() {
        val p = InMemorySharedPreferences()
        val d = gate(p).evaluate(yt, "Hide player controls Shorts player swipe up for next 0:45 clip\u001Eone", limit = 10)
        assertEquals(1, d.count)
        val stored = ShortFormQuotaStore(p).load(dayKey)
        assertEquals(LedgerRead.Counted::class, stored::class)
        assertEquals(1, (stored as LedgerRead.Counted).signatures.size)
    }

    @Test
    fun unreadableState_isReadAtMostOncePerRetryWindow_thenRecovers() {
        val p = prefs { it.putInt(countKey, 3).putString(sigKey, "") }
        var loads = 0
        val store = ShortFormQuotaStore(p)
        val counting = object : ShortFormQuotaGate.Ledger {
            override fun load(dayKey: String): LedgerRead = store.load(dayKey).also { loads++ }
            override fun save(dayKey: String, count: Int, signatures: Set<String>) =
                store.save(dayKey, count, signatures)
        }
        var clock = now
        val g = ShortFormQuotaGate(zoneId = zone, clock = { clock }, ledger = counting)

        repeat(50) { i ->
            clock = now + i * 500L
            assertEquals(ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE, g.evaluate(yt, shortPlay, limit = 10).action)
            g.evaluate(yt, ytHome, limit = 10)
        }
        assertEquals("one read inside the retry window", 1, loads)

        clock = now + ShortFormQuotaGate.UNREADABLE_RETRY_MS
        assertEquals(ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE, g.evaluate(yt, shortPlay, limit = 10).action)
        assertEquals("still broken: one more read after the window", 2, loads)

        p.edit().putInt(countKey, 3).putString(sigKey, "a\u001Eb\u001Ec").apply()
        clock += ShortFormQuotaGate.UNREADABLE_RETRY_MS - 1
        assertEquals(ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE, g.evaluate(yt, shortPlay, limit = 10).action)
        assertEquals(2, loads)

        clock += 1
        val recovered = g.evaluate(yt, otherShort, limit = 10)
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, recovered.action)
        assertEquals(4, recovered.count)
        assertEquals(3, loads)

        repeat(20) { g.evaluate(yt, ytHome, limit = 10) }
        assertEquals("a readable day is hydrated once", 3, loads)
        assertEquals(LedgerRead.Counted::class, store.load(dayKey)::class)
    }

    @Test
    fun clockMovingBackwards_retriesInsteadOfWaitingOutTheWindow() {
        val p = prefs { it.putString(countKey, "3") }
        var clock = now
        val g = ShortFormQuotaGate(zoneId = zone, clock = { clock }, ledger = ShortFormQuotaStore(p))
        assertEquals(ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE, g.evaluate(yt, shortPlay, limit = 10).action)
        p.edit().putInt(countKey, 0).putString(sigKey, "").apply()
        clock = now - 1_000L
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, g.evaluate(yt, shortPlay, limit = 10).action)
    }

    @Test
    fun unreadableState_leavesPassiveUnrelatedLongEducationalAndAdultRulesUnchanged() {
        val p = prefs { it.putString(countKey, "garbage") }
        val g = gate(p)

        val home = g.evaluate(yt, ytHome, limit = 10)
        assertEquals(ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY, home.action)
        assertNull(home.count)

        val whatsapp = g.evaluate("com.whatsapp", "WhatsApp Chats Updates Calls", limit = 10)
        assertEquals(ShortFormQuotaAction.NONE, whatsapp.action)
        assertFalse(whatsapp.shouldApply)

        val edu = g.evaluate(yt, lecture, limit = 10, allowLongEducational = true)
        assertEquals(ShortFormQuotaAction.ALLOW_LONG_EDUCATIONAL, edu.action)
        assertNull(edu.count)

        val adult = g.evaluate(VideoPlatformRegistry.NEWPIPE, adultShort, limit = 10)
        assertEquals(ShortFormQuotaAction.BLOCK_ADULT, adult.action)
        assertFalse(adult.counted)
    }

    @Test
    fun repairedRecord_recoversAfterTheRetryWindow_withTheRealCount() {
        val p = prefs { it.putString(countKey, "3") }
        var clock = now
        val g = ShortFormQuotaGate(zoneId = zone, clock = { clock }, ledger = ShortFormQuotaStore(p))
        assertEquals(ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE, g.evaluate(yt, shortPlay, limit = 10).action)
        p.edit().putInt(countKey, 3).putString(sigKey, "a\u001Eb\u001Ec").apply()
        clock += ShortFormQuotaGate.UNREADABLE_RETRY_MS
        val next = g.evaluate(yt, otherShort, limit = 10)
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, next.action)
        assertEquals(4, next.count)
    }

    @Test
    fun unreadableCopy_isHonest_andShowsNoNumber() {
        val copy = SessionEnforcementCopy.overlayEvidence(
            EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE,
            fallback = "3 shorts left of 10.",
            hardBlock = true
        )
        assertEquals(SessionEnforcementCopy.QUOTA_STATE_UNREADABLE_BLOCK, copy)
        assertFalse(copy.any { it.isDigit() })
        assertTrue(SessionEnforcementCopy.hasDedicatedOverlayCopy(EnforcementReasonCodes.QUOTA_BLOCK_STATE_UNREADABLE))
    }
}
