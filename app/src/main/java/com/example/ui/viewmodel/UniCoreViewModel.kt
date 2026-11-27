package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AcademicDepartment
import com.example.data.model.AdminRecord
import com.example.data.model.AuditLog
import com.example.data.model.AuditSeverity
import com.example.data.model.Course
import com.example.data.model.DriveFolderLink
import com.example.data.model.DriveSyncedFile
import com.example.data.model.MaterialType
import com.example.data.model.NotificationCategory
import com.example.data.model.Quiz
import com.example.data.model.QuizAttempt
import com.example.data.model.RoleInvite
import com.example.data.model.StudyChatMessage
import com.example.data.model.SupportChannel
import com.example.data.model.SupportMessage
import com.example.data.model.SystemSettings
import com.example.data.model.UniNotification
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.VerifiedFileRecord
import com.example.data.preferences.AppLanguage
import com.example.data.repository.AiTutorRepository
import com.example.data.repository.GoogleDriveSyncRepository
import com.example.data.repository.StudyMaterialMatcher
import com.example.data.repository.UniCoreGovernanceRepository
import com.example.data.repository.UniCoreRepository
import com.example.security.ImageAttachmentHelper
import com.example.security.InputValidator
import com.example.security.ProfanityFilter
import com.example.security.RateLimiter
import com.example.security.SecureFileValidator
import com.example.security.ZeroCostQuotaManager
import java.io.File
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface AiUiState {
    data object Idle : AiUiState
    data class Loading(val statusDetail: String? = null) : AiUiState
    data class Streaming(val text: String, val usedSources: List<String> = emptyList()) : AiUiState
    data class Success(val text: String, val usedSources: List<String> = emptyList()) : AiUiState
    data class PausedQuota(val reason: String) : AiUiState
    data class Error(val message: String) : AiUiState
}

data class InAppFilePreview(
    val file: File,
    val title: String,
    val mimeType: String
)

