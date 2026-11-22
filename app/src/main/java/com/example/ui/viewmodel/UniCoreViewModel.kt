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
import com.example.data.model.Quiz
import com.example.data.model.QuizAttempt
import com.example.data.model.SystemSettings
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.VerifiedFileRecord
import com.example.data.repository.AiTutorRepository
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
            val res = repository.ensureUserSessionProfile()
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
            else repository.observeSystemSettings()
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

    val auditLogsState: StateFlow<UiState<List<AuditLog>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role != UserRole.STUDENT) }
            .flatMapLatest { (ready, isAdmin) ->
                if (!ready || !isAdmin) flowOf(UiState.Success(emptyList()))
                else repository.observeAuditLogs().asUiState("audit_logs")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    val adminsState: StateFlow<UiState<List<AdminRecord>>> =
        combine(_sessionReady, _userRole) { ready, role -> ready to (role == UserRole.SUPER_ADMIN) }
            .flatMapLatest { (ready, isSuperAdmin) ->
                if (!ready || !isSuperAdmin) flowOf(UiState.Success(emptyList()))
                else repository.observeAdmins().asUiState("admins")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Success(emptyList()))

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun createCourse(
        title: String,
        description: String,
        materialLink: String,
        materialType: MaterialType
    ) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                repository.logAuditEvent(
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
                published = true
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Course published securely." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to create course.") }
            )
        }
    }

    fun createQuiz(
        courseId: String,
        title: String,
        durationMinutes: Int,
        questionPrompt: String,
        options: List<String>,
        correctIndex: Int
    ) {
        viewModelScope.launch {
            if (_userRole.value == UserRole.STUDENT) {
                repository.logAuditEvent(
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
                questionPrompt = questionPrompt,
                options = options,
                correctOptionIndex = correctIndex
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Quiz created with isolated protected answer key." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Failed to create quiz.") }
            )
        }
    }

    fun submitQuizAttempt(quiz: Quiz, selectedIndex: Int) {
        viewModelScope.launch {
            if (!checkWriteQuota()) return@launch
            when (val rate = rateLimiter.checkAndConsume(RateLimiter.Bucket.QUIZ_SUBMIT)) {
                is RateLimiter.Decision.Blocked -> {
                    repository.logAuditEvent(
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
                selectedIndices = listOf(selectedIndex)
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Quiz submitted! Grade awaits Admin verification against isolated key." },
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
                repository.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.CRITICAL,
                    "quiz_attempts/${attempt.id}",
                    "Student attempted to finalize grade."
                )
                _statusBanner.value = "Access denied."
                return@launch
            }
            val result = repository.gradeQuizAttemptAsAdmin(attempt)
            _statusBanner.value = result.fold(
                onSuccess = { score -> "Attempt graded against protected key: $score%." },
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
                    repository.logAuditEvent(
                        "FILE_UPLOAD_REJECTED",
                        AuditSeverity.WARN,
                        "users/$currentUserId/files",
                        validation.reason
                    )
                    _statusBanner.value = "Blocked: ${validation.reason}"
                }
                is SecureFileValidator.FileValidationResult.Success -> {
                    val res = repository.recordVerifiedFile(validation.file)
                    _statusBanner.value = res.fold(
                        onSuccess = {
                            "Verified & sandboxed (${validation.file.mimeType}, SHA-256 ${validation.file.sha256Hex.take(10)}...)"
                        },
                        onFailure = { InputValidator.safeUserErrorMessage(it, "Could not save file metadata.") }
                    )
                }
            }
        }
    }

    fun askAiTutor(rawPrompt: String) {
        viewModelScope.launch {
            val quota = quotaSnapshot.value
            if (quota.aiHealth == ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED) {
                val reason = if (!quota.aiGloballyEnabled || quota.zeroCostLockActive) {
                    "AI Tutor is paused by Super Admin Zero-Cost Lock."
                } else {
                    "Daily free-tier AI quota (${quota.aiDailyCap}/${quota.aiDailyCap}) reached. Paused safely at zero cost."
                }
                repository.logAuditEvent(
                    "QUOTA_LOCKED",
                    AuditSeverity.WARN,
                    "ai_logic",
                    reason
                )
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
                    repository.logAuditEvent(
                        "RATE_LIMIT_TRIGGERED",
                        AuditSeverity.WARN,
                        "ai_logic",
                        "AI Tutor rate limit enforced."
                    )
                    _aiUiState.value = AiUiState.Error(rate.reason)
                    return@launch
                }
                RateLimiter.Decision.Allowed -> Unit
            }

            if (quota.aiHealth == ZeroCostQuotaManager.QuotaHealth.WARNING_80_PERCENT) {
                repository.logAuditEvent(
                    "QUOTA_WARNING",
                    AuditSeverity.WARN,
                    "ai_logic",
                    "User reached >=80% of daily zero-cost AI cap (${quota.aiUsedToday}/${quota.aiDailyCap})."
                )
                _statusBanner.value = "Free-Tier Alert: ${quota.aiUsedToday}/${quota.aiDailyCap} AI calls used today."
            }

            _aiUiState.value = AiUiState.Loading
            var finalResponse = ""
            try {
                aiRepository.streamStudyAnswer(cleanPrompt).collect { partial ->
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

    fun updateSystemSettings(
        dailyAiLimit: Long,
        dailyWriteLimit: Long,
        aiEnabled: Boolean,
        zeroCostLockActive: Boolean
    ) {
        viewModelScope.launch {
            if (_userRole.value != UserRole.SUPER_ADMIN) {
                repository.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.CRITICAL,
                    "system_settings/global",
                    "Non-SuperAdmin attempted to alter Super Admin system settings."
                )
                _statusBanner.value = "Access denied: Super Admin privilege required."
                return@launch
            }
            val result = repository.updateSystemSettingsAsSuperAdmin(
                dailyAiLimit = dailyAiLimit,
                dailyWriteLimit = dailyWriteLimit,
                aiEnabled = aiEnabled,
                zeroCostLockActive = zeroCostLockActive
            )
            _statusBanner.value = result.fold(
                onSuccess = { "Super Admin Zero-Cost policies updated." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Failed to update system settings.") }
            )
        }
    }

    fun grantAdminRole(targetUid: String, targetEmail: String) {
        viewModelScope.launch {
            if (_userRole.value != UserRole.SUPER_ADMIN) {
                repository.logAuditEvent(
                    "UNAUTHORIZED_ADMIN_ATTEMPT",
                    AuditSeverity.CRITICAL,
                    "admins/$targetUid",
                    "Non-SuperAdmin attempted to grant Admin privilege."
                )
                _statusBanner.value = "Access denied: Only Super Admin can manage Admin roles."
                return@launch
            }
            val res = repository.grantAdminRoleAsSuperAdmin(targetUid, targetEmail)
            _statusBanner.value = res.fold(
                onSuccess = { "Admin role granted safely." },
                onFailure = { InputValidator.safeUserErrorMessage(it, it.message ?: "Role update failed.") }
            )
        }
    }

    fun revokeAdminRole(targetUid: String) {
        viewModelScope.launch {
            if (_userRole.value != UserRole.SUPER_ADMIN) return@launch
            val res = repository.revokeAdminRoleAsSuperAdmin(targetUid)
            _statusBanner.value = res.fold(
                onSuccess = { "Admin role revoked." },
                onFailure = { InputValidator.safeUserErrorMessage(it, "Could not revoke role.") }
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
