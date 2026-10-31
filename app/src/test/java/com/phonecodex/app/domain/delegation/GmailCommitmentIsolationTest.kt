package com.phonecodex.app.domain.delegation

import com.phonecodex.app.data.AppRulesStore
import com.phonecodex.app.data.CommitmentTeardown
import com.phonecodex.app.data.InMemorySharedPreferences
import com.phonecodex.app.data.MailDelegationStore
import com.phonecodex.app.data.SessionStore
import com.phonecodex.app.domain.enforcement.CommitmentPolicyEvaluator
import com.phonecodex.app.domain.enforcement.CommitmentSnapshot
import com.phonecodex.app.domain.enforcement.LawVerdict
import com.phonecodex.app.domain.enforcement.ShortFormQuotaGate
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.FocusPromiseParser
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog
import com.phonecodex.app.ui.home.buildHomeListItems
import com.phonecodex.app.ui.home.HomeListItem
import com.phonecodex.app.ui.home.mailAlertsCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The connector is an exception to a Gmail block, never a hole in it: Gmail stays blocked
 * by deterministic law, the helper can't reach enforcement, and everything ends together.
 */
class GmailCommitmentIsolationTest {

    private val gm = MailDelegationOfferLaw.GMAIL_PACKAGE
    private val goal = "Keep Gmail closed for three hours"

    private fun gmailVerdict(goal: String, rule: AppRuleBehavior?) = CommitmentPolicyEvaluator.evaluate(
        CommitmentSnapshot(
            packageName = gm,
            screenText = "Inbox Primary Promotions Compose",
            sessionStatus = SessionStatus.ACTIVE,
            goal = goal,
            settings = ConfirmedPromiseBinder.bind(FocusPromiseParser().parse(goal)),
            storedAppRule = rule,
            isSafePackage = SafeAppsCatalog.isDefaultPackage(gm)
        ),
        ShortFormQuotaGate()
    ).verdict

    @Test
    fun keepGmailClosed_producesBlockRule_andEvaluatorBlocksGmail() {
        val draft = FocusPromiseParser().parse(goal)
        assertEquals(AppRuleBehavior.BLOCK, draft.suggestedAppRules.single { it.packageName == gm }.behavior)
        assertEquals(LawVerdict.BLOCK, gmailVerdict(goal, AppRuleBehavior.BLOCK))
        for (phrase in listOf("Block Gmail for 2 hours", "No Gmail until 6pm", "gmail is off for 3 hours", "Gmail stays shut for an hour")) {
            val rule = FocusPromiseParser().parse(phrase).suggestedAppRules.firstOrNull { it.packageName == gm }
            assertEquals(phrase, AppRuleBehavior.BLOCK, rule?.behavior)
            assertEquals(phrase, LawVerdict.BLOCK, gmailVerdict(phrase, AppRuleBehavior.BLOCK))
        }
    }

    @Test
    fun unrelatedCommitment_leavesGmailOpen() {
        val draft = FocusPromiseParser().parse("Block Instagram for 1 hour")
        assertTrue(draft.suggestedAppRules.none { it.packageName == gm })
        assertNotEquals(LawVerdict.BLOCK, gmailVerdict("Block Instagram for 1 hour", null))
        for (phrase in listOf("I will check Gmail later", "use gmail for work", "block instagram, gmail is fine")) {
            assertTrue(phrase, FocusPromiseParser().parse(phrase).suggestedAppRules.none { it.packageName == gm && it.behavior == AppRuleBehavior.BLOCK })
        }
    }

    @Test
    fun delegationClause_neverReachesTheCompilerOrTheGoal() {
        val r = MailDelegationIntent.detect(
            "Keep Gmail closed for three hours, but let me ask Strlix to check messages from my professor and reply after I approve."
        )
        assertEquals(goal, r.commitmentText)
        assertFalse(r.commitmentText.contains("check", ignoreCase = true))
        assertEquals(LawVerdict.BLOCK, gmailVerdict(r.commitmentText, AppRuleBehavior.BLOCK))
    }

    @Test
    fun commitmentEnd_restoresGmail_andKillsTheGrant() {
        val sessionStore = SessionStore(InMemorySharedPreferences())
        val rules = AppRulesStore(InMemorySharedPreferences())
        val mailStore = MailDelegationStore(InMemorySharedPreferences())
        val before = rules.getBehaviorForPackage(gm)
        val session = FocusSession("s1", "study", goal, 0L, 10_800_000L, SessionStatus.ACTIVE, 0, StrictnessLevel.STRICT)

        rules.applyCommitmentSuggestions(FocusPromiseParser().parse(goal).suggestedAppRules)
        sessionStore.saveActiveSession(session)
        mailStore.saveGrant(MailDelegationGrant("s1", EmailAddress.parseUserEntry("prof@iitd.ac.in")!!, true, true, 0L, "me@gmail.com"))
        assertEquals(AppRuleBehavior.BLOCK, rules.getBehaviorForPackage(gm))
        assertTrue(MailDelegationPolicy.grantIsLive(mailStore.grant(), session, 1_000L))

        CommitmentTeardown(sessionStore, rules) {}.end(CommitmentTeardown.Kind.EARLY_END, "s1", "")
        assertEquals(before, rules.getBehaviorForPackage(gm))
        val after = sessionStore.getStoredSession()
        assertNull(after)
        assertNull(mailStore.liveGrant(after, 1_000L))
        assertNull("grant erased", mailStore.grant())
        assertFalse(MailDelegationPolicy.authorizeCheck(mailStore.grant(), after, 1_000L).allowed)
    }

