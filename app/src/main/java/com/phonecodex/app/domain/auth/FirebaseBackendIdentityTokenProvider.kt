package com.phonecodex.app.domain.auth

import android.content.Context
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/**
 * Firebase Auth (anonymous) -> ID token. Compiles WITHOUT the Firebase SDK: the SDK is
 * reached by reflection through [ReflectiveFirebaseAuthBridge], so credential-free
 * `phoneTest`/unit builds neither need google-services.json nor Firebase artifacts.
 *
 * Becomes live only when the owner adds the Firebase dependency + google-services plugin
 * + real google-services.json (see PHONECODEX-P0-AUTH-SCAFFOLD.md) AND the build gate
 * `STRLIX_USE_FIREBASE_ID_AUTH` is on. Until a Firebase app exists in the process it
 * reports Unconfigured.
 *
 * Revocation: after [BackendIdentityFailureKind.Revoked] the provider will NOT silently
 * mint a fresh anonymous UID (that would dodge per-UID limits). Call [clearRevoked] from an
 * explicit user action to allow a new bootstrap.
 */
class FirebaseBackendIdentityTokenProvider internal constructor(
    private val bridge: FirebaseAuthBridge,
    private val allowAnonymousBootstrap: Boolean = true,
    private val classify: (Throwable) -> BackendIdentityFailureKind = ::classifyFirebaseFailure
) : BackendIdentityTokenProvider {

    private val bootstrapLock = Any()

    @Volatile
    private var revoked = false

    fun clearRevoked() {
        revoked = false
    }

    override fun getIdTokenForRequest(forceRefresh: Boolean): BackendIdentityTokenResult {
        if (!bridge.isFirebaseAppPresent()) return failure(BackendIdentityFailureKind.Unconfigured)
        if (revoked) return failure(BackendIdentityFailureKind.Revoked)
        return try {
            if (!bridge.hasCurrentUser()) {
                if (!allowAnonymousBootstrap) return failure(BackendIdentityFailureKind.SignedOut)
                synchronized(bootstrapLock) {
                    if (!bridge.hasCurrentUser()) bridge.signInAnonymously()
                }
            }
            val token = bridge.getIdToken(forceRefresh)
            when {
                token == null && !bridge.hasCurrentUser() -> failure(BackendIdentityFailureKind.SignedOut)
                token.isNullOrBlank() -> failure(BackendIdentityFailureKind.RefreshFailed)
                else -> BackendIdentityTokenResult.Token(token)
            }
        } catch (err: Exception) {
            val kind = classify(err)
            if (kind == BackendIdentityFailureKind.Revoked) revoked = true
            failure(kind)
        }
    }

    private fun failure(kind: BackendIdentityFailureKind) = BackendIdentityTokenResult.Failure(kind)

    companion object {
        /** Real provider if the Firebase SDK class is on the classpath, else Unconfigured. */
        fun create(context: Context): BackendIdentityTokenProvider =
            if (ReflectiveFirebaseAuthBridge.sdkOnClasspath()) {
                FirebaseBackendIdentityTokenProvider(
                    ReflectiveFirebaseAuthBridge(context.applicationContext)
                )
            } else {
                UnconfiguredBackendIdentityTokenProvider
            }
    }
}

/** Test seam over the Firebase SDK. Implementations may throw; the provider classifies. */
internal interface FirebaseAuthBridge {
    fun isFirebaseAppPresent(): Boolean
    fun hasCurrentUser(): Boolean
    fun signInAnonymously()
    fun getIdToken(forceRefresh: Boolean): String?
}

/**
 * Maps SDK exceptions to failure kinds by class name (no SDK import). Walks the cause chain
 * because Task.await wraps errors in ExecutionException.
 */
internal fun classifyFirebaseFailure(error: Throwable): BackendIdentityFailureKind {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 8) {
        val kind = classifyExceptionClassName(current.javaClass.name)
            ?: if (current is IOException) BackendIdentityFailureKind.Offline else null
        if (kind != null) return kind
        current = current.cause
        depth++
    }
    return BackendIdentityFailureKind.RefreshFailed
}

internal fun classifyExceptionClassName(name: String): BackendIdentityFailureKind? = when (name) {
    "com.google.firebase.auth.FirebaseAuthInvalidUserException",
    "com.google.firebase.auth.FirebaseAuthInvalidCredentialsException" ->
        BackendIdentityFailureKind.Revoked
    "com.google.firebase.FirebaseNetworkException" -> BackendIdentityFailureKind.Offline
    else -> null
}

internal class ReflectiveFirebaseAuthBridge(private val context: Context) : FirebaseAuthBridge {

    override fun isFirebaseAppPresent(): Boolean = try {
        val apps = cls(APP).getMethod("getApps", Context::class.java).invoke(null, context)
        (apps as? Collection<*>)?.isNotEmpty() == true
    } catch (_: Exception) {
        false
    }

    override fun hasCurrentUser(): Boolean = currentUser() != null

    override fun signInAnonymously() {
        val task = call { cls(AUTH).getMethod("signInAnonymously").invoke(auth()) }
        await(task)
    }

    override fun getIdToken(forceRefresh: Boolean): String? {
        val user = currentUser() ?: return null
        val task = call {
            cls(USER).getMethod("getIdToken", java.lang.Boolean.TYPE).invoke(user, forceRefresh)
        }
        val result = await(task) ?: return null
        return call { cls(TOKEN_RESULT).getMethod("getToken").invoke(result) } as? String
    }

    private fun auth(): Any = call { cls(AUTH).getMethod("getInstance").invoke(null) }!!

    private fun currentUser(): Any? = call { cls(AUTH).getMethod("getCurrentUser").invoke(auth()) }

    private fun await(task: Any?): Any? {
        if (task == null) return null
        val taskClass = cls(TASK)
        return call {
            cls(TASKS)
                .getMethod("await", taskClass, java.lang.Long.TYPE, TimeUnit::class.java)
                .invoke(null, task, AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        }
    }

    /** Unwraps reflection and Task.await wrappers so classification sees the SDK exception. */
    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (err: InvocationTargetException) {
        throw err.targetException ?: err
    }

    private fun cls(name: String): Class<*> = Class.forName(name)

    companion object {
        private const val APP = "com.google.firebase.FirebaseApp"
        private const val AUTH = "com.google.firebase.auth.FirebaseAuth"
        private const val USER = "com.google.firebase.auth.FirebaseUser"
        private const val TOKEN_RESULT = "com.google.firebase.auth.GetTokenResult"
        private const val TASK = "com.google.android.gms.tasks.Task"
        private const val TASKS = "com.google.android.gms.tasks.Tasks"
        private const val AWAIT_TIMEOUT_SEC = 10L

        fun sdkOnClasspath(): Boolean = try {
            Class.forName(AUTH)
            Class.forName(TASKS)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}
