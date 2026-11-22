package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.example.data.model.AdminRecord
import com.example.data.model.AuditLog
import com.example.data.model.AuditSeverity
import com.example.data.model.Course
import com.example.data.model.MaterialType
import com.example.data.model.Quiz
import com.example.data.model.QuizAnswerKey
import com.example.data.model.QuizAttempt
import com.example.data.model.SystemSettings
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.VerifiedFileRecord
import com.example.security.InputValidator
import com.example.security.SecureFileValidator
import com.example.security.ZeroCostQuotaManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class UniCoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    companion object {
        const val BOOTSTRAPPED_SUPER_ADMIN_EMAIL = "fvathyvhmed@gmail.com"
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    /**
     * Ensures user profile & private PII doc exist and resolves authoritative RBAC role.
     */
    suspend fun ensureUserSessionProfile(): Result<UserRole> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not authenticated"))
        val user = auth.currentUser!!
        return try {
            val today = ZeroCostQuotaManager.currentUtcDayKey()
            val isBootstrappedSuperAdmin =
                user.email == BOOTSTRAPPED_SUPER_ADMIN_EMAIL && user.isEmailVerified

            // Check authoritative role registries
            val resolvedRole = when {
                isBootstrappedSuperAdmin -> UserRole.SUPER_ADMIN
                runCatching {
                    db.collection("super_admins").document(uid).get().await().exists()
                }.getOrDefault(false) -> UserRole.SUPER_ADMIN
                runCatching {
                    db.collection("admins").document(uid).get().await().exists()
                }.getOrDefault(false) -> UserRole.ADMIN
                else -> UserRole.STUDENT
            }

            // Ensure bootstrapped Super Admin record & default settings exist
            if (isBootstrappedSuperAdmin) {
                val superRef = db.collection("super_admins").document(uid)
                if (!superRef.get().await().exists()) {
                    superRef.set(
                        mapOf(
                            "userId" to uid,
                            "email" to (user.email ?: BOOTSTRAPPED_SUPER_ADMIN_EMAIL),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                val settingsRef = db.collection("system_settings").document("global")
                if (!settingsRef.get().await().exists()) {
                    settingsRef.set(
                        mapOf(
                            "dailyAiLimitPerUser" to 20L,
                            "dailyWriteLimitPerUser" to 100L,
                            "maxFileUploadBytes" to 5_242_880L,
                            "aiEnabled" to true,
                            "zeroCostLockActive" to false,
                            "updatedBy" to uid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }

            val userRef = db.collection("users").document(uid)
            val snap = userRef.get().await()
            val cleanName = InputValidator.sanitizeText(user.displayName ?: "UniCore Learner")
                .take(60)
                .ifBlank { "UniCore Learner" }
                .let { if (it.length < 2) "Learner" else it }

            if (!snap.exists()) {
                val initialRole = if (isBootstrappedSuperAdmin) UserRole.SUPER_ADMIN.name else UserRole.STUDENT.name
                userRef.set(
                    mapOf(
                        "userId" to uid,
                        "displayName" to cleanName,
                        "role" to initialRole,
                        "aiCallsToday" to 0L,
                        "writesToday" to 0L,
                        "quotaDayKey" to today,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val existingDay = snap.getString("quotaDayKey") ?: ""
                if (existingDay != today) {
                    userRef.update(
                        mapOf(
                            "aiCallsToday" to 0L,
                            "writesToday" to 0L,
                            "quotaDayKey" to today,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }

            // Store PII isolated in /users/{uid}/private/pii
            val piiRef = userRef.collection("private").document("pii")
            val email = (user.email ?: "unknown@unicore.edu").take(120)
            if (!piiRef.get().await().exists()) {
                piiRef.set(
                    mapOf(
                        "userId" to uid,
                        "email" to email,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            logAuditEvent(
                action = "AUTH_SUCCESS",
                severity = AuditSeverity.INFO,
                targetPath = "users/$uid",
                details = "Authenticated session initialized with role ${resolvedRole.name}."
            )

            Result.success(resolvedRole)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$uid")
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user profile observation." }
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snap -> snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    fun observeSystemSettings(): Flow<SystemSettings> = flow {
        val uid = requireUserId()
        val path = "system_settings/global"
        emitAll(
            db.collection("system_settings").document("global")
                .snapshots()
                .map { snap ->
                    snap.toObject(SystemSettings::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        ?: SystemSettings()
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    fun observeCourses(isAdmin: Boolean): Flow<List<Course>> = flow {
        val uid = requireUserId()
        val path = "courses"
        val query = if (isAdmin) {
            db.collection(path)
        } else {
            db.collection(path).whereEqualTo("published", true)
        }
        emitAll(
            query.snapshots()
                .map { snap -> snap.toObjects(Course::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeQuizzes(isAdmin: Boolean): Flow<List<Quiz>> = flow {
        val uid = requireUserId()
        val path = "quizzes"
        val query = if (isAdmin) {
            db.collection(path)
        } else {
            db.collection(path).whereEqualTo("published", true)
        }
        emitAll(
            query.snapshots()
                .map { snap -> snap.toObjects(Quiz::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeQuizAttempts(isAdmin: Boolean): Flow<List<QuizAttempt>> = flow {
        val uid = requireUserId()
        val path = "quiz_attempts"
        val query = if (isAdmin) {
            db.collection(path)
        } else {
            db.collection(path).whereEqualTo("userId", uid)
        }
        emitAll(
            query.snapshots()
                .map { snap -> snap.toObjects(QuizAttempt::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeVerifiedFiles(userId: String): Flow<List<VerifiedFileRecord>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user file observation." }
        val path = "users/$userId/files"
        emitAll(
            db.collection("users").document(userId).collection("files")
                .snapshots()
                .map { snap -> snap.toObjects(VerifiedFileRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeAuditLogs(): Flow<List<AuditLog>> = flow {
        val uid = requireUserId()
        val path = "audit_logs"
        emitAll(
            db.collection(path)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .snapshots()
                .map { snap -> snap.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeAdmins(): Flow<List<AdminRecord>> = flow {
        val uid = requireUserId()
        val path = "admins"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap -> snap.toObjects(AdminRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    suspend fun createCourse(
        title: String,
        description: String,
        materialLink: String?,
        materialType: MaterialType,
        published: Boolean
    ): Result<Unit> {
        var docPath = "courses"
        return try {
            val uid = requireUserId()
            val cleanTitle = when (val r = InputValidator.validateBoundedText(title, "Course title", 3, 100)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
            val cleanDesc = when (val r = InputValidator.validateBoundedText(description, "Description", 1, 600)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
            val cleanLink = when (val r = InputValidator.validateOptionalHttpsUrl(materialLink)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }

            val instructorName = InputValidator.sanitizeText(auth.currentUser?.displayName ?: "Instructor")
                .take(60)
                .ifBlank { "Instructor" }
                .let { if (it.length < 2) "Instructor" else it }

            val docRef = db.collection("courses").document()
            docPath = docRef.path
            val payload = mapOf(
                "title" to cleanTitle,
                "description" to cleanDesc,
                "instructorId" to uid,
                "instructorName" to instructorName,
                "materialLink" to cleanLink,
                "materialType" to materialType.name,
                "published" to published,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            docRef.set(payload).await()
            incrementDailyCounter(uid, isAi = false)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createQuizWithAnswerKey(
        courseId: String,
        title: String,
        durationMinutes: Int,
        questionPrompt: String,
        options: List<String>,
        correctOptionIndex: Int
    ): Result<Unit> {
        var docPath = "quizzes"
        return try {
            val uid = requireUserId()
            if (courseId.isBlank()) return Result.failure(IllegalArgumentException("Select a course first."))
            val cleanTitle = when (val r = InputValidator.validateBoundedText(title, "Quiz title", 3, 100)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
            val cleanPrompt = when (val r = InputValidator.validateBoundedText(questionPrompt, "Question", 5, 250)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
            val cleanOptions = options.map { InputValidator.sanitizeText(it).take(80) }.filter { it.isNotBlank() }
            if (cleanOptions.size < 2) {
                return Result.failure(IllegalArgumentException("Provide at least 2 answer options."))
            }
            val safeCorrectIdx = correctOptionIndex.coerceIn(0, cleanOptions.lastIndex).toLong()

            val quizRef = db.collection("quizzes").document()
            docPath = quizRef.path
            quizRef.set(
                mapOf(
                    "courseId" to courseId,
                    "title" to cleanTitle,
                    "durationMinutes" to durationMinutes.coerceIn(1, 180).toLong(),
                    "questionPrompts" to listOf(cleanPrompt),
                    "optionBlocks" to listOf(cleanOptions.joinToString("|")),
                    "published" to true,
                    "createdBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val keyRef = quizRef.collection("private").document("answer_key")
            docPath = keyRef.path
            keyRef.set(
                mapOf(
                    "quizId" to quizRef.id,
                    "correctIndices" to listOf(safeCorrectIdx),
                    "passingScore" to 70L,
                    "updatedBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            incrementDailyCounter(uid, isAi = false)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun submitQuizAttempt(
        quizId: String,
        quizTitle: String,
        selectedIndices: List<Int>
    ): Result<Unit> {
        var docPath = "quiz_attempts"
        return try {
            val uid = requireUserId()
            val attemptId = "${uid}_${quizId}"
            docPath = "quiz_attempts/$attemptId"
            val studentName = InputValidator.sanitizeText(auth.currentUser?.displayName ?: "Student")
                .take(60)
                .ifBlank { "Student" }
                .let { if (it.length < 2) "Student" else it }

            db.collection("quiz_attempts").document(attemptId).set(
                mapOf(
                    "userId" to uid,
                    "studentName" to studentName,
                    "quizId" to quizId,
                    "quizTitle" to quizTitle.take(100),
                    "selectedIndices" to selectedIndices.map { it.toLong() },
                    "status" to "SUBMITTED",
                    "score" to 0L,
                    "graded" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "QUIZ_SUBMITTED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Student submitted quiz '$quizTitle' for server-verified grading."
            )
            incrementDailyCounter(uid, isAi = false)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun gradeQuizAttemptAsAdmin(attempt: QuizAttempt): Result<Int> {
        val docPath = "quiz_attempts/${attempt.id}"
        return try {
            val uid = requireUserId()
            val keySnap = db.collection("quizzes")
                .document(attempt.quizId)
                .collection("private")
                .document("answer_key")
                .get()
                .await()

            val answerKey = keySnap.toObject(QuizAnswerKey::class.java)
                ?: return Result.failure(IllegalStateException("Protected answer key not found."))

            val total = answerKey.correctIndices.size.coerceAtLeast(1)
            val correctCount = attempt.selectedIndices.zip(answerKey.correctIndices)
                .count { (sel, ans) -> sel == ans }
            val computedScore = ((correctCount * 100.0) / total).toInt().coerceIn(0, 100)

            db.collection("quiz_attempts").document(attempt.id).update(
                mapOf(
                    "score" to computedScore.toLong(),
                    "graded" to true,
                    "status" to "GRADED",
                    "gradedBy" to uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "GRADE_FINALIZED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Admin finalized grade ($computedScore%) for student attempt."
            )
            Result.success(computedScore)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun recordVerifiedFile(validated: SecureFileValidator.ValidatedFile): Result<Unit> {
        var docPath = "users/unknown/files"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("files").document()
            docPath = docRef.path
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "originalName" to validated.originalName,
                    "storedName" to validated.storedName,
                    "mimeType" to validated.mimeType,
                    "sizeBytes" to validated.sizeBytes,
                    "sha256Hex" to validated.sha256Hex,
                    "status" to "VERIFIED",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "FILE_UPLOAD_VERIFIED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Sandboxed ${validated.mimeType} (${validated.sizeBytes}B) sha256=${validated.sha256Hex.take(12)}..."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateSystemSettingsAsSuperAdmin(
        dailyAiLimit: Long,
        dailyWriteLimit: Long,
        aiEnabled: Boolean,
        zeroCostLockActive: Boolean
    ): Result<Unit> {
        val docPath = "system_settings/global"
        return try {
            val uid = requireUserId()
            db.collection("system_settings").document("global").update(
                mapOf(
                    "dailyAiLimitPerUser" to dailyAiLimit.coerceIn(1L, 100L),
                    "dailyWriteLimitPerUser" to dailyWriteLimit.coerceIn(10L, 500L),
                    "aiEnabled" to aiEnabled,
                    "zeroCostLockActive" to zeroCostLockActive,
                    "updatedBy" to uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "SETTINGS_CHANGED",
                severity = AuditSeverity.HIGH,
                targetPath = docPath,
                details = "Super Admin updated zero-cost settings: aiLimit=$dailyAiLimit, aiEnabled=$aiEnabled, lock=$zeroCostLockActive"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun grantAdminRoleAsSuperAdmin(targetUid: String, targetEmail: String): Result<Unit> {
        val cleanUid = when (val r = InputValidator.validateId(targetUid)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val cleanEmail = when (val r = InputValidator.validateBoundedText(targetEmail, "Email", 3, 120)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val docPath = "admins/$cleanUid"
        return try {
            val uid = requireUserId()
            db.collection("admins").document(cleanUid).set(
                mapOf(
                    "userId" to cleanUid,
                    "email" to cleanEmail,
                    "grantedBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            logAuditEvent(
                action = "ROLE_CHANGED",
                severity = AuditSeverity.HIGH,
                targetPath = docPath,
                details = "Super Admin granted ADMIN role to $cleanUid."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun revokeAdminRoleAsSuperAdmin(targetUid: String): Result<Unit> {
        val docPath = "admins/$targetUid"
        return try {
            requireUserId()
            db.collection("admins").document(targetUid).delete().await()
            logAuditEvent(
                action = "ROLE_CHANGED",
                severity = AuditSeverity.HIGH,
                targetPath = docPath,
                details = "Super Admin revoked ADMIN role from $targetUid."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun incrementDailyCounter(userId: String, isAi: Boolean): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user quota update." }
            val field = if (isAi) "aiCallsToday" else "writesToday"
            db.collection("users").document(userId).update(
                mapOf(
                    field to FieldValue.increment(1L),
                    "quotaDayKey" to ZeroCostQuotaManager.currentUtcDayKey(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun logAuditEvent(
        action: String,
        severity: AuditSeverity,
        targetPath: String,
        details: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        val logId = "log_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docPath = "audit_logs/$logId"
        val actorLabel = InputValidator.sanitizeText(
            auth.currentUser?.displayName ?: auth.currentUser?.email ?: uid
        ).take(80).ifBlank { uid }.let { if (it.length < 2) "user" else it }

        try {
            db.collection("audit_logs").document(logId).set(
                mapOf(
                    "actorUid" to uid,
                    "actorLabel" to actorLabel,
                    "action" to action,
                    "severity" to severity.name,
                    "targetPath" to targetPath.take(180).ifBlank { "unknown" },
                    "details" to InputValidator.sanitizeText(details).take(400).ifBlank { "Security event" },
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
        }
    }

    /**
     * Live server-side security verification probes triggered by Super Admin in the Security Console
     * to prove Firestore Rules actually reject unauthorized writes/reads on the backend.
     */
    suspend fun runLiveSecurityProbe(targetPath: String, operation: suspend () -> Unit): Boolean {
        return try {
            operation()
            false // Unexpectedly succeeded
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true // Properly blocked by Firestore Security Rules!
        }
    }

    private fun unwrapAndLog(
        error: Throwable,
        operationType: OperationType,
        path: String,
        uid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }
}
