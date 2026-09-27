package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.session.CommitmentExitGate
import com.phonecodex.app.domain.session.ExitFriction

/** One explicit confirm, no typed sentence; bounded to once per day by EmergencyExitGate. */
@Composable
internal fun EmergencyExitDialog(onConfirm: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Emergency exit?") },
        text = {
            Column {
                Text(
                    text = "This ends your commitment now and puts your app rules back. " +
                        "You can use emergency exit once every 24 hours.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Permanent rules like the adult-content block stay on. " +
                        "Calls and emergency apps already work without this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("End commitment now", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text("Keep going") }
        }
    )
}

/** Confirm step for ending a Strict or Locked commitment early. */
@Composable
internal fun EndCommitmentDialog(
    friction: ExitFriction,
    phraseError: String?,
    onConfirm: (typedPhrase: String?) -> Unit,
    onKeep: () -> Unit
) {
    var typed by remember(friction) { mutableStateOf("") }
    val needsPhrase = friction == ExitFriction.TYPED_PHRASE
    val visibleError = phraseError?.takeUnless {
        CommitmentExitGate.mayEnd(friction, confirmed = true, typedPhrase = typed)
    }
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(if (needsPhrase) "End a locked commitment?" else "End this commitment early?") },
        text = {
            Column {
                Text(
                    text = if (needsPhrase) {
                        "You made this a locked promise. To end it now, type:"
                    } else {
                        "You chose Strict for this promise. Ending now breaks it."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (needsPhrase) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = CommitmentExitGate.LOCKED_EXIT_PHRASE,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        isError = visibleError != null
                    )
                    if (visibleError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = visibleError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Calls and emergency apps stay available either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (needsPhrase) typed else null) }) {
                Text("End commitment", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text("Keep going") }
        }
    )
}
