package com.phonecodex.app.data

import com.phonecodex.app.domain.model.AppRule
import org.json.JSONArray
import org.json.JSONObject

/** Raw app-rule state as stored: `behavior_<pkg>` values and `"pkg|label"` custom entries. */
data class AppRuleState(
    val behaviors: Map<String, String>,
    val customEntries: Set<String>
)

/**
 * What one commitment wrote over the user's rules, so it can be undone exactly.
 * [priorBehaviors] maps each touched package to its pre-session value (null = unset).
 */
data class CommitmentLedger(
    val priorBehaviors: Map<String, String?>,
    val writtenBehaviors: Map<String, String>,
    val addedCustomEntries: Set<String>
)

data class AppliedCommitment(val state: AppRuleState, val ledger: CommitmentLedger)

/**
 * Pure session scoping for commitment-written app rules. Applying writes only the
 * promise's suggestions (independent user rules stay as they are); restoring puts back
 * the pre-session value for every package the commitment still owns. A package the user
 * edited mid-session keeps the user's edit.
 */
object CommitmentAppRuleLedger {

    fun apply(
        state: AppRuleState,
        suggested: List<AppRule>,
        defaultPackages: Set<String>
    ): AppliedCommitment {
        val behaviors = state.behaviors.toMutableMap()
        val custom = state.customEntries.toMutableSet()
        val prior = linkedMapOf<String, String?>()
        val written = linkedMapOf<String, String>()
        val added = linkedSetOf<String>()
        for (rule in suggested.distinctBy { it.packageName }) {
            val pkg = rule.packageName
            prior[pkg] = state.behaviors[pkg]
            written[pkg] = rule.behavior.name
            behaviors[pkg] = rule.behavior.name
            if (pkg !in defaultPackages && custom.none { packageOf(it) == pkg }) {
                val entry = "$pkg|${rule.label}"
                custom += entry
                added += entry
            }
        }
        return AppliedCommitment(
            state = AppRuleState(behaviors, custom),
            ledger = CommitmentLedger(prior, written, added)
        )
    }

    fun restore(state: AppRuleState, ledger: CommitmentLedger): AppRuleState {
        val behaviors = state.behaviors.toMutableMap()
        val custom = state.customEntries.toMutableSet()
        for ((pkg, written) in ledger.writtenBehaviors) {
            if (behaviors[pkg] != written) continue
            val prior = ledger.priorBehaviors[pkg]
            if (prior == null) behaviors.remove(pkg) else behaviors[pkg] = prior
            ledger.addedCustomEntries.filter { packageOf(it) == pkg }.forEach { custom -= it }
        }
        return AppRuleState(behaviors, custom)
    }

    /** Restart / stale-session recovery: undo a leftover ledger only when no session runs. */
    fun reconcile(state: AppRuleState, ledger: CommitmentLedger, hasActiveSession: Boolean): AppRuleState =
        if (hasActiveSession) state else restore(state, ledger)

    fun encode(ledger: CommitmentLedger): String {
        val prior = JSONObject()
        ledger.priorBehaviors.forEach { (pkg, value) -> prior.put(pkg, value ?: JSONObject.NULL) }
        val written = JSONObject()
        ledger.writtenBehaviors.forEach { (pkg, value) -> written.put(pkg, value) }
        return JSONObject()
            .put("version", 1)
            .put("prior", prior)
            .put("written", written)
            .put("addedCustom", JSONArray(ledger.addedCustomEntries.toList()))
            .toString()
    }

    fun decode(raw: String?): CommitmentLedger? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("version") != 1) return null
            val priorObj = obj.getJSONObject("prior")
            val writtenObj = obj.getJSONObject("written")
            val prior = linkedMapOf<String, String?>()
            priorObj.keys().forEach { key ->
                prior[key] = if (priorObj.isNull(key)) null else priorObj.getString(key)
            }
            val written = linkedMapOf<String, String>()
            writtenObj.keys().forEach { key -> written[key] = writtenObj.getString(key) }
            val addedArr = obj.getJSONArray("addedCustom")
            val added = linkedSetOf<String>()
            for (i in 0 until addedArr.length()) added += addedArr.getString(i)
            CommitmentLedger(prior, written, added)
        }.getOrNull()
    }

    private fun packageOf(entry: String): String = entry.substringBefore('|')
}
