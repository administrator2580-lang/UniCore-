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
import com.example.data.repository.UniCoreGovernanceRepository
import com.example.data.repository.UniCoreRepository
import com.example.security.InputValidator
import com.example.security.RateLimiter
import com.example.security.SecureFileValidator
import com.example.security.ZeroCostQuotaManager
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
    data object Loading : AiUiState
    data class Streaming(val text: String) : AiUiState
    data class Success(val text: String) : AiUiState
    data class PausedQuota(val reason: String) : AiUiState
    data class Error(val message: String) : AiUiState
}

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
            ready && (role == UserRole.SUPER_ADMIN || profile?.accountDisabled != true)
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
                    val openRes = driveSyncRepository.openLocalFileWithIntent(context, localFile, mime)
                    if (openRes.isFailure) {
                        _statusBanner.value = "Saved in app cache (${localFile.name}). Install a PDF/image viewer to open."
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
                            val openRes = driveSyncRepository.openLocalFileWithIntent(context, outcome.file, outcome.mimeType)
                            if (openRes.isFailure) {
                                openExternalHttpsUrl(context, item.viewUrl)
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
        courseId: String,
        title: String,
        durationMinutes: Int,
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
            val result = repository.createQuizWithAnswerKey(
                courseId = courseId,
                title = title,
                durationMinutes = durationMinutes,
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

    fun askAiTutor(rawPrompt: String, preferredLanguage: AppLanguage) {
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

            _aiUiState.value = AiUiState.Loading
            var finalResponse = ""
            try {
                aiRepository.streamStudyAnswer(cleanPrompt, preferredLanguage).collect { partial ->
                    finalResponse = partial
                    _aiUiState.value = AiUiState.Streaming(partial)
                }
                if (finalResponse.isNotBlank()) {
                    _aiUiState.value = AiUiState.Success(finalResponse)
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

    fun sendSupportMessage(subject: String, body: String) {
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
            val res = governance.sendSupportMessage(
                channel = channel,
                senderRole = role,
                rawSubject = subject,
                rawBody = body
            )
            _statusBanner.value = res.fold(
                onSuccess = { "Message sent." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to send message.") }
            )
        }
    }

    fun replyToSupportMessage(message: SupportMessage, replyText: String) {
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
            val res = governance.replyToSupportMessage(message, replyText)
            _statusBanner.value = res.fold(
                onSuccess = { "Reply sent." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to send reply.") }
            )
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
