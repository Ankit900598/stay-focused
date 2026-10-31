package com.phonecodex.app.domain.auth

/**
 * Source of the Firebase ID token sent as `Authorization: Bearer <token>` to hosted
 * Strlix routes (see docs/release-api-auth-adr-2026-10-12.md).
 *
 * Rules for every implementation:
 * - No UI, Activity, or Gmail dependency. Accessibility-service code uses the same instance.
 * - Blocking, like the existing `HttpURLConnection` clients. Call from a background thread only.
 * - Never log, persist, or `toString()` the raw token.
 * - Never throw for expected states; return [BackendIdentityTokenResult.Failure].
 * - Never retry a paid operation on the caller's behalf.
 */
interface BackendIdentityTokenProvider {

    /**
     * @param forceRefresh true only when the backend just rejected a token as expired; the
     * SDK refreshes on its own otherwise.
     */
    fun getIdTokenForRequest(forceRefresh: Boolean = false): BackendIdentityTokenResult
}

enum class BackendIdentityFailureKind {
    /** No identity backend wired into this build (no Firebase app / gate off). */
    Unconfigured,

    /** No signed-in user and this provider will not create one. */
    SignedOut,

    /** Network unreachable while minting or refreshing a token. */
    Offline,

    /** Token mint/refresh failed for a reason other than offline or revocation. */
    RefreshFailed,

    /** Account disabled, deleted, or its credentials were revoked. Needs explicit user action. */
    Revoked,
}

sealed interface BackendIdentityTokenResult {

    /** Holds the raw token. [toString] is redacted so accidental logging cannot leak it. */
    class Token(private val value: String) : BackendIdentityTokenResult {
        init {
            require(value.isNotBlank()) { "token must be non-blank" }
        }

        fun reveal(): String = value

        override fun toString(): String = "Token(<redacted>)"
    }

    data class Failure(val kind: BackendIdentityFailureKind) : BackendIdentityTokenResult
}
