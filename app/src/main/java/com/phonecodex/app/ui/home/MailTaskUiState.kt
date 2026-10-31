package com.phonecodex.app.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.IntentSender
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.phonecodex.app.BuildConfig
import com.phonecodex.app.connector.UrlConnectionTransport
import com.phonecodex.app.connector.gmail.GmailRestConnector
import com.phonecodex.app.data.EventLogStore
import com.phonecodex.app.data.MailDelegationStore
import com.phonecodex.app.domain.delegation.EmailAddress
import com.phonecodex.app.domain.delegation.MailDelegationGrant
import com.phonecodex.app.domain.delegation.MailDelegationOfferLaw
import com.phonecodex.app.domain.delegation.MailDelegationRequest
import com.phonecodex.app.domain.delegation.MailHelperOffer
import com.phonecodex.app.domain.delegation.MailSendLedger
import com.phonecodex.app.domain.diagnostics.BackendConfig
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.mailtask.BackendMailAssistant
import com.phonecodex.app.mailtask.GoogleMailAuthorizer
import com.phonecodex.app.mailtask.MailTaskController
import com.phonecodex.app.mailtask.MailThreadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The mail helper's UI state: the preview choices before Start, then the live task thread
 * while the commitment runs. The grant is created only from what the user confirmed here.
 */
