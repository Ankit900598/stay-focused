package com.phonecodex.app.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TOKEN = "fb.id.token-CANARY-777"

private class FakeBridge(
    var appPresent: Boolean = true,
    var user: Boolean = false,
    var token: String? = TOKEN,
    var signInError: Exception? = null,
    var tokenError: Exception? = null,
    private val signInDelayMs: Long = 0
) : FirebaseAuthBridge {
    val signIns = AtomicInteger()

    override fun isFirebaseAppPresent() = appPresent

    @Synchronized
    override fun hasCurrentUser() = user

    override fun signInAnonymously() {
        signIns.incrementAndGet()
        if (signInDelayMs > 0) Thread.sleep(signInDelayMs)
        signInError?.let { throw it }
        synchronized(this) { user = true }
    }

    override fun getIdToken(forceRefresh: Boolean): String? {
        tokenError?.let { throw it }
        return token
    }
}

private class RevokedException : Exception("revoked")

class FirebaseBackendIdentityTokenProviderTest {

    private fun provider(
        bridge: FirebaseAuthBridge,
        bootstrap: Boolean = true
    ) = FirebaseBackendIdentityTokenProvider(
        bridge,
        bootstrap,
        classify = { if (it is RevokedException) BackendIdentityFailureKind.Revoked else classifyFirebaseFailure(it) }
    )

    private fun kindOf(r: BackendIdentityTokenResult) = (r as BackendIdentityTokenResult.Failure).kind

    @Test
    fun noFirebaseApp_isUnconfigured() {
        val r = provider(FakeBridge(appPresent = false)).getIdTokenForRequest()
        assertEquals(BackendIdentityFailureKind.Unconfigured, kindOf(r))
    }

    @Test
    fun noUser_bootstrapsAnonymously_thenReturnsToken() {
        val bridge = FakeBridge()
        val r = provider(bridge).getIdTokenForRequest()
        assertEquals(TOKEN, (r as BackendIdentityTokenResult.Token).reveal())
        assertEquals(1, bridge.signIns.get())
    }

    @Test
    fun noUser_bootstrapDisabled_isSignedOut_withoutSigningIn() {
        val bridge = FakeBridge()
        val r = provider(bridge, bootstrap = false).getIdTokenForRequest()
        assertEquals(BackendIdentityFailureKind.SignedOut, kindOf(r))
        assertEquals(0, bridge.signIns.get())
    }

    @Test
    fun signOutAfterToken_nextCallBootstrapsNewUserOnlyIfAllowed() {
        val bridge = FakeBridge(user = true)
        val p = provider(bridge, bootstrap = false)
        assertTrue(p.getIdTokenForRequest() is BackendIdentityTokenResult.Token)
        synchronized(bridge) { bridge.user = false }
        assertEquals(BackendIdentityFailureKind.SignedOut, kindOf(p.getIdTokenForRequest()))
    }

    @Test
    fun concurrentFirstCallers_bootstrapExactlyOnce() {
        val bridge = FakeBridge(signInDelayMs = 30)
        val p = provider(bridge)
        val n = 12
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val futures = (1..n).map {
            pool.submit<BackendIdentityTokenResult> { start.await(); p.getIdTokenForRequest() }
        }
        start.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(1, bridge.signIns.get())
        assertTrue(results.all { it is BackendIdentityTokenResult.Token })
    }

    @Test
    fun offlineDuringBootstrap_isOffline_andNextCallMayRetryBootstrap() {
        val bridge = FakeBridge(signInError = ExecutionException(IOException("net down")))
        val p = provider(bridge)
        assertEquals(BackendIdentityFailureKind.Offline, kindOf(p.getIdTokenForRequest()))
        bridge.signInError = null
        assertTrue(p.getIdTokenForRequest() is BackendIdentityTokenResult.Token)
    }

    @Test
    fun refreshFailure_isRefreshFailed_andBlankTokenToo() {
        val bridge = FakeBridge(user = true, tokenError = ExecutionException(IllegalStateException("x")))
        val p = provider(bridge)
        assertEquals(BackendIdentityFailureKind.RefreshFailed, kindOf(p.getIdTokenForRequest()))
        bridge.tokenError = null
        bridge.token = " "
        assertEquals(BackendIdentityFailureKind.RefreshFailed, kindOf(p.getIdTokenForRequest()))
    }

    @Test
    fun revoked_latches_andDoesNotSilentlyMintNewAnonymousUid() {
        val bridge = FakeBridge(user = true, tokenError = RevokedException())
        val p = provider(bridge)
        assertEquals(BackendIdentityFailureKind.Revoked, kindOf(p.getIdTokenForRequest()))
        // SDK signed the user out on revocation; provider must not re-bootstrap on its own.
        bridge.tokenError = null
        synchronized(bridge) { bridge.user = false }
        assertEquals(BackendIdentityFailureKind.Revoked, kindOf(p.getIdTokenForRequest()))
        assertEquals(0, bridge.signIns.get())
        p.clearRevoked()
        assertTrue(p.getIdTokenForRequest() is BackendIdentityTokenResult.Token)
        assertEquals(1, bridge.signIns.get())
    }

    @Test
    fun exceptionMessagesWithTokenAreNeverPartOfTheResult() {
        val bridge = FakeBridge(user = true, tokenError = RuntimeException("leaky $TOKEN"))
        val r = provider(bridge).getIdTokenForRequest()
        assertFalse(r.toString().contains(TOKEN))
    }

    @Test
    fun classification_walksCauseChain_byClassName() {
        assertEquals(
            BackendIdentityFailureKind.Revoked,
            classifyExceptionClassName("com.google.firebase.auth.FirebaseAuthInvalidUserException")
        )
        assertEquals(
            BackendIdentityFailureKind.Offline,
            classifyExceptionClassName("com.google.firebase.FirebaseNetworkException")
        )
        assertNull(classifyExceptionClassName("java.lang.RuntimeException"))
        assertEquals(
            BackendIdentityFailureKind.Offline,
            classifyFirebaseFailure(ExecutionException(RuntimeException(IOException("x"))))
        )
        assertEquals(
            BackendIdentityFailureKind.RefreshFailed,
            classifyFirebaseFailure(IllegalStateException("Must not be called on the main thread"))
        )
    }
}
