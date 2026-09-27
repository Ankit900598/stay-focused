package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel

class SessionStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun saveActiveSession(session: FocusSession) {
        prefs.edit()
            .putString(KEY_ID, session.id)
            .putString(KEY_WORLD_ID, session.worldId)
            .putString(KEY_GOAL, session.goal)
            .putLong(KEY_START_TIME_MILLIS, session.startTimeMillis)
            .putLong(KEY_DEADLINE_MILLIS, session.deadlineMillis)
            .putString(KEY_STATUS, session.status.name)
            .putInt(KEY_ATTEMPT_COUNT, session.attemptCount)
            .putString(KEY_STRICTNESS, session.strictness.name)
            .putString(KEY_PROMISE_STRICTNESS, session.promiseStrictness?.name)
            .apply()
    }

    /** Synchronous so a process death right after an exit cannot bring the session back. */
    fun clearSession() {
        prefs.edit().clear().commit()
    }

    fun getStoredSession(): FocusSession? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val worldId = prefs.getString(KEY_WORLD_ID, null) ?: return null
        val goal = prefs.getString(KEY_GOAL, null) ?: return null
        if (!prefs.contains(KEY_START_TIME_MILLIS)) return null
        if (!prefs.contains(KEY_DEADLINE_MILLIS)) return null
        if (!prefs.contains(KEY_ATTEMPT_COUNT)) return null

        val statusName = prefs.getString(KEY_STATUS, null) ?: return null
        val strictnessName = prefs.getString(KEY_STRICTNESS, null) ?: return null

        val status = runCatching { SessionStatus.valueOf(statusName) }.getOrNull()
            ?: return null
        if (status != SessionStatus.ACTIVE && status != SessionStatus.LOCKED) {
            return null
        }

        val strictness = runCatching { StrictnessLevel.valueOf(strictnessName) }.getOrNull()
            ?: return null

        return FocusSession(
            id = id,
            worldId = worldId,
            goal = goal,
            startTimeMillis = prefs.getLong(KEY_START_TIME_MILLIS, 0L),
            deadlineMillis = prefs.getLong(KEY_DEADLINE_MILLIS, 0L),
            status = status,
            attemptCount = prefs.getInt(KEY_ATTEMPT_COUNT, 0),
            strictness = strictness,
            promiseStrictness = prefs.getString(KEY_PROMISE_STRICTNESS, null)
                ?.let { name -> runCatching { StrictnessLevel.valueOf(name) }.getOrNull() }
        )
    }

    fun getActiveSession(): FocusSession? {
        val session = getStoredSession() ?: return null
        return if (session.status == SessionStatus.ACTIVE) session else null
    }

    fun incrementAttempt(): FocusSession? {
        val session = getActiveSession() ?: return null
        val updated = session.copy(attemptCount = session.attemptCount + 1)
        saveActiveSession(updated)
        return updated
    }

    fun lockSession(): FocusSession? {
        val session = getActiveSession() ?: return null
        val locked = session.copy(status = SessionStatus.LOCKED)
        saveActiveSession(locked)
        return locked
    }

    companion object {
        private const val PREFS_NAME = "phonecodex_session"
        private const val KEY_ID = "id"
        private const val KEY_WORLD_ID = "worldId"
        private const val KEY_GOAL = "goal"
        private const val KEY_START_TIME_MILLIS = "startTimeMillis"
        private const val KEY_DEADLINE_MILLIS = "deadlineMillis"
        private const val KEY_STATUS = "status"
        private const val KEY_ATTEMPT_COUNT = "attemptCount"
        private const val KEY_STRICTNESS = "strictness"
        private const val KEY_PROMISE_STRICTNESS = "promiseStrictness"
    }
}
