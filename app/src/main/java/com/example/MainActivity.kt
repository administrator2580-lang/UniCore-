package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.AcademicScopeFilter
import com.example.data.model.UserRole
import com.example.data.preferences.AppLanguage
import com.example.data.preferences.AppThemeMode
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AiTutorRepository
import com.example.data.repository.GoogleDriveSyncRepository
import com.example.data.repository.UniCoreGovernanceRepository
import com.example.data.repository.UniCoreRepository
import com.example.data.repository.signOut
import com.example.notifications.UniCoreNotificationManager
import com.example.security.RateLimiter
import com.example.security.configureAppCheck
import com.example.ui.components.AdMobBannerBar
import com.example.ui.components.initializeMobileAdsOnce
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.UniCoreStrings
import com.example.ui.i18n.stringsFor
import com.example.ui.screens.AiTutorScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CampusHubScreen
import com.example.ui.screens.CoursesScreen
import com.example.ui.screens.InAppFilePreviewDialog
import com.example.ui.screens.MyDueAssignmentsScreen
import com.example.ui.screens.NotificationsSheet
import com.example.ui.screens.OnboardingPlacementDialog
import com.example.ui.screens.QuizzesScreen
import com.example.ui.screens.SecurityConsoleScreen
import com.example.ui.screens.StudyChatScreen
import com.example.ui.theme.UniCoreTheme
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.UniCoreViewModel
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ENABLE_ADMOB_ON_STARTUP = true

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupCrashGuard.installUncaughtHandlerEarly(applicationContext)
        runCatching { enableEdgeToEdge() }

        val startInFullRecovery = StartupCrashGuard.isFullRecoveryMode(applicationContext)
        if (!startInFullRecovery) {
            safeInitializeFirebase()
        }
        runCatching { UniCoreNotificationManager.ensureChannel(applicationContext) }

        setContent {
            var inFullRecovery by remember { mutableStateOf(startInFullRecovery) }
            if (inFullRecovery) {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SafeRecoveryScreen(
                            crashSummary = StartupCrashGuard.getLastCrashSummary(applicationContext)
                                ?: "Startup crash detected. Launch in safe mode or copy report.",
                            onBootWithoutAds = {
                                StartupCrashGuard.bootWithoutAds(applicationContext)
                                safeInitializeFirebase()
                                inFullRecovery = false
                            },
                            onRetryFullBoot = {
                                StartupCrashGuard.clearCrashStateAndResumeAds(applicationContext)
                                safeInitializeFirebase()
                                inFullRecovery = false
                            }
                        )
                    }
                }
                return@setContent
            }

            val prefsRepository = remember { UserPreferencesRepository(applicationContext) }
            val prefs by prefsRepository.preferencesFlow.collectAsStateWithLifecycle(initialValue = UserPreferences())
            val appStrings = remember(prefs.language) { stringsFor(prefs.language) }
            val layoutDirection = if (prefs.language == AppLanguage.ARABIC) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }

            UniCoreTheme(themeMode = prefs.themeMode) {
                CompositionLocalProvider(
                    LocalAppStrings provides appStrings,
                    LocalLayoutDirection provides layoutDirection
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        UniCoreApp(
                            prefs = prefs,
                            prefsRepository = prefsRepository
                        )
                    }
                }
            }
        }
    }

    private fun safeInitializeFirebase() {
        try {
            if (FirebaseApp.getApps(applicationContext).isEmpty()) {
                FirebaseApp.initializeApp(applicationContext)
            }
            configureAppCheck(this, intent)
        } catch (err: Throwable) {
            android.util.Log.e("MainActivity", "Safe Firebase/AppCheck setup failed", err)
        }
    }
}

private enum class UniCoreTab(
    val icon: ImageVector,
    val testTag: String
) {
    COURSES(Icons.Default.School, "tab_courses"),
    DUE(Icons.Default.Assignment, "tab_due"),
    CAMPUS(Icons.Default.CalendarMonth, "tab_campus"),
    CHAT(Icons.Default.Forum, "tab_chat"),
    QUIZZES(Icons.Default.Quiz, "tab_quizzes"),
    AI_TUTOR(Icons.Default.AutoAwesome, "tab_ai_tutor"),
    ACCOUNT(Icons.Default.Person, "tab_security");

    fun label(strings: UniCoreStrings): String = when (this) {
        COURSES -> strings.navCourses
        DUE -> strings.navDue
        CAMPUS -> strings.navCampus
        CHAT -> strings.navChat
        QUIZZES -> strings.navQuizzes
        AI_TUTOR -> strings.navTutor
        ACCOUNT -> strings.navAccount
    }
}