@Stable
internal class MailTaskUiState(
    private val store: MailDelegationStore,
    private val scope: CoroutineScope,
    private val sessionSource: () -> FocusSession?,
    private val authorizer: GoogleMailAuthorizer,
    private val eventLog: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    // --- Preview (before Start) ---
    var request by mutableStateOf<MailDelegationRequest?>(null)
        private set
    var offer by mutableStateOf(MailHelperOffer.NONE)
        private set
    var helperOn by mutableStateOf(false)
    var addressInput by mutableStateOf("")
    var aiConsent by mutableStateOf(false)
    var allowSend by mutableStateOf(false)

    // --- Live thread (during the commitment) ---
    var thread by mutableStateOf<MailThreadState?>(null)
        private set
    var composerText by mutableStateOf("")
    var accountMessage by mutableStateOf<String?>(null)
        private set
    var accountActionBusy by mutableStateOf(false)
        private set
    private var grantDisabledForSession = false

    val isConfigured: Boolean get() = authorizer.isConfigured

    private val log: (String) -> Unit = { line ->
        Log.i(TAG, line)
        eventLog(line)
    }

    val controller = MailTaskController(
        grantSource = { if (grantDisabledForSession) null else store.liveGrant(sessionSource(), clock()) },
        sessionSource = sessionSource,
        saveGrant = store::saveGrant,
        connector = GmailRestConnector(UrlConnectionTransport()),
        authorizer = authorizer,
        assistant = BackendMailAssistant(BuildConfig.MAIL_ASSIST_URL, BackendConfig.appSecret, UrlConnectionTransport(readTimeoutMs = 45_000)),
        ledger = MailSendLedger(store),
        // No network-state permission: offline shows up as a failed connect (nothing sent).
        isOnline = { true },
        clock = clock,
        log = log,
        onState = { thread = it }
    )

    val helperAvailable: Boolean
        get() = offer == MailHelperOffer.REQUESTED || offer == MailHelperOffer.OPTIONAL

    fun onPreview(req: MailDelegationRequest, draft: FocusPromise) {
        request = req
        offer = MailDelegationOfferLaw.decide(req, MailDelegationOfferLaw.draftClosesGmail(draft))
        helperOn = offer == MailHelperOffer.REQUESTED
        addressInput = req.senderAddressInText?.value.orEmpty()
        aiConsent = false
        allowSend = req.wantsSend
        log("MAIL_HELPER preview offer=$offer alerts=${req.wantsProactiveAlerts}")
    }

    fun resetPreview() {
        request = null
        offer = MailHelperOffer.NONE
        helperOn = false
        addressInput = ""
        aiConsent = false
        allowSend = false
    }

    val parsedAddress: EmailAddress? get() = EmailAddress.parseUserEntry(addressInput)

    /** Non-null = Start must not proceed yet. */
    fun startBlockReason(): String? {
        if (!helperAvailable || !helperOn) return null
        if (parsedAddress == null) return "Type the exact email address Strlix may check, or turn the mail helper off."
        return null
    }

    /** After the commitment is saved: bind the confirmed exception to that exact session. */
    fun bindToSession(session: FocusSession) {
        grantDisabledForSession = false
        controller.beginNewCommitment()
        val address = parsedAddress
        if (!helperAvailable || !helperOn || address == null) {
            store.clearGrant()
            controller.onCommitmentEnded()
            thread = null
            return
        }
        val saved = store.saveGrant(
            MailDelegationGrant(
                sessionId = session.id,
                senderAddress = address,
                aiAssistConsent = aiConsent,
                allowSend = allowSend,
                createdAtMillis = clock()
            )
        )
        if (!saved) {
            accountMessage = "I couldn't save the mail permission safely. The mail helper stays off for this commitment."
            controller.onCommitmentEnded()
            return
        }
        log("MAIL_HELPER grant=CREATED ai=$aiConsent send=$allowSend")
        resetPreview()
        controller.start()
    }

    /** Call when the session may have changed (bootstrap, poll, end). */
    fun refresh() {
        val live = store.liveGrant(sessionSource(), clock())
        if (live == null) {
            if (thread != null) {
                controller.onCommitmentEnded()
                thread = null
                log("MAIL_HELPER grant=ENDED")
            }
            return
        }
        if (thread == null || thread?.turns.isNullOrEmpty()) controller.start()
    }

    fun onCommitmentEnded() {
        store.clearGrant()
        controller.onCommitmentEnded()
        thread = null
        composerText = ""
        accountMessage = null
        log("MAIL_HELPER grant=ENDED")
    }

    fun connectAccount() {
        if (accountActionBusy) return
        accountActionBusy = true
        accountMessage = null
        scope.launch {
            try {
                when (val r = authorizer.signIn()) {
                    is GoogleMailAuthorizer.SignInResult.Ok -> controller.onAccountLinked(r.email)
                    GoogleMailAuthorizer.SignInResult.NotConfigured ->
                        accountMessage = "The mail helper isn't set up in this build yet."
                    GoogleMailAuthorizer.SignInResult.Declined -> accountMessage = "Not connected. You can try again."
                    GoogleMailAuthorizer.SignInResult.Failed ->
                        accountMessage = "Google sign-in failed. Check your connection and try again."
                }
            } finally {
                accountActionBusy = false
            }
        }
    }

    fun disconnect() {
        if (accountActionBusy) return
        accountActionBusy = true
        accountMessage = "Disconnecting from Google…"
        val revokeAtGoogle = authorizer.prepareRevocation()
        val currentGrant = store.grant()
        val grantSaved = runCatching {
            currentGrant?.let {
                store.saveGrant(it.copy(accountEmail = null, aiAssistConsent = false, allowSend = false))
            } ?: store.clearGrant()
        }.getOrDefault(false)
        var fallbackClearSaved = false
        var sessionDenySaved = false
        if (!grantSaved) {
            fallbackClearSaved = runCatching { store.clearGrant() }.getOrDefault(false)
            if (!fallbackClearSaved) {
                sessionDenySaved = currentGrant?.let { store.disableSession(it.sessionId) } == true
            }
            grantDisabledForSession = true
        } else {
            grantDisabledForSession = false
        }
        controller.onAccountDisconnected()
        scope.launch {
            try {
                val revoked = try {
                    revokeAtGoogle()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                accountMessage = when {
                    !grantSaved && sessionDenySaved -> "Google tokens were cleared from memory. I couldn't remove the old grant, so mail is disabled for this commitment until it ends."
                    !grantSaved && fallbackClearSaved -> "Google tokens were cleared from memory and the local grant was removed. Start a new commitment before reconnecting."
                    !grantSaved -> "Google tokens were cleared from memory, but I couldn't persist or remove the local grant. Mail is disabled only while this app stays open; end this commitment and remove Strlix at myaccount.google.com/permissions."
                    revoked -> "Disconnected. Strlix's Gmail access was revoked at Google, and this thread was cleared."
                    else -> "Disconnected on this phone, and this thread was cleared. To be sure Google access is revoked, remove Strlix at myaccount.google.com/permissions."
                }
                log("MAIL_HELPER action=DISCONNECT revoked=$revoked localSaved=$grantSaved fallbackClear=$fallbackClearSaved sessionDeny=$sessionDenySaved")
            } finally {
                accountActionBusy = false
            }
        }
    }

    fun submitComposer() {
        val text = composerText
        if (text.isBlank() || thread?.busy == true) return
        composerText = ""
        scope.launch { controller.submitTyped(text) }
    }

    fun check() = scope.launch { controller.check() }
    fun showText(id: String) = scope.launch { controller.showText(id) }
    fun summarize(id: String) = scope.launch { controller.summarize(id) }
    fun draftReply(id: String) = scope.launch { controller.draftReply(id, "") }
    fun writeMyself(id: String) = controller.writeReplyMyself(id)
    fun enableAi() = controller.enableAiConsent()
    fun editDraft(draftId: String, body: String) = controller.editDraft(draftId, body)
    fun reviewSend(draftId: String) = controller.reviewSend(draftId)
    fun cancelSend() = controller.cancelSend()
    fun confirmSend(draftId: String) = scope.launch { controller.confirmSend(draftId) }
    fun checkSent(draftId: String) = scope.launch { controller.checkSent(draftId) }

    private companion object {
        const val TAG = "StrlixMailTask"
    }
}

/** Launches Google's consent screen from a suspend call and returns its result. */
internal class IntentSenderBridge {
    var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<ActivityResult?>? = null

    suspend fun launch(sender: IntentSender): ActivityResult? {
        val l = launcher ?: return null
        pending?.complete(null)
        val deferred = CompletableDeferred<ActivityResult?>()
        pending = deferred
        return try {
            l.launch(IntentSenderRequest.Builder(sender).build())
            deferred.await()
        } catch (_: Exception) {
            null
        } finally {
            if (pending === deferred) pending = null
        }
    }

    fun complete(result: ActivityResult) {
        pending?.complete(result)
    }
}

@Composable
internal fun rememberMailTaskUiState(home: HomeScreenState): MailTaskUiState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { IntentSenderBridge() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        bridge.complete(it)
    }
    bridge.launcher = launcher
    val mail = remember {
        val activity = context.findActivity()
        val appContext = context.applicationContext
        val eventLogStore = EventLogStore(appContext)
        MailTaskUiState(
            store = MailDelegationStore(appContext),
            scope = scope,
            sessionSource = { home.session },
            authorizer = GoogleMailAuthorizer(
                activity = activity ?: error("Mail helper needs an Activity"),
                webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
                launchIntentSender = bridge::launch
            ),
            eventLog = eventLogStore::addEvent
        )
    }
    home.mailTask = mail
    return mail
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