@OptIn(ExperimentalCoroutinesApi::class)
class UniCoreViewModel(
    private val repository: UniCoreRepository,
    private val governance: UniCoreGovernanceRepository,
    private val aiRepository: AiTutorRepository,
    private val driveSyncRepository: GoogleDriveSyncRepository,
    private val rateLimiter: RateLimiter,
    private val currentUserId: String
) : ViewModel() {

    private val _sessionReady = MutableStateFlow(false)
    private val _userRole = MutableStateFlow(UserRole.STUDENT)
    val userRole: StateFlow<UserRole> = _userRole.asStateFlow()

    private val _aiUiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val aiUiState: StateFlow<AiUiState> = _aiUiState.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _driveFilesState = MutableStateFlow<UiState<List<DriveSyncedFile>>>(UiState.Success(emptyList()))
    val driveFilesState: StateFlow<UiState<List<DriveSyncedFile>>> = _driveFilesState.asStateFlow()

    private val _isDriveRefreshing = MutableStateFlow(false)
    val isDriveRefreshing: StateFlow<Boolean> = _isDriveRefreshing.asStateFlow()

    private val _openingCourseId = MutableStateFlow<String?>(null)
    val openingCourseId: StateFlow<String?> = _openingCourseId.asStateFlow()

    private val _openingDriveFileId = MutableStateFlow<String?>(null)
    val openingDriveFileId: StateFlow<String?> = _openingDriveFileId.asStateFlow()

    private val _inAppPreviewFile = MutableStateFlow<InAppFilePreview?>(null)
    val inAppPreviewFile: StateFlow<InAppFilePreview?> = _inAppPreviewFile.asStateFlow()

    private val _selectedChatRoomId = MutableStateFlow("general")
    val selectedChatRoomId: StateFlow<String> = _selectedChatRoomId.asStateFlow()

    private var activeAiJob: Job? = null

    init {
        viewModelScope.launch {
            val res = repository.ensureUserSessionProfile(governance)
            res.onSuccess { resolvedRole ->
                _userRole.value = resolvedRole
                _sessionReady.value = true
            }.onFailure { err ->
                Log.e(TAG, "Session bootstrap failed", err)
                _statusBanner.value = InputValidator.safeUserErrorMessage(
                    err,
                    "Could not verify session profile."
                )
                _sessionReady.value = true
            }
        }
    }

    val userProfile: StateFlow<UserProfile?> = _sessionReady
        .flatMapLatest { ready ->
            if (!ready) flowOf(null)
            else repository.observeUserProfile(currentUserId)
                .catch { error ->
                    unwrapFirestoreOrRethrow(error, "observeUserProfile")
                    emit(null)
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val systemSettings: StateFlow<SystemSettings> = _sessionReady
        .flatMapLatest { ready ->
            if (!ready) flowOf(SystemSettings())
            else governance.observeSystemSettings()
                .catch { error ->
                    unwrapFirestoreOrRethrow(error, "observeSystemSettings")
                    emit(SystemSettings())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SystemSettings())

    val quotaSnapshot: StateFlow<ZeroCostQuotaManager.QuotaSnapshot> =
        combine(userProfile, systemSettings) { profile, settings ->
            ZeroCostQuotaManager.QuotaSnapshot(
                aiUsedToday = (profile?.aiCallsToday ?: 0L).toInt(),
                aiDailyCap = settings.dailyAiLimitPerUser.toInt().coerceAtLeast(1),
                writesUsedToday = (profile?.writesToday ?: 0L).toInt(),
                writesDailyCap = settings.dailyWriteLimitPerUser.toInt().coerceAtLeast(1),
                aiGloballyEnabled = settings.aiEnabled,
                zeroCostLockActive = settings.zeroCostLockActive
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ZeroCostQuotaManager.QuotaSnapshot(0, 20, 0, 100, aiGloballyEnabled = true, zeroCostLockActive = false)
        )

    private val isActiveSessionFlow: Flow<Boolean> =
        combine(_sessionReady, userProfile, _userRole) { ready, profile, role ->
            val now = System.currentTimeMillis()
            val timedBanned = (profile?.banUntilMillis ?: 0L) > now
            ready && (role == UserRole.SUPER_ADMIN || (profile?.accountDisabled != true && !timedBanned))
        }

    val coursesState: StateFlow<UiState<List<Course>>> =
        combine(isActiveSessionFlow, _userRole) { active, role -> active to (role != UserRole.STUDENT) }
            .flatMapLatest { (active, isAdmin) ->
                if (!active) flowOf(UiState.Loading)
                else repository.observeCourses(isAdmin).asUiState("courses")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val driveFoldersState: StateFlow<UiState<List<DriveFolderLink>>> =
        isActiveSessionFlow
            .flatMapLatest { active ->
                if (!active) flowOf(UiState.Success(emptyList()))
                else governance.observeDriveFolders().asUiState("drive_folders")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    val departmentsState: StateFlow<UiState<List<AcademicDepartment>>> =
        isActiveSessionFlow
            .flatMapLatest { active ->
                if (!active) flowOf(UiState.Success(emptyList()))
                else governance.observeDepartments().asUiState("departments")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    init {
        viewModelScope.launch {
            driveFoldersState.collect { state ->
                if (state is UiState.Success) {
                    refreshDriveFiles(state.data)
                }
            }
        }
    }

    val quizzesState: StateFlow<UiState<List<Quiz>>> =
        combine(isActiveSessionFlow, _userRole) { active, role -> active to (role != UserRole.STUDENT) }
            .flatMapLatest { (active, isAdmin) ->
                if (!active) flowOf(UiState.Loading)
                else repository.observeQuizzes(isAdmin).asUiState("quizzes")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val attemptsState: StateFlow<UiState<List<QuizAttempt>>> =
        combine(isActiveSessionFlow, _userRole) { active, role -> active to (role != UserRole.STUDENT) }
            .flatMapLatest { (active, isAdmin) ->
                if (!active) flowOf(UiState.Loading)
                else repository.observeQuizAttempts(isAdmin).asUiState("quiz_attempts")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val verifiedFilesState: StateFlow<UiState<List<VerifiedFileRecord>>> = isActiveSessionFlow
        .flatMapLatest { active ->
            if (!active) flowOf(UiState.Loading)
            else repository.observeVerifiedFiles(currentUserId).asUiState("verified_files")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val availableAiSources: StateFlow<List<StudyMaterialMatcher.Candidate>> =
        combine(coursesState, driveFilesState, verifiedFilesState) { coursesUi, driveUi, vaultUi ->
            val courses = (coursesUi as? UiState.Success)?.data.orEmpty()
            val driveFiles = (driveUi as? UiState.Success)?.data.orEmpty()
            val vaultFiles = (vaultUi as? UiState.Success)?.data.orEmpty()
            StudyMaterialMatcher.buildCandidateList(courses, driveFiles, vaultFiles)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val notificationsState: StateFlow<UiState<List<UniNotification>>> =
        combine(isActiveSessionFlow, _userRole) { active, role -> active to (role != UserRole.STUDENT) }
            .flatMapLatest { (active, isAdmin) ->
                if (!active) flowOf(UiState.Loading)
                else governance.observeNotifications(isAdmin).asUiState("notifications")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val supportMessagesState: StateFlow<UiState<List<SupportMessage>>> =
        combine(isActiveSessionFlow, _userRole) { active, role -> active to role }
            .flatMapLatest { (active, role) ->
                if (!active) flowOf(UiState.Success(emptyList()))
                else governance.observeSupportMessages(role).asUiState("support_messages")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    val usersDirectoryState: StateFlow<UiState<List<UserProfile>>> =
        combine(isActiveSessionFlow, _userRole) { active, role -> active to (role != UserRole.STUDENT) }
            .flatMapLatest { (active, isAdmin) ->
                if (!active || !isAdmin) flowOf(UiState.Success(emptyList()))
                else governance.observeAllUsers().asUiState("users")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    val roleInvitesState: StateFlow<UiState<List<RoleInvite>>> =
        combine(isActiveSessionFlow, _userRole) { active, role -> active to (role != UserRole.STUDENT) }
            .flatMapLatest { (active, isAdmin) ->
                if (!active || !isAdmin) flowOf(UiState.Success(emptyList()))
                else governance.observeRoleInvites().asUiState("role_invites")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    // Strictly restricted to SUPER_ADMIN only (hidden from both Students & Regular Admins)
    val auditLogsState: StateFlow<UiState<List<AuditLog>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role == UserRole.SUPER_ADMIN) }
            .flatMapLatest { (ready, isSuperAdmin) ->
                if (!ready || !isSuperAdmin) flowOf(UiState.Success(emptyList()))
                else governance.observeAuditLogs().asUiState("audit_logs")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    val adminsState: StateFlow<UiState<List<AdminRecord>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role == UserRole.SUPER_ADMIN) }
            .flatMapLatest { (ready, isSuperAdmin) ->
                if (!ready || !isSuperAdmin) flowOf(UiState.Success(emptyList()))
                else governance.observeAdmins().asUiState("admins")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    val chatMessagesState: StateFlow<UiState<List<StudyChatMessage>>> =
        combine(isActiveSessionFlow, _selectedChatRoomId) { active, roomId -> active to roomId }
            .flatMapLatest { (active, roomId) ->
                if (!active) flowOf(UiState.Success(emptyList()))
                else governance.observeChatMessages(roomId).asUiState("chat_$roomId")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    fun selectChatRoom(departmentId: String?, yearIndex: Int?) {
        _selectedChatRoomId.value = governance.buildChatRoomId(departmentId, yearIndex)
    }

    fun dismissInAppPreview() {
        _inAppPreviewFile.value = null
    }

    fun openPreviewWithExternalApp(context: Context, preview: InAppFilePreview) {
        val openRes = driveSyncRepository.openLocalFileWithIntent(context, preview.file, preview.mimeType)
        if (openRes.isFailure) {
            _statusBanner.value = "Saved in Downloads/UniCore (${preview.file.name}). No external viewer found."
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun clearAiState() {
        activeAiJob?.cancel()
        _aiUiState.value = AiUiState.Idle
    }

    fun cancelAiTutor() {
        activeAiJob?.cancel()
        activeAiJob = null
        if (_aiUiState.value is AiUiState.Loading || _aiUiState.value is AiUiState.Streaming) {
            _aiUiState.value = AiUiState.Idle
        }
    }

    fun createCourseWithOptionalFile(
        context: Context,
        title: String,
        description: String,
        materialLink: String,
        materialType: MaterialType,
        selectedFileUri: Uri?,
        departmentId: String? = null,
        departmentName: String? = null,
        academicYear: String? = null
    ) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.HIGH,
                    "courses",
                    "Student role attempted privileged course creation."
                )
                _statusBanner.value = "Access denied: Admin privileges required."
                return@launch
            }
            if (!checkWriteQuota()) return@launch
            when (val rate = rateLimiter.checkAndConsume(RateLimiter.Bucket.ADMIN_ACTION)) {
                is RateLimiter.Decision.Blocked -> {
                    _statusBanner.value = rate.reason
                    return@launch
                }
                RateLimiter.Decision.Allowed -> Unit
            }

            val validatedFile = if (selectedFileUri != null) {
                val res = withContext(Dispatchers.IO) {
                    SecureFileValidator.validateAndStoreInSandbox(
                        context = context,
                        uri = selectedFileUri,
                        maxBytes = UniCoreRepository.MAX_INLINE_COURSE_FILE_BYTES.toInt()
                    )
                }
                when (res) {
                    is SecureFileValidator.FileValidationResult.Rejected -> {
                        governance.logAuditEvent(
                            "FILE_UPLOAD_REJECTED",
                            AuditSeverity.WARN,
                            "courses",
                            res.reason
                        )
                        _statusBanner.value = "Blocked: ${res.reason}"
                        return@launch
                    }
                    is SecureFileValidator.FileValidationResult.Success -> res.file
                }
            } else {
                null
            }

            val result = repository.createCourse(
                title = title,
                description = description,
                materialLink = materialLink.ifBlank { null },
                materialType = materialType,
                published = true,
                governance = governance,
                attachedFile = validatedFile,
                departmentId = departmentId,
                departmentName = departmentName,
                academicYear = academicYear
            )
            _statusBanner.value = result.fold(
                onSuccess = {
                    if (validatedFile != null) "Uploaded (${validatedFile.originalName}) & published."
                    else "Course published."
                },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to create course.") }
            )
        }
    }

    fun updateCourseDetails(
        courseId: String,
        title: String,
        description: String,
        departmentId: String?,
        departmentName: String?,
        academicYear: String?
    ) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.HIGH,
                    "courses/$courseId",
                    "Student role attempted to edit course department."
                )
                _statusBanner.value = "Access denied: Admin privileges required."
                return@launch
            }
            if (!checkWriteQuota()) return@launch
            val result = repository.updateCourseDetails(
                courseId = courseId,
                title = title,
                description = description,
                departmentId = departmentId,
                departmentName = departmentName,
                academicYear = academicYear
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Course updated." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to update course.") }
            )
        }
    }

    fun saveDepartment(existingDeptId: String?, name: String, years: List<String>) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.HIGH,
                    "departments",
                    "Student role attempted to modify departments."
                )
                _statusBanner.value = "Access denied: Only Admins or Super Admins can edit departments."
                return@launch
            }
            if (!checkWriteQuota()) return@launch
            val res = governance.saveDepartment(existingDeptId, name, years)
            _statusBanner.value = res.fold(
                onSuccess = { if (existingDeptId == null) "Department added." else "Department updated." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Could not save department.") }
            )
        }
    }

    fun deleteDepartment(deptId: String) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                _statusBanner.value = "Access denied: Only Admins or Super Admins can delete departments."
                return@launch
            }
            val res = governance.deleteDepartment(deptId)
            _statusBanner.value = res.fold(
                onSuccess = { "Department deleted." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Could not delete department.") }
            )
        }
    }

    fun openCourseAttachment(context: Context, course: Course) {
        viewModelScope.launch {
            _openingCourseId.value = course.id
            try {
                val res = repository.downloadOrOpenCourseAttachment(context, course)
                res.onSuccess { localFile ->
                    val mime = course.attachmentMimeType ?: "application/pdf"
                    withContext(Dispatchers.IO) {
                        driveSyncRepository.exportFileToDownloadsFolder(context, localFile, mime)
                    }
                    if (mime == "application/pdf" || mime.startsWith("image/")) {
                        _inAppPreviewFile.value = InAppFilePreview(localFile, localFile.name, mime)
                        _statusBanner.value = "Saved: ${localFile.name} (Downloads/UniCore)"
                    } else {
                        val openRes = driveSyncRepository.openLocalFileWithIntent(context, localFile, mime)
                        if (openRes.isFailure) {
                            _statusBanner.value = "Saved in Downloads/UniCore (${localFile.name})."
                        }
                    }
                }.onFailure { err ->
                    _statusBanner.value = InputValidator.safeUserErrorMessage(err, err.message ?: "Could not open file.")
                }
            } finally {
                _openingCourseId.value = null
            }
        }
    }

    fun deleteCourse(courseId: String) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) return@launch
            val result = repository.deleteCourse(courseId)
            _statusBanner.value = result.fold(
                onSuccess = { "Course deleted." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Could not delete course.") }
            )
        }
    }

    fun linkDriveFolder(
        title: String,
        rawUrl: String,
        departmentId: String? = null,
        departmentName: String? = null,
        academicYear: String? = null
    ) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.HIGH,
                    "drive_folders",
                    "Student attempted to link a Drive folder."
                )
                _statusBanner.value = "Access denied: Admin privileges required."
                return@launch
            }
            if (!checkWriteQuota()) return@launch
            val res = governance.linkDriveFolder(title, rawUrl, departmentId, departmentName, academicYear)
            _statusBanner.value = res.fold(
                onSuccess = { "Google Drive folder linked & auto-synced." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Could not link Drive folder.") }
            )
        }
    }

    fun unlinkDriveFolder(folderId: String) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) return@launch
            val res = governance.unlinkDriveFolder(folderId)
            _statusBanner.value = res.fold(
                onSuccess = { "Drive folder unlinked." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Could not remove folder.") }
            )
        }
    }

    fun refreshDriveFilesNow() {
        val currentFolders = (driveFoldersState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch { refreshDriveFiles(currentFolders) }
    }

    private suspend fun refreshDriveFiles(folders: List<DriveFolderLink>) {
        if (folders.isEmpty()) {
            _driveFilesState.value = UiState.Success(emptyList())
            return
        }
        _isDriveRefreshing.value = true
        try {
            val res = driveSyncRepository.syncDriveFolders(folders)
            _driveFilesState.value = res.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(InputValidator.safeUserErrorMessage(it, it.message ?: "Drive sync failed.")) }
            )
        } finally {
            _isDriveRefreshing.value = false
        }
    }

    fun openDriveSyncedFile(context: Context, item: DriveSyncedFile) {
        viewModelScope.launch {
            _openingDriveFileId.value = item.fileId
            try {
                val res = driveSyncRepository.downloadDriveFile(context, item)
                res.onSuccess { outcome ->
                    when (outcome) {
                        is GoogleDriveSyncRepository.DriveOpenOutcome.LocalFileReady -> {
                            _statusBanner.value = if (outcome.savedToDownloads) {
                                "Downloaded: ${outcome.file.name} (Downloads/UniCore)"
                            } else {
                                "Downloaded: ${outcome.file.name}"
                            }
                            if (outcome.mimeType == "application/pdf" || outcome.mimeType.startsWith("image/")) {
                                _inAppPreviewFile.value = InAppFilePreview(
                                    file = outcome.file,
                                    title = outcome.file.name,
                                    mimeType = outcome.mimeType
                                )
                            } else {
                                val openRes = driveSyncRepository.openLocalFileWithIntent(
                                    context, outcome.file, outcome.mimeType
                                )
                                if (openRes.isFailure) {
                                    _statusBanner.value = "Saved '${outcome.file.name}' in Downloads/UniCore."
                                }
                            }
                        }
                        is GoogleDriveSyncRepository.DriveOpenOutcome.FallbackToBrowser -> {
                            openExternalHttpsUrl(context, outcome.url)
                        }
                    }
                }.onFailure { err ->
                    _statusBanner.value = InputValidator.safeUserErrorMessage(err, err.message ?: "Could not open Drive file.")
                }
            } finally {
                _openingDriveFileId.value = null
            }
        }
    }

    private fun openExternalHttpsUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            _statusBanner.value = "No viewer or browser app found to open link."
        }
    }

    fun createQuiz(
        context: Context,
        courseId: String,
        title: String,
        durationMinutes: Int,
        startAtMillis: Long,
        endAtMillis: Long,
        quizImageUri: Uri?,
        questionPrompts: List<String>,
        optionBlocks: List<String>,
        correctIndices: List<Int>
    ) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.HIGH,
                    "quizzes",
                    "Student attempted to create quiz and answer key."
                )
                _statusBanner.value = "Access denied: Admin privileges required."
                return@launch
            }
            if (!checkWriteQuota()) return@launch

            val compressedImage = if (quizImageUri != null) {
                val res = withContext(Dispatchers.IO) {
                    ImageAttachmentHelper.compressUriToBase64(
                        context = context,
                        uri = quizImageUri,
                        maxBase64Chars = 1_500_000,
                        maxDimensionPx = 1400
                    )
                }
                res.getOrElse { err ->
                    _statusBanner.value = err.message ?: "Could not prepare quiz image."
                    return@launch
                }
            } else {
                null
            }

            val result = repository.createQuizWithAnswerKey(
                courseId = courseId,
                title = title,
                durationMinutes = durationMinutes,
                startAtMillis = startAtMillis,
                endAtMillis = endAtMillis,
                quizImage = compressedImage,
                questionPrompts = questionPrompts,
                optionBlocks = optionBlocks,
                correctOptionIndices = correctIndices,
                governance = governance
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Quiz created." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to create quiz.") }
            )
        }
    }

    suspend fun fetchQuizImageBytes(quiz: Quiz): ByteArray? {
        return repository.loadQuizImageBytes(quiz).getOrNull()
    }

    fun deleteQuiz(quizId: String) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) return@launch
            val result = repository.deleteQuiz(quizId)
            _statusBanner.value = result.fold(
                onSuccess = { "Quiz deleted." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Could not delete quiz.") }
            )
        }
    }

    fun submitQuizAttempt(quiz: Quiz, selectedIndices: List<Int>) {
        viewModelScope.launch {
            if (!checkWriteQuota()) return@launch
            when (val rate = rateLimiter.checkAndConsume(RateLimiter.Bucket.QUIZ_SUBMIT)) {
                is RateLimiter.Decision.Blocked -> {
                    governance.logAuditEvent(
                        "RATE_LIMIT_TRIGGERED",
                        AuditSeverity.WARN,
                        "quiz_attempts",
                        "Quiz submission rate limit triggered."
                    )
                    _statusBanner.value = rate.reason
                    return@launch
                }
                RateLimiter.Decision.Allowed -> Unit
            }

            val result = repository.submitQuizAttempt(
                quizId = quiz.id,
                quizTitle = quiz.title,
                selectedIndices = selectedIndices,
                governance = governance
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Quiz submitted for grading." },
                onFailure = {
                    InputValidator.safeUserErrorMessage(
                        it,
                        "Duplicate submission or permission restriction blocked."
                    )
                }
            )
        }
    }

    fun cancelQuizForViolation(quiz: Quiz, selectedIndices: List<Int>, reason: String) {
        viewModelScope.launch {
            val result = repository.cancelQuizForViolation(
                quizId = quiz.id,
                quizTitle = quiz.title,
                selectedIndices = selectedIndices,
                reason = reason,
                governance = governance
            )
            result.onFailure { err ->
                Log.w(TAG, "Could not persist violation cancellation", err)
            }
        }
    }

    fun resetCancelledAttempt(attempt: QuizAttempt) {
        viewModelScope.launch {
            if (_userRole.value != UserRole.SUPER_ADMIN) {
                _statusBanner.value = "Only Super Admin can unlock cancelled quiz attempts."
                return@launch
            }
            val result = repository.resetCancelledAttemptAsSuperAdmin(attempt, governance)
            _statusBanner.value = result.fold(
                onSuccess = { "Cancelled attempt unlocked." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Could not unlock attempt.") }
            )
        }
    }

    fun gradeAttemptAsAdmin(attempt: QuizAttempt) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.CRITICAL,
                    "quiz_attempts/${attempt.id}",
                    "Student attempted to finalize grade."
                )
                _statusBanner.value = "Access denied."
                return@launch
            }
            val result = repository.gradeQuizAttemptAsAdmin(attempt, governance)
            _statusBanner.value = result.fold(
                onSuccess = { score -> "Attempt graded: $score%." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Unable to finalize grade.") }
            )
        }
    }

    fun validateAndStoreFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            if (!checkWriteQuota()) return@launch
            val maxBytes = systemSettings.value.maxFileUploadBytes.toInt()
                .coerceIn(102_400, SecureFileValidator.DEFAULT_MAX_BYTES)

            val validation = withContext(Dispatchers.IO) {
                SecureFileValidator.validateAndStoreInSandbox(context, uri, maxBytes)
            }
            when (validation) {
                is SecureFileValidator.FileValidationResult.Rejected -> {
                    governance.logAuditEvent(
                        "FILE_UPLOAD_REJECTED",
                        AuditSeverity.WARN,
                        "users/$currentUserId/files",
                        validation.reason
                    )
                    _statusBanner.value = "Blocked: ${validation.reason}"
                }
                is SecureFileValidator.FileValidationResult.Success -> {
                    val res = repository.recordVerifiedFile(validation.file, governance)
                    _statusBanner.value = res.fold(
                        onSuccess = { "Saved (${validation.file.originalName})" },
                        onFailure = { InputValidator.safeUserErrorMessage(it, "Could not save file metadata.") }
                    )
                }
            }
        }
    }

    fun deleteVerifiedFile(context: Context, record: VerifiedFileRecord) {
        viewModelScope.launch {
            val res = repository.deleteVerifiedFile(context, record)
            _statusBanner.value = res.fold(
                onSuccess = { "Deleted (${record.originalName})." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Could not delete file.") }
            )
        }
    }

    fun askAiTutor(
        context: Context,
        rawPrompt: String,
        preferredLanguage: AppLanguage,
        selectedSourceId: String = StudyMaterialMatcher.SOURCE_AUTO
    ) {
        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch {
            val quota = quotaSnapshot.value
            if (quota.aiHealth == ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED) {
                val reason = if (!quota.aiGloballyEnabled || quota.zeroCostLockActive) {
                    "AI Tutor is paused by Super Admin Zero-Cost Lock."
                } else {
                    "Daily free-tier AI quota (${quota.aiDailyCap}/${quota.aiDailyCap}) reached."
                }
                governance.logAuditEvent("QUOTA_LOCKED", AuditSeverity.WARN, "ai_logic", reason)
                _aiUiState.value = AiUiState.PausedQuota(reason)
                return@launch
            }

            val cleanPrompt = when (val v = InputValidator.validateBoundedText(rawPrompt, "Prompt", 3, 600)) {
                is InputValidator.ValidationResult.Valid -> v.value
                is InputValidator.ValidationResult.Invalid -> {
                    _aiUiState.value = AiUiState.Error(v.reason)
                    return@launch
                }
            }

            when (val rate = rateLimiter.checkAndConsume(RateLimiter.Bucket.AI_TUTOR)) {
                is RateLimiter.Decision.Blocked -> {
                    governance.logAuditEvent("RATE_LIMIT_TRIGGERED", AuditSeverity.WARN, "ai_logic", "AI rate limit.")
                    _aiUiState.value = AiUiState.Error(rate.reason)
                    return@launch
                }
                RateLimiter.Decision.Allowed -> Unit
            }

            if (quota.aiHealth == ZeroCostQuotaManager.QuotaHealth.WARNING_80_PERCENT) {
                governance.logAuditEvent(
                    "QUOTA_WARNING",
                    AuditSeverity.WARN,
                    "ai_logic",
                    "User reached >=80% daily AI cap (${quota.aiUsedToday}/${quota.aiDailyCap})."
                )
                _statusBanner.value = "Free-Tier Alert: ${quota.aiUsedToday}/${quota.aiDailyCap} AI calls used."
            }

            val allCandidates = availableAiSources.value
            val matchedCandidates = StudyMaterialMatcher.findBestMatches(
                prompt = cleanPrompt,
                candidates = allCandidates,
                pinnedSourceId = selectedSourceId,
                maxResults = 2
            )

            val initialLoadingText = if (matchedCandidates.isNotEmpty()) {
                val names = matchedCandidates.joinToString(" • ") { it.fileName }
                if (preferredLanguage == AppLanguage.ARABIC) "جاري فتح وقراءة: $names..."
                else "Reading: $names..."
            } else {
                null
            }
            _aiUiState.value = AiUiState.Loading(initialLoadingText)

            val loadedParts = loadMatchedMaterialsForAi(context, matchedCandidates)
            val usedLabels = loadedParts.map { it.sourceLabel }.distinct()
            val catalogSummary = if (loadedParts.isEmpty() && allCandidates.isNotEmpty()) {
                allCandidates.take(25).joinToString("\n") { c ->
                    "- ${c.displayTitle} (${c.fileName})${if (c.contextSubtitle.isNotBlank()) ": ${c.contextSubtitle}" else ""}"
                }
            } else {
                ""
            }

            var finalResponse = ""
            try {
                aiRepository.streamStudyAnswer(
                    prompt = cleanPrompt,
                    preferredLanguage = preferredLanguage,
                    materials = loadedParts,
                    availableCatalogSummary = catalogSummary
                ).collect { partial ->
                    finalResponse = partial
                    _aiUiState.value = AiUiState.Streaming(partial, usedLabels)
                }
                if (finalResponse.isNotBlank()) {
                    _aiUiState.value = AiUiState.Success(finalResponse, usedLabels)
                    repository.incrementDailyCounter(currentUserId, isAi = true)
                } else {
                    _aiUiState.value = AiUiState.Error("Tutor returned an empty response.")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "AI Tutor timed out", e)
                _aiUiState.value = AiUiState.Error("AI request timed out. Please check your connection and try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI Tutor failed", e)
                _aiUiState.value = AiUiState.Error(
                    InputValidator.safeUserErrorMessage(e, "AI Tutor is temporarily unavailable.")
                )
            }
        }
    }

    private suspend fun loadMatchedMaterialsForAi(
        context: Context,
        candidates: List<StudyMaterialMatcher.Candidate>
    ): List<AiTutorRepository.AiMaterialPart> {
        if (candidates.isEmpty()) return emptyList()
        val parts = mutableListOf<AiTutorRepository.AiMaterialPart>()
        var cumulativeBytes = 0

        for (candidate in candidates) {
            when (candidate) {
                is StudyMaterialMatcher.Candidate.CourseMaterial -> {
                    val course = candidate.course
                    val summaryText = buildString {
                        append("Course Title: ${course.title}\n")
                        if (!course.departmentName.isNullOrBlank()) append("Department: ${course.departmentName}\n")
                        if (!course.academicYear.isNullOrBlank()) append("Year: ${course.academicYear}\n")
                        if (course.description.isNotBlank()) append("Description: ${course.description}\n")
                    }.trim()

                    var localFile: File? = null
                    var mime = course.attachmentMimeType ?: "application/pdf"
                    if (candidate.hasBinaryFile) {
                        localFile = repository.downloadOrOpenCourseAttachment(context, course).getOrNull()
                    }

                    val filePart = localFile?.let { file ->
                        readFileIntoAiPart(
                            sourceLabel = candidate.fileName,
                            file = file,
                            mimeType = mime,
                            summary = summaryText,
                            cumulativeBytes = cumulativeBytes
                        ) { added -> cumulativeBytes += added }
                    }
                    parts.add(
                        filePart ?: AiTutorRepository.AiMaterialPart(
                            sourceLabel = candidate.displayTitle,
                            mimeType = "text/plain",
                            courseSummary = summaryText
                        )
                    )
                }
                is StudyMaterialMatcher.Candidate.DriveMaterial -> {
                    val outcome = driveSyncRepository.downloadDriveFile(
                        context = context,
                        item = candidate.file,
                        exportToDownloads = false
                    ).getOrNull()
                    if (outcome is GoogleDriveSyncRepository.DriveOpenOutcome.LocalFileReady) {
                        val filePart = readFileIntoAiPart(
                            sourceLabel = outcome.file.name.ifBlank { candidate.fileName },
                            file = outcome.file,
                            mimeType = outcome.mimeType,
                            summary = candidate.contextSubtitle,
                            cumulativeBytes = cumulativeBytes
                        ) { added -> cumulativeBytes += added }
                        if (filePart != null) parts.add(filePart)
                    }
                }
                is StudyMaterialMatcher.Candidate.VaultMaterial -> {
                    val record = candidate.record
                    val sandboxFile = withContext(Dispatchers.IO) {
                        File(File(context.filesDir, "secure_uploads"), record.storedName)
                    }
                    if (sandboxFile.exists() && sandboxFile.length() > 0L) {
                        val filePart = readFileIntoAiPart(
                            sourceLabel = record.originalName,
                            file = sandboxFile,
                            mimeType = record.mimeType,
                            summary = candidate.contextSubtitle,
                            cumulativeBytes = cumulativeBytes
                        ) { added -> cumulativeBytes += added }
                        if (filePart != null) parts.add(filePart)
                    }
                }
            }
        }
        return parts
    }

    private suspend fun readFileIntoAiPart(
        sourceLabel: String,
        file: File,
        mimeType: String,
        summary: String,
        cumulativeBytes: Int,
        onBytesUsed: (Int) -> Unit
    ): AiTutorRepository.AiMaterialPart? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) return@withContext null
        val size = file.length()
        if (StudyMaterialMatcher.isSupportedInlineMediaMime(mimeType) &&
            size <= StudyMaterialMatcher.MAX_INLINE_FILE_BYTES &&
            cumulativeBytes + size <= StudyMaterialMatcher.MAX_TOTAL_INLINE_BYTES
        ) {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
            onBytesUsed(bytes.size)
            AiTutorRepository.AiMaterialPart(
                sourceLabel = sourceLabel,
                mimeType = mimeType,
                inlineBytes = bytes,
                courseSummary = summary
            )
        } else {
            val extracted = StudyMaterialMatcher.extractReadableTextFromDocument(file, mimeType)
            if (!extracted.isNullOrBlank()) {
                AiTutorRepository.AiMaterialPart(
                    sourceLabel = sourceLabel,
                    mimeType = "text/plain",
                    extractedText = extracted,
                    courseSummary = summary
                )
            } else {
                AiTutorRepository.AiMaterialPart(
                    sourceLabel = sourceLabel,
                    mimeType = mimeType,
                    courseSummary = summary
                )
            }
        }
    }

    fun sendSupportMessage(context: Context, subject: String, body: String, imageUri: Uri?) {
        viewModelScope.launch {
            val role = _userRole.value
            val channel = when (role) {
                UserRole.STUDENT -> SupportChannel.STUDENT_TO_ADMIN
                UserRole.ADMIN -> SupportChannel.ADMIN_TO_SUPER
                UserRole.SUPER_ADMIN -> {
                    _statusBanner.value = "Super Admin handles incoming escalations."
                    return@launch
                }
            }
            if (!checkWriteQuota()) return@launch

            val compressed = if (imageUri != null) {
                withContext(Dispatchers.IO) {
                    ImageAttachmentHelper.compressUriToBase64(context, imageUri, maxBase64Chars = 640_000)
                }.getOrElse { err ->
                    _statusBanner.value = err.message ?: "Invalid image attachment."
                    return@launch
                }
            } else {
                null
            }

            val res = governance.sendSupportMessage(
                channel = channel,
                senderRole = role,
                rawSubject = subject,
                rawBody = body,
                attachment = compressed
            )
            _statusBanner.value = res.fold(
                onSuccess = { "Message sent." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to send message.") }
            )
        }
    }

    fun replyToSupportMessage(context: Context, message: SupportMessage, replyText: String, imageUri: Uri?) {
        viewModelScope.launch {
            val role = _userRole.value
            val channel = SupportChannel.fromRaw(message.channel)
            val allowed = (channel == SupportChannel.STUDENT_TO_ADMIN && role != UserRole.STUDENT) ||
                (channel == SupportChannel.ADMIN_TO_SUPER && role == UserRole.SUPER_ADMIN)
            if (!allowed) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.HIGH,
                    "support_messages/${message.id}",
                    "Unauthorized reply attempt."
                )
                _statusBanner.value = "Access denied."
                return@launch
            }

            val compressed = if (imageUri != null) {
                withContext(Dispatchers.IO) {
                    ImageAttachmentHelper.compressUriToBase64(context, imageUri, maxBase64Chars = 640_000)
                }.getOrElse { err ->
                    _statusBanner.value = err.message ?: "Invalid image attachment."
                    return@launch
                }
            } else {
                null
            }

            val res = governance.replyToSupportMessage(message, replyText, compressed)
            _statusBanner.value = res.fold(
                onSuccess = { "Reply sent." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to send reply.") }
            )
        }
    }

    fun sendChatMessage(
        context: Context,
        roomId: String,
        departmentName: String?,
        academicYear: String?,
        rawText: String,
        imageUri: Uri?
    ) {
        viewModelScope.launch {
            if (!checkWriteQuota()) return@launch
            if (ProfanityFilter.containsProfanity(rawText)) {
                governance.logAuditEvent(
                    "PROFANITY_BLOCKED",
                    AuditSeverity.WARN,
                    "chat_rooms/$roomId",
                    "Blocked abusive message in study chat."
                )
                _statusBanner.value = "تم حظر الإرسال لاحتواء النص على ألفاظ غير لائقة."
                return@launch
            }
            val compressed = if (imageUri != null) {
                withContext(Dispatchers.IO) {
                    ImageAttachmentHelper.compressUriToBase64(context, imageUri, maxBase64Chars = 640_000)
                }.getOrElse { err ->
                    _statusBanner.value = err.message ?: "Invalid image attachment."
                    return@launch
                }
            } else {
                null
            }
            val res = governance.sendChatMessage(
                roomId = roomId,
                departmentName = departmentName,
                academicYear = academicYear,
                senderRole = _userRole.value,
                rawText = rawText,
                attachment = compressed
            )
            res.onFailure { err ->
                _statusBanner.value = InputValidator.safeUserErrorMessage(err, err.message ?: "Could not send chat message.")
            }
        }
    }

    fun deleteChatMessage(roomId: String, messageId: String) {
        viewModelScope.launch {
            val res = governance.deleteChatMessage(roomId, messageId)
            res.onFailure { err ->
                _statusBanner.value = InputValidator.safeUserErrorMessage(err, "Could not delete chat message.")
            }
        }
    }

    fun inviteAccountByEmail(email: String, nameHint: String, assignedRole: UserRole) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                _statusBanner.value = "Access denied."
                return@launch
            }
            if (assignedRole == UserRole.ADMIN && _userRole.value != UserRole.SUPER_ADMIN) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.CRITICAL,
                    "role_invites",
                    "Regular Admin attempted to invite an Admin account."
                )
                _statusBanner.value = "Only Super Admin can add Admin accounts."
                return@launch
            }
            val res = governance.inviteAccountByEmail(email, nameHint, assignedRole)
            _statusBanner.value = res.fold(
                onSuccess = { "Account added (${assignedRole.name}). Activated on Google Sign-In." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to add account.") }
            )
        }
    }

    fun revokeInvite(emailKey: String) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) return@launch
            val res = governance.revokeInvite(emailKey)
            _statusBanner.value = res.fold(
                onSuccess = { "Invite removed." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Could not remove invite.") }
            )
        }
    }

    fun setUserAccountDisabled(targetUser: UserProfile, disabled: Boolean) {
        viewModelScope.launch {
            if (_userRole.value != UserRole.SUPER_ADMIN) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.CRITICAL,
                    "users/${targetUser.userId}",
                    "Non-SuperAdmin attempted to toggle account status."
                )
                _statusBanner.value = "Only Super Admin can enable or disable accounts."
                return@launch
            }
            val res = governance.setUserAccountDisabled(targetUser, disabled)
            _statusBanner.value = res.fold(
                onSuccess = {
                    if (disabled) "${targetUser.displayName} disabled." else "${targetUser.displayName} re-enabled."
                },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Could not update account status.") }
            )
        }
    }

    fun setUserTimedBan(targetUser: UserProfile, durationMinutes: Long, reason: String) {
        viewModelScope.launch {
            val actorRole = _userRole.value
            val targetRole = UserRole.fromRaw(targetUser.role)
            val allowed = (actorRole == UserRole.SUPER_ADMIN && targetUser.userId != currentUserId && targetRole != UserRole.SUPER_ADMIN) ||
                (actorRole == UserRole.ADMIN && targetRole == UserRole.STUDENT && targetUser.userId != currentUserId)
            if (!allowed) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.HIGH,
                    "users/${targetUser.userId}",
                    "Unauthorized timed ban attempt."
                )
                _statusBanner.value = "Insufficient permission to ban this account."
                return@launch
            }
            val res = governance.setUserTimedBan(targetUser, durationMinutes, reason)
            _statusBanner.value = res.fold(
                onSuccess = {
                    if (durationMinutes > 0L) "${targetUser.displayName} temporarily banned."
                    else "Ban lifted for ${targetUser.displayName}."
                },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Could not update ban.") }
            )
        }
    }

    fun promoteUserToAdmin(targetUser: UserProfile) {
        viewModelScope.launch {
            if (_userRole.value != UserRole.SUPER_ADMIN) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.CRITICAL,
                    "admins/${targetUser.userId}",
                    "Non-SuperAdmin attempted to promote user."
                )
                _statusBanner.value = "Only Super Admin can promote Admins."
                return@launch
            }
            val res = governance.promoteUserToAdmin(targetUser)
            _statusBanner.value = res.fold(
                onSuccess = { "${targetUser.displayName} promoted to Admin." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Promotion failed.") }
            )
        }
    }

    fun demoteAdminToStudent(targetUid: String) {
        viewModelScope.launch {
            if (_userRole.value != UserRole.SUPER_ADMIN) return@launch
            val res = governance.demoteAdminToStudent(targetUid)
            _statusBanner.value = res.fold(
                onSuccess = { "User set back to Student." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Could not update role.") }
            )
        }
    }

    fun sendBroadcastNotification(title: String, body: String, category: NotificationCategory) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) return@launch
            val res = governance.sendNotification(title, body, category, "ALL")
            _statusBanner.value = res.fold(
                onSuccess = { "Notification sent." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Could not send notification.") }
            )
        }
    }

    fun updateSystemSettings(
        dailyAiLimit: Long,
        dailyWriteLimit: Long,
        aiEnabled: Boolean,
        zeroCostLockActive: Boolean
    ) {
        viewModelScope.launch {
            if (_userRole.value != UserRole.SUPER_ADMIN) {
                governance.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.CRITICAL,
                    "system_settings/global",
                    "Non-SuperAdmin attempted to alter Super Admin settings."
                )
                _statusBanner.value = "Super Admin privilege required."
                return@launch
            }
            val result = governance.updateSystemSettingsAsSuperAdmin(
                dailyAiLimit = dailyAiLimit,
                dailyWriteLimit = dailyWriteLimit,
                aiEnabled = aiEnabled,
                zeroCostLockActive = zeroCostLockActive
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Settings saved." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Failed to update settings.") }
            )
        }
    }

    private suspend fun checkWriteQuota(): Boolean {
        val quota = quotaSnapshot.value
        if (quota.writeHealth == ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED) {
            _statusBanner.value = "Zero-Cost Safe Pause: Daily free-tier write limit reached."
            return false
        }
        if (quota.writeHealth == ZeroCostQuotaManager.QuotaHealth.WARNING_80_PERCENT) {
            _statusBanner.value = "Notice: ${quota.writesUsedToday}/${quota.writesDailyCap} daily writes used."
        }
        return true
    }

    private fun <T> Flow<List<T>>.asUiState(label: String): Flow<UiState<List<T>>> =
        this.map<List<T>, UiState<List<T>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = unwrapFirestoreOrRethrow(error, label)
                emit(
                    UiState.Error(
                        InputValidator.safeUserErrorMessage(
                            firestoreError ?: error,
                            "Failed to load $label."
                        )
                    )
                )
            }

    private fun unwrapFirestoreOrRethrow(error: Throwable, label: String): FirebaseFirestoreException? {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        Log.w(TAG, "Stream error on $label", firestoreError ?: error)
        return firestoreError
    }

    private companion object {
        const val TAG = "UniCoreVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
