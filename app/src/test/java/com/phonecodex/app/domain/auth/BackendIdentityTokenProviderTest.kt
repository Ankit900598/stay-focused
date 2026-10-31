package com.phonecodex.app.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val FAKE_TOKEN = "header.payload-SECRET-CANARY-1234.signature"
private const val FAKE_SHARED_SECRET = "shared-secret-canary-9876"

/** Credential-free fake: scripted results, call counting, sign-out. */
internal class FakeBackendIdentityTokenProvider(
    @Volatile var next: BackendIdentityTokenResult =
        BackendIdentityTokenResult.Token(FAKE_TOKEN),
    private val delayMs: Long = 0
) : BackendIdentityTokenProvider {
    val calls = AtomicInteger()
    val forceRefreshCalls = AtomicInteger()

    fun signOut() {
        next = BackendIdentityTokenResult.Failure(BackendIdentityFailureKind.SignedOut)
    }

    override fun getIdTokenForRequest(forceRefresh: Boolean): BackendIdentityTokenResult {
        calls.incrementAndGet()
        if (forceRefresh) forceRefreshCalls.incrementAndGet()
        if (delayMs > 0) Thread.sleep(delayMs)
        return next
    }
}

class BackendIdentityTokenProviderTest {

    private fun resolve(
        provider: BackendIdentityTokenProvider,
        useBearer: Boolean = true,
        secret: String = "",
        allowSecret: Boolean = true,
        log: (String) -> Unit = {}
    ) = BackendAuthHeaders.resolve(useBearer, secret, allowSecret, provider, log = log)

    @Test
    fun unconfigured_reportsUnconfigured_andGateOnSendsNoCredential() {
        val r = UnconfiguredBackendIdentityTokenProvider.getIdTokenForRequest()
        assertEquals(
            BackendIdentityTokenResult.Failure(BackendIdentityFailureKind.Unconfigured),
            r
        )
        val resolution = resolve(UnconfiguredBackendIdentityTokenProvider, secret = FAKE_SHARED_SECRET)
        assertTrue(resolution.headers.isEmpty())
        assertEquals(BackendIdentityFailureKind.Unconfigured, resolution.failure)
    }

    @Test
    fun gateOn_tokenBecomesBearerHeader_andSharedSecretIsNotSent() {
        val resolution = resolve(FakeBackendIdentityTokenProvider(), secret = FAKE_SHARED_SECRET)
        assertEquals(mapOf("Authorization" to "Bearer $FAKE_TOKEN"), resolution.headers)
        assertNull(resolution.failure)
    }

