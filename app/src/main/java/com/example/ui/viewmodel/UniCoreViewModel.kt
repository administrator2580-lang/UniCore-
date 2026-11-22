package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AdminRecord
import com.example.data.model.AuditLog
import com.example.data.model.AuditSeverity
import com.example.data.model.Course
import com.example.data.model.MaterialType
import com.example.data.model.NotificationCategory
import com.example.data.model.Quiz
import com.example.data.model.QuizAttempt
import com.example.data.model.RoleInvite
import com.example.data.model.SystemSettings
import com.example.data.model.UniNotification
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.VerifiedFileRecord
import com.example.data.preferences.AppLanguage
import com.example.data.repository.AiTutorRepository
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

    val coursesState: StateFlow<UiState<List<Course>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role != UserRole.STUDENT) }
            .flatMapLatest { (ready, isAdmin) ->
                if (!ready) flowOf(UiState.Loading)
                else repository.observeCourses(isAdmin).asUiState("courses")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val quizzesState: StateFlow<UiState<List<Quiz>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role != UserRole.STUDENT) }
            .flatMapLatest { (ready, isAdmin) ->
                if (!ready) flowOf(UiState.Loading)
                else repository.observeQuizzes(isAdmin).asUiState("quizzes")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val attemptsState: StateFlow<UiState<List<QuizAttempt>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role != UserRole.STUDENT) }
            .flatMapLatest { (ready, isAdmin) ->
                if (!ready) flowOf(UiState.Loading)
                else repository.observeQuizAttempts(isAdmin).asUiState("quiz_attempts")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val verifiedFilesState: StateFlow<UiState<List<VerifiedFileRecord>>> = _sessionReady
        .flatMapLatest { ready ->
            if (!ready) flowOf(UiState.Loading)
            else repository.observeVerifiedFiles(currentUserId).asUiState("verified_files")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val notificationsState: StateFlow<UiState<List<UniNotification>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role != UserRole.STUDENT) }
            .flatMapLatest { (ready, isAdmin) ->
                if (!ready) flowOf(UiState.Loading)
                else governance.observeNotifications(isAdmin).asUiState("notifications")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val usersDirectoryState: StateFlow<UiState<List<UserProfile>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role != UserRole.STUDENT) }
            .flatMapLatest { (ready, isAdmin) ->
                if (!ready || !isAdmin) flowOf(UiState.Success(emptyList()))
                else governance.observeAllUsers().asUiState("users")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    val roleInvitesState: StateFlow<UiState<List<RoleInvite>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role != UserRole.STUDENT) }
            .flatMapLatest { (ready, isAdmin) ->
                if (!ready || !isAdmin) flowOf(UiState.Success(emptyList()))
                else governance.observeRoleInvites().asUiState("role_invites")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    val auditLogsState: StateFlow<UiState<List<AuditLog>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role != UserRole.STUDENT) }
            .flatMapLatest { (ready, isAdmin) ->
                if (!ready || !isAdmin) flowOf(UiState.Success(emptyList()))
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
        _aiUiState.value = AiUiState.Idle
    }

    fun createCourse(
        title: String,
        description: String,
        materialLink: String,
        materialType: MaterialType
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

            val result = repository.createCourse(
                title = title,
                description = description,
                materialLink = materialLink.ifBlank { null },
                materialType = materialType,
                published = true,
                governance = governance
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Course published." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to create course.") }
            )
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
        viewModelScope.launch {
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
                _aiUiState.value = AiUiState.Error("Request timed out after 60 seconds.")
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
