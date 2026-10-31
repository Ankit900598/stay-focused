package com.phonecodex.app.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeSummaryLawTest {
    @Test
    fun totalForegroundMillis_sumsDurationsAndIgnoresInvalidEntries() {
        assertEquals(
            3_600_000L,
            ScreenTimeSummaryLaw.totalForegroundMillis(
                listOf(
                    AppForegroundDuration(2_400_000L),
                    AppForegroundDuration(1_200_000L),
                    AppForegroundDuration(-30L)
                )
            )
        )
    }

    @Test
    fun totalForegroundMillis_saturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            ScreenTimeSummaryLaw.totalForegroundMillis(
                listOf(
                    AppForegroundDuration(Long.MAX_VALUE),
                    AppForegroundDuration(1L)
                )
            )
        )
    }

    @Test
    fun formatForegroundTime_isReadableAcrossMinuteAndHourBoundaries() {
        assertEquals("Less than 1 min", ScreenTimeSummaryLaw.formatForegroundTime(59_999L))
        assertEquals("1 min", ScreenTimeSummaryLaw.formatForegroundTime(60_000L))
        assertEquals("59 min", ScreenTimeSummaryLaw.formatForegroundTime(3_599_999L))
        assertEquals("1h", ScreenTimeSummaryLaw.formatForegroundTime(3_600_000L))
        assertEquals("2h 14m", ScreenTimeSummaryLaw.formatForegroundTime(8_040_000L))
    }
}
