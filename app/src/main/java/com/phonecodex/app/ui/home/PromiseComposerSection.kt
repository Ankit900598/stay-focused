package com.phonecodex.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

private val ComposerCorner = 28.dp

/**
 * ChatGPT/Codex-style promise composer: one rounded surface, mic + send inside.
 * Voice only fills text — never starts a commitment.
 */
@Composable
internal fun PromiseComposerSection(
    promiseText: String,
    onPromiseTextChange: (String) -> Unit,
    onExampleSelected: (ExamplePromise) -> Unit,
    onUnderstandPromise: () -> Unit,
    onVoicePromise: () -> Unit,
    voiceInputStatus: String? = null,
    understandingInProgress: Boolean = false,
    compact: Boolean = false,
    voiceRecording: Boolean = false,
    voiceBusy: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val canSend = promiseText.isNotBlank() && !understandingInProgress
    val canVoice = !understandingInProgress && (!voiceBusy || voiceRecording)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!compact) {
            Text(
                text = "What promise should I protect?",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Type or speak. I will show what I understood before anything starts.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("promise_composer_surface"),
            shape = RoundedCornerShape(ComposerCorner),
            color = colors.surfaceVariant.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.8f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (promiseText.isEmpty()) {
                        Text(
                            text = if (compact) {
                                "Type a promise in any language…"
                            } else {
                                "Example: Keep me on one lecture playlist for 3 hours…"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    BasicTextField(
                        value = promiseText,
                        onValueChange = onPromiseTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = if (compact) 48.dp else 88.dp,
                                max = if (compact) 120.dp else 160.dp
                            )
                            .testTag("promise_composer_input")
                            .semantics { contentDescription = "Promise text" },
                        enabled = !understandingInProgress,
                        textStyle = TextStyle(
                            color = colors.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                        ),
                        cursorBrush = SolidColor(colors.primary),
                        maxLines = 6
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (promiseText.isNotBlank() && !understandingInProgress) {
                        IconButton(
                            onClick = { onPromiseTextChange("") },
                            modifier = Modifier
                                .size(40.dp)
                                .semantics { contentDescription = "Clear promise text" }
                                .testTag("promise_composer_clear")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = onVoicePromise,
                        enabled = canVoice,
                        modifier = Modifier
                            .size(44.dp)
                            .semantics {
                                contentDescription = if (voiceRecording) {
                                    "Finish voice recording"
                                } else {
                                    "Speak promise"
                                }
                            }
                            .testTag("promise_composer_mic")
                    ) {
                        Icon(
                            imageVector = if (voiceRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = null,
                            tint = if (canVoice) {
                                colors.onSurfaceVariant
                            } else {
                                colors.outline
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Surface(
                        onClick = onUnderstandPromise,
                        enabled = canSend,
                        shape = CircleShape,
                        color = if (canSend) colors.primary else colors.surfaceVariant,
                        modifier = Modifier
                            .size(44.dp)
                            .semantics {
                                contentDescription = if (understandingInProgress) {
                                    "Understanding promise"
                                } else {
                                    "Understand promise"
                                }
                            }
                            .testTag("promise_composer_send")
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = if (canSend) {
                                    colors.onPrimary
                                } else {
                                    colors.outline
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        when {
            understandingInProgress -> {
                Spacer(modifier = Modifier.height(10.dp))
                StatusPill(text = "Understanding with AI…", tone = PillTone.CALM)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Review the confirmation next. Start appears only after you confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            voiceInputStatus != null -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = voiceInputStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag("promise_composer_voice_status")
                )
            }
        }

        if (!compact) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Try an example",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EXAMPLE_PROMISES.forEach { example ->
                    PromiseChip(
                        text = example.chipLabel,
                        onClick = { onExampleSelected(example) },
                        subtle = true,
                        enabled = !understandingInProgress
                    )
                }
            }
        }
    }
}
