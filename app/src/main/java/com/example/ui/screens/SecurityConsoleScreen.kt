package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.AdminRecord
import com.example.data.model.AuditLog
import com.example.data.model.SystemSettings
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.security.ZeroCostQuotaManager
import com.example.ui.viewmodel.UiState

@Composable
fun SecurityConsoleScreen(
    userRole: UserRole,
    userProfile: UserProfile?,
    systemSettings: SystemSettings,
    quotaSnapshot: ZeroCostQuotaManager.QuotaSnapshot,
    auditLogsState: UiState<List<AuditLog>>,
    adminsState: UiState<List<AdminRecord>>,
    onUpdateSettings: (Long, Long, Boolean, Boolean) -> Unit,
    onGrantAdmin: (String, String) -> Unit,
    onRevokeAdmin: (String) -> Unit,
    onSignOut: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = "Security Role",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = userProfile?.displayName ?: "UniCore Member",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = userRole.name,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        text = "UID: ${userProfile?.userId?.take(16) ?: "active"}... • Spark Zero-Cost Tier",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Daily Firestore Writes: ${quotaSnapshot.writesUsedToday} / ${quotaSnapshot.writesDailyCap}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { quotaSnapshot.writesRatio },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sign_out_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign out",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Sign Out & Clear Credentials")
                    }
                }
            }
        }

        // Super Admin Governance Panel (Strictly hidden & server-gated to SUPER_ADMIN)
        if (userRole == UserRole.SUPER_ADMIN) {
            item {
                SuperAdminControlsCard(
                    settings = systemSettings,
                    onUpdateSettings = onUpdateSettings,
                    onGrantAdmin = onGrantAdmin
                )
            }

            val admins = (adminsState as? UiState.Success)?.data.orEmpty()
            if (admins.isNotEmpty()) {
                item {
                    Text(
                        text = "Delegated Admins (${admins.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(admins, key = { it.id }) { admin ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = admin.email, style = MaterialTheme.typography.titleMedium)
                                Text(text = "UID: ${admin.userId}", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { onRevokeAdmin(admin.userId) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Revoke Admin")
                            }
                        }
                    }
                }
            }
        }

        // Immutable Audit Logs Viewer (Visible to ADMIN and SUPER_ADMIN only; server-denied for STUDENT)
        if (userRole != UserRole.STUDENT) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Immutable Security Audit Logs",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Text(
                    text = "Append-only telemetry. Update & Delete operations are permanently blocked by Firestore Rules for all roles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (auditLogsState) {
                is UiState.Loading -> item { Text("Loading audit telemetry...") }
                is UiState.Error -> item { ErrorBannerCard(auditLogsState.message) }
                is UiState.Success -> {
                    items(auditLogsState.data, key = { it.id }) { log ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = log.action,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = log.severity,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when (log.severity) {
                                            "CRITICAL", "HIGH" -> MaterialTheme.colorScheme.error
                                            "WARN" -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.secondary
                                        }
                                    )
                                }
                                Text(text = log.details, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Actor: ${log.actorLabel} • Path: ${log.targetPath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperAdminControlsCard(
    settings: SystemSettings,
    onUpdateSettings: (Long, Long, Boolean, Boolean) -> Unit,
    onGrantAdmin: (String, String) -> Unit
) {
    var aiEnabled by remember(settings.aiEnabled) { mutableStateOf(settings.aiEnabled) }
    var zeroCostLock by remember(settings.zeroCostLockActive) { mutableStateOf(settings.zeroCostLockActive) }
    var aiLimitText by remember(settings.dailyAiLimitPerUser) { mutableStateOf(settings.dailyAiLimitPerUser.toString()) }
    var writeLimitText by remember(settings.dailyWriteLimitPerUser) { mutableStateOf(settings.dailyWriteLimitPerUser.toString()) }

    var newAdminUid by remember { mutableStateOf("") }
    var newAdminEmail by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Text("Super Admin Zero-Cost Governance", style = MaterialTheme.typography.titleLarge)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable AI Tutor (Free Tier)", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = aiEnabled, onCheckedChange = { aiEnabled = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Emergency Zero-Cost Lock", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = zeroCostLock, onCheckedChange = { zeroCostLock = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = aiLimitText,
                    onValueChange = { aiLimitText = it.filter(Char::isDigit) },
                    label = { Text("AI Cap/Day") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = writeLimitText,
                    onValueChange = { writeLimitText = it.filter(Char::isDigit) },
                    label = { Text("Writes Cap/Day") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = {
                    onUpdateSettings(
                        aiLimitText.toLongOrNull() ?: 20L,
                        writeLimitText.toLongOrNull() ?: 100L,
                        aiEnabled,
                        zeroCostLock
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("save_super_admin_settings")
            ) {
                Text("Save Super Admin Policy")
            }

            OutlinedTextField(
                value = newAdminUid,
                onValueChange = { newAdminUid = it },
                label = { Text("Grant Admin by User UID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newAdminEmail,
                onValueChange = { newAdminEmail = it },
                label = { Text("Admin Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    if (newAdminUid.isNotBlank() && newAdminEmail.isNotBlank()) {
                        onGrantAdmin(newAdminUid, newAdminEmail)
                        newAdminUid = ""
                        newAdminEmail = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delegate Admin Privilege")
            }
        }
    }
}
