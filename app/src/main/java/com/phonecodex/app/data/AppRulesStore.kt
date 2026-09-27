package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences
import com.phonecodex.app.data.CommitmentRuleRestore.Outcome
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior

/** Result of undoing (or declining to undo) a commitment's app-rule writes. */
data class CommitmentRuleRestore(val outcome: Outcome, val eventMessage: String?) {
    enum class Outcome { RESTORED, NO_LEDGER, SESSION_ACTIVE, UNREADABLE_LEDGER_RULES_KEPT }
}

class AppRulesStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun getRules(): List<AppRule> {
        val defaultRules = DEFAULT_RULES.map { default ->
            default.copy(behavior = resolveBehavior(default.packageName, default.behavior))
        }
        val defaultPackages = defaultRules.map { it.packageName }.toSet()
        val customRules = loadCustomRules()
            .filter { it.packageName !in defaultPackages }
            .map { custom ->
                custom.copy(behavior = resolveBehavior(custom.packageName, custom.behavior))
            }
        return defaultRules + customRules
    }

    fun getRuleForPackage(packageName: String): AppRule? {
        return getRules().find { it.packageName == packageName }
    }

    fun getBehaviorForPackage(packageName: String): AppRuleBehavior? {
        val fallback = DEFAULT_RULES
            .find { it.packageName == packageName }
            ?.behavior
            ?: loadCustomRules()
                .find { it.packageName == packageName }
                ?.behavior
            ?: return null

        return resolveBehavior(packageName, fallback)
    }

    fun addRule(
        packageName: String,
        label: String,
        behavior: AppRuleBehavior = AppRuleBehavior.WARN
    ) {
        if (getRuleForPackage(packageName) != null) return

        val updatedCustomRules = loadCustomRules() + AppRule(
            packageName = packageName,
            label = label,
            behavior = behavior
        )
        saveCustomRules(updatedCustomRules)
        prefs.edit()
            .putString(behaviorKey(packageName), behavior.name)
            .apply()
    }

    fun setBehavior(packageName: String, behavior: AppRuleBehavior) {
        if (getRuleForPackage(packageName) == null) return
        prefs.edit()
            .putString(behaviorKey(packageName), behavior.name)
            .apply()
    }

    fun applySuggestedRule(rule: AppRule) {
        if (getRuleForPackage(rule.packageName) != null) {
            setBehavior(rule.packageName, rule.behavior)
        } else {
            addRule(
                packageName = rule.packageName,
                label = rule.label,
                behavior = rule.behavior
            )
        }
    }

    /**
     * Applies this promise's suggestions for the session only. Existing overrides and custom
     * entries have unprovable origin, so they are treated as the user's and only overlaid.
     * A leftover readable ledger is undone first; an unreadable one is quarantined and
     * reported, never guessed at. [restoreCommitmentRules] undoes this write.
     */
    fun applyCommitmentSuggestions(suggested: List<AppRule>): CommitmentRuleRestore {
        val leftover = undoLedger(reportMissing = false)
        val applied = CommitmentAppRuleLedger.apply(
            readState(),
            suggested,
            DEFAULT_RULES.map { it.packageName }.toSet()
        )
        writeState(applied.state, CommitmentAppRuleLedger.encode(applied.ledger))
        return if (leftover.outcome == Outcome.UNREADABLE_LEDGER_RULES_KEPT) {
            leftover
        } else {
            CommitmentRuleRestore(Outcome.NO_LEDGER, eventMessage = null)
        }
    }

    /** Manual end / expiry: put back the pre-session rules the commitment still owns. */
    fun restoreCommitmentRules(): CommitmentRuleRestore = undoLedger(reportMissing = true)

    /** Process restart / stale session: a ledger with no running session is undone. */
    fun restoreStaleCommitmentRules(hasActiveSession: Boolean): CommitmentRuleRestore {
        if (hasActiveSession) return CommitmentRuleRestore(Outcome.SESSION_ACTIVE, eventMessage = null)
        return undoLedger(reportMissing = false)
    }

    fun hasCommitmentLedger(): Boolean = prefs.contains(KEY_COMMITMENT_LEDGER)

    private fun undoLedger(reportMissing: Boolean): CommitmentRuleRestore {
        if (!prefs.contains(KEY_COMMITMENT_LEDGER)) {
            return CommitmentRuleRestore(
                Outcome.NO_LEDGER,
                eventMessage = if (reportMissing) NO_LEDGER_MESSAGE else null
            )
        }
        val raw = prefs.all[KEY_COMMITMENT_LEDGER]
        val ledger = (raw as? String)?.let(CommitmentAppRuleLedger::decode)
        if (ledger == null) {
            quarantineLedger(raw)
            return CommitmentRuleRestore(Outcome.UNREADABLE_LEDGER_RULES_KEPT, UNREADABLE_LEDGER_MESSAGE)
        }
        writeState(CommitmentAppRuleLedger.restore(readState(), ledger), ledger = null)
        return CommitmentRuleRestore(Outcome.RESTORED, eventMessage = null)
    }

    /** Keeps current rules untouched and preserves every unreadable ledger for diagnosis. */
    private fun quarantineLedger(raw: Any?) {
        val earlier = prefs.all[KEY_LEDGER_QUARANTINE] as? String
        val quarantined = listOfNotNull(earlier, raw.toString()).joinToString(QUARANTINE_SEPARATOR)
        prefs.edit()
            .putString(KEY_LEDGER_QUARANTINE, quarantined)
            .putString(KEY_LEDGER_RECOVERY_NOTE, "${System.currentTimeMillis()} $UNREADABLE_LEDGER_MESSAGE")
            .remove(KEY_COMMITMENT_LEDGER)
            .commit()
    }

    private fun readState(): AppRuleState = AppRuleState(
        behaviors = prefs.all
            .filterKeys { it.startsWith(BEHAVIOR_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? String)?.let { key.removePrefix(BEHAVIOR_PREFIX) to it }
            }
            .toMap(),
        customEntries = (prefs.all[KEY_CUSTOM_RULES] as? Set<*>).orEmpty().filterIsInstance<String>().toSet()
    )

    /** Writes only what changed, so values this store cannot read are never touched. */
    private fun writeState(state: AppRuleState, ledger: String?) {
        val current = readState()
        val editor = prefs.edit()
        (current.behaviors.keys - state.behaviors.keys).forEach { editor.remove(behaviorKey(it)) }
        state.behaviors
            .filter { (pkg, behavior) -> current.behaviors[pkg] != behavior }
            .forEach { (pkg, behavior) -> editor.putString(behaviorKey(pkg), behavior) }
        if (state.customEntries != current.customEntries) {
            editor.putStringSet(KEY_CUSTOM_RULES, state.customEntries)
        }
        if (ledger == null) editor.remove(KEY_COMMITMENT_LEDGER) else editor.putString(KEY_COMMITMENT_LEDGER, ledger)
        editor.commit()
    }

    fun isDefaultRule(packageName: String): Boolean {
        return DEFAULT_RULES.any { it.packageName == packageName }
    }

    fun removeCustomRule(packageName: String) {
        if (isDefaultRule(packageName)) return

        val updatedCustomRules = loadCustomRules()
            .filter { it.packageName != packageName }
        saveCustomRules(updatedCustomRules)
        prefs.edit()
            .remove(behaviorKey(packageName))
            .apply()
    }

    private fun resolveBehavior(
        packageName: String,
        fallback: AppRuleBehavior
    ): AppRuleBehavior {
        val stored = prefs.getString(behaviorKey(packageName), null) ?: return fallback
        return runCatching { AppRuleBehavior.valueOf(stored) }.getOrNull() ?: fallback
    }

    private fun loadCustomRules(): List<AppRule> {
        return prefs.getStringSet(KEY_CUSTOM_RULES, emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val separator = entry.indexOf('|')
                if (separator <= 0) return@mapNotNull null
                val packageName = entry.substring(0, separator)
                val label = entry.substring(separator + 1)
                AppRule(
                    packageName = packageName,
                    label = label,
                    behavior = AppRuleBehavior.WARN
                )
            }
    }

    private fun saveCustomRules(rules: List<AppRule>) {
        val encoded = rules.map { rule -> "${rule.packageName}|${rule.label}" }.toSet()
        prefs.edit()
            .putStringSet(KEY_CUSTOM_RULES, encoded)
            .apply()
    }

    private fun behaviorKey(packageName: String): String {
        return "$BEHAVIOR_PREFIX$packageName"
    }

    companion object {
        private const val PREFS_NAME = "phonecodex_app_rules"
        private const val KEY_CUSTOM_RULES = "custom_rules"
        private const val BEHAVIOR_PREFIX = "behavior_"
        private const val KEY_COMMITMENT_LEDGER = "commitment_ledger_v1"
        private const val KEY_LEDGER_QUARANTINE = "commitment_ledger_quarantine_v1"
        private const val KEY_LEDGER_RECOVERY_NOTE = "commitment_ledger_recovery_note"
        private const val QUARANTINE_SEPARATOR = "\n---\n"

        const val NO_LEDGER_MESSAGE =
            "App rules kept as they are: no record of this commitment's rule changes was found, " +
                "so nothing was reset. Review App Rules if anything looks wrong."
        const val UNREADABLE_LEDGER_MESSAGE =
            "App rules kept as they are: this commitment's rule record could not be read, " +
                "so nothing was guessed or reset. Review App Rules if anything looks wrong."

        private val DEFAULT_RULES = listOf(
            AppRule(
                packageName = "com.android.chrome",
                label = "Chrome",
                behavior = AppRuleBehavior.AI_DECIDE
            ),
            AppRule(
                packageName = "com.google.android.youtube",
                label = "YouTube",
                behavior = AppRuleBehavior.AI_DECIDE
            ),
            AppRule(
                packageName = "org.schabi.newpipe",
                label = "NewPipe",
                behavior = AppRuleBehavior.AI_DECIDE
            ),
            AppRule(
                packageName = "com.instagram.android",
                label = "Instagram",
                // Default AI_DECIDE — hard BLOCK only when the promise asks for it.
                behavior = AppRuleBehavior.AI_DECIDE
            ),
            AppRule(
                packageName = "com.facebook.katana",
                label = "Facebook",
                behavior = AppRuleBehavior.AI_DECIDE
            ),
            AppRule(
                packageName = "com.zhiliaoapp.musically",
                label = "TikTok",
                behavior = AppRuleBehavior.AI_DECIDE
            ),
            AppRule(
                packageName = "com.snapchat.android",
                label = "Snapchat",
                behavior = AppRuleBehavior.AI_DECIDE
            )
        )
    }
}
