package com.phonecodex.app.domain.auth

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * RUN-ON-DEVICE smoke: proves the Firebase "Anonymous ID token path" (anonymous sign-in ->
 * getIdToken) against the real Firebase project. Needs a device/emulator WITH network and the
 * git-ignored app/google-services.json at build time.
 *
 * Deliberately independent of BuildConfig.STRLIX_USE_FIREBASE_ID_AUTH: it talks to the Firebase
 * SDK directly, so it does not enable (or depend on) any release/phoneTest auth gate. It never
 * logs or prints the token; it only asserts on the token's shape and non-secret claims.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.phonecodex.app.domain.auth.FirebaseAnonymousIdTokenSmokeTest
 */
@RunWith(AndroidJUnit4::class)
class FirebaseAnonymousIdTokenSmokeTest {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    @After
    fun cleanUp() {
        // Do not leave throwaway anonymous users behind in the project.
        runCatching {
            auth.currentUser?.let { Tasks.await(it.delete(), TIMEOUT_S, TimeUnit.SECONDS) }
        }
        auth.signOut()
    }

    @Test
    fun anonymousSignIn_thenGetIdToken_returnsAnonymousJwtForExpectedProject() {
        val result = Tasks.await(auth.signInAnonymously(), TIMEOUT_S, TimeUnit.SECONDS)
        val user = result.user
        assertNotNull("anonymous sign-in returned no user", user)
        assertTrue("user should be anonymous", user!!.isAnonymous)

        val token = Tasks.await(user.getIdToken(true), TIMEOUT_S, TimeUnit.SECONDS).token
        assertFalse("ID token is null/blank", token.isNullOrBlank())

        val parts = token!!.split(".")
        assertEquals("ID token should be a 3-part JWT", 3, parts.size)

        val claims = JSONObject(
            String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        )
        assertEquals(EXPECTED_PROJECT_ID, claims.getString("aud"))
        assertEquals(
            "https://securetoken.google.com/$EXPECTED_PROJECT_ID",
            claims.getString("iss")
        )
        assertEquals("anonymous", claims.getJSONObject("firebase").getString("sign_in_provider"))
        assertFalse("sub (uid) missing", claims.optString("sub").isBlank())
    }

    private companion object {
        const val EXPECTED_PROJECT_ID = "phonecodex-strlix"
        const val TIMEOUT_S = 30L
    }
}
