package com.phonecodex.app.ui.home

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.phonecodex.app.data.ScreenTimeReadResult
import com.phonecodex.app.data.ScreenTimeUsageReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class ScreenTimeInsightsState(
    private val readToday: suspend () -> ScreenTimeReadResult,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context) : this({
        ScreenTimeUsageReader(context.applicationContext).readToday()
    })

    private var lastRefreshElapsedMillis = 0L
    private var requestVersion = 0L

    var result by mutableStateOf<ScreenTimeReadResult>(ScreenTimeReadResult.Loading)
        private set
    var loading by mutableStateOf(false)
        private set

    fun markUnavailable() {
        requestVersion++
        loading = false
        result = ScreenTimeReadResult.Unavailable
    }

    fun refresh(scope: CoroutineScope, force: Boolean = false) {
        if (loading && !force) return
        val nowElapsed = elapsedRealtimeMillis()
        if (!force && lastRefreshElapsedMillis > 0L &&
            nowElapsed - lastRefreshElapsedMillis < REFRESH_COOLDOWN_MILLIS
        ) {
            return
        }
        lastRefreshElapsedMillis = nowElapsed
        val version = ++requestVersion
        loading = true
        result = ScreenTimeReadResult.Loading
        scope.launch {
            try {
                val nextResult = withContext(ioDispatcher) { readToday() }
                if (version == requestVersion) result = nextResult
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == requestVersion) result = ScreenTimeReadResult.Unavailable
            } finally {
                if (version == requestVersion) loading = false
            }
        }
    }

    companion object {
        private const val REFRESH_COOLDOWN_MILLIS = 30_000L
    }
}

@Composable
internal fun rememberScreenTimeInsightsState(): ScreenTimeInsightsState {
    val context = LocalContext.current.applicationContext
    return remember(context) { ScreenTimeInsightsState(context) }
}
