package com.phonecodex.app.domain.delegation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phonecodex.app.data.MailDelegationStore
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MailDisconnectPersistenceInstrumentedTest {

    @Test
    fun durableDisconnectMarkerRejectsAStaleGrantAfterStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val grantPrefs = context.getSharedPreferences("mail_disconnect_grant_$suffix", 0)
        val disconnectPrefs = context.getSharedPreferences("mail_disconnect_safety_$suffix", 0)
        try {
            val session = FocusSession(
                id = "session-$suffix",
                worldId = "study",
                goal = "Keep Gmail closed for three hours",
                startTimeMillis = System.currentTimeMillis(),
                deadlineMillis = System.currentTimeMillis() + 3_600_000L,
                status = SessionStatus.ACTIVE,
                attemptCount = 0,
                strictness = StrictnessLevel.STRICT
            )
            val grant = MailDelegationGrant(
                sessionId = session.id,
                senderAddress = EmailAddress.parseUserEntry("prof@example.edu")!!,
                aiAssistConsent = true,
                allowSend = true,
                createdAtMillis = System.currentTimeMillis(),
                accountEmail = "test@example.com"
            )
            val store = MailDelegationStore(grantPrefs, disconnectPrefs)

            assertTrue(store.saveGrant(grant))
            assertTrue(store.disableSession(session.id))
            assertTrue("test represents a grant that survived a failed clear", store.saveGrant(grant))

            val recreatedStore = MailDelegationStore(grantPrefs, disconnectPrefs)
            assertNull(recreatedStore.liveGrant(session, System.currentTimeMillis()))
        } finally {
            grantPrefs.edit().clear().commit()
            disconnectPrefs.edit().clear().commit()
        }
    }
}
