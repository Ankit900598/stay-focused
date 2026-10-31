package com.phonecodex.app.domain.session

import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import java.time.Instant
import java.time.ZoneId

object SessionExpiryPolicy {

    const val EXPIRED_EVENT_MESSAGE = "Study World ended because timer expired"

    fun isExpired(
        session: FocusSession,
        nowMillis: Long,
        shortFormDailyQuotaLimit: Int? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (session.status != SessionStatus.ACTIVE && session.status != SessionStatus.LOCKED) {
            return false
        }
        return nowMillis >= effectiveDeadlineMillis(session, nowMillis, shortFormDailyQuotaLimit, zoneId)
    }

    /**
     * A shorts-count quota without "today" was being stored as a 30-minute
     * session. Quota law lasts until the local midnight that ends the start day; measured
     * from now instead, that midnight moves forward every day and the session never ends.
     */
    fun effectiveDeadlineMillis(
        session: FocusSession,
        nowMillis: Long,
        shortFormDailyQuotaLimit: Int?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        if (shortFormDailyQuotaLimit == null || shortFormDailyQuotaLimit <= 0) {
            return session.deadlineMillis
        }
        val endOfDay = Instant.ofEpochMilli(session.startTimeMillis).atZone(zoneId).toLocalDate()
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        return maxOf(session.deadlineMillis, endOfDay)
    }
}
