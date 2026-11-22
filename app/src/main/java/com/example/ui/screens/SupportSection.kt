package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.SupportChannel
import com.example.data.model.SupportMessage
import com.example.data.model.UserRole
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState

@Composable
fun SupportMessagingCard(
    userRole: UserRole,
    currentUserId: String,
    messagesState: UiState<List<SupportMessage>>,
    onSendMessage: (String, String) -> Unit,
    onReplyMessage: (SupportMessage, String) -> Unit
) {
    val strings = LocalAppStrings.current
    var showComposeDialog by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<SupportMessage?>(null) }

    val messages = (messagesState as? UiState.Success)?.data.orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.SupportAgent,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = strings.supportSectionTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                when (userRole) {
                    UserRole.STUDENT -> {
                        Button(
                            onClick = { showComposeDialog = true },
                            modifier = Modifier.testTag("contact_admin_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(strings.contactAdminAction)
                        }
                    }
                    UserRole.ADMIN -> {
                        Button(
                            onClick = { showComposeDialog = true },
                            modifier = Modifier.testTag("contact_super_admin_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(strings.contactSuperAdminAction)
                        }
                    }
                    UserRole.SUPER_ADMIN -> Unit
                }
            }

            if (messages.isEmpty()) {
                Text(
                    text = strings.noSupportMessages,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                when (userRole) {
                    UserRole.STUDENT -> {
                        Text(text = strings.mySentMessagesTitle, style = MaterialTheme.typography.titleMedium)
                        messages.forEach { msg ->
                            SupportMessageItem(
                                message = msg,
                                canReply = false,
                                onReplyClick = {}
                            )
                        }
                    }
                    UserRole.ADMIN -> {
                        val adminToSuper = messages.filter {
                            it.channel == SupportChannel.ADMIN_TO_SUPER.name && it.senderUid == currentUserId
                        }
                        val studentInquiries = messages.filter {
                            it.channel == SupportChannel.STUDENT_TO_ADMIN.name
                        }

                        if (adminToSuper.isNotEmpty()) {
                            Text(text = strings.adminToSuperInboxTitle, style = MaterialTheme.typography.titleMedium)
                            adminToSuper.forEach { msg ->
                                SupportMessageItem(
                                    message = msg,
                                    canReply = false,
                                    onReplyClick = {}
                                )
                            }
                        }

                        if (studentInquiries.isNotEmpty()) {
                            Text(text = strings.studentInquiriesTitle, style = MaterialTheme.typography.titleMedium)
                            studentInquiries.forEach { msg ->
                                SupportMessageItem(
                                    message = msg,
                                    canReply = true,
                                    onReplyClick = { replyingTo = msg }
                                )
                            }
                        }
                    }
                    UserRole.SUPER_ADMIN -> {
                        val adminEscalations = messages.filter { it.channel == SupportChannel.ADMIN_TO_SUPER.name }
                        val studentMessages = messages.filter { it.channel == SupportChannel.STUDENT_TO_ADMIN.name }

                        if (adminEscalations.isNotEmpty()) {
                            Text(text = strings.adminToSuperInboxTitle, style = MaterialTheme.typography.titleMedium)
                            adminEscalations.forEach { msg ->
                                SupportMessageItem(
                                    message = msg,
                                    canReply = true,
                                    onReplyClick = { replyingTo = msg }
                                )
                            }
                        }

                        if (studentMessages.isNotEmpty()) {
                            Text(text = strings.studentInquiriesTitle, style = MaterialTheme.typography.titleMedium)
                            studentMessages.forEach { msg ->
                                SupportMessageItem(
                                    message = msg,
                                    canReply = true,
                                    onReplyClick = { replyingTo = msg }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showComposeDialog) {
        val dialogTitle = if (userRole == UserRole.ADMIN) {
            strings.contactSuperAdminAction
        } else {
            strings.contactAdminAction
        }
        ComposeSupportDialog(
            title = dialogTitle,
            onDismiss = { showComposeDialog = false },
            onConfirm = { subject, body ->
                onSendMessage(subject, body)
                showComposeDialog = false
            }
        )
    }

    val targetReply = replyingTo
    if (targetReply != null) {
        ReplySupportDialog(
            message = targetReply,
            onDismiss = { replyingTo = null },
            onConfirm = { replyText ->
                onReplyMessage(targetReply, replyText)
                replyingTo = null
            }
        )
    }
}

@Composable
private fun SupportMessageItem(
    message: SupportMessage,
    canReply: Boolean,
    onReplyClick: () -> Unit
) {
    val strings = LocalAppStrings.current
    val isReplied = message.status == "REPLIED" && !message.replyText.isNullOrBlank()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.subject,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (isReplied) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = if (isReplied) strings.statusReplied else strings.statusOpen,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = "${message.senderName} (${message.senderRole})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium
            )

            if (isReplied) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = message.repliedByName ?: strings.roleAdmin,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = message.replyText.orEmpty(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (canReply) {
                OutlinedButton(onClick = onReplyClick) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(strings.replyAction)
                }
            }
        }
    }
}

@Composable
private fun ComposeSupportDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    val strings = LocalAppStrings.current
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text(strings.supportSubjectLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("support_subject_input")
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(strings.supportBodyLabel) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("support_body_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(subject, body) },
                enabled = subject.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.testTag("confirm_send_support")
            ) {
                Text(strings.sendAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
        }
    )
}

@Composable
private fun ReplySupportDialog(
    message: SupportMessage,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    var reply by remember { mutableStateOf(message.replyText.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${strings.replyAction}: ${message.subject}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = message.body, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    label = { Text(strings.replyHint) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("support_reply_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reply) },
                enabled = reply.isNotBlank(),
                modifier = Modifier.testTag("confirm_reply_support")
            ) {
                Text(strings.sendAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
        }
    )
}
