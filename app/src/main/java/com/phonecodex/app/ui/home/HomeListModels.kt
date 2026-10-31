package com.phonecodex.app.ui.home

import androidx.compose.runtime.Immutable
import com.phonecodex.app.domain.model.InstalledApp
import com.phonecodex.app.domain.protection.ProtectionReliabilityLevel

/**
 * One row in the home LazyColumn. Building a list of these (instead of one giant Column)
 * lets Compose compose only on-screen sections.
 */
@Immutable
internal sealed class HomeListItem(val id: String) {
    data object Header : HomeListItem("header")
    data object ProtectionStatus : HomeListItem("protection_status")
    data object ProtectionSetup : HomeListItem("protection_setup")
    data object TodayInsight : HomeListItem("today_insight")
    data object ActiveCommitment : HomeListItem("active_commitment")
    data object MailTask : HomeListItem("mail_task")
    data object LastDecision : HomeListItem("last_decision")
    data object PromiseComposer : HomeListItem("promise_composer")
    data object PromiseUnderstanding : HomeListItem("promise_understanding")
    data object AdvancedToggle : HomeListItem("advanced_toggle")

    data object AdvancedIntro : HomeListItem("adv_intro")
    data object AdvancedPrivacyPolicy : HomeListItem("adv_privacy")
    data object AdvancedSessionTuning : HomeListItem("adv_tuning")
    data object AdvancedPermanentCommitments : HomeListItem("adv_permanent")
    data object AdvancedProtectionHealth : HomeListItem("adv_protection")
    data object AdvancedActiveSession : HomeListItem("adv_session")
    data object AdvancedRecoveryPolicy : HomeListItem("adv_recovery")
    data object AdvancedEngineeringToggle : HomeListItem("adv_engineering_toggle")
    data object AdvancedAppRules : HomeListItem("adv_app_rules")
    data object AdvancedSafeApps : HomeListItem("adv_safe_apps")
    data object AdvancedEvents : HomeListItem("adv_events")
    data object AdvancedDebug : HomeListItem("adv_debug")
    data object AdvancedDiagnostics : HomeListItem("adv_diagnostics")
    data object AdvancedFeedback : HomeListItem("adv_feedback")
    data object BottomSpacer : HomeListItem("bottom_spacer")
}

/** ChatGPT-style idle/confirm home. Session or Advanced falls back to the settings list. */
internal fun usesChatHome(
    hasStoredSession: Boolean,
    showAdvancedControls: Boolean
): Boolean = !hasStoredSession && !showAdvancedControls

enum class ChatHomeProtectionMode {
    COMPACT,
    HIDDEN
}

/** Composer stays off-screen while the user reviews Start. */
internal fun confirmationReviewHidesComposer(hasUnderstanding: Boolean): Boolean =
    hasUnderstanding

internal fun chatHomeProtectionMode(
    hasUnderstanding: Boolean,
    protectionLevel: ProtectionReliabilityLevel
): ChatHomeProtectionMode =
    if (hasUnderstanding && protectionLevel == ProtectionReliabilityLevel.PROTECTED) {
        ChatHomeProtectionMode.HIDDEN
    } else {
        ChatHomeProtectionMode.COMPACT
    }

/**
 * Builds the visible home list from lightweight flags. Pure function — no I/O.
 * Engineering (app rules / debug / events / feedback) stays collapsed until explicitly opened.
 */
internal fun buildHomeListItems(
    hasStoredSession: Boolean,
    hasPromiseUnderstanding: Boolean,
    showAdvancedControls: Boolean,
    showAdvancedEngineering: Boolean = false,
    hasMailTask: Boolean = false
): List<HomeListItem> {
    val items = ArrayList<HomeListItem>(24)
    items += HomeListItem.Header
    if (!showAdvancedControls) {
        items += HomeListItem.ProtectionStatus
        items += HomeListItem.TodayInsight
    }
    if (hasStoredSession) {
        items += HomeListItem.ActiveCommitment
        if (hasMailTask) items += HomeListItem.MailTask
        items += HomeListItem.LastDecision
    } else if (hasPromiseUnderstanding) {
        items += HomeListItem.PromiseUnderstanding
    } else {
        items += HomeListItem.PromiseComposer
    }
    items += HomeListItem.AdvancedToggle
    if (showAdvancedControls) {
        items += HomeListItem.AdvancedIntro
        items += HomeListItem.AdvancedPrivacyPolicy
        if (!hasStoredSession) {
            items += HomeListItem.LastDecision
        }
        items += HomeListItem.AdvancedSessionTuning
        items += HomeListItem.AdvancedPermanentCommitments
        items += HomeListItem.AdvancedProtectionHealth
        if (hasStoredSession) {
            items += HomeListItem.AdvancedActiveSession
        }
        items += HomeListItem.AdvancedRecoveryPolicy
        items += HomeListItem.AdvancedEngineeringToggle
        if (showAdvancedEngineering) {
            items += HomeListItem.AdvancedAppRules
            items += HomeListItem.AdvancedSafeApps
            items += HomeListItem.AdvancedEvents
            items += HomeListItem.AdvancedDebug
            items += HomeListItem.AdvancedDiagnostics
            items += HomeListItem.AdvancedFeedback
        }
    }
    items += HomeListItem.BottomSpacer
    return items
}

/**
 * Cached filtering for installed-app pickers. Call from remember() / derivedStateOf, never
 * recompute on every scroll frame.
 */
internal fun filterInstalledApps(
    installedApps: List<InstalledApp>,
    excludedPackages: Set<String>,
    searchQuery: String,
    limit: Int = MAX_VISIBLE_INSTALLED_APPS
): FilteredInstalledApps {
    val query = searchQuery.trim().lowercase()
    val matching = installedApps.asSequence()
        .filter { app -> app.packageName !in excludedPackages }
        .filter { app ->
            query.isEmpty() ||
                app.label.lowercase().contains(query) ||
                app.packageName.lowercase().contains(query)
        }
        .toList()
    return FilteredInstalledApps(
        visible = matching.take(limit),
        totalMatching = matching.size
    )
}

@Immutable
internal data class FilteredInstalledApps(
    val visible: List<InstalledApp>,
    val totalMatching: Int
)