private val CompactBottomTabs = listOf(
    UniCoreTab.COURSES,
    UniCoreTab.DUE,
    UniCoreTab.CAMPUS,
    UniCoreTab.CHAT,
    UniCoreTab.QUIZZES,
    UniCoreTab.AI_TUTOR
)

@Composable
fun UniCoreApp(
    prefs: UserPreferences,
    prefsRepository: UserPreferencesRepository,
    authOverride: FirebaseAuth? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adsSafeMode by remember {
        mutableStateOf(StartupCrashGuard.isAdIsolationActive(context.applicationContext))
    }
    var lastCrashSummary by remember {
        mutableStateOf(StartupCrashGuard.getLastCrashSummary(context.applicationContext))
    }
    val adsEnabled = ENABLE_ADMOB_ON_STARTUP && !adsSafeMode

    LaunchedEffect(Unit) {
        delay(5_000L)
        StartupCrashGuard.markHealthyLaunch(context.applicationContext)
    }

    LaunchedEffect(adsEnabled) {
        if (adsEnabled) {
            // Defer MobileAds init cleanly until after the first UI frame has painted
            delay(1_200L)
            try {
                initializeMobileAdsOnce(context.applicationContext, enabled = true)
            } catch (err: Throwable) {
                android.util.Log.w("MainActivity", "Safe MobileAds deferred init failed", err)
            }
        }
    }

    val auth = remember(context, authOverride) {
        authOverride ?: runCatching {
            if (FirebaseApp.getApps(context.applicationContext).isEmpty()) {
                FirebaseApp.initializeApp(context.applicationContext)
            }
            Firebase.auth
        }.getOrNull()
    }

    if (auth == null) {
        StartupServiceUnavailableGate(
            title = LocalAppStrings.current.appName,
            message = "Unable to initialize authentication services. Check network or Firebase configuration and reopen the app."
        )
        return
    }

    var currentUser by remember(auth) { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val cycleTheme: () -> Unit = {
        val next = when (prefs.themeMode) {
            AppThemeMode.SYSTEM -> AppThemeMode.DARK
            AppThemeMode.DARK -> AppThemeMode.LIGHT
            AppThemeMode.LIGHT -> AppThemeMode.SYSTEM
        }
        scope.launch { prefsRepository.setThemeMode(next) }
    }

    val setLanguage: (AppLanguage) -> Unit = { lang ->
        scope.launch { prefsRepository.setLanguage(lang) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (lastCrashSummary != null) {
            StartupCrashDiagnosticBanner(
                summary = lastCrashSummary.orEmpty(),
                adsIsolated = adsSafeMode,
                onDismiss = {
                    StartupCrashGuard.dismissLastCrashOnly(context.applicationContext)
                    lastCrashSummary = null
                },
                onReEnableAds = {
                    StartupCrashGuard.clearCrashStateAndResumeAds(context.applicationContext)
                    adsSafeMode = false
                    lastCrashSummary = null
                }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val user = currentUser
            if (user == null) {
                AuthScreen(
                    currentLanguage = prefs.language,
                    currentThemeMode = prefs.themeMode,
                    onToggleLanguage = setLanguage,
                    onCycleTheme = cycleTheme,
                    onAuthSuccess = { currentUser = auth.currentUser }
                )
            } else {
                val uid = user.uid
                key(uid) {
                    UniCoreAuthenticatedShell(
                        currentUserId = uid,
                        prefs = prefs,
                        prefsRepository = prefsRepository,
                        adsEnabled = adsEnabled,
                        onSetLanguage = setLanguage,
                        onCycleTheme = cycleTheme
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UniCoreAuthenticatedShell(
    currentUserId: String,
    prefs: UserPreferences,
    prefsRepository: UserPreferencesRepository,
    adsEnabled: Boolean,
    onSetLanguage: (AppLanguage) -> Unit,
    onCycleTheme: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) {
        runCatching { CredentialManager.create(context) }.getOrNull()
    }
    val firestoreDb = remember(context) {
        runCatching {
            val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
            FirebaseFirestore.getInstance(databaseId)
        }.getOrNull()
    }

    if (firestoreDb == null) {
        StartupServiceUnavailableGate(
            title = strings.appName,
            message = "Unable to connect to academic database services. Please restart UniCore."
        )
        return
    }

    val viewModel: UniCoreViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                UniCoreViewModel(
                    appContext = app.applicationContext,
                    repository = UniCoreRepository(firestoreDb),
                    governance = UniCoreGovernanceRepository(firestoreDb),
                    aiRepository = AiTutorRepository(),
                    driveSyncRepository = GoogleDriveSyncRepository(),
                    rateLimiter = RateLimiter(app.applicationContext),
                    currentUserId = currentUserId
                )
            }
        }
    )

    var selectedTab by remember { mutableStateOf(UniCoreTab.COURSES) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var showPlacementEditor by remember { mutableStateOf(false) }
    var isTakingQuiz by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab != UniCoreTab.QUIZZES) {
            isTakingQuiz = false
        }
    }

    if (selectedTab != UniCoreTab.COURSES) {
        BackHandler { selectedTab = UniCoreTab.COURSES }
    }

    val userRole by viewModel.userRole.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val systemSettings by viewModel.systemSettings.collectAsStateWithLifecycle()
    val quotaSnapshot by viewModel.quotaSnapshot.collectAsStateWithLifecycle()
    val coursesState by viewModel.coursesState.collectAsStateWithLifecycle()
    val departmentsState by viewModel.departmentsState.collectAsStateWithLifecycle()
    val driveFoldersState by viewModel.driveFoldersState.collectAsStateWithLifecycle()
    val driveFilesState by viewModel.driveFilesState.collectAsStateWithLifecycle()
    val isDriveRefreshing by viewModel.isDriveRefreshing.collectAsStateWithLifecycle()
    val downloadedDriveFileIds by viewModel.downloadedDriveFileIds.collectAsStateWithLifecycle()
    val downloadingOfflineIds by viewModel.downloadingOfflineIds.collectAsStateWithLifecycle()
    val isBatchDownloadingOffline by viewModel.isBatchDownloadingOffline.collectAsStateWithLifecycle()
    val openingCourseId by viewModel.openingCourseId.collectAsStateWithLifecycle()
    val openingDriveFileId by viewModel.openingDriveFileId.collectAsStateWithLifecycle()
    val quizzesState by viewModel.quizzesState.collectAsStateWithLifecycle()
    val attemptsState by viewModel.attemptsState.collectAsStateWithLifecycle()
    val filesState by viewModel.verifiedFilesState.collectAsStateWithLifecycle()
    val notificationsState by viewModel.notificationsState.collectAsStateWithLifecycle()
    val supportMessagesState by viewModel.supportMessagesState.collectAsStateWithLifecycle()
    val usersDirectoryState by viewModel.usersDirectoryState.collectAsStateWithLifecycle()
    val roleInvitesState by viewModel.roleInvitesState.collectAsStateWithLifecycle()
    val auditLogsState by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val aiUiState by viewModel.aiUiState.collectAsStateWithLifecycle()
    val availableAiSources by viewModel.availableAiSources.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val inAppPreviewFile by viewModel.inAppPreviewFile.collectAsStateWithLifecycle()
    val selectedChatRoomId by viewModel.selectedChatRoomId.collectAsStateWithLifecycle()
    val chatMessagesState by viewModel.chatMessagesState.collectAsStateWithLifecycle()
    val assignmentsState by viewModel.assignmentsState.collectAsStateWithLifecycle()
    val scheduleItemsState by viewModel.scheduleItemsState.collectAsStateWithLifecycle()
    val zoomSessionsState by viewModel.zoomSessionsState.collectAsStateWithLifecycle()
    val instructionsState by viewModel.instructionsState.collectAsStateWithLifecycle()
    val sectionMessagesState by viewModel.sectionMessagesState.collectAsStateWithLifecycle()

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(userProfile?.banUntilMillis) {
        val until = userProfile?.banUntilMillis ?: 0L
        nowMillis = System.currentTimeMillis()
        while (until > System.currentTimeMillis()) {
            delay(10_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    if (userProfile?.accountDisabled == true && userRole != UserRole.SUPER_ADMIN) {
        AccountSuspendedGate(
            title = strings.accountSuspendedTitle,
            body = strings.accountSuspendedBody,
            signOutLabel = strings.signOutAction,
            onSignOut = { signOut(credentialManager, scope) }
        )
        return
    }

    val banUntil = userProfile?.banUntilMillis ?: 0L
    if (banUntil > nowMillis && userRole != UserRole.SUPER_ADMIN) {
        val unlockTimeStr = remember(banUntil) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(banUntil))
        }
        val reasonLine = userProfile?.banReason?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        AccountSuspendedGate(
            title = strings.bannedUntilTitle,
            body = "${strings.bannedUntilBody} $unlockTimeStr$reasonLine",
            signOutLabel = strings.signOutAction,
            onSignOut = { signOut(credentialManager, scope) }
        )
        return
    }

    val notifications = (notificationsState as? UiState.Success)?.data.orEmpty()
    val unreadCount = remember(notifications, prefs.readNotificationIds) {
        notifications.count { it.id !in prefs.readNotificationIds }
    }

    // Dispatch local tray notifications only for newly incoming unread alerts after initial snapshot seed
    val seenNotificationIds = remember(currentUserId) { mutableSetOf<String>() }
    var initializedNotifications by remember(currentUserId) { mutableStateOf(false) }
    LaunchedEffect(notificationsState, prefs.systemNotificationsEnabled, prefs.notificationSoundEnabled) {
        val currentList = (notificationsState as? UiState.Success)?.data ?: return@LaunchedEffect
        if (!initializedNotifications) {
            seenNotificationIds.addAll(currentList.map { it.id })
            initializedNotifications = true
            return@LaunchedEffect
        }
        val newAlerts = currentList.filter { it.id !in seenNotificationIds }
        seenNotificationIds.addAll(newAlerts.map { it.id })
        if (prefs.systemNotificationsEnabled) {
            newAlerts
                .filter { it.id !in prefs.readNotificationIds && it.senderUid != currentUserId }
                .take(3)
                .forEach { alert ->
                    UniCoreNotificationManager.showSystemNotification(
                        context = context,
                        notificationIdKey = alert.id,
                        title = alert.title,
                        body = alert.body,
                        soundEnabled = prefs.notificationSoundEnabled
                    )
                }
        }
    }

    // Schedule 24-hour-before-deadline local reminders for upcoming incomplete assignments
    LaunchedEffect(
        assignmentsState,
        userProfile?.completedAssignmentIds,
        userProfile?.departmentId,
        userProfile?.academicYear,
        userProfile?.sectionName,
        prefs.systemNotificationsEnabled,
        prefs.scheduledReminderIds
    ) {
        val list = (assignmentsState as? UiState.Success)?.data ?: return@LaunchedEffect
        val completedIds = userProfile?.completedAssignmentIds?.toSet() ?: emptySet()
        val now = System.currentTimeMillis()
        list.forEach { assignment ->
            if (assignment.id in completedIds) {
                UniCoreNotificationManager.cancelAssignmentReminder24h(context, assignment.id)
                return@forEach
            }
            val inScope = AcademicScopeFilter.matchesStudentScope(
                userProfile = userProfile,
                userRole = userRole,
                itemDeptId = assignment.departmentId,
                itemDeptName = assignment.departmentName,
                itemYear = assignment.academicYear,
                itemSection = assignment.sectionName
            )
            if (prefs.systemNotificationsEnabled &&
                inScope &&
                assignment.dueAtMillis > now &&
                assignment.id !in prefs.scheduledReminderIds
            ) {
                val reminderBody = listOfNotNull(
                    assignment.courseTitle?.takeIf { it.isNotBlank() },
                    assignment.description.takeIf { it.isNotBlank() }
                ).joinToString(" • ").ifBlank { strings.dueSoonBadge }
                UniCoreNotificationManager.scheduleAssignmentReminder24h(
                    context = context,
                    assignmentId = assignment.id,
                    title = "⏰ ${strings.dueSoonBadge}: ${assignment.title}",
                    body = reminderBody,
                    dueAtMillis = assignment.dueAtMillis,
                    nowMillis = now
                )
                prefsRepository.markReminderScheduled(assignment.id)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val localizedRole = when (userRole) {
        UserRole.SUPER_ADMIN -> strings.roleSuperAdmin
        UserRole.ADMIN -> strings.roleAdmin
        UserRole.STUDENT -> strings.roleStudent
    }

    val placementLine = remember(userProfile) {
        listOfNotNull(
            userProfile?.departmentName?.takeIf { it.isNotBlank() },
            userProfile?.academicYear?.takeIf { it.isNotBlank() },
            userProfile?.sectionName?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
    }

    val needsFirstTimePlacement = remember(userProfile, userRole) {
        val p = userProfile
        p != null && userRole == UserRole.STUDENT &&
            (!p.placementCompleted || p.academicYear.isNullOrBlank() || p.sectionName.isNullOrBlank())
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column(
                            modifier = Modifier.clickable { showPlacementEditor = true }
                        ) {
                            Text(
                                text = "${strings.appName} • $localizedRole",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (placementLine.isNotBlank()) {
                                Text(
                                    text = placementLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                val nextLang = if (prefs.language == AppLanguage.ARABIC) {
                                    AppLanguage.ENGLISH
                                } else {
                                    AppLanguage.ARABIC
                                }
                                onSetLanguage(nextLang)
                            },
                            modifier = Modifier.testTag("topbar_language_toggle")
                        ) {
                            Text(strings.switchLanguageShort, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = onCycleTheme,
                            modifier = Modifier.testTag("topbar_theme_toggle")
                        ) {
                            Icon(
                                imageVector = when (prefs.themeMode) {
                                    AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                    AppThemeMode.DARK -> Icons.Default.DarkMode
                                    AppThemeMode.LIGHT -> Icons.Default.LightMode
                                },
                                contentDescription = strings.themeLabel
                            )
                        }

                        IconButton(
                            onClick = { showNotificationsSheet = true },
                            modifier = Modifier.testTag("topbar_notifications_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (unreadCount > 0) {
                                        Badge {
                                            Text(if (unreadCount > 9) "9+" else unreadCount.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = strings.notificationsTitle
                                )
                            }
                        }

                        if (!useNavigationRail) {
                            IconButton(
                                onClick = { selectedTab = UniCoreTab.ACCOUNT },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (selectedTab == UniCoreTab.ACCOUNT) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                modifier = Modifier.testTag(UniCoreTab.ACCOUNT.testTag)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = strings.navAccount,
                                    tint = if (selectedTab == UniCoreTab.ACCOUNT) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!isTakingQuiz && adsEnabled) {
                        AdMobBannerBar(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = adsEnabled
                        )
                    }
                    if (!useNavigationRail) {
                        NavigationBar {
                            CompactBottomTabs.forEach { tab ->
                                val label = tab.label(strings)
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            maxLines = 1,
                                            softWrap = false,
                                            fontSize = 10.sp,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail {
                        UniCoreTab.entries.forEach { tab ->
                            val label = tab.label(strings)
                            NavigationRailItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label, maxLines = 1) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(modifier = Modifier.widthIn(max = 760.dp)) {
                        when (selectedTab) {
                            UniCoreTab.COURSES -> CoursesScreen(
                                coursesState = coursesState,
                                departmentsState = departmentsState,
                                driveFoldersState = driveFoldersState,
                                driveFilesState = driveFilesState,
                                filesState = filesState,
                                isDriveRefreshing = isDriveRefreshing,
                                downloadedDriveFileIds = downloadedDriveFileIds,
                                downloadingOfflineIds = downloadingOfflineIds,
                                isBatchDownloadingOffline = isBatchDownloadingOffline,
                                openingCourseId = openingCourseId,
                                openingDriveFileId = openingDriveFileId,
                                userRole = userRole,
                                userProfile = userProfile,
                                onCreateCourseWithFile = { title, desc, link, type, uri, deptId, deptName, year, section, videoUrl, videoTitle ->
                                    viewModel.createCourseWithOptionalFile(
                                        context = context,
                                        title = title,
                                        description = desc,
                                        materialLink = link,
                                        materialType = type,
                                        selectedFileUri = uri,
                                        departmentId = deptId,
                                        departmentName = deptName,
                                        academicYear = year,
                                        sectionName = section,
                                        videoUrl = videoUrl,
                                        videoTitle = videoTitle
                                    )
                                },
                                onUpdateCourseDetails = viewModel::updateCourseDetails,
                                onDeleteCourse = viewModel::deleteCourse,
                                onOpenCourseAttachment = { course ->
                                    viewModel.openCourseAttachment(context, course)
                                },
                                onToggleFavoriteCourse = viewModel::toggleFavoriteCourse,
                                onPickVaultFile = { uri -> viewModel.validateAndStoreFile(context, uri) },
                                onDeleteVaultFile = { record -> viewModel.deleteVerifiedFile(context, record) },
                                onSaveDepartment = viewModel::saveDepartment,
                                onDeleteDepartment = viewModel::deleteDepartment,
                                onLinkDriveFolder = viewModel::linkDriveFolder,
                                onUpdateDriveFolder = viewModel::updateDriveFolder,
                                onUnlinkDriveFolder = viewModel::unlinkDriveFolder,
                                onRefreshDrive = viewModel::refreshDriveFilesNow,
                                onOpenDriveFile = { item ->
                                    viewModel.openDriveSyncedFile(context, item)
                                },
                                onSaveDriveFileOffline = { item ->
                                    viewModel.saveDriveFileOffline(context, item)
                                },
                                onRemoveDriveFileOffline = { item ->
                                    viewModel.removeDriveFileOffline(context, item)
                                },
                                onDownloadAllOffline = { items ->
                                    viewModel.downloadAllDriveFilesOffline(context, items)
                                }
                            )
                            UniCoreTab.DUE -> MyDueAssignmentsScreen(
                                currentUserId = currentUserId,
                                userRole = userRole,
                                userProfile = userProfile,
                                assignmentsState = assignmentsState,
                                coursesState = coursesState,
                                departmentsState = departmentsState,
                                onToggleCompleted = viewModel::toggleAssignmentCompleted,
                                onCreateAssignment = { title, desc, dueMs, courseId, courseTitle, deptId, deptName, year, section, classroomLink, videoUrl ->
                                    viewModel.createAssignment(
                                        title = title,
                                        description = desc,
                                        dueAtMillis = dueMs,
                                        courseId = courseId,
                                        courseTitle = courseTitle,
                                        departmentId = deptId,
                                        departmentName = deptName,
                                        academicYear = year,
                                        sectionName = section,
                                        classroomLink = classroomLink,
                                        videoUrl = videoUrl,
                                        sourceType = "APP"
                                    )
                                },
                                onImportClassroomTask = { title, courseTitle, dueMs, classroomUrl ->
                                    viewModel.importClassroomAssignments(
                                        items = listOf(Triple(title, courseTitle, dueMs)),
                                        classroomLinks = listOf(classroomUrl),
                                        courseTitles = listOf(courseTitle)
                                    )
                                },
                                onDeleteAssignment = viewModel::deleteAssignment
                            )
                            UniCoreTab.CAMPUS -> CampusHubScreen(
                                userRole = userRole,
                                userProfile = userProfile,
                                systemSettings = systemSettings,
                                departmentsState = departmentsState,
                                scheduleItemsState = scheduleItemsState,
                                zoomSessionsState = zoomSessionsState,
                                instructionsState = instructionsState,
                                onCreateScheduleItem = { title, type, day, startTxt, endTxt, location, instructor, deptId, deptName, year, section, courseId, videoUrl ->
                                    viewModel.createScheduleItem(
                                        title = title,
                                        itemType = type,
                                        dayOfWeek = day,
                                        startTimeText = startTxt,
                                        endTimeText = endTxt,
                                        locationOrMode = location,
                                        instructorName = instructor,
                                        departmentId = deptId,
                                        departmentName = deptName,
                                        academicYear = year,
                                        sectionName = section,
                                        courseId = courseId,
                                        videoUrl = videoUrl
                                    )
                                },
                                onDeleteScheduleItem = viewModel::deleteScheduleItem,
                                onCreateZoomSession = { title, topic, url, scheduledMs, durationMins, deptId, deptName, year, section ->
                                    viewModel.createZoomSession(
                                        title = title,
                                        topic = topic,
                                        zoomUrl = url,
                                        scheduledAtMillis = scheduledMs,
                                        durationMinutes = durationMins,
                                        departmentId = deptId,
                                        departmentName = deptName,
                                        academicYear = year,
                                        sectionName = section
                                    )
                                },
                                onDeleteZoomSession = viewModel::deleteZoomSession,
                                onCreateInstruction = viewModel::createInstruction,
                                onDeleteInstruction = viewModel::deleteInstruction
                            )
                            UniCoreTab.CHAT -> StudyChatScreen(
                                currentUserId = currentUserId,
                                userRole = userRole,
                                userProfile = userProfile,
                                departmentsState = departmentsState,
                                selectedRoomId = selectedChatRoomId,
                                messagesState = chatMessagesState,
                                sectionMessagesState = sectionMessagesState,
                                onSelectRoom = viewModel::selectChatRoom,
                                onSendMessage = { roomId, deptName, yearLabel, text, imageUri ->
                                    viewModel.sendChatMessage(
                                        context = context,
                                        roomId = roomId,
                                        departmentName = deptName,
                                        academicYear = yearLabel,
                                        rawText = text,
                                        imageUri = imageUri
                                    )
                                },
                                onDeleteMessage = viewModel::deleteChatMessage,
                                onSendSectionMessage = viewModel::sendSectionMessage,
                                onDeleteSectionMessage = viewModel::deleteSectionMessage
                            )
                            UniCoreTab.QUIZZES -> QuizzesScreen(
                                currentUserId = currentUserId,
                                quizzesState = quizzesState,
                                attemptsState = attemptsState,
                                coursesState = coursesState,
                                userRole = userRole,
                                onCreateQuiz = { courseId, title, duration, startMs, endMs, imageUri, prompts, blocks, corrects ->
                                    viewModel.createQuiz(
                                        context = context,
                                        courseId = courseId,
                                        title = title,
                                        durationMinutes = duration,
                                        startAtMillis = startMs,
                                        endAtMillis = endMs,
                                        quizImageUri = imageUri,
                                        questionPrompts = prompts,
                                        optionBlocks = blocks,
                                        correctIndices = corrects
                                    )
                                },
                                onDeleteQuiz = viewModel::deleteQuiz,
                                onSubmitAttempt = viewModel::submitQuizAttempt,
                                onCancelForViolation = viewModel::cancelQuizForViolation,
                                onResetCancelledAttempt = viewModel::resetCancelledAttempt,
                                onGradeAttempt = viewModel::gradeAttemptAsAdmin,
                                onLoadQuizImage = viewModel::fetchQuizImageBytes,
                                onQuizActiveChanged = { active -> isTakingQuiz = active }
                            )
                            UniCoreTab.AI_TUTOR -> AiTutorScreen(
                                aiState = aiUiState,
                                quotaSnapshot = quotaSnapshot,
                                availableSources = availableAiSources,
                                onAskTutor = { prompt, sourceId ->
                                    viewModel.askAiTutor(context, prompt, prefs.language, sourceId)
                                },
                                onCancelTutor = viewModel::cancelAiTutor,
                                onClearTutor = viewModel::clearAiState
                            )
                            UniCoreTab.ACCOUNT -> SecurityConsoleScreen(
                                currentUserId = currentUserId,
                                userRole = userRole,
                                userProfile = userProfile,
                                systemSettings = systemSettings,
                                quotaSnapshot = quotaSnapshot,
                                currentLanguage = prefs.language,
                                currentThemeMode = prefs.themeMode,
                                systemNotificationsEnabled = prefs.systemNotificationsEnabled,
                                notificationSoundEnabled = prefs.notificationSoundEnabled,
                                auditLogsState = auditLogsState,
                                usersDirectoryState = usersDirectoryState,
                                roleInvitesState = roleInvitesState,
                                supportMessagesState = supportMessagesState,
                                onSetLanguage = onSetLanguage,
                                onSetThemeMode = { mode ->
                                    scope.launch { prefsRepository.setThemeMode(mode) }
                                },
                                onSetSystemNotifications = { enabled ->
                                    scope.launch { prefsRepository.setSystemNotificationsEnabled(enabled) }
                                },
                                onSetNotificationSound = { enabled ->
                                    scope.launch { prefsRepository.setNotificationSoundEnabled(enabled) }
                                },
                                onTestSound = {
                                    UniCoreNotificationManager.playChimePreview(context)
                                },
                                onOpenPlacementEditor = { showPlacementEditor = true },
                                onInviteAccount = viewModel::inviteAccountByEmail,
                                onRevokeInvite = viewModel::revokeInvite,
                                onPromoteUser = viewModel::promoteUserToAdmin,
                                onPromoteSuperAdmin = viewModel::promoteUserToSuperAdmin,
                                onDemoteAdmin = viewModel::demoteAdminToStudent,
                                onDemoteSuperAdmin = viewModel::demoteSuperAdminToAdmin,
                                onToggleAccountDisabled = viewModel::setUserAccountDisabled,
                                onTimedBanUser = viewModel::setUserTimedBan,
                                onSendSupportMessage = { subject, body, imageUri ->
                                    viewModel.sendSupportMessage(context, subject, body, imageUri)
                                },
                                onReplySupportMessage = { msg, reply, imageUri ->
                                    viewModel.replyToSupportMessage(context, msg, reply, imageUri)
                                },
                                onUpdateSettings = viewModel::updateSystemSettings,
                                onSignOut = { signOut(credentialManager, scope) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (needsFirstTimePlacement || showPlacementEditor) {
        val departments = (departmentsState as? UiState.Success)?.data.orEmpty()
        OnboardingPlacementDialog(
            userProfile = userProfile,
            departments = departments,
            isBlockingFirstTime = needsFirstTimePlacement && !showPlacementEditor,
            onDismiss = { showPlacementEditor = false },
            onSavePlacement = { deptId, deptName, year, section ->
                viewModel.saveAcademicPlacement(deptId, deptName, year, section)
                showPlacementEditor = false
            }
        )
    }

    val activePreview = inAppPreviewFile
    if (activePreview != null) {
        InAppFilePreviewDialog(
            preview = activePreview,
            onDismiss = viewModel::dismissInAppPreview,
            onOpenExternal = { viewModel.openPreviewWithExternalApp(context, activePreview) }
        )
    }

    if (showNotificationsSheet) {
        NotificationsSheet(
            notificationsState = notificationsState,
            readIds = prefs.readNotificationIds,
            userRole = userRole,
            onDismiss = { showNotificationsSheet = false },
            onMarkRead = { notifId ->
                scope.launch { prefsRepository.markNotificationRead(notifId) }
            },
            onMarkAllRead = { ids ->
                scope.launch { prefsRepository.markAllNotificationsRead(ids) }
            },
            onSendAnnouncement = viewModel::sendBroadcastNotification,
            onPermissionResult = { granted ->
                scope.launch { prefsRepository.setSystemNotificationsEnabled(granted) }
            }
        )
    }
}

@Composable
private fun AccountSuspendedGate(
    title: String,
    body: String,
    signOutLabel: String,
    onSignOut: () -> Unit
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.widthIn(max = 460.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("suspended_sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(signOutLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupCrashDiagnosticBanner(
    summary: String,
    adsIsolated: Boolean,
    onDismiss: () -> Unit,
    onReEnableAds: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (adsIsolated) "Safe Mode Active (AdMob Isolated)" else "Previous Crash Diagnostic",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(summary))
                    }
                ) {
                    Text("Copy")
                }
                if (adsIsolated) {
                    TextButton(onClick = onReEnableAds) {
                        Text("Retry Ads")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun StartupServiceUnavailableGate(
    title: String,
    message: String
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.widthIn(max = 460.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SafeRecoveryScreen(
    crashSummary: String,
    onBootWithoutAds: () -> Unit,
    onRetryFullBoot: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.widthIn(max = 520.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "UniCore Safe Recovery",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = crashSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(crashSummary))
                            }
                        ) {
                            Text("Copy")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onRetryFullBoot) {
                            Text("Retry Full")
                        }
                        Button(onClick = onBootWithoutAds) {
                            Text("Safe Boot")
                        }
                    }
                }
            }
        }
    }
}
