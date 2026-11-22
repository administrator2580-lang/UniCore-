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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
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

@Composable
fun AccountsManagementCard(
    currentRole: UserRole,
    currentUserId: String,
    usersState: UiState<List<UserProfile>>,
    invitesState: UiState<List<RoleInvite>>,
    onInviteAccount: (String, String, UserRole) -> Unit,
    onRevokeInvite: (String) -> Unit,
    onPromoteUser: (UserProfile) -> Unit,
    onDemoteAdmin: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    var showInviteDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val users = (usersState as? UiState.Success)?.data.orEmpty()
    val filteredUsers = remember(users, searchQuery) {
        if (searchQuery.isBlank()) users
        else users.filter { it.displayName.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val invites = (invitesState as? UiState.Success)?.data.orEmpty()

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
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
                        }

                        if (currentRole == UserRole.SUPER_ADMIN && user.userId != currentUserId && roleEnum != UserRole.SUPER_ADMIN) {
                            if (roleEnum == UserRole.STUDENT) {
                                OutlinedButton(onClick = { onPromoteUser(user) }) {
                                    Text(strings.promoteToAdmin)
                                }
                            } else if (roleEnum == UserRole.ADMIN) {
                                OutlinedButton(onClick = { onDemoteAdmin(user.userId) }) {
                                    Text(strings.demoteToStudent)
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
}

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
