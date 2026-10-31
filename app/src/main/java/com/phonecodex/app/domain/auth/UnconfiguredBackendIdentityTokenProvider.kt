package com.phonecodex.app.domain.auth

/**
 * Default provider until the owner adds Firebase (google-services.json + Gradle deps).
 * Always reports [BackendIdentityFailureKind.Unconfigured].
 */
object UnconfiguredBackendIdentityTokenProvider : BackendIdentityTokenProvider {
    override fun getIdTokenForRequest(forceRefresh: Boolean): BackendIdentityTokenResult =
        BackendIdentityTokenResult.Failure(BackendIdentityFailureKind.Unconfigured)
}