    @Test
    fun gateOff_usesSharedSecretOnly_andNeverAsksProvider() {
        val provider = FakeBackendIdentityTokenProvider()
        val resolution = resolve(provider, useBearer = false, secret = " $FAKE_SHARED_SECRET ")
        assertEquals(mapOf("X-Strlix-App-Secret" to FAKE_SHARED_SECRET), resolution.headers)
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun gateOff_releaseNeverSendsSharedSecret_andEmptySecretSendsNothing() {
        val provider = FakeBackendIdentityTokenProvider()
        assertTrue(resolve(provider, useBearer = false, secret = FAKE_SHARED_SECRET, allowSecret = false).headers.isEmpty())
        assertTrue(resolve(provider, useBearer = false, secret = "  ").headers.isEmpty())
    }

    @Test
    fun signOut_afterToken_failsClosedWithoutFallbackToSecret() {
        val provider = FakeBackendIdentityTokenProvider()
        assertTrue(resolve(provider, secret = FAKE_SHARED_SECRET).headers.containsKey("Authorization"))
        provider.signOut()
        val after = resolve(provider, secret = FAKE_SHARED_SECRET)
        assertTrue(after.headers.isEmpty())
        assertEquals(BackendIdentityFailureKind.SignedOut, after.failure)
    }

    @Test
    fun everyFailureKind_failsClosed_andProviderIsNotRetried() {
        for (kind in BackendIdentityFailureKind.entries) {
            val provider = FakeBackendIdentityTokenProvider(BackendIdentityTokenResult.Failure(kind))
            val resolution = resolve(provider, secret = FAKE_SHARED_SECRET)
            assertTrue("$kind", resolution.headers.isEmpty())
            assertEquals(kind, resolution.failure)
            assertEquals("no silent retry for $kind", 1, provider.calls.get())
        }
    }

    @Test
    fun refreshFailure_isSurfacedAsRefreshFailed_whenProviderThrows() {
        val throwing = object : BackendIdentityTokenProvider {
            override fun getIdTokenForRequest(forceRefresh: Boolean): BackendIdentityTokenResult =
                throw IllegalStateException("boom $FAKE_TOKEN")
        }
        val logs = mutableListOf<String>()
        val resolution = resolve(throwing, secret = FAKE_SHARED_SECRET, log = { logs += it })
        assertTrue(resolution.headers.isEmpty())
        assertEquals(BackendIdentityFailureKind.RefreshFailed, resolution.failure)
        assertTrue(logs.none { it.contains(FAKE_TOKEN) })
    }

    @Test
    fun forceRefreshIsForwarded() {
        val provider = FakeBackendIdentityTokenProvider()
        BackendAuthHeaders.resolve(true, "", true, provider, forceRefresh = true)
        assertEquals(1, provider.forceRefreshCalls.get())
    }

    @Test
    fun noTokenOrSecretEverReachesLogsOrToString() {
        val logs = mutableListOf<String>()
        val ok = FakeBackendIdentityTokenProvider()
        resolve(ok, secret = FAKE_SHARED_SECRET, log = { logs += it })
        resolve(ok, useBearer = false, secret = FAKE_SHARED_SECRET, log = { logs += it })
        for (kind in BackendIdentityFailureKind.entries) {
            resolve(
                FakeBackendIdentityTokenProvider(BackendIdentityTokenResult.Failure(kind)),
                secret = FAKE_SHARED_SECRET,
                log = { logs += it }
            )
        }
        assertTrue("failures must log something", logs.isNotEmpty())
        assertTrue(logs.none { it.contains(FAKE_TOKEN) || it.contains(FAKE_SHARED_SECRET) })
        val token = BackendIdentityTokenResult.Token(FAKE_TOKEN)
        assertFalse(token.toString().contains(FAKE_TOKEN))
    }

    @Test
    fun blankTokenIsRejected() {
        val result = runCatching { BackendIdentityTokenResult.Token("  ") }
        assertTrue(result.isFailure)
    }

    @Test
    fun concurrentCallers_allGetTheirOwnResolution_withoutCrossTalk() {
        val provider = FakeBackendIdentityTokenProvider(delayMs = 5)
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val results = (1..threads).map {
            pool.submit<BackendAuthResolution> {
                start.await()
                resolve(provider, secret = FAKE_SHARED_SECRET)
            }
        }
        start.countDown()
        val resolved = results.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(threads, provider.calls.get())
        assertTrue(resolved.all { it.headers == mapOf("Authorization" to "Bearer $FAKE_TOKEN") })
    }

    @Test
    fun sharedHolder_defaultsToUnconfigured_andReplacementIsSharedAcrossCallers() {
        BackendIdentityTokenProviders.replaceForTesting(null)
        try {
            assertEquals(UnconfiguredBackendIdentityTokenProvider, BackendIdentityTokenProviders.shared)
            val fake = FakeBackendIdentityTokenProvider()
            BackendIdentityTokenProviders.replaceForTesting(fake)
            // "Activity" and "accessibility service" read the same instance.
            assertTrue(BackendIdentityTokenProviders.shared === fake)
            assertTrue(BackendIdentityTokenProviders.shared === BackendIdentityTokenProviders.shared)
        } finally {
            BackendIdentityTokenProviders.replaceForTesting(null)
        }
    }
}
