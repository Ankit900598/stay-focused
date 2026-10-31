package com.phonecodex.app.domain.auth

/**
 * Pure auth-header decision for hosted backend requests. No Android or BuildConfig access,
 * so it is unit-testable. [com.phonecodex.app.domain.diagnostics.BackendConfig.applyAuth]
 * feeds it build flags and applies the result.
 */
data class BackendAuthResolution(
    val headers: Map<String, String>,
    /** Non-null when bearer auth was required but no token could be produced. */
    val failure: BackendIdentityFailureKind? = null
)

object BackendAuthHeaders {
    const val AUTHORIZATION_HEADER = "Authorization"
    const val APP_SECRET_HEADER = "X-Strlix-App-Secret"

    /**
     * - [useBearer] on: ask [provider]. Token -> `Authorization: Bearer`. Failure -> no auth
     *   header at all (the backend answers 401) and never a fall-back to the shared secret.
     * - [useBearer] off: today's debug/local shared-secret behavior, only when
     *   [allowSharedSecret] (false in release builds) and the secret is non-empty.
     *
     * [log] receives failure kinds only, never token or secret material.
     */
    fun resolve(
        useBearer: Boolean,
        sharedSecret: String,
        allowSharedSecret: Boolean,
        provider: BackendIdentityTokenProvider,
        forceRefresh: Boolean = false,
        log: (String) -> Unit = {}
    ): BackendAuthResolution {
        if (useBearer) {
            val result = try {
                provider.getIdTokenForRequest(forceRefresh)
            } catch (_: Exception) {
                BackendIdentityTokenResult.Failure(BackendIdentityFailureKind.RefreshFailed)
            }
            return when (result) {
                is BackendIdentityTokenResult.Token ->
                    BackendAuthResolution(
                        mapOf(AUTHORIZATION_HEADER to "Bearer ${result.reveal()}")
                    )
                is BackendIdentityTokenResult.Failure -> {
                    log("backend identity token unavailable: ${result.kind}")
                    BackendAuthResolution(emptyMap(), result.kind)
                }
            }
        }
        val secret = sharedSecret.trim()
        return if (allowSharedSecret && secret.isNotEmpty()) {
            BackendAuthResolution(mapOf(APP_SECRET_HEADER to secret))
        } else {
            BackendAuthResolution(emptyMap())
        }
    }
}
