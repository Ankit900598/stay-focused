package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.phonecodex.app.data.ScreenTimeReadResult
import com.phonecodex.app.domain.usage.ScreenTimeSummaryLaw

@Composable
internal fun TodayScreenTimeCard(
    result: ScreenTimeReadResult,
    loading: Boolean,
    onEnableAccess: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    CalmCard(modifier = modifier.testTag("today_screen_time_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                MicroLabel(text = "Today")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your day, in focus",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface
                )
            }
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = "Usage insight stays on this device",
                tint = colors.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        when (result) {
            ScreenTimeReadResult.Loading -> {
                Text(
                    text = "Getting today's app-time summary…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                PrivacyNote()
            }
            is ScreenTimeReadResult.Available -> {
                Text(
                    text = ScreenTimeSummaryLaw.formatForegroundTime(
                        result.summary.appForegroundMillis
                    ),
                    style = MaterialTheme.typography.displaySmall,
                    color = colors.onSurface,
                    modifier = Modifier.testTag("today_app_foreground_time")
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Approximate app foreground time so far today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                PrivacyNote()
            }
            ScreenTimeReadResult.NeedsAccess -> {
                Text(
                    text = "See how much time apps have been in the foreground today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Optional Android Usage Access. Strlix reads app-time totals only; " +
                        "it adds durations on this phone without displaying or storing app names. " +
                        "It does not read messages or screen contents, and does not upload this data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                PrimaryAction(
                    text = "Review Usage Access",
                    onClick = onEnableAccess,
                    enabled = !loading
                )
            }
            ScreenTimeReadResult.NoData -> {
                Text(
                    text = if (loading) "Checking today's app-time summary…" else
                        "Android has not reported today's app-time summary yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                if (!loading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    QuietAction(text = "Refresh", onClick = onRefresh)
                }
                Spacer(modifier = Modifier.height(12.dp))
                PrivacyNote()
            }
            ScreenTimeReadResult.Unavailable -> {
                Text(
                    text = "Today's app-time summary is temporarily unavailable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                QuietAction(
                    text = if (loading) "Refreshing…" else "Try again",
                    onClick = onRefresh,
                    enabled = !loading
                )
                Spacer(modifier = Modifier.height(12.dp))
                PrivacyNote()
            }
        }
    }
}

@Composable
private fun PrivacyNote() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Calculated on this phone · never sent to Strlix AI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
