package com.phonecodex.app.domain.auth

import android.content.Context
import com.phonecodex.app.BuildConfig

/**
 * Process-wide holder so the Activity and the AccessibilityService use ONE provider instance
 * (both run in the app's single process; a Kotlin `object` is the DI singleton).
 *
 * Both entry points call [installIfNeeded] with their context early in their lifecycle
 * (MainActivity.onCreate, PhoneCodexAccessibilityService.onServiceConnected). It is
 * idempotent. With the gate off (default) nothing is installed and [shared] stays
 * [UnconfiguredBackendIdentityTokenProvider]. Clients never take a provider constructor
 * argument; they go through BackendConfig.applyAuth, which reads [shared].
 */
object BackendIdentityTokenProviders {

    @Volatile
    private var provider: BackendIdentityTokenProvider = UnconfiguredBackendIdentityTokenProvider

    @Volatile
    private var installed = false

    val shared: BackendIdentityTokenProvider
        get() = provider

    fun installIfNeeded(
        context: Context,
        useFirebaseIdAuth: Boolean = BuildConfig.STRLIX_USE_FIREBASE_ID_AUTH
    ) {
        if (installed || !useFirebaseIdAuth) return
        synchronized(this) {
            if (installed) return
            provider = FirebaseBackendIdentityTokenProvider.create(context)
            installed = true
        }
    }

    /** Tests only. */
    internal fun replaceForTesting(replacement: BackendIdentityTokenProvider?) {
        synchronized(this) {
            provider = replacement ?: UnconfiguredBackendIdentityTokenProvider
            installed = replacement != null
        }
    }
}
