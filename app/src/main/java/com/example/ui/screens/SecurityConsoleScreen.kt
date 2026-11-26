package com.example.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AuditLog
import com.example.data.model.RoleInvite
import com.example.data.model.SupportMessage
import com.example.data.model.SystemSettings
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.preferences.AppLanguage
import com.example.data.preferences.AppThemeMode
import com.example.notifications.UniCoreNotificationManager
import com.example.security.ZeroCostQuotaManager
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState

@Composable
fun SecurityConsoleScreen(
    currentUserId: String,
    userRole: UserRole,
    userProfile: UserProfile?,
    systemSettings: SystemSettings,
    quotaSnapshot: ZeroCostQuotaManager.QuotaSnapshot,
    currentLanguage: AppLanguage,
    currentThemeMode: AppThemeMode,
    systemNotificationsEnabled: Boolean,
    auditLogsState: UiState<List<AuditLog>>,
    usersDirectoryState: UiState<List<UserProfile>>,
    roleInvitesState: UiState<List<RoleInvite>>,
    supportMessagesState: UiState<List<SupportMessage>>,
    onSetLanguage: (AppLanguage) -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onSetSystemNotifications: (Boolean) -> Unit,
    onInviteAccount: (String, String, UserRole) -> Unit,
    onRevokeInvite: (String) -> Unit,
    onPromoteUser: (UserProfile) -> Unit,
    onDemoteAdmin: (String) -> Unit,
    onToggleAccountDisabled: (UserProfile, Boolean) -> Unit,
    onTimedBanUser: (UserProfile, Long, String) -> Unit,
    onSendSupportMessage: (String, String, Uri?) -> Unit,
    onReplySupportMessage: (SupportMessage, String, Uri?) -> Unit,
    onUpdateSettings: (Long, Long, Boolean, Boolean) -> Unit,
    onSignOut: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    var showAuditLogs by remember { mutableStateOf(false) }

    var hasSystemPermission by remember {
        mutableStateOf(UniCoreNotificationManager.hasNotificationPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasSystemPermission = granted
        onSetSystemNotifications(granted)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Profile & Session Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Icon(
                                    Icons.Default.Shield,
                                    contentDescription = strings.navAccount,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(22.dp)
                                )
                            }
                            Text(
                                text = userProfile?.displayName ?: strings.appName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = when (userRole) {
                                    UserRole.SUPER_ADMIN -> strings.roleSuperAdmin
                                    UserRole.ADMIN -> strings.roleAdmin
                                    UserRole.STUDENT -> strings.roleStudent
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Text(
                        text = "${strings.dailyWritesLabel}: ${quotaSnapshot.writesUsedToday} / ${quotaSnapshot.writesDailyCap}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            contentDescription = strings.signOutAction,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(strings.signOutAction)
                    }
                }
            }
        }

        // Language, Theme & Notifications Card (Visible to ALL roles)
        item {
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = strings.appearanceCardTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Language Switcher
                    Text(
                        text = strings.languageLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = currentLanguage == AppLanguage.ARABIC,
                            onClick = { onSetLanguage(AppLanguage.ARABIC) },
                            leadingIcon = {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text("العربية") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lang_chip_ar")
                        )
                        FilterChip(
                            selected = currentLanguage == AppLanguage.ENGLISH,
                            onClick = { onSetLanguage(AppLanguage.ENGLISH) },
                            leadingIcon = {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text("English") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lang_chip_en")
                        )
                    }

                    // Theme Mode Selector
                    Text(
                        text = strings.themeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = currentThemeMode == AppThemeMode.SYSTEM,
                            onClick = { onSetThemeMode(AppThemeMode.SYSTEM) },
                            leadingIcon = {
                                Icon(Icons.Default.BrightnessAuto, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(strings.themeSystem) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("theme_chip_system")
                        )
                        FilterChip(
                            selected = currentThemeMode == AppThemeMode.DARK,
                            onClick = { onSetThemeMode(AppThemeMode.DARK) },
                            leadingIcon = {
                                Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(strings.themeDark) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("theme_chip_dark")
                        )
                        FilterChip(
                            selected = currentThemeMode == AppThemeMode.LIGHT,
                            onClick = { onSetThemeMode(AppThemeMode.LIGHT) },
                            leadingIcon = {
                                Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(strings.themeLight) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("theme_chip_light")
                        )
                    }

                    // Phone Notifications Toggle
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
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = strings.notificationsEnabledLabel,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Switch(
                            checked = systemNotificationsEnabled && hasSystemPermission,
                            onCheckedChange = { wantEnabled ->
                                if (wantEnabled && !hasSystemPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onSetSystemNotifications(wantEnabled)
                                }
                            },
                            modifier = Modifier.testTag("system_notifications_switch")
                        )
                    }
                }
            }
        }

        // Tiered Support & Escalation Messaging (Student <-> Admin, Admin <-> Super Admin)
        item {
            SupportMessagingCard(
                userRole = userRole,
                currentUserId = currentUserId,
                messagesState = supportMessagesState,
                onSendMessage = onSendSupportMessage,
                onReplyMessage = onReplySupportMessage
            )
        }

        // Accounts & Role Management (ADMIN + SUPER_ADMIN)
        if (userRole != UserRole.STUDENT) {
            item {
                AccountsManagementCard(
                    currentRole = userRole,
                    currentUserId = currentUserId,
                    usersState = usersDirectoryState,
                    invitesState = roleInvitesState,
                    onInviteAccount = onInviteAccount,
                    onRevokeInvite = onRevokeInvite,
                    onPromoteUser = onPromoteUser,
                    onDemoteAdmin = onDemoteAdmin,
                    onToggleAccountDisabled = onToggleAccountDisabled,
                    onTimedBanUser = onTimedBanUser
                )
            }
        }

        // Super Admin Policy Controls (SUPER_ADMIN only)
        if (userRole == UserRole.SUPER_ADMIN) {
            item {
                SuperAdminControlsCard(
                    settings = systemSettings,
                    onUpdateSettings = onUpdateSettings
                )
            }
        }

        // Collapsible Immutable Audit Logs (Strictly SUPER_ADMIN ONLY — hidden from Students & Regular Admins)
        if (userRole == UserRole.SUPER_ADMIN) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAuditLogs = !showAuditLogs }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(
                                    text = strings.auditLogsTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = strings.auditLogsSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = if (showAuditLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = strings.auditLogsTitle
                        )
                    }
                }
            }

            if (showAuditLogs) {
                when (auditLogsState) {
                    is UiState.Loading -> item { Text(strings.loadingText) }
                    is UiState.Error -> item { ErrorBannerCard(auditLogsState.message) }
                    is UiState.Success -> {
                        items(auditLogsState.data, key = { it.id }) { log ->
                            AnimatedVisibility(visible = true) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                                            text = "${log.actorLabel} • ${log.targetPath}",
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
    }
}

@Composable
private fun SuperAdminControlsCard(
    settings: SystemSettings,
    onUpdateSettings: (Long, Long, Boolean, Boolean) -> Unit
) {
    val strings = LocalAppStrings.current
    var aiEnabled by remember(settings.aiEnabled) { mutableStateOf(settings.aiEnabled) }
    var zeroCostLock by remember(settings.zeroCostLockActive) { mutableStateOf(settings.zeroCostLockActive) }
    var aiLimitText by remember(settings.dailyAiLimitPerUser) { mutableStateOf(settings.dailyAiLimitPerUser.toString()) }
    var writeLimitText by remember(settings.dailyWriteLimitPerUser) { mutableStateOf(settings.dailyWriteLimitPerUser.toString()) }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Text(strings.governanceTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.enableAiTutorSwitch, style = MaterialTheme.typography.bodyLarge)
                Switch(checked = aiEnabled, onCheckedChange = { aiEnabled = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.emergencyZeroCostLock, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = zeroCostLock, onCheckedChange = { zeroCostLock = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = aiLimitText,
                    onValueChange = { aiLimitText = it.filter(Char::isDigit) },
                    label = { Text(strings.aiCapPerDay) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = writeLimitText,
                    onValueChange = { writeLimitText = it.filter(Char::isDigit) },
                    label = { Text(strings.writesCapPerDay) },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save_super_admin_settings")
            ) {
                Text(strings.savePolicyButton)
            }
        }
    }
}
