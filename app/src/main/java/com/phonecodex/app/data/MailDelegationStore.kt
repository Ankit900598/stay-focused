package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences
import com.phonecodex.app.domain.delegation.EmailAddress
import com.phonecodex.app.domain.delegation.MailDelegationGrant
import com.phonecodex.app.domain.delegation.MailDelegationPolicy
import com.phonecodex.app.domain.delegation.MailSendLedger
import com.phonecodex.app.domain.delegation.SendRecord
import com.phonecodex.app.domain.delegation.SendRecordStore
import com.phonecodex.app.domain.delegation.SendState
import com.phonecodex.app.domain.model.FocusSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * The confirmed mail exception plus one-send-only records. Holds addresses, flags and
 * delivery metadata only — never message bodies, summaries, drafts or body-derived hashes.
 */
class MailDelegationStore(
    private val prefs: SharedPreferences,
    private val disconnectPrefs: SharedPreferences = prefs
) : SendRecordStore {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        context.getSharedPreferences(DISCONNECT_PREFS, Context.MODE_PRIVATE)
    )

    fun saveGrant(grant: MailDelegationGrant): Boolean {
        val json = JSONObject()
            .put("sessionId", grant.sessionId)
            .put("sender", grant.senderAddress.value)
            .put("aiConsent", grant.aiAssistConsent)
            .put("allowSend", grant.allowSend)
            .put("createdAt", grant.createdAtMillis)
            .put("account", grant.accountEmail ?: JSONObject.NULL)
        return runCatching { prefs.edit().putString(KEY_GRANT, json.toString()).commit() }.getOrDefault(false)
    }

    fun grant(): MailDelegationGrant? {
        val raw = runCatching { prefs.getString(KEY_GRANT, null) }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(raw)
            MailDelegationGrant(
                sessionId = o.getString("sessionId"),
                senderAddress = EmailAddress.parseUserEntry(o.getString("sender")) ?: return null,
                aiAssistConsent = o.optBoolean("aiConsent", false),
                allowSend = o.optBoolean("allowSend", false),
                createdAtMillis = o.optLong("createdAt", 0L),
                accountEmail = if (o.isNull("account")) null else o.optString("account").ifBlank { null }
            )
        }.getOrNull()
    }

    /** The grant only while its own commitment runs; a stale grant is erased on read. */
    fun liveGrant(session: FocusSession?, nowMillis: Long): MailDelegationGrant? {
        val disabledSessionId = try {
            disconnectPrefs.getString(KEY_DISABLED_SESSION, null)
        } catch (_: Exception) {
            return null
        }
        val g = grant() ?: return null
        if (disabledSessionId == g.sessionId) {
            clearGrant()
            MailSendLedger(this).retainUnresolved()
            return null
        }
        if (MailDelegationPolicy.grantIsLive(g, session, nowMillis)) return g
        clearGrant()
        MailSendLedger(this).retainUnresolved()
        return null
    }

    fun clearGrant(): Boolean = prefs.edit().remove(KEY_GRANT).commit()

    fun disableSession(sessionId: String): Boolean {
        val id = sessionId.trim()
        if (id.isEmpty()) return false
        return runCatching {
            disconnectPrefs.edit().putString(KEY_DISABLED_SESSION, id).commit()
        }.getOrDefault(false)
    }

    override fun load(): List<SendRecord> {
        val raw = try {
            prefs.getString(KEY_SENDS, null)
        } catch (e: ClassCastException) {
            throw IllegalStateException("Mail send history is unreadable", e)
        } ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            throw IllegalStateException("Mail send history is unreadable", e)
        }
        var containsLegacyBodyHash = false
        val records = try {
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                containsLegacyBodyHash = containsLegacyBodyHash || o.has("bodySha256")
                SendRecord(
                    draftId = o.getString("draftId"),
                    recipient = o.getString("recipient"),
                    attemptedAtMillis = o.getLong("attemptedAt"),
                    state = runCatching { SendState.valueOf(o.getString("state")) }.getOrDefault(SendState.UNCERTAIN),
                    gmailMessageId = if (o.isNull("gmailMessageId")) null else o.optString("gmailMessageId").ifBlank { null },
                    accountEmail = if (o.isNull("accountEmail")) null else o.optString("accountEmail").trim().lowercase().ifBlank { null }
                )
            }
        } catch (e: Exception) {
            throw IllegalStateException("Mail send history is unreadable", e)
        }
        if (containsLegacyBodyHash && !save(records)) {
            throw IllegalStateException("Mail send history privacy migration could not be saved")
        }
        return records
    }

    override fun save(records: List<SendRecord>): Boolean {
        val unresolved = records.filter { it.state == SendState.SENDING || it.state == SendState.UNCERTAIN }
        if (unresolved.size > MAX_RECORDS) return false
        val resolved = records.filterNot { it.state == SendState.SENDING || it.state == SendState.UNCERTAIN }
            .takeLast(MAX_RECORDS - unresolved.size)
        val retained = resolved + unresolved
        val arr = JSONArray()
        retained.forEach { r ->
            arr.put(
                JSONObject()
                    .put("draftId", r.draftId)
                    .put("recipient", r.recipient)
                    .put("attemptedAt", r.attemptedAtMillis)
                    .put("state", r.state.name)
                    .put("gmailMessageId", r.gmailMessageId ?: JSONObject.NULL)
                    .put("accountEmail", r.accountEmail ?: JSONObject.NULL)
            )
        }
        return prefs.edit().putString(KEY_SENDS, arr.toString()).commit()
    }

    companion object {
        const val PREFS = "phonecodex_mail_delegation"
        private const val DISCONNECT_PREFS = "phonecodex_mail_disconnect_safety"
        private const val KEY_GRANT = "grant_v1"
        private const val KEY_DISABLED_SESSION = "disabled_session_v1"
        private const val KEY_SENDS = "send_records_v1"
        private const val MAX_RECORDS = 20
    }
}
