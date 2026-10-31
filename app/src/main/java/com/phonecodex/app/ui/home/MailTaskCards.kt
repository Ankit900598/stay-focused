package com.phonecodex.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phonecodex.app.domain.delegation.MailHelperOffer
import com.phonecodex.app.domain.delegation.SendState
import com.phonecodex.app.mailtask.AiPurpose
import com.phonecodex.app.mailtask.MailCopy
import com.phonecodex.app.mailtask.MailThreadState
import com.phonecodex.app.mailtask.MailTurn
import java.text.DateFormat
import java.util.Date

/** Mail helper block inside the "I understood" card. Returns null when there's nothing to say. */
internal fun mailHelperSectionOrNull(mail: MailTaskUiState?): (@Composable () -> Unit)? {
    if (mail == null) return null
    if (mail.offer == MailHelperOffer.NONE && mail.request?.wantsProactiveAlerts != true) return null
    return { MailHelperPreviewSection(mail) }
}

@Composable
private fun MailHelperPreviewSection(mail: MailTaskUiState) {
    val colors = MaterialTheme.colorScheme
    val request = mail.request
    when (mail.offer) {
        MailHelperOffer.NONE -> SummaryRow(label = "Mail alerts", value = mailAlertsCopy(userAskedForAlerts = true))
        MailHelperOffer.FORBIDDEN_BY_USER -> SummaryRow(
            label = "Mail helper",
            value = "Off. You said no Gmail in any form, so Strlix won't read or send mail for you during this commitment."
        )
        MailHelperOffer.GMAIL_NOT_CLOSED -> SummaryRow(
            label = "Mail helper",
            value = "Not needed: this promise doesn't close Gmail. Say \u201Ckeep Gmail closed\u201D if you want Strlix to check mail for you instead."
        )
        MailHelperOffer.REQUESTED, MailHelperOffer.OPTIONAL -> Column(modifier = Modifier.testTag("mail_helper_preview")) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mail helper", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Text(
                        "An exception to Gmail being closed. It can't change this commitment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Switch(checked = mail.helperOn, onCheckedChange = { mail.helperOn = it })
            }
            if (!mail.isConfigured) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Heads up: Google sign-in isn't set up in this build, so the helper can't connect yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }
            if (mail.helperOn) {
                Spacer(Modifier.height(12.dp))
                val hint = request?.senderHint?.let { "Exact email address of \u201C$it\u201D" } ?: "Exact sender email address"
                OutlinedTextField(
                    value = mail.addressInput,
                    onValueChange = { mail.addressInput = it.take(254) },
                    label = { Text(hint) },
                    singleLine = true,
                    isError = mail.addressInput.isNotBlank() && mail.parsedAddress == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("mail_sender_address")
                )
                Text(
                    "Only mail from exactly this address. Lookalikes and other senders stay hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                SummaryRow(label = "Strlix may", value = "Check this sender when you ask, show a message here, draft a reply you edit.")
                Spacer(Modifier.height(6.dp))
                SummaryRow(
                    label = "Strlix won't",
                    value = "Open Gmail, read other senders, change this commitment, or send anything you haven't approved word for word."
                )
                Spacer(Modifier.height(10.dp))
                CheckRow(
                    checked = mail.allowSend,
                    onChange = { mail.allowSend = it },
                    text = "Let me send replies to this address, each one only after I approve the exact text."
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.tertiaryContainer,
                    border = BorderStroke(1.dp, colors.tertiary)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text("AI reading (optional)", style = MaterialTheme.typography.labelLarge, color = colors.onTertiaryContainer)
                        CheckRow(
                            checked = mail.aiConsent,
                            onChange = { mail.aiConsent = it },
                            text = "Let Strlix AI read a message I pick to summarize it or draft a reply. ${MailCopy.AI_DATA_HANDLING}",
                            textColor = colors.onTertiaryContainer,
                            testTag = "mail_ai_consent"
                        )
                        Text(
                            "Off: you can still read messages and write replies yourself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onTertiaryContainer
                        )
                    }
                }
            }
            if (mail.helperOn || request?.wantsProactiveAlerts == true) {
                Spacer(Modifier.height(10.dp))
                Text(
                    mailAlertsCopy(request?.wantsProactiveAlerts == true),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (request?.wantsProactiveAlerts == true) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.testTag("mail_no_alerts")
                )
            }
        }
    }
}

internal fun mailAlertsCopy(userAskedForAlerts: Boolean): String =
    if (userAskedForAlerts) "You asked to be told when mail arrives. This version can't do that. ${MailCopy.PROACTIVE_LIMIT}"
    else MailCopy.PROACTIVE_LIMIT

