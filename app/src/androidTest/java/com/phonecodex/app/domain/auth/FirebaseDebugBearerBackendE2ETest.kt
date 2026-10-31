package com.phonecodex.app.domain.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.phonecodex.app.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Debug E2E: Anonymous Firebase ID token from the app → Authorization Bearer → local backend
 * (adb reverse to host 127.0.0.1:8787). Requires:
 * - local.properties strlix.useFirebaseIdAuth=true (debug BuildConfig only)
 * - local backend listening; `adb reverse tcp:8787 tcp:8787`
 *
 * Release/phoneTest gates stay hard false. Never logs the token or shared secret.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseDebugBearerBackendE2ETest {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    @After
    fun cleanUp() {
        runCatching {
            auth.currentUser?.let { Tasks.await(it.delete(), TIMEOUT_S, TimeUnit.SECONDS) }
        }
        auth.signOut()
    }

    @Test
    fun anonymousBearer_hitsLocalBackend_capacityAndNoSecretFallback() {
        assumeTrue(
            "debug opt-in strlix.useFirebaseIdAuth=true required",
            BuildConfig.STRLIX_USE_FIREBASE_ID_AUTH
        )
        assumeTrue("debug build only", BuildConfig.DEBUG)

        val args = InstrumentationRegistry.getArguments()
        val base = (args.getString("backendUrl") ?: BuildConfig.BACKEND_BASE_URL)
            .trim().trimEnd('/')
        assumeTrue("backend should be loopback for adb reverse", base.contains("127.0.0.1") || base.contains("localhost"))

        // No shared-secret fallback when bearer required but provider fails
        val failResolve = BackendAuthHeaders.resolve(
            useBearer = true,
            sharedSecret = "should-not-appear",
            allowSharedSecret = true,
            provider = object : BackendIdentityTokenProvider {
                override fun getIdTokenForRequest(forceRefresh: Boolean) =
                    BackendIdentityTokenResult.Failure(BackendIdentityFailureKind.Unconfigured)
            }
        )
        assertTrue(failResolve.headers.isEmpty())
        assertFalse(failResolve.headers.containsKey(BackendAuthHeaders.APP_SECRET_HEADER))

        val result = Tasks.await(auth.signInAnonymously(), TIMEOUT_S, TimeUnit.SECONDS)
        val user = result.user
        assertNotNull(user)
        assertTrue(user!!.isAnonymous)
        val token = Tasks.await(user.getIdToken(true), TIMEOUT_S, TimeUnit.SECONDS).token
        assertFalse(token.isNullOrBlank())

        val ok1 = postClassify(base, "Bearer $token")
        assertEquals("first bearer should pass capacity", 200, ok1)

        val ok2 = postClassify(base, "Bearer $token")
        // capacity default on e2e server is 2
        assertEquals("second bearer within capacity 2", 200, ok2)

        val limited = postClassify(base, "Bearer $token")
        assertEquals("third should rate_limit", 429, limited)

        val noAuth = postClassify(base, null)
        assertEquals("missing bearer → 401", 401, noAuth)

        val secretOnly = postClassifyWithSecret(base, "should-not-work-as-fallback")
        assertEquals("app secret alone → 401 (no fallback)", 401, secretOnly)
    }

    private fun postClassify(base: String, authorization: String?): Int {
        val url = URL("$base/classify")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (authorization != null) setRequestProperty("Authorization", authorization)
        }
        conn.outputStream.use { it.write("{}".toByteArray()) }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun postClassifyWithSecret(base: String, secret: String): Int {
        val url = URL("$base/classify")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty(BackendAuthHeaders.APP_SECRET_HEADER, secret)
        }
        conn.outputStream.use { it.write("{}".toByteArray()) }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_S = 45L
    }
}
