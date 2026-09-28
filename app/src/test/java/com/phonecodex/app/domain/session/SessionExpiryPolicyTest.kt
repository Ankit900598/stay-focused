package com.phonecodex.app.domain.session

import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExpiryPolicyTest {

    private val session = FocusSession(
        id = "session-1",
        worldId = "study",
        goal = "Study",
        startTimeMillis = 1_000L,
        deadlineMillis = 10_000L,
        status = SessionStatus.ACTIVE,
        attemptCount = 0,
        strictness = StrictnessLevel.STRICT
    )

    @Test
    fun isExpired_falseBeforeDeadline() {
        assertFalse(SessionExpiryPolicy.isExpired(session, 9_999L))
    }

    @Test
    fun isExpired_trueAtDeadline() {
        assertTrue(SessionExpiryPolicy.isExpired(session, 10_000L))
    }

    @Test
    fun isExpired_trueAfterDeadline() {
        assertTrue(SessionExpiryPolicy.isExpired(session, 10_001L))
    }

    @Test
    fun isExpired_falseForNonActiveOrLockedSession() {
        val endedSession = session.copy(status = SessionStatus.ENDED)
        assertFalse(SessionExpiryPolicy.isExpired(endedSession, 20_000L))
    }

    @Test
    fun shortsQuota_extendsPastThirtyMinuteDeadlineUntilMidnight() {
        val zone = java.time.ZoneId.of("UTC")
        val start = java.time.Instant.parse("2026-09-19T10:00:00Z").toEpochMilli()
        val thirtyMinDeadline = start + 30L * 60L * 1000L
        val quotaSession = session.copy(
            startTimeMillis = start,
            deadlineMillis = thirtyMinDeadline
        )
        val thirtyOneMinLater = start + 31L * 60L * 1000L
        assertTrue(SessionExpiryPolicy.isExpired(quotaSession, thirtyOneMinLater))
        assertFalse(
            SessionExpiryPolicy.isExpired(quotaSession, thirtyOneMinLater, 10, zone)
        )
        val nextMidnight = java.time.Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()
        assertFalse(SessionExpiryPolicy.isExpired(quotaSession, nextMidnight - 1, 10, zone))
        assertTrue(SessionExpiryPolicy.isExpired(quotaSession, nextMidnight, 10, zone))
    }

    @Test
    fun shortsQuota_endsAtItsDeadline_theMidnightDoesNotMoveWithTheClock() {
        val zone = java.time.ZoneId.of("UTC")
        val start = java.time.Instant.parse("2026-09-19T10:00:00Z").toEpochMilli()
        val dayDeadline = start + 24L * 60L * 60L * 1000L
        val quotaSession = session.copy(startTimeMillis = start, deadlineMillis = dayDeadline)
        assertFalse(SessionExpiryPolicy.isExpired(quotaSession, dayDeadline - 1, 2, zone))
        assertTrue(SessionExpiryPolicy.isExpired(quotaSession, dayDeadline, 2, zone))
        assertTrue(SessionExpiryPolicy.isExpired(quotaSession, dayDeadline + 3L * 24 * 60 * 60 * 1000, 2, zone))
    }
}