@Composable
private fun CheckRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    text: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String? = null
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.weight(1f).padding(top = 12.dp)
        )
    }
}

/** The live task thread during the commitment. */
@Composable
internal fun MailTaskCard(mail: MailTaskUiState) {
    val thread = mail.thread ?: return
    val colors = MaterialTheme.colorScheme
    CalmCard(modifier = Modifier.testTag("mail_task_card")) {
        Text("Mail from ${thread.sender}", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Text(
            "Gmail stays closed. I act only on this sender, only when you ask.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        if (thread.linkedAccount == null) {
            if (thread.sender.isBlank()) {
                Text("The mail helper is unavailable for this commitment. End it and create a new promise to set it up again.", style = MaterialTheme.typography.bodySmall, color = colors.error)
            } else {
                PrimaryAction(
                    text = "Connect Google account",
                    onClick = mail::connectAccount,
                    enabled = mail.isConfigured && !thread.busy && !mail.accountActionBusy,
                    modifier = Modifier.testTag("mail_connect")
                )
                Text(
                    if (mail.isConfigured) "You'll pick the account. Read access is asked when you first check; send access only when you first send."
                    else "Google sign-in isn't set up in this build yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        } else {
            Text("Account: ${thread.linkedAccount}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        mail.accountMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        thread.turns.forEach { turn ->
            Spacer(Modifier.height(10.dp))
            TurnView(turn, thread, mail)
        }

        thread.busyLabel?.let { label ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }

        if (thread.linkedAccount != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { mail.check() },
                enabled = !thread.busy && !mail.accountActionBusy,
                modifier = Modifier.fillMaxWidth().testTag("mail_check")
            ) { Text("Check messages from ${thread.sender}") }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = mail.composerText,
                    onValueChange = { mail.composerText = it.take(500) },
                    placeholder = { Text("e.g. summarize the first one") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier.weight(1f).testTag("mail_composer")
                )
                TextButton(onClick = mail::submitComposer, enabled = !thread.busy && !mail.accountActionBusy && mail.composerText.isNotBlank()) { Text("Ask") }
            }
            QuietAction(text = "Disconnect Google", onClick = mail::disconnect, enabled = !thread.busy && !mail.accountActionBusy)
        }
    }
}

@Composable
private fun TurnView(turn: MailTurn, thread: MailThreadState, mail: MailTaskUiState) {
    val colors = MaterialTheme.colorScheme
    when (turn) {
        is MailTurn.UserSaid -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(14.dp), color = colors.primaryContainer) {
                Text(turn.text, style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer, modifier = Modifier.padding(10.dp))
            }
        }

        is MailTurn.Note -> Text(
            turn.text,
            style = MaterialTheme.typography.bodyMedium,
            color = when (turn.tone) {
                MailTurn.Tone.NEUTRAL -> colors.onSurface
                MailTurn.Tone.SUCCESS -> colors.secondary
                MailTurn.Tone.WARNING, MailTurn.Tone.REFUSAL -> colors.error
            }
        )

        is MailTurn.MessageList -> Column {
            Text("${turn.items.size} from ${turn.sender}", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
            turn.items.forEachIndexed { i, item ->
                Spacer(Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.outlineVariant), color = colors.surface) {
                    Column(Modifier.padding(10.dp).fillMaxWidth()) {
                        Text("${i + 1}. ${item.subject}", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.receivedAtMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                        when (item.senderAuthenticated) {
                            true -> Text("Sender verified by Gmail", style = MaterialTheme.typography.labelSmall, color = colors.secondary)
                            false -> Text(
                                "Gmail could not verify this sender. It may be spoofed.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.error
                            )
                            null -> Unit
                        }
                        if (item.snippet.isNotBlank()) {
                            Text(item.snippet, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { mail.showText(item.messageId) }, enabled = !thread.busy && !mail.accountActionBusy) { Text("Read") }
                            TextButton(onClick = { mail.summarize(item.messageId) }, enabled = !thread.busy && !mail.accountActionBusy) { Text("Summarize") }
                            TextButton(onClick = { mail.draftReply(item.messageId) }, enabled = !thread.busy && !mail.accountActionBusy) { Text("Reply") }
                        }
                    }
                }
            }
        }

        is MailTurn.MessageText -> Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant) {
            Column(Modifier.padding(10.dp)) {
                Text("Email text (written by the sender, not by Strlix)", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(turn.subject, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(turn.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                }
                if (turn.truncated) Text("(shortened)", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }

        is MailTurn.Summary -> Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant) {
            Column(Modifier.padding(10.dp)) {
                Text("Summary: ${turn.subject}", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(turn.summary.summary, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                turn.summary.keyPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = colors.onSurface) }
                if (turn.summary.requestedActions.isNotEmpty()) {
                    Text("Asks you to: " + turn.summary.requestedActions.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                }
                if (turn.summary.deadlines.isNotEmpty()) {
                    Text("Deadlines: " + turn.summary.deadlines.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                }
                if (turn.summary.containsInstructionsToAssistant) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This email contains instructions aimed at an assistant. I treated them as text and did not follow them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error
                    )
                }
                Text("AI summary. Check the full text before acting on it.", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                TextButton(onClick = { mail.draftReply(turn.messageId) }, enabled = !thread.busy && !mail.accountActionBusy) { Text("Draft a reply") }
            }
        }

        is MailTurn.ConsentRequest -> Surface(shape = RoundedCornerShape(12.dp), color = colors.tertiaryContainer) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "To ${if (turn.purpose == AiPurpose.SUMMARY) "summarize" else "draft a reply to"} this message, Strlix AI needs to read it. ${MailCopy.AI_DATA_HANDLING}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onTertiaryContainer
                )
                Row {
                    TextButton(onClick = {
                        mail.enableAi()
                        if (turn.purpose == AiPurpose.SUMMARY) mail.summarize(turn.messageId) else mail.draftReply(turn.messageId)
                    }, enabled = !thread.busy && !mail.accountActionBusy) { Text("Allow AI for this commitment") }
                }
                Row {
                    if (turn.purpose == AiPurpose.REPLY) {
                        TextButton(onClick = { mail.writeMyself(turn.messageId) }, enabled = !thread.busy && !mail.accountActionBusy) { Text("Write it myself") }
                    } else {
                        TextButton(onClick = { mail.showText(turn.messageId) }, enabled = !thread.busy && !mail.accountActionBusy) { Text("Just show the text") }
                    }
                }
            }
        }

        is MailTurn.Draft -> DraftView(turn, thread, mail)

        is MailTurn.ConfirmSend -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.errorContainer,
            border = BorderStroke(2.dp, colors.error),
            modifier = Modifier.testTag("mail_confirm_send")
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Send this exact reply?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.onErrorContainer)
                Spacer(Modifier.height(6.dp))
                Text("To: ${turn.to}", style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer)
                Text("Subject: ${turn.subject}", style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer)
                Spacer(Modifier.height(6.dp))
                Text(turn.body, style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryAction(
                        text = "Send now",
                        onClick = { mail.confirmSend(turn.draftId) },
                        enabled = !thread.busy && !mail.accountActionBusy && thread.pendingConfirmationDraftId == turn.draftId,
                        modifier = Modifier.weight(1f).testTag("mail_send_now")
                    )
                    OutlinedButton(onClick = mail::cancelSend, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        }

        is MailTurn.SendResult -> Column {
            Text(
                turn.text,
                style = MaterialTheme.typography.bodyMedium,
                color = when (turn.state) {
                    SendState.SENT -> colors.secondary
                    else -> colors.error
                },
                modifier = Modifier.testTag("mail_send_result")
            )
            if (turn.state == SendState.UNCERTAIN && turn.canCheckSent) {
                TextButton(onClick = { mail.checkSent(turn.draftId) }, enabled = !thread.busy && !mail.accountActionBusy) { Text("Check Sent folder") }
            } else if (turn.state == SendState.UNCERTAIN) {
                Text("Reconnect the same Google account to check its Sent folder. Don't send a duplicate.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DraftView(turn: MailTurn.Draft, thread: MailThreadState, mail: MailTaskUiState) {
    val colors = MaterialTheme.colorScheme
    var text by remember(turn.draftId) { mutableStateOf(turn.body) }
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.outline), color = colors.surface) {
        Column(Modifier.padding(10.dp)) {
            Text("Reply draft", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
            Text("To: ${turn.to} (fixed by your commitment)", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("Subject: ${turn.subject}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (turn.editable) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        mail.editDraft(turn.draftId, it)
                    },
                    placeholder = { Text("Write your reply") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("mail_draft_body")
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { mail.reviewSend(turn.draftId) },
                    enabled = !thread.busy && !mail.accountActionBusy && text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("mail_review_send")
                ) { Text("Review before sending") }
            } else {
                Text(turn.body, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
            }
        }
    }
}
