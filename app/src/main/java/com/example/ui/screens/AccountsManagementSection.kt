package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.InviteStatus
import com.example.data.model.RoleInvite
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsManagementCard(
    currentRole: UserRole,
    currentUserId: String,
    usersState: UiState<List<UserProfile>>,
    invitesState: UiState<List<RoleInvite>>,
    onInviteAccount: (String, String, UserRole) -> Unit,
    onRevokeInvite: (String) -> Unit,
    onPromoteUser: (UserProfile) -> Unit,
    onPromoteSuperAdmin: (UserProfile) -> Unit,
    onDemoteAdmin: (String) -> Unit,
    onDemoteSuperAdmin: (String) -> Unit,
    onToggleAccountDisabled: (UserProfile, Boolean) -> Unit,
    onTimedBanUser: (UserProfile, Long, String) -> Unit
) {
    val strings = LocalAppStrings.current
    var showInviteDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var banTargetUser by remember { mutableStateOf<UserProfile?>(null) }

    val users = (usersState as? UiState.Success)?.data.orEmpty()
    val filteredUsers = remember(users, searchQuery) {
        if (searchQuery.isBlank()) users
        else users.filter { it.displayName.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val invites = (invitesState as? UiState.Success)?.data.orEmpty()
    val nowMillis = remember(users) { System.currentTimeMillis() }

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
                        Icons.Default.ManageAccounts,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = strings.accountsSectionTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = { showInviteDialog = true },
                    modifier = Modifier.testTag("add_account_button")
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(strings.addAccountAction)
                }
            }

            Text(
                text = strings.accountsSectionSub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Invites List
            if (invites.isNotEmpty()) {
                Text(
                    text = "${strings.pendingInvitesTitle} (${invites.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                invites.forEach { invite ->
                    val status = InviteStatus.fromRaw(invite.status)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${invite.displayNameHint} • ${invite.assignedRole}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${invite.email} • ${if (status == InviteStatus.PENDING) strings.pendingStatus else strings.claimedStatus}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (currentRole == UserRole.SUPER_ADMIN || invite.assignedRole == UserRole.STUDENT.name) {
                                IconButton(onClick = { onRevokeInvite(invite.email) }) {
                                    Icon(Icons.Default.Delete, contentDescription = strings.revokeInvite)
                                }
                            }
                        }
                    }
                }
            }

            // Registered Users List
            Text(
                text = "${strings.registeredUsersTitle} (${users.size})",
                style = MaterialTheme.typography.titleMedium
            )

            if (users.size > 3) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(strings.searchUsersHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            filteredUsers.forEach { user ->
                val roleEnum = UserRole.fromRaw(user.role)
                val isTimedBanned = user.banUntilMillis > nowMillis
                val canTimedBan = (currentRole == UserRole.SUPER_ADMIN && user.userId != currentUserId && roleEnum != UserRole.SUPER_ADMIN) ||
                    (currentRole == UserRole.ADMIN && roleEnum == UserRole.STUDENT && user.userId != currentUserId)

                Surface(
                    color = if (user.accountDisabled || isTimedBanned) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = user.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = when (roleEnum) {
                                        UserRole.SUPER_ADMIN -> strings.roleSuperAdmin
                                        UserRole.ADMIN -> strings.roleAdmin
                                        UserRole.STUDENT -> strings.roleStudent
                                    } + " • AI ${user.aiCallsToday} • Sync ${user.writesToday}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val placementSummary = listOfNotNull(
                                    user.departmentName?.takeIf { it.isNotBlank() },
                                    user.academicYear?.takeIf { it.isNotBlank() },
                                    user.sectionName?.takeIf { it.isNotBlank() }
                                ).joinToString(" • ")
                                if (placementSummary.isNotBlank()) {
                                    Text(
                                        text = placementSummary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (isTimedBanned) {
                                    val formatted = remember(user.banUntilMillis) {
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                            .format(Date(user.banUntilMillis))
                                    }
                                    Text(
                                        text = "${strings.bannedUntilBody} $formatted" +
                                            if (user.banReason.isNotBlank()) " (${user.banReason})" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Surface(
                                color = if (user.accountDisabled || isTimedBanned) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = when {
                                        user.accountDisabled -> strings.accountStatusDisabled
                                        isTimedBanned -> strings.banUserAction
                                        else -> strings.accountStatusActive
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (user.accountDisabled || isTimedBanned) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if ((currentRole == UserRole.SUPER_ADMIN && user.userId != currentUserId) || canTimedBan) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (currentRole == UserRole.SUPER_ADMIN && user.userId != currentUserId) {
                                    when (roleEnum) {
                                        UserRole.STUDENT -> {
                                            OutlinedButton(
                                                onClick = { onPromoteUser(user) },
                                                modifier = Modifier.testTag("promote_admin_${user.userId}")
                                            ) {
                                                Text(strings.promoteToAdmin)
                                            }
                                            OutlinedButton(
                                                onClick = { onPromoteSuperAdmin(user) },
                                                modifier = Modifier.testTag("promote_super_${user.userId}")
                                            ) {
                                                Text(strings.promoteToSuperAdmin)
                                            }
                                        }
                                        UserRole.ADMIN -> {
                                            OutlinedButton(
                                                onClick = { onPromoteSuperAdmin(user) },
                                                modifier = Modifier.testTag("promote_super_${user.userId}")
                                            ) {
                                                Text(strings.promoteToSuperAdmin)
                                            }
                                            OutlinedButton(
                                                onClick = { onDemoteAdmin(user.userId) },
                                                modifier = Modifier.testTag("demote_student_${user.userId}")
                                            ) {
                                                Text(strings.demoteToStudent)
                                            }
                                        }
                                        UserRole.SUPER_ADMIN -> {
                                            OutlinedButton(
                                                onClick = { onDemoteSuperAdmin(user.userId) },
                                                modifier = Modifier.testTag("demote_admin_${user.userId}")
                                            ) {
                                                Text(strings.demoteToAdmin)
                                            }
                                        }
                                    }

                                    if (roleEnum != UserRole.SUPER_ADMIN) {
                                        val nextDisabled = !user.accountDisabled
                                        OutlinedButton(
                                            onClick = { onToggleAccountDisabled(user, nextDisabled) },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = if (nextDisabled) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.secondary
                                                }
                                            ),
                                            modifier = Modifier.testTag("toggle_account_disabled_${user.userId}")
                                        ) {
                                            Icon(
                                                imageVector = if (nextDisabled) Icons.Default.Block else Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.size(4.dp))
                                            Text(if (nextDisabled) strings.disableAccountAction else strings.enableAccountAction)
                                        }
                                    }
                                }

                                if (canTimedBan) {
                                    if (isTimedBanned) {
                                        OutlinedButton(
                                            onClick = { onTimedBanUser(user, 0L, "") },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.secondary
                                            ),
                                            modifier = Modifier.testTag("unban_user_${user.userId}")
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.size(4.dp))
                                            Text(strings.unbanUserAction)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { banTargetUser = user },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            ),
                                            modifier = Modifier.testTag("timed_ban_${user.userId}")
                                        ) {
                                            Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.size(4.dp))
                                            Text(strings.banUserAction)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInviteDialog) {
        AddAccountDialog(
            isSuperAdmin = currentRole == UserRole.SUPER_ADMIN,
            onDismiss = { showInviteDialog = false },
            onConfirm = { email, nameHint, role ->
                onInviteAccount(email, nameHint, role)
                showInviteDialog = false
            }
        )
    }

    val targetBan = banTargetUser
    if (targetBan != null) {
        TimedBanDialog(
            targetUser = targetBan,
            onDismiss = { banTargetUser = null },
            onConfirm = { minutes, reason ->
                onTimedBanUser(targetBan, minutes, reason)
                banTargetUser = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimedBanDialog(
    targetUser: UserProfile,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit
) {
    val strings = LocalAppStrings.current
    var durationMinutes by remember { mutableLongStateOf(60L) }
    var customMinutesText by remember { mutableStateOf("60") }
    var reason by remember { mutableStateOf("") }

    val presets = listOf(
        60L to "1h",
        360L to "6h",
        1440L to "24h",
        4320L to "3d",
        10080L to "7d",
        43200L to "30d"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${strings.timedBanTitle}: ${targetUser.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presets.forEach { (mins, label) ->
                        FilterChip(
                            selected = durationMinutes == mins,
                            onClick = {
                                durationMinutes = mins
                                customMinutesText = mins.toString()
                            },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = customMinutesText,
                    onValueChange = { raw ->
                        val digits = raw.filter(Char::isDigit).take(6)
                        customMinutesText = digits
                        durationMinutes = digits.toLongOrNull() ?: 0L
                    },
                    label = { Text(strings.durationLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ban_duration_input")
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(strings.banReasonLabel) },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("ban_reason_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(durationMinutes.coerceAtLeast(1L), reason) },
                enabled = durationMinutes > 0L,
                modifier = Modifier.testTag("confirm_timed_ban")
            ) {
                Text(strings.banUserAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddAccountDialog(
    isSuperAdmin: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, UserRole) -> Unit
) {
    val strings = LocalAppStrings.current
    var email by remember { mutableStateOf("") }
    var nameHint by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf(UserRole.STUDENT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addAccountAction) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nameHint,
                    onValueChange = { nameHint = it },
                    label = { Text(strings.inviteNameLabel) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("invite_name_input")
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(strings.inviteEmailLabel) },
                    placeholder = { Text("student@gmail.com") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("invite_email_input")
                )
                Text(text = strings.selectRoleLabel, style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = selectedRole == UserRole.STUDENT,
                        onClick = { selectedRole = UserRole.STUDENT },
                        label = { Text(strings.roleStudent) }
                    )
                    if (isSuperAdmin) {
                        FilterChip(
                            selected = selectedRole == UserRole.ADMIN,
                            onClick = { selectedRole = UserRole.ADMIN },
                            label = { Text(strings.roleAdmin) }
                        )
                        FilterChip(
                            selected = selectedRole == UserRole.SUPER_ADMIN,
                            onClick = { selectedRole = UserRole.SUPER_ADMIN },
                            label = { Text(strings.roleSuperAdmin) },
                            modifier = Modifier.testTag("invite_super_admin_chip")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(email, nameHint, selectedRole) },
                enabled = email.isNotBlank() && nameHint.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_account")
            ) {
                Text(strings.addAccountAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
        }
    )
}
