package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.repository.AiTutorRepository
import com.example.data.repository.UniCoreRepository
import com.example.data.repository.signOut
import com.example.security.RateLimiter
import com.example.security.configureAppCheck
import com.example.ui.screens.AiTutorScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CoursesScreen
import com.example.ui.screens.QuizzesScreen
import com.example.ui.screens.SecureFilesVaultScreen
import com.example.ui.screens.SecurityConsoleScreen
import com.example.ui.theme.UniCoreTheme
import com.example.ui.viewmodel.UniCoreViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Step 1: Configure Firebase App Check before Compose UI or any Firebase operation
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            UniCoreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UniCoreApp()
                }
            }
        }
    }
}

private enum class UniCoreTab(
    val labelRes: Int,
    val icon: ImageVector,
    val testTag: String
) {
    COURSES(R.string.nav_courses, Icons.Default.School, "tab_courses"),
    QUIZZES(R.string.nav_quizzes, Icons.Default.Quiz, "tab_quizzes"),
    AI_TUTOR(R.string.nav_tutor, Icons.Default.AutoAwesome, "tab_ai_tutor"),
    VAULT(R.string.nav_vault, Icons.Default.FolderSpecial, "tab_vault"),
    SECURITY(R.string.nav_security, Icons.Default.Security, "tab_security")
}

@Composable
fun UniCoreApp(auth: FirebaseAuth = Firebase.auth) {
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = { currentUser = auth.currentUser })
    } else {
        val uid = user.uid
        key(uid) {
            UniCoreAuthenticatedShell(currentUserId = uid)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UniCoreAuthenticatedShell(currentUserId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: UniCoreViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                UniCoreViewModel(
                    repository = UniCoreRepository(db),
                    aiRepository = AiTutorRepository(),
                    rateLimiter = RateLimiter(app.applicationContext),
                    currentUserId = currentUserId
                )
            }
        }
    )

    var selectedTab by remember { mutableStateOf(UniCoreTab.COURSES) }
    if (selectedTab != UniCoreTab.COURSES) {
        BackHandler { selectedTab = UniCoreTab.COURSES }
    }

    val userRole by viewModel.userRole.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val systemSettings by viewModel.systemSettings.collectAsStateWithLifecycle()
    val quotaSnapshot by viewModel.quotaSnapshot.collectAsStateWithLifecycle()
    val coursesState by viewModel.coursesState.collectAsStateWithLifecycle()
    val quizzesState by viewModel.quizzesState.collectAsStateWithLifecycle()
    val attemptsState by viewModel.attemptsState.collectAsStateWithLifecycle()
    val filesState by viewModel.verifiedFilesState.collectAsStateWithLifecycle()
    val auditLogsState by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val adminsState by viewModel.adminsState.collectAsStateWithLifecycle()
    val aiUiState by viewModel.aiUiState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "UniCore • ${userRole.name.replace("_", " ")}",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        UniCoreTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
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
                            NavigationRailItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
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
                    Box(modifier = Modifier.widthIn(max = 720.dp)) {
                        when (selectedTab) {
                            UniCoreTab.COURSES -> CoursesScreen(
                                coursesState = coursesState,
                                userRole = userRole,
                                onCreateCourse = viewModel::createCourse
                            )
                            UniCoreTab.QUIZZES -> QuizzesScreen(
                                quizzesState = quizzesState,
                                attemptsState = attemptsState,
                                coursesState = coursesState,
                                userRole = userRole,
                                onCreateQuiz = viewModel::createQuiz,
                                onSubmitAttempt = viewModel::submitQuizAttempt,
                                onGradeAttempt = viewModel::gradeAttemptAsAdmin
                            )
                            UniCoreTab.AI_TUTOR -> AiTutorScreen(
                                aiState = aiUiState,
                                quotaSnapshot = quotaSnapshot,
                                onAskTutor = viewModel::askAiTutor
                            )
                            UniCoreTab.VAULT -> SecureFilesVaultScreen(
                                filesState = filesState,
                                onFilePicked = { uri -> viewModel.validateAndStoreFile(context, uri) }
                            )
                            UniCoreTab.SECURITY -> SecurityConsoleScreen(
                                userRole = userRole,
                                userProfile = userProfile,
                                systemSettings = systemSettings,
                                quotaSnapshot = quotaSnapshot,
                                auditLogsState = auditLogsState,
                                adminsState = adminsState,
                                onUpdateSettings = viewModel::updateSystemSettings,
                                onGrantAdmin = viewModel::grantAdminRole,
                                onRevokeAdmin = viewModel::revokeAdminRole,
                                onSignOut = { signOut(credentialManager, scope) }
                            )
                        }
                    }
                }
            }
        }
    }
}
