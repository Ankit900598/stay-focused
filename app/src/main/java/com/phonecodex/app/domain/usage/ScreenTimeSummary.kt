package com.phonecodex.app.domain.usage

data class AppForegroundDuration(val durationMillis: Long)

data class ScreenTimeSummary(
    val appForegroundMillis: Long,
    val sampledAtMillis: Long
)

object ScreenTimeSummaryLaw {
    fun totalForegroundMillis(durations: List<AppForegroundDuration>): Long =
        durations.asSequence()
            .map { it.durationMillis.coerceAtLeast(0L) }
            .fold(0L) { total, duration ->
                if (Long.MAX_VALUE - total < duration) Long.MAX_VALUE else total + duration
            }

    fun formatForegroundTime(durationMillis: Long): String {
        val minutes = durationMillis.coerceAtLeast(0L) / MILLIS_PER_MINUTE
        if (minutes == 0L) return "Less than 1 min"
        val hours = minutes / MINUTES_PER_HOUR
        val remainingMinutes = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0L -> "$remainingMinutes min"
            remainingMinutes == 0L -> "${hours}h"
            else -> "${hours}h ${remainingMinutes}m"
        }
    }

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
}
