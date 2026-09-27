package com.phonecodex.app.domain.session

import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.RecoveryPolicy

/**
 * Emergency exit ends the running commitment now after one explicit confirm, with no typed
 * sentence. Because it skips the early-end friction it is bounded to once per [WINDOW_MILLIS].
 * Calls, contacts and emergency apps never depend on it: they are always allowed by the law.
 */
object EmergencyExitGate {

    const val WINDOW_MILLIS = 24L * 60L * 60L * 1000L

    enum class Availability { AVAILABLE, NO_SESSION, DISABLED_BY_POLICY, USED_RECENTLY }

    fun availability(
        session: FocusSession?,
        policy: RecoveryPolicy,
        lastUsedAtMillis: Long?,
        nowMillis: Long
    ): Availability = when {
        session == null -> Availability.NO_SESSION
        !policy.emergencyOverrideAllowed -> Availability.DISABLED_BY_POLICY
        lastUsedAtMillis != null && nowMillis - lastUsedAtMillis in 0 until WINDOW_MILLIS ->
            Availability.USED_RECENTLY
        else -> Availability.AVAILABLE
    }

    fun nextAvailableAtMillis(lastUsedAtMillis: Long?): Long? =
        lastUsedAtMillis?.let { it + WINDOW_MILLIS }
}