    @Test
    fun disconnectDenyMarker_survivesRestartWhenGrantRewriteAndClearFail() {
        val grantPrefs = InMemorySharedPreferences()
        val disconnectPrefs = InMemorySharedPreferences()
        val session = FocusSession("mail-session", "study", goal, 0L, 10_800_000L, SessionStatus.ACTIVE, 0, StrictnessLevel.STRICT)
        val grant = MailDelegationGrant(
            sessionId = session.id,
            senderAddress = EmailAddress.parseUserEntry("prof@iitd.ac.in")!!,
            aiAssistConsent = true,
            allowSend = true,
            createdAtMillis = 0L,
            accountEmail = "me@gmail.com"
        )
        val store = MailDelegationStore(grantPrefs, disconnectPrefs)
        assertTrue(store.saveGrant(grant))
        grantPrefs.commitSucceeds = false

        assertTrue(store.disableSession(session.id))
        assertNull(store.liveGrant(session, 1_000L))
        assertTrue("failed clear leaves the old grant persisted", store.grant() != null)

        val recreatedStore = MailDelegationStore(grantPrefs, disconnectPrefs)
        assertNull("durable deny marker blocks the stale grant after process recreation", recreatedStore.liveGrant(session, 2_000L))
        assertTrue(recreatedStore.grant() != null)
    }

    @Test
    fun unreadableDisconnectSafetyState_failsClosed() {
        val grantPrefs = InMemorySharedPreferences()
        val disconnectPrefs = InMemorySharedPreferences()
        val session = FocusSession("mail-session", "study", goal, 0L, 10_800_000L, SessionStatus.ACTIVE, 0, StrictnessLevel.STRICT)
        val grant = MailDelegationGrant(
            sessionId = session.id,
            senderAddress = EmailAddress.parseUserEntry("prof@iitd.ac.in")!!,
            aiAssistConsent = true,
            allowSend = true,
            createdAtMillis = 0L,
            accountEmail = "me@gmail.com"
        )
        val store = MailDelegationStore(grantPrefs, disconnectPrefs)
        assertTrue(store.saveGrant(grant))
        disconnectPrefs.edit().putBoolean("disabled_session_v1", true).commit()

        assertNull(store.liveGrant(session, 1_000L))
        assertTrue("unreadable deny record leaves the grant unusable", store.grant() != null)
    }

    @Test
    fun homeList_showsMailThreadOnlyDuringASession() {
        assertTrue(HomeListItem.MailTask in buildHomeListItems(hasStoredSession = true, hasPromiseUnderstanding = false, showAdvancedControls = false, hasMailTask = true))
        assertFalse(HomeListItem.MailTask in buildHomeListItems(hasStoredSession = true, hasPromiseUnderstanding = false, showAdvancedControls = false))
        assertFalse(HomeListItem.MailTask in buildHomeListItems(hasStoredSession = false, hasPromiseUnderstanding = true, showAdvancedControls = false, hasMailTask = true))
    }

    @Test
    fun alertsCopy_neverClaimsAutomaticAlerts() {
        for (asked in listOf(true, false)) {
            val copy = mailAlertsCopy(asked)
            assertTrue(copy.contains("only when you ask"))
            assertTrue(copy.contains("does not alert you"))
        }
    }

    private fun sources(relativeDir: String): List<File> {
        val base = listOf(File("src/main/java/com/phonecodex/app"), File("app/src/main/java/com/phonecodex/app")).first { it.exists() }
        return File(base, relativeDir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun structure_connectorNeverOpensGmail_andAccessibilityNeverTouchesMail() {
        val helperFiles = sources("connector") + sources("mailtask") + sources("domain/delegation") +
            sources("ui/home").filter { it.name.startsWith("MailTask") }
        assertTrue(helperFiles.size >= 10)
        for (f in helperFiles) {
            val text = f.readText()
            for (banned in listOf("getLaunchIntentForPackage", "startActivity(", "ACTION_VIEW", "android.webkit", "uiautomator", "AccessibilityNodeInfo")) {
                assertFalse("${f.name} must not use $banned", text.contains(banned))
            }
        }
        for (f in sources("accessibility")) {
            val text = f.readText()
            assertFalse("${f.name}: no mail/network work in Accessibility callbacks", text.contains("mailtask") || text.contains(".connector."))
        }
    }

    @Test
    fun structure_leastPrivilegeScopesOnly_noEmbeddedSecrets() {
        val all = sources("").map { it.readText() }.joinToString("\n")
        assertFalse(all.contains("gmail.modify"))
        assertFalse(all.contains("https://mail.google.com/"))
        assertFalse(all.contains("gmail.compose"))
        assertFalse(all.contains("gmail.insert"))
        val scopes = Regex("""https://www\.googleapis\.com/auth/[a-z.]+""").findAll(all).map { it.value }.toSet()
        assertEquals(setOf("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.send"), scopes)
        val auth = sources("mailtask").single { it.name == "GoogleMailAuthorizer.kt" }.readText()
        assertFalse("no client secret on the phone", auth.contains("client_secret", ignoreCase = true))
        assertFalse(auth.contains("password", ignoreCase = true))
    }

    @Test
    fun structure_disconnectClosesLocalMailBeforeWaitingForGoogleRevocation() {
        val ui = sources("ui/home").single { it.name == "MailTaskUiState.kt" }.readText()
        val disconnect = ui.substringAfter("fun disconnect() {").substringBefore("\n    fun submitComposer()")
        assertTrue(disconnect.contains("authorizer.prepareRevocation()"))
        assertTrue(disconnect.indexOf("controller.onAccountDisconnected()") < disconnect.indexOf("scope.launch"))
        assertTrue(disconnect.indexOf("scope.launch") < disconnect.indexOf("revokeAtGoogle()"))
    }
}
