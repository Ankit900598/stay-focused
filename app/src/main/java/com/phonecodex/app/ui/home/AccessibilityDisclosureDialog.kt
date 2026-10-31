package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phonecodex.app.R

@Composable
internal fun AccessibilityDisclosureDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    consentSaveFailed: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.accessibility_disclosure_body),
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onOpenPrivacyPolicy) {
                    Text(stringResource(R.string.privacy_policy_open))
                }
                if (consentSaveFailed) {
                    Text(
                        text = stringResource(R.string.accessibility_disclosure_save_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.accessibility_disclosure_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.accessibility_disclosure_dismiss))
            }
        }
    )
}
