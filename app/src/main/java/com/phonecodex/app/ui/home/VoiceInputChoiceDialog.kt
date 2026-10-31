package com.phonecodex.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceInputChoiceDialog(
    onDismiss: () -> Unit,
    onPhoneRecognizer: () -> Unit,
    onAzureSpeech: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose voice input") },
        text = {
            Column {
                Text(
                    "Phone recognizer",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Opens Android’s selected speech service. Strlix receives only the transcript it returns.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )
                Text(
                    "Azure Speech",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Records for up to 15 seconds and sends this clip through Strlix’s backend to Azure Speech. Strlix does not write raw audio to local files or logs; Azure service terms apply to processing. The transcript only fills the promise box; review it before continuing.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAzureSpeech) { Text("Send one clip to Azure") }
        },
        dismissButton = {
            TextButton(onClick = onPhoneRecognizer) { Text("Use phone speech") }
        }
    )
}
