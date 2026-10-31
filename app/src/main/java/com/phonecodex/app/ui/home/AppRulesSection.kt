package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.InstalledApp

@Composable
internal fun AppRulesSection(
    appRules: List<AppRule>,
    isDefaultRule: (String) -> Boolean,
    installedApps: List<InstalledApp>,
    installedAppsLoading: Boolean,
    installedAppsSearch: String,
    onInstalledAppsSearchChange: (String) -> Unit,
    onLoadInstalledApps: () -> Unit,
    onSelectBehavior: (AppRule, AppRuleBehavior) -> Unit,
    onAddAppRule: (InstalledApp, AppRuleBehavior) -> Unit,
    onRemoveRule: (String) -> Unit
) {
    Text(text = "App Rules", fontSize = 18.sp)
    Spacer(modifier = Modifier.height(4.dp))
    Text(text = "Choose how each app behaves during a commitment.", fontSize = 12.sp)
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = "Current Rules", fontSize = 16.sp)
    Spacer(modifier = Modifier.height(8.dp))
    if (appRules.isEmpty()) {
        Text(text = "No saved rules yet")
    } else {
        appRules.forEach { rule ->
            SavedAppRuleRow(
                rule = rule,
                isDefault = isDefaultRule(rule.packageName),
                onSelectBehavior = { behavior -> onSelectBehavior(rule, behavior) },
                onRemove = { onRemoveRule(rule.packageName) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(text = "Launchable Apps", fontSize = 16.sp)
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = onLoadInstalledApps,
        modifier = Modifier.fillMaxWidth(),
        enabled = !installedAppsLoading
    ) {
        Text(if (installedAppsLoading) "Loading apps…" else "Load Launchable Apps")
    }

    if (installedApps.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = installedAppsSearch,
            onValueChange = onInstalledAppsSearchChange,
            label = { Text("Search launchable apps") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        val ruledPackages = remember(appRules) {
            appRules.map { it.packageName }.toSet()
        }
        val filtered = remember(installedApps, installedAppsSearch, ruledPackages) {
            filterInstalledApps(
                installedApps = installedApps,
                excludedPackages = ruledPackages,
                searchQuery = installedAppsSearch
            )
        }

        if (filtered.visible.isEmpty()) {
            Text(text = "No matching apps without a rule")
        } else {
            if (filtered.totalMatching > MAX_VISIBLE_INSTALLED_APPS) {
                Text(
                    text = "Showing $MAX_VISIBLE_INSTALLED_APPS of ${filtered.totalMatching} matching apps",
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Nested LazyColumn with fixed height — parent home LazyColumn stays smooth.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = filtered.visible,
                    key = { app -> app.packageName },
                    contentType = { "installed_app_rule" }
                ) { app ->
                    InstalledAppRulePickerRow(
                        app = app,
                        onSelectBehavior = { behavior -> onAddAppRule(app, behavior) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SavedAppRuleRow(
    rule: AppRule,
    isDefault: Boolean,
    onSelectBehavior: (AppRuleBehavior) -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = rule.label)
                Text(text = rule.packageName, fontSize = 12.sp)
            }
            if (isDefault) {
                Text(text = "Default", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Current: ${behaviorLabel(rule.behavior)}", fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        BehaviorSelectorRow(
            selected = rule.behavior,
            onSelect = onSelectBehavior
        )
        if (!isDefault) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRemove) {
                Text("Remove Rule")
            }
        }
    }
}

@Composable
internal fun InstalledAppRulePickerRow(
    app: InstalledApp,
    onSelectBehavior: (AppRuleBehavior) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = app.label)
        Text(text = app.packageName, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Set behavior:", fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        BehaviorSelectorRow(
            selected = null,
            onSelect = onSelectBehavior
        )
    }
}

@Composable
internal fun BehaviorSelectorRow(
    selected: AppRuleBehavior?,
    onSelect: (AppRuleBehavior) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AppRuleBehavior.entries.forEach { behavior ->
            Button(
                onClick = { onSelect(behavior) },
                modifier = Modifier.weight(1f),
                enabled = selected != behavior
            ) {
                Text(text = behaviorLabel(behavior), fontSize = 11.sp)
            }
        }
    }
}

internal fun behaviorLabel(behavior: AppRuleBehavior): String {
    return when (behavior) {
        AppRuleBehavior.AI_DECIDE -> "AI"
        else -> behavior.name
    }
}
