package com.phonecodex.app.data

import com.phonecodex.app.data.ScreenTimeReadResult.Available
import com.phonecodex.app.data.ScreenTimeReadResult.NeedsAccess
import com.phonecodex.app.data.ScreenTimeReadResult.NoData
import com.phonecodex.app.data.ScreenTimeReadResult.Unavailable
import com.phonecodex.app.domain.usage.ScreenTimeSummary
import com.phonecodex.app.ui.home.ScreenTimeInsightsState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeUsageReaderTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun readToday_withoutUsageAccess_doesNotQueryUsageData() {
        val source = FakeScreenTimeUsageSource(hasAccess = false)

        assertEquals(NeedsAccess, reader(source).readToday(NOW_MILLIS))
        assertFalse(source.wasQueried)
    }

    @Test
    fun readToday_queriesOnlyFromLocalMidnightAndKeepsSampleTime() {
        val source = FakeScreenTimeUsageSource(durations = listOf(2_400_000L, 1_200_000L))

        val result = reader(source).readToday(NOW_MILLIS)

        assertEquals(
            LocalDate.of(2026, 10, 11).atStartOfDay(zone).toInstant().toEpochMilli(),
            source.beginMillis
        )
        assertEquals(NOW_MILLIS, source.endMillis)
        assertEquals(
            Available(
                com.phonecodex.app.domain.usage.ScreenTimeSummary(
                    appForegroundMillis = 3_600_000L,
                    sampledAtMillis = NOW_MILLIS
                )
            ),
            result
        )
    }

    @Test
    fun readToday_nullPlatformResult_isUnavailableAndEmptyResultIsNoData() {
        assertEquals(Unavailable, reader(FakeScreenTimeUsageSource(durations = null)).readToday(NOW_MILLIS))
        assertEquals(NoData, reader(FakeScreenTimeUsageSource(durations = emptyList())).readToday(NOW_MILLIS))
    }

    @Test
    fun readToday_permissionRevokedBetweenCheckAndQuery_returnsNeedsAccess() {
        val source = FakeScreenTimeUsageSource(queryFailure = SecurityException())

        assertEquals(NeedsAccess, reader(source).readToday(NOW_MILLIS))
    }

    @Test
    fun readToday_permissionRevokedDuringQuery_discardsTheResult() {
        val source = FakeScreenTimeUsageSource(
            durations = listOf(60_000L),
            revokeAfterQuery = true
        )

        assertEquals(NeedsAccess, reader(source).readToday(NOW_MILLIS))
    }

    @Test
    fun readToday_platformFailure_returnsUnavailableWithoutThrowing() {
        val source = FakeScreenTimeUsageSource(queryFailure = IllegalStateException())

        assertEquals(Unavailable, reader(source).readToday(NOW_MILLIS))
    }

    @Test
    fun refreshHidesCachedUsageWhilePermissionIsRechecked() {
        val firstResult = Available(ScreenTimeSummary(13L * 60L * 60L * 1_000L, NOW_MILLIS))
        val nextResult = CompletableDeferred<ScreenTimeReadResult>()
        var readNextResult = false
        val state = ScreenTimeInsightsState(
            readToday = {
                if (readNextResult) nextResult.await() else firstResult
            },
            elapsedRealtimeMillis = { NOW_MILLIS },
            ioDispatcher = Dispatchers.Unconfined
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            state.refresh(scope, force = true)
            assertEquals(firstResult, state.result)

            readNextResult = true
            state.refresh(scope, force = true)

            assertEquals(ScreenTimeReadResult.Loading, state.result)
            assertEquals(NeedsAccess, run {
                nextResult.complete(NeedsAccess)
                state.result
            })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun markUnavailable_invalidatesAnInFlightRead() {
        val pending = CompletableDeferred<ScreenTimeReadResult>()
        val state = ScreenTimeInsightsState(
            readToday = { pending.await() },
            elapsedRealtimeMillis = { NOW_MILLIS },
            ioDispatcher = Dispatchers.Unconfined
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            state.refresh(scope, force = true)
            state.markUnavailable()
            pending.complete(Available(ScreenTimeSummary(60_000L, NOW_MILLIS)))

            assertEquals(Unavailable, state.result)
            assertFalse(state.loading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oldReadCannotOverwriteOrFinishANewRefresh() {
        val oldRead = CompletableDeferred<ScreenTimeReadResult>()
        val newRead = CompletableDeferred<ScreenTimeReadResult>()
        var callCount = 0
        val state = ScreenTimeInsightsState(
            readToday = {
                callCount++
                if (callCount == 1) oldRead.await() else newRead.await()
            },
            elapsedRealtimeMillis = { NOW_MILLIS },
            ioDispatcher = Dispatchers.Unconfined
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            state.refresh(scope, force = true)
            state.markUnavailable()
            state.refresh(scope, force = true)
            oldRead.complete(Available(ScreenTimeSummary(60_000L, NOW_MILLIS)))

            assertEquals(ScreenTimeReadResult.Loading, state.result)
            assertTrue(state.loading)

            val expected = Available(ScreenTimeSummary(120_000L, NOW_MILLIS))
            newRead.complete(expected)
            assertEquals(expected, state.result)
            assertFalse(state.loading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun forcedRefreshWhileLoadingStartsNewReadAndIgnoresOldPermissionResult() {
        val oldRead = CompletableDeferred<ScreenTimeReadResult>()
        val newRead = CompletableDeferred<ScreenTimeReadResult>()
        var callCount = 0
        val state = ScreenTimeInsightsState(
            readToday = {
                callCount++
                if (callCount == 1) oldRead.await() else newRead.await()
            },
            elapsedRealtimeMillis = { NOW_MILLIS },
            ioDispatcher = Dispatchers.Unconfined
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            state.refresh(scope, force = true)
            state.refresh(scope, force = true)

            assertEquals(2, callCount)
            val expected = Available(ScreenTimeSummary(120_000L, NOW_MILLIS))
            newRead.complete(expected)
            oldRead.complete(NeedsAccess)
            assertEquals(expected, state.result)
            assertFalse(state.loading)
        } finally {
            scope.cancel()
        }
    }

    private fun reader(source: FakeScreenTimeUsageSource) =
        ScreenTimeUsageReader(source, { zone })

    private class FakeScreenTimeUsageSource(
        private var hasAccess: Boolean = true,
        private val durations: List<Long>? = listOf(1L),
        private val queryFailure: Exception? = null,
        private val revokeAfterQuery: Boolean = false
    ) : ScreenTimeUsageSource {
        var wasQueried = false
            private set
        var beginMillis: Long? = null
            private set
        var endMillis: Long? = null
            private set

        override fun hasUsageAccess(): Boolean = hasAccess

        override fun queryForegroundDurations(beginMillis: Long, endMillis: Long): List<Long>? {
            wasQueried = true
            this.beginMillis = beginMillis
            this.endMillis = endMillis
            queryFailure?.let { throw it }
            if (revokeAfterQuery) hasAccess = false
            return durations
        }
    }

    companion object {
        private val NOW_MILLIS = Instant.parse("2026-10-11T09:00:00Z").toEpochMilli()
    }
}
