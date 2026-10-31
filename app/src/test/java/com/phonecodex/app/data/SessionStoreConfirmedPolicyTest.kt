package com.phonecodex.app.data

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ConfirmedSessionPolicy
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreConfirmedPolicyTest {

    @Test
    fun activeSession_roundTripsExactlyTheConfirmedRules() {
        val store = SessionStore(InMemorySharedPreferences())
        val policy = ConfirmedSessionPolicy(
            appRules = listOf(AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.AI_DECIDE)),
            contentRules = listOf(
                ContentRule(
                    appLabel = null,
                    packageName = null,
                    surface = "any",
                    contentType = "adult_sexual",
                    operator = null,
                    value = null,
                    unit = null,
                    action = ContentRuleAction.BLOCK
                ),
                ContentRule(
                    appLabel = "Instagram",
                    packageName = "com.instagram.android",
                    surface = "reels",
                    contentType = "short_form_video",
                    operator = "gte",
                    value = 3,
                    unit = "minutes",
                    action = ContentRuleAction.WARN
                )
            )
        )
        val session = activeSession().copy(confirmedPolicy = policy)

        store.saveActiveSession(session)

        assertEquals(session, store.getStoredSession())
    }

    @Test
    fun legacySession_hasNoClassifierBlockAuthority() {
        val store = SessionStore(InMemorySharedPreferences())
        store.saveActiveSession(activeSession())

        assertNull(store.getStoredSession()?.confirmedPolicy)
    }

    @Test
    fun corruptPolicyRecord_preservesSessionButDropsAllClassifierAuthority() {
        val prefs = InMemorySharedPreferences()
        val store = SessionStore(prefs)
        store.saveActiveSession(activeSession())
        prefs.edit().putString("confirmedPolicyV1", "confirmed-session-policy-v1\nC|!|not-base64").commit()

        val restored = store.getStoredSession()

        assertEquals("session-1", restored?.id)
        assertNull(restored?.confirmedPolicy)
    }

    @Test
    fun wrongTypePolicyPreference_doesNotCrashOrGrantAuthority() {
        val prefs = InMemorySharedPreferences()
        val store = SessionStore(prefs)
        store.saveActiveSession(activeSession())
        prefs.edit().putInt("confirmedPolicyV1", 4).commit()

        val restored = store.getStoredSession()

        assertEquals("session-1", restored?.id)
        assertNull(restored?.confirmedPolicy)
    }

    @Test
    fun clearSession_alsoRemovesConfirmedPolicy() {
        val prefs = InMemorySharedPreferences()
        val store = SessionStore(prefs)
        store.saveActiveSession(
            activeSession().copy(
                confirmedPolicy = ConfirmedSessionPolicy(
                    listOf(AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.AI_DECIDE)),
                    emptyList()
                )
            )
        )

        store.clearSession()

        assertNull(store.getStoredSession())
        assertEquals(emptyMap<String, Any?>(), prefs.snapshot())
    }

    private fun activeSession() = FocusSession(
        id = "session-1",
        worldId = "study",
        goal = "Keep focus",
        startTimeMillis = 1L,
        deadlineMillis = 60_001L,
        status = SessionStatus.ACTIVE,
        attemptCount = 0,
        strictness = StrictnessLevel.STRICT
    )
}
