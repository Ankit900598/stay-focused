package com.phonecodex.app.mailtask

import android.accounts.Account
import android.app.Activity
import android.content.IntentSender
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.phonecodex.app.connector.AccessToken
import com.phonecodex.app.connector.gmail.GmailRestConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Google identity + Gmail consent on the phone, via the official Credential Manager and
 * Identity AuthorizationClient APIs. The user signs in on Google's own screens; no
 * embedded credentials, no WebView.
 *
 * - Sign in with Google picks the account and yields an ID token (audience = Strlix web
 *   client) that proves the user to Strlix's backend. It grants no Gmail access.
 * - gmail.readonly and gmail.send are requested separately, each on first real use, for the
 *   linked account only.
 * Tokens live in this object's memory; nothing is written to disk or logged.
 */
class GoogleMailAuthorizer(
    private val activity: Activity,
    private val webClientId: String,
    private val launchIntentSender: suspend (IntentSender) -> ActivityResult?,
    private val clock: () -> Long = System::currentTimeMillis
) : MailAuthorizer {

    val isConfigured: Boolean get() = webClientId.isNotBlank()

    private class Cached(val token: AccessToken, val expiresAtMillis: Long)
    private class CachedId(val token: String, val email: String, val expiresAtMillis: Long)

    private var read: Cached? = null
    private var send: Cached? = null
    private var identity: CachedId? = null

    /** Account chooser (always shown, so multiple Google accounts are explicit). */
    suspend fun signIn(): SignInResult {
        if (!isConfigured) return SignInResult.NotConfigured
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        return when (val r = requestIdToken(GetCredentialRequest.Builder().addCredentialOption(option).build())) {
            is IdResult.Ok -> {
                if (identity?.email != null && identity?.email != r.cached.email) clear()
                identity = r.cached
                SignInResult.Ok(r.cached.email)
            }
            IdResult.Cancelled -> SignInResult.Declined
            IdResult.Failed -> SignInResult.Failed
        }
    }

    override suspend fun identityToken(account: String): String? {
        if (!isConfigured) return null
        val want = account.trim().lowercase()
        identity?.let { if (it.email == want && clock() < it.expiresAtMillis) return it.token }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build()
        return when (val r = requestIdToken(GetCredentialRequest.Builder().addCredentialOption(option).build())) {
            is IdResult.Ok -> if (r.cached.email == want) {
                identity = r.cached
                r.cached.token
            } else null
            else -> null
        }
    }

    override suspend fun readToken(account: String): TokenResult =
        token(account, GmailRestConnector.SCOPE_READONLY, read) { read = it }

    override suspend fun sendToken(account: String): TokenResult =
        token(account, GmailRestConnector.SCOPE_SEND, send) { send = it }

    override fun invalidate(token: AccessToken) {
        if (read?.token?.value == token.value) read = null
        if (send?.token?.value == token.value) send = null
    }

    override fun clear() {
        read = null
        send = null
        identity = null
    }

    /** Capture tokens and erase them synchronously before any disconnect UI starts waiting. */
    fun prepareRevocation(): suspend () -> Boolean {
        val tokens = listOfNotNull(read?.token?.value, send?.token?.value).distinct()
        clear()
        return { revokeTokensAtGoogle(tokens) }
    }

    /** Revokes Strlix's Gmail grant at Google (all scopes for this app+account). */
    suspend fun revokeAtGoogle(): Boolean = prepareRevocation().invoke()

    private suspend fun revokeTokensAtGoogle(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            tokens.map { t ->
                runCatching {
                    val conn = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write("token=${URLEncoder.encode(t, "UTF-8")}".toByteArray()) }
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 200..299
                }.getOrDefault(false)
            }.any { it }
        }
    }

    private suspend fun token(account: String, scope: String, cached: Cached?, store: (Cached) -> Unit): TokenResult {
        if (!isConfigured) return TokenResult.NotConfigured
        val want = account.trim().lowercase()
        if (cached != null && cached.token.account == want && clock() < cached.expiresAtMillis) return TokenResult.Ok(cached.token)
        val client = Identity.getAuthorizationClient(activity)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(scope)))
            .setAccount(Account(want, "com.google"))
            .build()
        val first = try {
            client.authorize(request).awaitResult()
        } catch (e: IOException) {
            return TokenResult.Offline
        } catch (e: ApiException) {
            Log.w(TAG, "authorize failed status=${e.statusCode}")
            return if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) TokenResult.Offline else TokenResult.Failed
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed ${e.javaClass.simpleName}")
            return TokenResult.Failed
        }
        val result = if (first.hasResolution()) {
            val sender = first.pendingIntent?.intentSender ?: return TokenResult.Failed
            val activityResult = launchIntentSender(sender) ?: return TokenResult.Declined
            if (activityResult.resultCode != Activity.RESULT_OK) return TokenResult.Declined
            try {
                client.getAuthorizationResultFromIntent(activityResult.data)
            } catch (e: ApiException) {
                Log.w(TAG, "consent result status=${e.statusCode}")
                return TokenResult.Declined
            }
        } else first
        return accessTokenFrom(result, want, scope)?.let {
            store(Cached(it, clock() + ACCESS_TOKEN_TTL_MS))
            TokenResult.Ok(it)
        } ?: TokenResult.Declined
    }

    private fun accessTokenFrom(result: AuthorizationResult, account: String, scope: String): AccessToken? {
        val value = result.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val granted = result.grantedScopes.toSet()
        // A user can untick a scope on the consent screen: no scope, no token.
        if (scope !in granted) return null
        return AccessToken(value = value, grantedScopes = granted, account = account)
    }

    private sealed class IdResult {
        class Ok(val cached: CachedId) : IdResult()
        data object Cancelled : IdResult()
        data object Failed : IdResult()
    }

    private suspend fun requestIdToken(request: GetCredentialRequest): IdResult {
        val response = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            return IdResult.Cancelled
        } catch (e: NoCredentialException) {
            return IdResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.w(TAG, "sign-in failed type=${e.type}")
            return IdResult.Failed
        }
        val cred = response.credential
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return IdResult.Failed
        }
        val google = runCatching { GoogleIdTokenCredential.createFrom(cred.data) }.getOrNull() ?: return IdResult.Failed
        val email = google.id.trim().lowercase()
        val exp = jwtExpiryMillis(google.idToken) ?: (clock() + 50 * 60 * 1000L)
        return IdResult.Ok(CachedId(google.idToken, email, exp - ID_TOKEN_EARLY_REFRESH_MS))
    }

    private fun jwtExpiryMillis(jwt: String): Long? = runCatching {
        val payload = jwt.split(".")[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        JSONObject(json).getLong("exp") * 1000L
    }.getOrNull()

    sealed class SignInResult {
        data class Ok(val email: String) : SignInResult()
        data object NotConfigured : SignInResult()
        data object Declined : SignInResult()
        data object Failed : SignInResult()
    }

    private companion object {
        const val TAG = "StrlixMailAuth"
        const val ACCESS_TOKEN_TTL_MS = 45 * 60 * 1000L
        const val ID_TOKEN_EARLY_REFRESH_MS = 5 * 60 * 1000L
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { e -> cont.resumeWith(Result.failure(e)) }
    addOnCanceledListener { cont.cancel() }
}
