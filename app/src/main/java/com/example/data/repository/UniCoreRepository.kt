package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.example.data.model.AuditSeverity
import com.example.data.model.Course
import com.example.data.model.MaterialType
import com.example.data.model.NotificationCategory
import com.example.data.model.Quiz
import com.example.data.model.QuizAnswerKey
import com.example.data.model.QuizAttempt
import com.example.data.model.RoleInvite
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.VerifiedFileRecord
import com.example.security.ImageAttachmentHelper
import com.example.security.InputValidator
import com.example.security.ProfanityFilter
import com.example.security.SecureFileValidator
import com.example.security.ZeroCostQuotaManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale

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
        const val MAX_INLINE_COURSE_FILE_BYTES = 2_097_152L // 2 MB Spark-plan chunk limit
        private const val CHUNK_RAW_BYTES = 512 * 1024 // 512 KB raw -> ~683 KB Base64 (< 750 KB rule)
        private const val FAST_SESSION_PREFS = "unicore_fast_session"

        fun getCachedUserRole(context: Context, uid: String): UserRole? {
            if (uid.isBlank()) return null
            val raw = context.applicationContext
                .getSharedPreferences(FAST_SESSION_PREFS, Context.MODE_PRIVATE)
                .getString("role_$uid", null)
                ?: return null
            return UserRole.entries.firstOrNull { it.name == raw }
        }
    }

    internal fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    /**
     * Ensures user profile, private PII, and any pending email-based role invite are synced
     * cleanly using parallelized Firestore lookups and local fast-session caching.
     */
    suspend fun ensureUserSessionProfile(
        governance: UniCoreGovernanceRepository,
        context: Context? = null
    ): Result<UserRole> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not authenticated"))
        val user = auth.currentUser!!
        val prefs = context?.applicationContext?.getSharedPreferences(FAST_SESSION_PREFS, Context.MODE_PRIVATE)
        return try {
            val today = ZeroCostQuotaManager.currentUtcDayKey()
            val normalizedEmail = user.email?.trim()?.lowercase(Locale.ROOT)
            val isBootstrappedSuperAdmin =
                normalizedEmail == BOOTSTRAPPED_SUPER_ADMIN_EMAIL && user.isEmailVerified

            val (pendingInvite, hasSuperAdminDoc, hasAdminDoc, userSnap) = coroutineScope {
                val inviteDeferred = async {
                    if (!normalizedEmail.isNullOrBlank() && user.isEmailVerified) {
                        runCatching {
                            val snap = db.collection("role_invites").document(normalizedEmail).get().await()
                            snap.toObject(RoleInvite::class.java)?.takeIf { it.status == "PENDING" }
                        }.getOrNull()
                    } else {
                        null
                    }
                }
                val superDeferred = async {
                    if (isBootstrappedSuperAdmin) true
                    else runCatching {
                        db.collection("super_admins").document(uid).get().await().exists()
                    }.getOrDefault(false)
                }
                val adminDeferred = async {
                    runCatching {
                        db.collection("admins").document(uid).get().await().exists()
                    }.getOrDefault(false)
                }
                val userSnapDeferred = async {
                    runCatching {
                        db.collection("users").document(uid).get().await()
                    }.getOrNull()
                }
                BootstrapSnapshot(
                    pendingInvite = inviteDeferred.await(),
                    hasSuperAdminDoc = superDeferred.await(),
                    hasAdminDoc = adminDeferred.await(),
                    userSnap = userSnapDeferred.await()
                )
            }

            val hasPendingAdminInvite = pendingInvite?.assignedRole == UserRole.ADMIN.name

            val resolvedRole = when {
                isBootstrappedSuperAdmin || hasSuperAdminDoc -> UserRole.SUPER_ADMIN
                hasAdminDoc || hasPendingAdminInvite -> UserRole.ADMIN
                else -> UserRole.STUDENT
            }

            if (isBootstrappedSuperAdmin && prefs?.getBoolean("super_bootstrapped_$uid", false) != true) {
                runCatching {
                    val superRef = db.collection("super_admins").document(uid)
                    if (!superRef.get().await().exists()) {
                        superRef.set(
                            mapOf(
                                "userId" to uid,
                                "email" to (normalizedEmail ?: BOOTSTRAPPED_SUPER_ADMIN_EMAIL),
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
                    prefs?.edit()?.putBoolean("super_bootstrapped_$uid", true)?.apply()
                }
            }

            // Step 1: If user has a PENDING ADMIN invite, create /admins/{uid} while invite is still PENDING
            if (hasPendingAdminInvite && !hasAdminDoc && pendingInvite != null && !normalizedEmail.isNullOrBlank()) {
                db.collection("admins").document(uid).set(
                    mapOf(
                        "userId" to uid,
                        "email" to normalizedEmail,
                        "grantedBy" to pendingInvite.invitedBy,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            // Step 2: Create or update /users/{uid} (role promotion rule also requires PENDING invite)
            val userRef = db.collection("users").document(uid)
            val cleanName = InputValidator.sanitizeText(
                user.displayName ?: pendingInvite?.displayNameHint ?: "UniCore Learner"
            )
                .take(60)
                .ifBlank { "UniCore Learner" }
                .let { if (it.length < 2) "Learner" else it }

            if (userSnap != null && !userSnap.exists()) {
                userRef.set(
                    mapOf(
                        "userId" to uid,
                        "displayName" to cleanName,
                        "role" to resolvedRole.name,
                        "aiCallsToday" to 0L,
                        "writesToday" to 0L,
                        "quotaDayKey" to today,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else if (userSnap != null && userSnap.exists()) {
                val existingDay = userSnap.getString("quotaDayKey") ?: ""
                val existingRole = userSnap.getString("role") ?: UserRole.STUDENT.name
                val updates = mutableMapOf<String, Any>(
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (existingDay != today) {
                    updates["aiCallsToday"] = 0L
                    updates["writesToday"] = 0L
                    updates["quotaDayKey"] = today
                }
                if (hasPendingAdminInvite && existingRole == UserRole.STUDENT.name) {
                    updates["role"] = UserRole.ADMIN.name
                }
                if (updates.size > 1) {
                    runCatching { userRef.update(updates).await() }
                }
            }

            // Step 3: Mark invite CLAIMED after admin registry and profile are updated
            if (pendingInvite != null && !normalizedEmail.isNullOrBlank()) {
                runCatching {
                    db.collection("role_invites").document(normalizedEmail).update(
                        mapOf(
                            "status" to "CLAIMED",
                            "claimedByUid" to uid,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }

            // Step 4: Store PII isolated in /users/{uid}/private/pii (skip lookup if already cached locally)
            if (prefs?.getBoolean("pii_saved_$uid", false) != true) {
                runCatching {
                    val piiRef = userRef.collection("private").document("pii")
                    val email = (normalizedEmail ?: "unknown@unicore.edu").take(120)
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
                    prefs?.edit()?.putBoolean("pii_saved_$uid", true)?.apply()
                }
            }

            val effectiveRole = when {
                resolvedRole != UserRole.STUDENT -> resolvedRole
                else -> UserRole.fromRaw(userSnap?.getString("role"))
            }

            prefs?.edit()?.putString("role_$uid", effectiveRole.name)?.apply()

            if (prefs?.getString("last_audit_day_$uid", null) != today) {
                runCatching {
                    governance.logAuditEvent(
                        action = "AUTH_SUCCESS",
                        severity = AuditSeverity.INFO,
                        targetPath = "users/$uid",
                        details = "Authenticated session initialized with role ${effectiveRole.name}."
                    )
                    prefs?.edit()?.putString("last_audit_day_$uid", today)?.apply()
                }
            }

            Result.success(effectiveRole)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallbackRole = prefs?.let { getCachedUserRole(context, uid) }
            if (fallbackRole != null) {
                Result.success(fallbackRole)
            } else {
                handleFirestoreError(e, OperationType.WRITE, "users/$uid")
                Result.failure(e)
            }
        }
    }

    private data class BootstrapSnapshot(
        val pendingInvite: RoleInvite?,
        val hasSuperAdminDoc: Boolean,
        val hasAdminDoc: Boolean,
        val userSnap: DocumentSnapshot?
    )

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
                .map { snap ->
                    snap.toObjects(Course::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
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
                .map { snap ->
                    snap.toObjects(Quiz::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
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
                .map { snap ->
                    snap.toObjects(QuizAttempt::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt?.seconds ?: Long.MAX_VALUE }
                }
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
                .map { snap ->
                    snap.toObjects(VerifiedFileRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
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
        published: Boolean,
        governance: UniCoreGovernanceRepository,
        attachedFile: SecureFileValidator.ValidatedFile? = null,
        departmentId: String? = null,
        departmentName: String? = null,
        academicYear: String? = null
    ): Result<Unit> {
        var docPath = "courses"
        val docRef = db.collection("courses").document()
        var createdDoc = false
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

            val encodedChunks = if (attachedFile != null) {
                if (attachedFile.sizeBytes > MAX_INLINE_COURSE_FILE_BYTES) {
                    return Result.failure(
                        IllegalArgumentException(
                            "Direct file uploads are limited to 2 MB. For larger files, connect a Google Drive folder instead."
                        )
                    )
                }
                withContext(Dispatchers.IO) {
                    val fileBytes = File(attachedFile.sandboxPath).readBytes()
                    if (fileBytes.isEmpty()) throw IllegalStateException("Selected attachment is empty.")
                    val chunks = mutableListOf<String>()
                    var offset = 0
                    while (offset < fileBytes.size) {
                        val end = (offset + CHUNK_RAW_BYTES).coerceAtMost(fileBytes.size)
                        val slice = fileBytes.copyOfRange(offset, end)
                        chunks.add(Base64.encodeToString(slice, Base64.NO_WRAP))
                        offset = end
                    }
                    chunks
                }
            } else {
                emptyList()
            }

            if (encodedChunks.size > 6) {
                return Result.failure(IllegalArgumentException("Attached file exceeds maximum chunk count."))
            }

            val instructorName = InputValidator.sanitizeText(auth.currentUser?.displayName ?: "Instructor")
                .take(60)
                .ifBlank { "Instructor" }
                .let { if (it.length < 2) "Instructor" else it }

            val resolvedType = if (attachedFile != null) MaterialType.FILE else materialType
            docPath = docRef.path

            val payload = mutableMapOf<String, Any>(
                "title" to cleanTitle,
                "description" to cleanDesc,
                "instructorId" to uid,
                "instructorName" to instructorName,
                "materialType" to resolvedType.name,
                "hasAttachment" to (attachedFile != null),
                "published" to if (attachedFile != null) false else published,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (cleanLink != null) payload["materialLink"] = cleanLink
            if (!departmentId.isNullOrBlank()) payload["departmentId"] = departmentId
            if (!departmentName.isNullOrBlank() && departmentName.length >= 2) {
                payload["departmentName"] = InputValidator.sanitizeText(departmentName).take(80)
            }
            if (!academicYear.isNullOrBlank()) {
                payload["academicYear"] = InputValidator.sanitizeText(academicYear).take(40)
            }
            if (attachedFile != null) {
                payload["attachmentName"] = attachedFile.originalName.take(140)
                payload["attachmentMimeType"] = attachedFile.mimeType
                payload["attachmentSizeBytes"] = attachedFile.sizeBytes
                payload["chunkCount"] = encodedChunks.size.toLong()
                payload["attachmentSha256"] = attachedFile.sha256Hex
            }

            docRef.set(payload).await()
            createdDoc = true

            if (attachedFile != null) {
                encodedChunks.forEachIndexed { index, base64Data ->
                    docRef.collection("chunks").document(index.toString()).set(
                        mapOf(
                            "chunkIndex" to index.toLong(),
                            "data" to base64Data,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                if (published) {
                    docRef.update(
                        mapOf(
                            "published" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }

            incrementDailyCounter(uid, isAi = false)
            if (published) {
                governance.sendNotification(
                    title = cleanTitle,
                    body = if (attachedFile != null) {
                        "$instructorName uploaded ${attachedFile.originalName}."
                    } else {
                        "$instructorName published new course material."
                    },
                    category = NotificationCategory.COURSE,
                    targetUid = "ALL"
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (createdDoc && attachedFile != null) {
                runCatching { deleteCourse(docRef.id) }
            }
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateCourseDetails(
        courseId: String,
        title: String,
        description: String,
        departmentId: String?,
        departmentName: String?,
        academicYear: String?
    ): Result<Unit> {
        val docPath = "courses/$courseId"
        return try {
            requireUserId()
            val cleanTitle = when (val r = InputValidator.validateBoundedText(title, "Course title", 3, 100)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
            val cleanDesc = when (val r = InputValidator.validateBoundedText(description, "Description", 1, 600)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
            val updates = mutableMapOf<String, Any>(
                "title" to cleanTitle,
                "description" to cleanDesc,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!departmentId.isNullOrBlank()) {
                updates["departmentId"] = departmentId
            } else {
                updates["departmentId"] = FieldValue.delete()
            }
            if (!departmentName.isNullOrBlank() && departmentName.length >= 2) {
                updates["departmentName"] = InputValidator.sanitizeText(departmentName).take(80)
            } else {
                updates["departmentName"] = FieldValue.delete()
            }
            if (!academicYear.isNullOrBlank()) {
                updates["academicYear"] = InputValidator.sanitizeText(academicYear).take(40)
            } else {
                updates["academicYear"] = FieldValue.delete()
            }
            db.collection("courses").document(courseId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun downloadOrOpenCourseAttachment(context: Context, course: Course): Result<File> {
        val docPath = "courses/${course.id}/chunks"
        return try {
            requireUserId()
            if (!course.hasAttachment || course.chunkCount <= 0L || course.attachmentSha256.isNullOrBlank()) {
                return Result.failure(IllegalStateException("This course does not have a downloadable attachment."))
            }
            val safeName = (course.attachmentName ?: "material.pdf")
                .replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF ]"), "_")
                .take(80)
                .ifBlank { "material.pdf" }
            val cacheDir = File(context.filesDir, "course_cache").apply { mkdirs() }
            val targetFile = File(cacheDir, "course_${course.id}_$safeName")
            if (!targetFile.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)) {
                return Result.failure(SecurityException("Invalid local cache path."))
            }

            if (targetFile.exists() && targetFile.length() > 0L) {
                val existingSha = withContext(Dispatchers.IO) {
                    sha256Hex(targetFile.readBytes())
                }
                if (existingSha.equals(course.attachmentSha256, ignoreCase = true)) {
                    return Result.success(targetFile)
                }
                targetFile.delete()
            }

            val chunkSnapshots = db.collection("courses")
                .document(course.id)
                .collection("chunks")
                .get()
                .await()

            val orderedChunks = chunkSnapshots.documents
                .mapNotNull { doc ->
                    val idx = doc.getLong("chunkIndex") ?: doc.id.toLongOrNull() ?: return@mapNotNull null
                    val data = doc.getString("data") ?: return@mapNotNull null
                    idx to data
                }
                .sortedBy { it.first }

            if (orderedChunks.size.toLong() < course.chunkCount) {
                return Result.failure(IllegalStateException("Attachment chunks are incomplete."))
            }

            val assembledBytes = withContext(Dispatchers.IO) {
                val out = ByteArrayOutputStream()
                orderedChunks.forEach { (_, b64) ->
                    out.write(Base64.decode(b64, Base64.DEFAULT))
                }
                out.toByteArray()
            }

            val actualSha = sha256Hex(assembledBytes)
            if (!actualSha.equals(course.attachmentSha256, ignoreCase = true)) {
                return Result.failure(SecurityException("File integrity check (SHA-256) failed."))
            }

            withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "course_${course.id}_$safeName.tmp")
                tmp.writeBytes(assembledBytes)
                if (targetFile.exists()) targetFile.delete()
                tmp.renameTo(targetFile)
            }
            Result.success(targetFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, docPath)
            Result.failure(e)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun deleteCourse(courseId: String): Result<Unit> {
        val docPath = "courses/$courseId"
        return try {
            requireUserId()
            runCatching {
                val chunks = db.collection("courses").document(courseId).collection("chunks").get().await()
                chunks.documents.forEach { it.reference.delete().await() }
            }
            db.collection("courses").document(courseId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createQuizWithAnswerKey(
        courseId: String,
        title: String,
        durationMinutes: Int,
        startAtMillis: Long = 0L,
        endAtMillis: Long = 0L,
        quizImage: ImageAttachmentHelper.CompressedImage? = null,
        questionPrompts: List<String>,
        optionBlocks: List<String>,
        correctOptionIndices: List<Int>,
        governance: UniCoreGovernanceRepository
    ): Result<Unit> {
        var docPath = "quizzes"
        val quizRef = db.collection("quizzes").document()
        var createdDoc = false
        return try {
            val uid = requireUserId()
            if (courseId.isBlank()) return Result.failure(IllegalArgumentException("Select a course first."))
            val cleanTitle = when (val r = InputValidator.validateBoundedText(title, "Quiz title", 3, 100)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
            val cleanPrompts = questionPrompts.mapNotNull { raw ->
                when (val r = InputValidator.validateBoundedText(raw, "Question", 3, 250)) {
                    is InputValidator.ValidationResult.Valid -> r.value
                    is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
                }
            }
            if (cleanPrompts.isEmpty() || cleanPrompts.size != optionBlocks.size || cleanPrompts.size != correctOptionIndices.size) {
                return Result.failure(IllegalArgumentException("Provide at least 1 complete question."))
            }

            val cleanOptionBlocks = optionBlocks.map { block ->
                ProfanityFilter.assertCleanText(block)
                val opts = block.split("|").map { InputValidator.sanitizeText(it).take(80) }.filter { it.isNotBlank() }
                if (opts.size < 2) return Result.failure(IllegalArgumentException("Each question needs at least 2 options."))
                if (opts.size > 6) return Result.failure(IllegalArgumentException("Maximum 6 options per question."))
                opts.joinToString("|")
            }

            val safeStartMs = startAtMillis.coerceAtLeast(0L)
            val safeEndMs = endAtMillis.coerceAtLeast(0L)
            if (safeStartMs > 0L && safeEndMs > 0L && safeEndMs <= safeStartMs) {
                return Result.failure(IllegalArgumentException("Quiz closing time must be after opening time."))
            }

            val imageChunks = if (quizImage != null) {
                val chunkLen = 450_000
                val list = mutableListOf<String>()
                var pos = 0
                while (pos < quizImage.base64.length) {
                    val end = (pos + chunkLen).coerceAtMost(quizImage.base64.length)
                    list.add(quizImage.base64.substring(pos, end))
                    pos = end
                }
                if (list.size > 4) {
                    return Result.failure(IllegalArgumentException("Quiz image exceeds maximum allowed size."))
                }
                list
            } else {
                emptyList()
            }

            val safeCorrect = correctOptionIndices.mapIndexed { i, idx ->
                val count = cleanOptionBlocks[i].split("|").size
                idx.coerceIn(0, (count - 1).coerceAtLeast(0)).toLong()
            }

            docPath = quizRef.path
            val mins = durationMinutes.coerceIn(1, 180).toLong()
            val payload = mutableMapOf<String, Any>(
                "courseId" to courseId,
                "title" to cleanTitle,
                "durationMinutes" to mins,
                "questionPrompts" to cleanPrompts,
                "optionBlocks" to cleanOptionBlocks,
                "hasImage" to (quizImage != null),
                "published" to (quizImage == null),
                "createdBy" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (safeStartMs > 0L) payload["startAtMillis"] = safeStartMs
            if (safeEndMs > 0L) payload["endAtMillis"] = safeEndMs
            if (quizImage != null) {
                payload["imageName"] = quizImage.fileName
                payload["imageMimeType"] = quizImage.mimeType
                payload["chunkCount"] = imageChunks.size.toLong()
            }

            quizRef.set(payload).await()
            createdDoc = true

            if (quizImage != null) {
                imageChunks.forEachIndexed { idx, chunkData ->
                    quizRef.collection("assets").document(idx.toString()).set(
                        mapOf(
                            "chunkIndex" to idx.toLong(),
                            "data" to chunkData,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                quizRef.update(
                    mapOf(
                        "published" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val keyRef = quizRef.collection("private").document("answer_key")
            docPath = keyRef.path
            keyRef.set(
                mapOf(
                    "quizId" to quizRef.id,
                    "correctIndices" to safeCorrect,
                    "passingScore" to 70L,
                    "updatedBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            incrementDailyCounter(uid, isAi = false)
            governance.sendNotification(
                title = cleanTitle,
                body = "New ${cleanPrompts.size}-question quiz published ($mins min).",
                category = NotificationCategory.QUIZ,
                targetUid = "ALL"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (createdDoc) {
                runCatching { deleteQuiz(quizRef.id) }
            }
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun loadQuizImageBytes(quiz: Quiz): Result<ByteArray?> {
        if (!quiz.hasImage || quiz.chunkCount <= 0L) return Result.success(null)
        val docPath = "quizzes/${quiz.id}/assets"
        return try {
            requireUserId()
            val snap = db.collection("quizzes").document(quiz.id).collection("assets").get().await()
            val ordered = snap.documents
                .mapNotNull { doc ->
                    val idx = doc.getLong("chunkIndex") ?: doc.id.toLongOrNull() ?: return@mapNotNull null
                    val data = doc.getString("data") ?: return@mapNotNull null
                    idx to data
                }
                .sortedBy { it.first }
            if (ordered.isEmpty()) return Result.success(null)
            val fullBase64 = ordered.joinToString("") { it.second }
            val bytes = withContext(Dispatchers.IO) {
                Base64.decode(fullBase64, Base64.DEFAULT)
            }
            Result.success(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteQuiz(quizId: String): Result<Unit> {
        val docPath = "quizzes/$quizId"
        return try {
            requireUserId()
            runCatching {
                db.collection("quizzes").document(quizId).collection("private").document("answer_key").delete().await()
            }
            runCatching {
                val assets = db.collection("quizzes").document(quizId).collection("assets").get().await()
                assets.documents.forEach { it.reference.delete().await() }
            }
            db.collection("quizzes").document(quizId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun submitQuizAttempt(
        quizId: String,
        quizTitle: String,
        selectedIndices: List<Int>,
        governance: UniCoreGovernanceRepository
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

            governance.logAuditEvent(
                action = "QUIZ_SUBMITTED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Student submitted quiz '$quizTitle' for verification."
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

    suspend fun cancelQuizForViolation(
        quizId: String,
        quizTitle: String,
        selectedIndices: List<Int>,
        reason: String,
        governance: UniCoreGovernanceRepository
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
            val cleanReason = InputValidator.sanitizeText(reason)
                .take(200)
                .ifBlank { "Left the application during an active quiz." }
                .let { if (it.length < 3) "Left app during quiz" else it }
            val safeIndices = if (selectedIndices.isNotEmpty()) {
                selectedIndices.map { it.toLong() }
            } else {
                listOf(0L)
            }

            db.collection("quiz_attempts").document(attemptId).set(
                mapOf(
                    "userId" to uid,
                    "studentName" to studentName,
                    "quizId" to quizId,
                    "quizTitle" to quizTitle.take(100),
                    "selectedIndices" to safeIndices,
                    "status" to "CANCELLED_VIOLATION",
                    "score" to 0L,
                    "graded" to true,
                    "cancellationReason" to cleanReason,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            governance.logAuditEvent(
                action = "QUIZ_VIOLATION_CANCELLED",
                severity = AuditSeverity.HIGH,
                targetPath = docPath,
                details = "Quiz '$quizTitle' cancelled for violation: $cleanReason"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun resetCancelledAttemptAsSuperAdmin(
        attempt: QuizAttempt,
        governance: UniCoreGovernanceRepository
    ): Result<Unit> {
        val docPath = "quiz_attempts/${attempt.id}"
        return try {
            requireUserId()
            db.collection("quiz_attempts").document(attempt.id).delete().await()
            governance.logAuditEvent(
                action = "QUIZ_VIOLATION_CANCELLED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Super Admin unlocked cancelled attempt for ${attempt.studentName}."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun gradeQuizAttemptAsAdmin(
        attempt: QuizAttempt,
        governance: UniCoreGovernanceRepository
    ): Result<Int> {
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

            governance.logAuditEvent(
                action = "GRADE_FINALIZED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Admin finalized grade ($computedScore%) for student attempt."
            )
            governance.sendNotification(
                title = attempt.quizTitle,
                body = "Your quiz result is out: $computedScore%.",
                category = NotificationCategory.GRADE,
                targetUid = attempt.userId
            )
            Result.success(computedScore)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun recordVerifiedFile(
        validated: SecureFileValidator.ValidatedFile,
        governance: UniCoreGovernanceRepository
    ): Result<Unit> {
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

            governance.logAuditEvent(
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

    suspend fun deleteVerifiedFile(context: Context, record: VerifiedFileRecord): Result<Unit> {
        val uid = requireUserId()
        val docPath = "users/$uid/files/${record.id}"
        return try {
            db.collection("users").document(uid).collection("files").document(record.id).delete().await()
            withContext(Dispatchers.IO) {
                val sandboxDir = File(context.filesDir, "secure_uploads")
                val localFile = File(sandboxDir, record.storedName)
                if (localFile.exists()) {
                    localFile.delete()
                }
            }
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

    internal fun unwrapAndLog(
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
