package com.phonecodex.app.domain.diagnostics

import android.util.Log
import com.phonecodex.app.BuildConfig
import com.phonecodex.app.domain.auth.BackendAuthHeaders
import com.phonecodex.app.domain.auth.BackendAuthResolution
import com.phonecodex.app.domain.auth.BackendIdentityTokenProvider
import com.phonecodex.app.domain.auth.BackendIdentityTokenProviders
import java.net.HttpURLConnection

/**
 * Where the phone talks to Promise Compiler / classify.
 *
 * Debug default is loopback (USB `adb reverse`). Closed-beta builds set
 * `strlix.backendUrl` + `strlix.backendSecret` in `local.properties` (not git).
 */
object BackendConfig {

    val baseUrl: String
        get() = BuildConfig.BACKEND_BASE_URL.trim().trimEnd('/')

    val appSecret: String
        get() = BuildConfig.BACKEND_APP_SECRET.trim()

    const val APP_SECRET_HEADER = BackendAuthHeaders.APP_SECRET_HEADER

    /** Build gate `STRLIX_USE_FIREBASE_ID_AUTH`: false by default and always false in release. */
    val useFirebaseIdAuth: Boolean
        get() = BuildConfig.STRLIX_USE_FIREBASE_ID_AUTH

    /**
     * Single auth entry point for every hosted call (PromiseCompilerClient,
     * NetworkAiContentClassifier, PromiseSpeechClient, AppDossierClient, BackendHealthProbe).
     *
     * Gate on: `Authorization: Bearer <Firebase ID token>` from the shared provider; on token
     * failure sends NO credential (backend answers 401) and never falls back to the shared
     * secret. Gate off: the debug/local shared secret as before. Release builds never send
     * the shared secret. Blocking; call from a background thread. Never throws.
     */
    fun applyAuth(
        connection: HttpURLConnection,
        provider: BackendIdentityTokenProvider = BackendIdentityTokenProviders.shared
    ): BackendAuthResolution {
        val resolution = BackendAuthHeaders.resolve(
            useBearer = useFirebaseIdAuth,
            sharedSecret = appSecret,
            allowSharedSecret = BuildConfig.DEBUG,
            provider = provider,
            log = { line -> Log.w(TAG, line) }
        )
        resolution.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return resolution
    }

    private const val TAG = "BackendConfig"
}
