package com.phonecodex.app.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShortFormQuotaGateTest {

    private lateinit var gate: ShortFormQuotaGate

    private val dailyPromise =
        "I want to watch at most 10 shorts today, but never adult/sexual shorts. " +
            "After 10 shorts, stop shorts for the rest of the day. " +
            "Long educational YouTube should still be allowed."

    private fun newPipeShort(title: String, totalClock: String = "00:59"): String =
        "Play Pause 00:15 / $totalClock $title Samay Raina Related videos"

    @Before
    fun setUp() {
        gate = ShortFormQuotaGate()
    }

    @Test
    fun newPipeActiveShortClock_isShortFormPlayer() {
        val detection = SurfaceDetector.detect(
            VideoPlatformRegistry.NEWPIPE,
            newPipeShort("Let’s talk now…")
        )
        assertEquals(SurfaceDetectionResult.SHORT_FORM_PLAYER, detection.surface)
        assertEquals(59, detection.durationSeconds)
    }

    @Test
    fun persistLedger_survivesNewGateInstance() {
        var storedCount = 0
        var storedSigs = emptySet<String>()
        val ledger = object : ShortFormQuotaGate.Ledger {
            override fun load(dayKey: String) = ShortFormQuotaGate.LedgerRead.Counted(storedCount, storedSigs)
            override fun save(dayKey: String, count: Int, signatures: Set<String>) {
                storedCount = count
                storedSigs = signatures
            }
        }
        val first = ShortFormQuotaGate(ledger = ledger)
        first.evaluate(
            VideoPlatformRegistry.YOUTUBE,
            "Like Dislike Comment Share Remix Subscribe clip one Home Shorts",
            limit = 10
        )
        val second = ShortFormQuotaGate(ledger = ledger)
        val d = second.evaluate(
            VideoPlatformRegistry.YOUTUBE,
            "Like Dislike Comment Share Remix Subscribe clip two Home Shorts",
            limit = 10
        )
        assertEquals(2, d.count)
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, d.action)
    }

    @Test
    fun firstShortUnderQuota_allowsAndIncrements() {
        val limit = ShortFormQuotaGate.parseDailyShortFormLimit(dailyPromise)!!
        assertEquals(10, limit)
        val d = gate.evaluate(
            packageName = VideoPlatformRegistry.NEWPIPE,
            screenText = newPipeShort("short one"),
            limit = limit
        )
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, d.action)
        assertEquals(1, d.count)
        assertTrue(d.counted)
        assertEquals("ALLOW", d.decisionName)
    }

    @Test
    fun tenthShort_stillAllows() {
        val limit = 10
        repeat(9) { i ->
            gate.evaluate(
                VideoPlatformRegistry.NEWPIPE,
                newPipeShort("clip $i", "00:${40 + (i % 10)}"),
                limit
            )
        }
        val tenth = gate.evaluate(
            VideoPlatformRegistry.NEWPIPE,
            newPipeShort("clip tenth", "00:58"),
            limit
        )
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, tenth.action)
        assertEquals(10, tenth.count)
        assertEquals("ALLOW", tenth.decisionName)
    }

    @Test
    fun eleventhShort_blocks() {
        val limit = 10
        repeat(10) { i ->
            gate.evaluate(
                VideoPlatformRegistry.YOUTUBE,
                "Hide player controls Shorts player swipe up for next 0:45 title$i",
                limit
            )
        }
        val eleventh = gate.evaluate(
            VideoPlatformRegistry.NEWPIPE,
            newPipeShort("overflow", "00:50"),
            limit
        )
        assertEquals(ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED, eleventh.action)
        assertEquals(10, eleventh.count)
        assertFalse(eleventh.counted)
        assertEquals("BLOCK", eleventh.decisionName)
    }

    @Test
    fun adultShort_blocksWithoutConsumingQuota() {
        val limit = 10
        gate.evaluate(
            VideoPlatformRegistry.NEWPIPE,
            newPipeShort("normal short"),
            limit
        )
        val adult = gate.evaluate(
            VideoPlatformRegistry.NEWPIPE,
            "Play Pause 00:10 / 00:45 porn xxx adult video",
            limit
        )
        assertEquals(ShortFormQuotaAction.BLOCK_ADULT, adult.action)
        assertEquals(1, adult.count)
        assertFalse(adult.counted)
    }

    @Test
    fun chromeSearchShortVideos_notCounted() {
        val text =
            "Google Search Videos Short videos Images Shopping All filters " +
                "About 1,200,000 results"
        val d = gate.evaluate(VideoPlatformRegistry.CHROME, text, limit = 10)
        assertEquals(ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY, d.action)
        assertEquals(SurfaceDetectionResult.SEARCH, d.surface)
        assertEquals(0, d.count)
        assertFalse(d.counted)
    }

    @Test
    fun youtubeHomeShortsShelf_notCounted() {
        val d = gate.evaluate(
            VideoPlatformRegistry.YOUTUBE,
            "Home Shorts Subscriptions Library Shorts shelf recommended",
            limit = 10
        )
        assertEquals(ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY, d.action)
        assertEquals(SurfaceDetectionResult.HOME, d.surface)
        assertFalse(d.counted)
    }

    @Test
    fun longEducationalLecture_allowsWithoutCounting() {
        val d = gate.evaluate(
            packageName = VideoPlatformRegistry.YOUTUBE,
            screenText =
                "Hide player controls Time duration 1 hour 46 minutes " +
                    "Neso Academy OS lecture Subscribe Share",
            limit = 10,
            allowLongEducational = true
        )
        assertEquals(ShortFormQuotaAction.ALLOW_LONG_EDUCATIONAL, d.action)
        assertEquals(SurfaceDetectionResult.LONG_FORM_PLAYER, d.surface)
        assertEquals(0, d.count)
        assertFalse(d.counted)
    }

    @Test
    fun instagramRecognizedAsVideoPlatform() {
        assertTrue(VideoPlatformRegistry.isKnownVideoPlatform(VideoPlatformRegistry.INSTAGRAM))
        assertTrue(VideoPlatformRegistry.participatesInShortFormQuota(VideoPlatformRegistry.INSTAGRAM))
        assertEquals(
            VideoPlatformKind.INSTAGRAM,
            VideoPlatformRegistry.platformKind(VideoPlatformRegistry.INSTAGRAM)
        )
    }

    @Test
    fun unknownClone_isWarnCandidate() {
        assertTrue(
            VideoPlatformRegistry.isUnknownVideoCloneCandidate(
                "com.sketchy.tubefork",
                "Play video Subscribe Channel 12:04"
            )
        )
        assertFalse(
            VideoPlatformRegistry.isUnknownVideoCloneCandidate(
                VideoPlatformRegistry.YOUTUBE,
                "Home Shorts"
            )
        )
    }

    @Test
    fun aiAllowCannotOverrideQuotaBlock() {
        assertEquals(
            "BLOCK",
            DeterministicDecisionPriority.finalDecision(
                deterministicDecision = "BLOCK",
                aiDecision = "ALLOW",
                isSafeOrEmergencyApp = false
            )
        )
        assertFalse(DeterministicDecisionPriority.aiMayRun("BLOCK"))
    }

    @Test
    fun durationFlicker_sameTitle_doesNotDoubleCount() {
        val first = gate.evaluate(
            VideoPlatformRegistry.NEWPIPE,
            "Play Pause 00:10 / 00:50 same short title",
            limit = 10
        )
        val second = gate.evaluate(
            VideoPlatformRegistry.NEWPIPE,
            "Play Pause 00:15 / 00:50 same short title",
            limit = 10
        )
        assertEquals(ShortFormQuotaAction.ALLOW_AND_COUNT, first.action)
        assertEquals(1, first.count)
        assertTrue(
            second.action == ShortFormQuotaAction.ALLOW_DUPLICATE || second.count == 1
        )
        assertEquals(1, gate.currentCount())
    }

    @Test
    fun dayKey_usesInjectableClock() {
        val morning = 1_725_000_000_000L // fixed millis
        val gateTimed = ShortFormQuotaGate(
            zoneId = java.time.ZoneId.of("UTC"),
            clock = { morning }
        )
        val keyA = gateTimed.todayKey(morning)
        val nextDay = morning + 86_400_000L
        val keyB = gateTimed.todayKey(nextDay)
        assertTrue(keyA != keyB)
        assertTrue(keyA.endsWith(":short_form_video"))
    }

    @Test
    fun dayKey_followsDeviceTimezoneChange_forTheSameInstant() {
        var deviceZone = java.time.ZoneId.of("UTC")
        val instant = java.time.Instant.parse("2026-09-27T20:00:00Z").toEpochMilli()
        val moving = ShortFormQuotaGate(clock = { instant }, zone = { deviceZone })
        val shortA = "Hide player controls Shorts player swipe up for next 0:45 clipA"
        val shortB = "Hide player controls Shorts player swipe up for next 0:30 clipB"

        assertEquals("2026-09-27:short_form_video", moving.todayKey(instant))
        assertEquals(1, moving.evaluate(VideoPlatformRegistry.YOUTUBE, shortA, 10).count)

        deviceZone = java.time.ZoneId.of("Asia/Kolkata")
        assertEquals("2026-09-28:short_form_video", moving.todayKey(instant))
        val afterMove = moving.evaluate(VideoPlatformRegistry.YOUTUBE, shortB, 10)
        assertEquals("2026-09-28:short_form_video", afterMove.dayKey)
        assertEquals(1, afterMove.count)
        assertEquals(1, moving.currentCount("2026-09-27:short_form_video"))
    }

    @Test
    fun defaultZone_isReReadFromTheDeviceOnEveryCall() {
        val original = java.util.TimeZone.getDefault()
        val instant = java.time.Instant.parse("2026-09-27T20:00:00Z").toEpochMilli()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val live = ShortFormQuotaGate()
            assertEquals("2026-09-27:short_form_video", live.todayKey(instant))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals("2026-09-28:short_form_video", live.todayKey(instant))
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun ledgerThatThrows_failsClosedOnActiveShortOnly() {
        val broken = object : ShortFormQuotaGate.Ledger {
            override fun load(dayKey: String): ShortFormQuotaGate.LedgerRead = throw ClassCastException("bad")
            override fun save(dayKey: String, count: Int, signatures: Set<String>) =
                error("unreadable state must never be overwritten")
        }
        val g = ShortFormQuotaGate(ledger = broken)
        val short = g.evaluate(
            VideoPlatformRegistry.YOUTUBE,
            "Hide player controls Shorts player swipe up for next 0:45 clipA",
            10
        )
        assertEquals(ShortFormQuotaAction.BLOCK_QUOTA_UNREADABLE, short.action)
        assertEquals(null, short.count)
        val home = g.evaluate(VideoPlatformRegistry.YOUTUBE, "Home Shorts Subscriptions Library Shorts shelf recommended", 10)
        assertEquals(ShortFormQuotaAction.SKIP_NOT_SHORT_PLAY, home.action)
    }

    @Test
    fun aiBlockCannotOverrideSafeAppAllow() {
        assertEquals(
            "ALLOW",
            DeterministicDecisionPriority.finalDecision(
                deterministicDecision = "BLOCK",
                aiDecision = "BLOCK",
                isSafeOrEmergencyApp = true
            )
        )
    }

    @Test
    fun crossAppQuotaSharesSameDailyCounter() {
        val limit = 2
        gate.evaluate(VideoPlatformRegistry.NEWPIPE, newPipeShort("a", "00:40"), limit)
        gate.evaluate(
            VideoPlatformRegistry.INSTAGRAM,
            "Reel player Play audio Liked Send swipe up clipB",
            limit
        )
        val third = gate.evaluate(
            VideoPlatformRegistry.YOUTUBE,
            "Hide player controls Shorts player swipe up for next 0:30 clipC",
            limit
        )
        assertEquals(ShortFormQuotaAction.BLOCK_QUOTA_EXCEEDED, third.action)
        assertEquals(2, third.count)
    }

    @Test
    fun parseAtMostTenShortsToday() {
        assertTrue(ShortFormQuotaGate.isDailyShortFormPromise(dailyPromise))
        assertEquals(10, ShortFormQuotaGate.parseDailyShortFormLimit(dailyPromise))
        assertTrue(ShortFormQuotaGate.allowsLongEducational(dailyPromise))
    }

    @Test
    fun parseMessyShotsAndSortsQuota() {
        val spoken =
            "allow me to watch only up to Max 10 shots and remind me how much Sort is left " +
                "to watch for next 30 minutes"
        assertEquals(10, ShortFormQuotaGate.parseDailyShortFormLimit(spoken))
        assertEquals(10, ShortFormLanguage.parseLimit("up to max 10 shots"))
        assertEquals(10, ShortFormLanguage.parseLimit("allow me upto 10 shorts"))
        assertEquals(10, ShortFormLanguage.parseLimit("only 10 sorts"))
        assertEquals(10, ShortFormQuotaGate.parseDailyShortFormLimit("allow only 10 shorts video"))
    }
}

