package com.phonecodex.app.mailtask

import com.phonecodex.app.connector.AccessToken
import com.phonecodex.app.connector.MailMessage

/** Model output for one message. Plain data: nothing in it can trigger an action. */
data class MailSummary(
    val summary: String,
    val keyPoints: List<String>,
    val requestedActions: List<String>,
    val deadlines: List<String>,
    /** The email contains text aimed at an AI/assistant; it was treated as content, not obeyed. */
    val containsInstructionsToAssistant: Boolean
)

enum class AssistFailure { OFFLINE, NOT_SIGNED_IN, CONSENT_REQUIRED, UNAVAILABLE, RATE_LIMITED, FAILED }

sealed class AssistResult<out T> {
    data class Ok<T>(val value: T) : AssistResult<T>()
    data class Failure(val kind: AssistFailure) : AssistResult<Nothing>()
}

/**
 * Strlix backend AI for one user-selected message. The caller has already checked consent;
 * implementations must never log message content.
 */
interface MailAssistant {
    suspend fun summarize(identityToken: String, message: MailMessage): AssistResult<MailSummary>
    suspend fun draftReply(identityToken: String, message: MailMessage, userNote: String): AssistResult<String>
}

sealed class TokenResult {
    data class Ok(val token: AccessToken) : TokenResult()
    /** No Google client configured in this build. */
    data object NotConfigured : TokenResult()
    /** The user closed or declined the Google consent screen. */
    data object Declined : TokenResult()
    data object Offline : TokenResult()
    data object Failed : TokenResult()
}

/**
 * OAuth on the phone. Read and send are separate consents; tokens live in memory only.
 */
interface MailAuthorizer {
    suspend fun readToken(account: String): TokenResult
    suspend fun sendToken(account: String): TokenResult
    /** Google ID token proving who the user is to Strlix's backend (not a Gmail token). */
    suspend fun identityToken(account: String): String?
    fun invalidate(token: AccessToken)
    fun clear()
}
