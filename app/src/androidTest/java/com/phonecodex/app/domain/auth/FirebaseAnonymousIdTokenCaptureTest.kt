package com.phonecodex.app.domain.auth

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Device capture for backend Admin verify. Writes claims (safe) + token (secret file)
 * under the instrumentation context files dir. Does NOT delete the anonymous user
 * (verifyIdToken(..., true) would fail if revoked). Clean up via Admin deleteUser after verify.
 *
 * Never logs the token. Claims-only assertions in the test body.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.phonecodex.app.domain.auth.FirebaseAnonymousIdTokenCaptureTest
 */
@RunWith(AndroidJUnit4::class)
class FirebaseAnonymousIdTokenCaptureTest {

    @Test
    fun captureAnonymousIdTokenForAdminVerify() {
        val auth = FirebaseAuth.getInstance()
        auth.signOut()
        val result = Tasks.await(auth.signInAnonymously(), TIMEOUT_S, TimeUnit.SECONDS)
        val user = result.user
        assertNotNull(user)
        assertTrue(user!!.isAnonymous)

        val token = Tasks.await(user.getIdToken(true), TIMEOUT_S, TimeUnit.SECONDS).token
        assertFalse(token.isNullOrBlank())
        val parts = token!!.split(".")
        assertEquals(3, parts.size)

        val claims = JSONObject(
            String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        )
        assertEquals(EXPECTED_PROJECT_ID, claims.getString("aud"))
        assertEquals("https://securetoken.google.com/$EXPECTED_PROJECT_ID", claims.getString("iss"))
        assertEquals("anonymous", claims.getJSONObject("firebase").getString("sign_in_provider"))
        val uid = claims.getString("sub")
        assertFalse(uid.isBlank())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.filesDir
        File(dir, "fb_anon_claims.json").writeText(
            JSONObject()
                .put("aud", claims.getString("aud"))
                .put("iss", claims.getString("iss"))
                .put("uid", uid)
                .put("sign_in_provider", "anonymous")
                .toString()
        )
        File(dir, "fb_anon_id_token.txt").writeText(token)
        // Also drop on external cache for adb pull without run-as (debug builds)
        val ext = ctx.externalCacheDir ?: dir
        File(ext, "fb_anon_claims.json").writeText(
            JSONObject()
                .put("aud", claims.getString("aud"))
                .put("iss", claims.getString("iss"))
                .put("uid", uid)
                .put("sign_in_provider", "anonymous")
                .toString()
        )
        File(ext, "fb_anon_id_token.txt").writeText(token)
    }

    private companion object {
        const val EXPECTED_PROJECT_ID = "phonecodex-strlix"
        const val TIMEOUT_S = 30L
    }
}
