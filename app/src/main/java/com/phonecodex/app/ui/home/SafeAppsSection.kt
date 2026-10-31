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
import com.phonecodex.app.domain.model.InstalledApp
import com.phonecodex.app.domain.model.SafeApp

@Composable
internal fun SafeAppsSection(
    safeApps: List<SafeApp>,
    installedApps: List<InstalledApp>,
    installedAppsLoading: Boolean,
    safeAppsSearch: String,
    onSafeAppsSearchChange: (String) -> Unit,
    onLoadInstalledApps: () -> Unit,
    onAddSafeApp: (InstalledApp) -> Unit,
    onRemoveSafeApp: (String) -> Unit
) {
    Text(text = "Safe Apps", fontSize = 18.sp)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Always allowed. Safety and essential communication beat focus rules.",
        fontSize = 12.sp
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = "Saved Safe Apps", fontSize = 16.sp)
    Spacer(modifier = Modifier.height(8.dp))
    if (safeApps.isEmpty()) {
        Text(text = "No safe apps saved yet")
    } else {
        safeApps.forEach { app ->
            SavedSafeAppRow(
                app = app,
                onRemove = { onRemoveSafeApp(app.packageName) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(text = "Add Safe App", fontSize = 16.sp)
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
            value = safeAppsSearch,
            onValueChange = onSafeAppsSearchChange,
            label = { Text("Search launchable apps") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        val safePackages = remember(safeApps) {
            safeApps.map { it.packageName }.toSet()
        }
        val filtered = remember(installedApps, safeAppsSearch, safePackages) {
            filterInstalledApps(
                installedApps = installedApps,
                excludedPackages = safePackages,
                searchQuery = safeAppsSearch
            )
        }

        if (filtered.visible.isEmpty()) {
            Text(text = "No matching apps to add")
        } else {
            if (filtered.totalMatching > MAX_VISIBLE_INSTALLED_APPS) {
                Text(
                    text = "Showing $MAX_VISIBLE_INSTALLED_APPS of ${filtered.totalMatching} matching apps",
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filtered.visible,
                    key = { app -> app.packageName },
                    contentType = { "installed_safe_app" }
                ) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.label)
                            Text(text = app.packageName, fontSize = 12.sp)
                        }
                        Button(onClick = { onAddSafeApp(app) }) {
                            Text("Add")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavedSafeAppRow(
    app: SafeApp,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label)
            Text(text = app.packageName, fontSize = 12.sp)
        }
        if (app.isDefault) {
            Text(text = "Default", fontSize = 12.sp)
        } else {
            Button(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}
