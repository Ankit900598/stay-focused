package com.phonecodex.app.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import com.phonecodex.app.domain.usage.AppForegroundDuration
import com.phonecodex.app.domain.usage.ScreenTimeSummary
import com.phonecodex.app.domain.usage.ScreenTimeSummaryLaw
import java.time.Instant
import java.time.ZoneId

sealed interface ScreenTimeReadResult {
    data object Loading : ScreenTimeReadResult
    data object NeedsAccess : ScreenTimeReadResult
    data object NoData : ScreenTimeReadResult
    data object Unavailable : ScreenTimeReadResult
    data class Available(val summary: ScreenTimeSummary) : ScreenTimeReadResult
}

internal interface ScreenTimeUsageSource {
    fun hasUsageAccess(): Boolean

    fun queryForegroundDurations(beginMillis: Long, endMillis: Long): List<Long>?
}

class ScreenTimeUsageReader internal constructor(
    private val source: ScreenTimeUsageSource,
    private val zoneId: () -> ZoneId
) {
    constructor(context: Context) : this(
        AndroidScreenTimeUsageSource(context.applicationContext),
        ZoneId::systemDefault
    )

    fun readToday(nowMillis: Long = System.currentTimeMillis()): ScreenTimeReadResult {
        if (!source.hasUsageAccess()) return ScreenTimeReadResult.NeedsAccess

        return try {
            val zone = zoneId()
            val startOfToday = Instant.ofEpochMilli(nowMillis)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            val durations = source.queryForegroundDurations(startOfToday, nowMillis)
            if (!source.hasUsageAccess()) return ScreenTimeReadResult.NeedsAccess
            if (durations == null) return ScreenTimeReadResult.Unavailable
            if (durations.isEmpty()) return ScreenTimeReadResult.NoData

            val total = ScreenTimeSummaryLaw.totalForegroundMillis(
                durations.map(::AppForegroundDuration)
            )
            ScreenTimeReadResult.Available(
                ScreenTimeSummary(
                    appForegroundMillis = total,
                    sampledAtMillis = nowMillis
                )
            )
        } catch (_: SecurityException) {
            ScreenTimeReadResult.NeedsAccess
        } catch (_: Exception) {
            ScreenTimeReadResult.Unavailable
        }
    }

    companion object {
        const val USAGE_ACCESS_SETTINGS_ACTION = Settings.ACTION_USAGE_ACCESS_SETTINGS
    }
}

private class AndroidScreenTimeUsageSource(context: Context) : ScreenTimeUsageSource {
    private val appContext = context.applicationContext

    override fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    override fun queryForegroundDurations(beginMillis: Long, endMillis: Long): List<Long>? {
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: throw IllegalStateException("Usage stats service unavailable")
        return manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            beginMillis,
            endMillis
        )?.map { it.totalTimeInForeground }
    }
}
