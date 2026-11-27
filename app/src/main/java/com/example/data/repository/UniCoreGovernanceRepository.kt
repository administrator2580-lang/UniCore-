package com.example.data.repository

import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.example.data.model.AcademicDepartment
import com.example.data.model.AdminRecord
import com.example.data.model.AuditLog
import com.example.data.model.AuditSeverity
import com.example.data.model.DriveFolderLink
import com.example.data.model.NotificationCategory
import com.example.data.model.RoleInvite
import com.example.data.model.StudyChatMessage
import com.example.data.model.SupportChannel
import com.example.data.model.SupportMessage
import com.example.data.model.SystemSettings
import com.example.data.model.UniNotification
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.security.ImageAttachmentHelper
import com.example.security.InputValidator
import com.example.security.ProfanityFilter
import kotlinx.coroutines.flow.combine
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
import java.util.Locale
import java.util.UUID

class UniCoreGovernanceRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private val emailRegex = Regex("^[a-z0-9_.+-]+@[a-z0-9.-]+\\.[a-z]{2,}$")

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in before accessing governance endpoints.")
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

    fun observeAllUsers(): Flow<List<UserProfile>> = flow {
        val uid = requireUserId()
        val path = "users"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(compareBy<UserProfile> { it.role }.thenBy { it.displayName.lowercase(Locale.ROOT) })
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeRoleInvites(): Flow<List<RoleInvite>> = flow {
        val uid = requireUserId()
        val path = "role_invites"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(RoleInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
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

    fun observeNotifications(isAdmin: Boolean): Flow<List<UniNotification>> = flow {
        val uid = requireUserId()
        val path = "notifications"
        val query = if (isAdmin) {
            db.collection(path)
        } else {
            db.collection(path).whereIn("targetUid", listOf("ALL", uid))
        }
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(UniNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
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

    fun observeDriveFolders(): Flow<List<DriveFolderLink>> = flow {
        val uid = requireUserId()
        val path = "drive_folders"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(DriveFolderLink::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeDepartments(): Flow<List<AcademicDepartment>> = flow {
        val uid = requireUserId()
        val path = "departments"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(AcademicDepartment::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeSupportMessages(role: UserRole): Flow<List<SupportMessage>> = flow {
        val uid = requireUserId()
        val path = "support_messages"
        val messagesFlow: Flow<List<SupportMessage>> = when (role) {
            UserRole.SUPER_ADMIN -> {
                db.collection(path).snapshots().map { snap ->
                    snap.toObjects(SupportMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
            }
            UserRole.ADMIN -> {
                val studentTicketsFlow = db.collection(path)
                    .whereEqualTo("channel", SupportChannel.STUDENT_TO_ADMIN.name)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(SupportMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    }
                val ownAdminTicketsFlow = db.collection(path)
                    .whereEqualTo("senderUid", uid)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(SupportMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    }
                combine(studentTicketsFlow, ownAdminTicketsFlow) { studentList, ownList ->
                    (studentList + ownList).distinctBy { it.id }
                }
            }
            UserRole.STUDENT -> {
                db.collection(path)
                    .whereEqualTo("senderUid", uid)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(SupportMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    }
            }
        }

        emitAll(
            messagesFlow
                .map { list ->
                    list.sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    suspend fun inviteAccountByEmail(
        rawEmail: String,
        rawNameHint: String,
        assignedRole: UserRole
    ): Result<Unit> {
        val email = rawEmail.trim().lowercase(Locale.ROOT)
        if (email.length !in 3..120 || !emailRegex.matches(email)) {
            return Result.failure(IllegalArgumentException("Enter a valid Google email address."))
        }
        val cleanHint = when (val r = InputValidator.validateBoundedText(rawNameHint, "Name", 2, 60)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }

        val docPath = "role_invites/$email"
        return try {
            val uid = requireUserId()
            val inviterLabel = InputValidator.sanitizeText(
                auth.currentUser?.displayName ?: auth.currentUser?.email ?: uid
            ).take(80).ifBlank { uid }.let { if (it.length < 2) "Admin" else it }

            db.collection("role_invites").document(email).set(
                mapOf(
                    "email" to email,
                    "displayNameHint" to cleanHint,
                    "assignedRole" to assignedRole.name,
                    "invitedBy" to uid,
                    "invitedByLabel" to inviterLabel,
                    "status" to "PENDING",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "ACCOUNT_INVITED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Pre-registered ${assignedRole.name} account invite."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun revokeInvite(emailKey: String): Result<Unit> {
        val docPath = "role_invites/$emailKey"
        return try {
            requireUserId()
            db.collection("role_invites").document(emailKey).delete().await()
            logAuditEvent(
                action = "ROLE_CHANGED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Removed account invite."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun promoteUserToAdmin(targetUser: UserProfile): Result<Unit> {
        val targetUid = targetUser.userId
        val cleanUid = when (val r = InputValidator.validateId(targetUid)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val adminPath = "admins/$cleanUid"
        return try {
            val uid = requireUserId()
            val piiEmail = runCatching {
                db.collection("users").document(cleanUid).collection("private").document("pii").get().await()
                    .getString("email")
            }.getOrNull()?.takeIf { it.length in 3..120 } ?: "${cleanUid.take(12)}@unicore.edu"

            db.collection("admins").document(cleanUid).set(
                mapOf(
                    "userId" to cleanUid,
                    "email" to piiEmail,
                    "grantedBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.collection("users").document(cleanUid).update(
                mapOf(
                    "role" to UserRole.ADMIN.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "ROLE_CHANGED",
                severity = AuditSeverity.HIGH,
                targetPath = adminPath,
                details = "Super Admin promoted ${targetUser.displayName} to ADMIN."
            )
            sendNotification(
                title = "Admin Access Granted",
                body = "Your UniCore account has been promoted to Admin.",
                category = NotificationCategory.ROLE,
                targetUid = cleanUid
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, adminPath)
            Result.failure(e)
        }
    }

    suspend fun demoteAdminToStudent(targetUid: String): Result<Unit> {
        val docPath = "admins/$targetUid"
        return try {
            requireUserId()
            db.collection("admins").document(targetUid).delete().await()
            runCatching {
                db.collection("users").document(targetUid).update(
                    mapOf(
                        "role" to UserRole.STUDENT.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            logAuditEvent(
                action = "ROLE_CHANGED",
                severity = AuditSeverity.HIGH,
                targetPath = docPath,
                details = "Super Admin changed role of $targetUid to STUDENT."
            )
            sendNotification(
                title = "Role Updated",
                body = "Your UniCore role has been updated to Student.",
                category = NotificationCategory.ROLE,
                targetUid = targetUid
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun promoteUserToSuperAdmin(targetUser: UserProfile): Result<Unit> {
        val cleanUid = when (val r = InputValidator.validateId(targetUser.userId)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val superPath = "super_admins/$cleanUid"
        return try {
            requireUserId()
            val piiEmail = runCatching {
                db.collection("users").document(cleanUid).collection("private").document("pii").get().await()
                    .getString("email")
            }.getOrNull()?.takeIf { it.length in 3..120 } ?: "${cleanUid.take(12)}@unicore.edu"

            db.collection("super_admins").document(cleanUid).set(
                mapOf(
                    "userId" to cleanUid,
                    "email" to piiEmail,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            runCatching {
                db.collection("admins").document(cleanUid).delete().await()
            }

            db.collection("users").document(cleanUid).update(
                mapOf(
                    "role" to UserRole.SUPER_ADMIN.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "SUPER_ADMIN_GRANTED",
                severity = AuditSeverity.HIGH,
                targetPath = superPath,
                details = "Super Admin granted SUPER_ADMIN role to ${targetUser.displayName}."
            )
            sendNotification(
                title = "Super Admin Access Granted",
                body = "Your UniCore account has been promoted to Super Admin.",
                category = NotificationCategory.ROLE,
                targetUid = cleanUid
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, superPath)
            Result.failure(e)
        }
    }

    suspend fun demoteSuperAdminToAdmin(targetUid: String): Result<Unit> {
        val cleanUid = when (val r = InputValidator.validateId(targetUid)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val superPath = "super_admins/$cleanUid"
        return try {
            val actorUid = requireUserId()
            if (cleanUid == actorUid) {
                return Result.failure(IllegalArgumentException("You cannot demote your own Super Admin account."))
            }
            val piiEmail = runCatching {
                db.collection("users").document(cleanUid).collection("private").document("pii").get().await()
                    .getString("email")
            }.getOrNull()?.takeIf { it.length in 3..120 } ?: "${cleanUid.take(12)}@unicore.edu"

            db.collection("super_admins").document(cleanUid).delete().await()

            db.collection("admins").document(cleanUid).set(
                mapOf(
                    "userId" to cleanUid,
                    "email" to piiEmail,
                    "grantedBy" to actorUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            runCatching {
                db.collection("users").document(cleanUid).update(
                    mapOf(
                        "role" to UserRole.ADMIN.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            logAuditEvent(
                action = "ROLE_CHANGED",
                severity = AuditSeverity.HIGH,
                targetPath = superPath,
                details = "Super Admin changed $cleanUid from SUPER_ADMIN to ADMIN."
            )
            sendNotification(
                title = "Role Updated",
                body = "Your UniCore role has been updated to Admin.",
                category = NotificationCategory.ROLE,
                targetUid = cleanUid
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, superPath)
            Result.failure(e)
        }
    }

    suspend fun setUserAccountDisabled(targetUser: UserProfile, disabled: Boolean): Result<Unit> {
        val cleanUid = when (val r = InputValidator.validateId(targetUser.userId)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val docPath = "users/$cleanUid"
        return try {
            val actorUid = requireUserId()
            if (cleanUid == actorUid) {
                return Result.failure(IllegalArgumentException("You cannot disable your own Super Admin account."))
            }
            db.collection("users").document(cleanUid).update(
                mapOf(
                    "accountDisabled" to disabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "ACCOUNT_STATUS_CHANGED",
                severity = if (disabled) AuditSeverity.HIGH else AuditSeverity.INFO,
                targetPath = docPath,
                details = "Super Admin ${if (disabled) "disabled" else "re-enabled"} account ${targetUser.displayName}."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun setUserTimedBan(
        targetUser: UserProfile,
        durationMinutes: Long,
        reason: String
    ): Result<Unit> {
        val cleanUid = when (val r = InputValidator.validateId(targetUser.userId)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val docPath = "users/$cleanUid"
        return try {
            val actorUid = requireUserId()
            if (cleanUid == actorUid) {
                return Result.failure(IllegalArgumentException("You cannot ban your own account."))
            }
            ProfanityFilter.assertCleanText(reason)
            val cleanReason = InputValidator.sanitizeText(reason).take(200)
            val mins = durationMinutes.coerceAtLeast(0L)
            val banUntilMillis = if (mins > 0L) System.currentTimeMillis() + mins * 60_000L else 0L

            db.collection("users").document(cleanUid).update(
                mapOf(
                    "banUntilMillis" to banUntilMillis,
                    "banReason" to if (mins > 0L) cleanReason.ifBlank { "مخالفة قواعد الاستخدام" } else "",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "ACCOUNT_BANNED",
                severity = if (mins > 0L) AuditSeverity.HIGH else AuditSeverity.INFO,
                targetPath = docPath,
                details = if (mins > 0L) {
                    "Banned ${targetUser.displayName} for $mins minutes (${cleanReason.ifBlank { "policy violation" }})."
                } else {
                    "Lifted temporary ban for ${targetUser.displayName}."
                }
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveDepartment(
        existingDeptId: String?,
        rawName: String,
        rawYears: List<String>
    ): Result<Unit> {
        val cleanName = when (val r = InputValidator.validateBoundedText(rawName, "Department name", 2, 80)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val cleanYears = rawYears
            .map { InputValidator.sanitizeText(it).take(40) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
        if (cleanYears.isEmpty()) {
            return Result.failure(IllegalArgumentException("Provide at least 1 study year or level."))
        }
        val deptId = existingDeptId?.takeIf { it.isNotBlank() }
            ?: "dept_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docPath = "departments/$deptId"
        return try {
            val uid = requireUserId()
            if (existingDeptId.isNullOrBlank()) {
                db.collection("departments").document(deptId).set(
                    mapOf(
                        "name" to cleanName,
                        "years" to cleanYears,
                        "createdBy" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                db.collection("departments").document(deptId).update(
                    mapOf(
                        "name" to cleanName,
                        "years" to cleanYears,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            logAuditEvent(
                action = "DEPARTMENT_UPDATED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "${if (existingDeptId.isNullOrBlank()) "Created" else "Updated"} department '$cleanName' (${cleanYears.size} levels)."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (existingDeptId.isNullOrBlank()) OperationType.CREATE else OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteDepartment(deptId: String): Result<Unit> {
        val cleanId = when (val r = InputValidator.validateId(deptId)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val docPath = "departments/$cleanId"
        return try {
            requireUserId()
            db.collection("departments").document(cleanId).delete().await()
            logAuditEvent(
                action = "DEPARTMENT_UPDATED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Deleted academic department."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun linkDriveFolder(
        rawTitle: String,
        rawUrl: String,
        departmentId: String? = null,
        departmentName: String? = null,
        academicYear: String? = null
    ): Result<Unit> {
        val cleanTitle = when (val r = InputValidator.validateBoundedText(rawTitle, "Folder title", 2, 100)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val (folderId, canonicalUrl) = when (val r = InputValidator.extractGoogleDriveFolder(rawUrl)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }

        val docPath = "drive_folders/$folderId"
        return try {
            val uid = requireUserId()
            val addedByName = InputValidator.sanitizeText(auth.currentUser?.displayName ?: "Admin")
                .take(60)
                .ifBlank { "Admin" }
                .let { if (it.length < 2) "Admin" else it }

            val payload = mutableMapOf<String, Any>(
                "folderId" to folderId,
                "title" to cleanTitle,
                "folderUrl" to canonicalUrl,
                "addedBy" to uid,
                "addedByName" to addedByName,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!departmentId.isNullOrBlank()) payload["departmentId"] = departmentId
            if (!departmentName.isNullOrBlank() && departmentName.length >= 2) {
                payload["departmentName"] = InputValidator.sanitizeText(departmentName).take(80)
            }
            if (!academicYear.isNullOrBlank()) {
                payload["academicYear"] = InputValidator.sanitizeText(academicYear).take(40)
            }

            db.collection("drive_folders").document(folderId).set(payload).await()

            logAuditEvent(
                action = "DRIVE_FOLDER_LINKED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Connected cloud storage folder '$cleanTitle'."
            )
            sendNotification(
                title = cleanTitle,
                body = "New study materials are now available in the Courses library ($cleanTitle).",
                category = NotificationCategory.COURSE,
                targetUid = "ALL"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateDriveFolder(
        existingFolderId: String,
        rawTitle: String,
        rawUrl: String,
        departmentId: String? = null,
        departmentName: String? = null,
        academicYear: String? = null
    ): Result<Unit> {
        val cleanOldId = when (val r = InputValidator.validateId(existingFolderId)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val cleanTitle = when (val r = InputValidator.validateBoundedText(rawTitle, "Folder title", 2, 100)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val (newFolderId, canonicalUrl) = when (val r = InputValidator.extractGoogleDriveFolder(rawUrl)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }

        if (newFolderId != cleanOldId) {
            val createRes = linkDriveFolder(cleanTitle, canonicalUrl, departmentId, departmentName, academicYear)
            return if (createRes.isSuccess) {
                unlinkDriveFolder(cleanOldId)
            } else {
                createRes
            }
        }

        val docPath = "drive_folders/$cleanOldId"
        return try {
            requireUserId()
            val updates = mutableMapOf<String, Any>(
                "title" to cleanTitle,
                "folderUrl" to canonicalUrl,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            updates["departmentId"] = departmentId?.takeIf { it.isNotBlank() } ?: FieldValue.delete()
            updates["departmentName"] = departmentName
                ?.takeIf { InputValidator.sanitizeText(it).length >= 2 }
                ?.let { InputValidator.sanitizeText(it).take(80) }
                ?: FieldValue.delete()
            updates["academicYear"] = academicYear
                ?.takeIf { InputValidator.sanitizeText(it).isNotBlank() }
                ?.let { InputValidator.sanitizeText(it).take(40) }
                ?: FieldValue.delete()

            db.collection("drive_folders").document(cleanOldId).update(updates).await()

            logAuditEvent(
                action = "DRIVE_FOLDER_UPDATED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Super Admin updated storage folder '$cleanTitle'."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun unlinkDriveFolder(folderId: String): Result<Unit> {
        val cleanId = when (val r = InputValidator.validateId(folderId)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val docPath = "drive_folders/$cleanId"
        return try {
            requireUserId()
            db.collection("drive_folders").document(cleanId).delete().await()
            logAuditEvent(
                action = "DRIVE_FOLDER_REMOVED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Super Admin removed storage folder."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun sendSupportMessage(
        channel: SupportChannel,
        senderRole: UserRole,
        rawSubject: String,
        rawBody: String,
        attachment: ImageAttachmentHelper.CompressedImage? = null
    ): Result<Unit> {
        val cleanSubject = when (val r = InputValidator.validateBoundedText(rawSubject, "Subject", 2, 100)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val cleanBody = when (val r = InputValidator.validateBoundedText(rawBody, "Message", 2, 800)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val msgId = "msg_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docPath = "support_messages/$msgId"
        return try {
            val uid = requireUserId()
            val senderName = InputValidator.sanitizeText(auth.currentUser?.displayName ?: "UniCore User")
                .take(60)
                .ifBlank { "UniCore User" }
                .let { if (it.length < 2) "User" else it }

            val safeSenderRole = if (senderRole == UserRole.ADMIN) UserRole.ADMIN.name else UserRole.STUDENT.name
            val payload = mutableMapOf<String, Any>(
                "channel" to channel.name,
                "senderUid" to uid,
                "senderName" to senderName,
                "senderRole" to safeSenderRole,
                "subject" to cleanSubject,
                "body" to cleanBody,
                "status" to "OPEN",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (attachment != null) {
                payload["attachmentBase64"] = attachment.base64
                payload["attachmentMimeType"] = attachment.mimeType
                payload["attachmentName"] = attachment.fileName
            }

            db.collection("support_messages").document(msgId).set(payload).await()

            logAuditEvent(
                action = "SUPPORT_MESSAGE_SENT",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Support inquiry (${channel.name}): $cleanSubject"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun replyToSupportMessage(
        message: SupportMessage,
        rawReply: String,
        attachment: ImageAttachmentHelper.CompressedImage? = null
    ): Result<Unit> {
        val cleanReply = when (val r = InputValidator.validateBoundedText(rawReply, "Reply", 1, 800)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val docPath = "support_messages/${message.id}"
        return try {
            val uid = requireUserId()
            val replierName = InputValidator.sanitizeText(auth.currentUser?.displayName ?: "Administrator")
                .take(60)
                .ifBlank { "Administrator" }
                .let { if (it.length < 2) "Admin" else it }

            val updates = mutableMapOf<String, Any>(
                "status" to "REPLIED",
                "replyText" to cleanReply,
                "repliedByUid" to uid,
                "repliedByName" to replierName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (attachment != null) {
                updates["replyAttachmentBase64"] = attachment.base64
                updates["replyAttachmentMimeType"] = attachment.mimeType
                updates["replyAttachmentName"] = attachment.fileName
            }

            db.collection("support_messages").document(message.id).update(updates).await()

            sendNotification(
                title = "Reply: ${message.subject}",
                body = "$replierName replied to your support message.",
                category = NotificationCategory.ANNOUNCEMENT,
                targetUid = message.senderUid
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- Department & Academic Year Group Chat ---

    fun buildChatRoomId(departmentId: String?, yearIndex: Int?): String {
        if (departmentId.isNullOrBlank() || yearIndex == null) return "general"
        return "dept_${departmentId}_y_$yearIndex"
    }

    fun observeChatMessages(roomId: String): Flow<List<StudyChatMessage>> = flow {
        val uid = requireUserId()
        val safeRoomId = if (roomId.matches(Regex("^[a-zA-Z0-9_\\-]{1,128}$"))) roomId else "general"
        val path = "chat_rooms/$safeRoomId/messages"
        emitAll(
            db.collection("chat_rooms").document(safeRoomId).collection("messages")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(80)
                .snapshots()
                .map { snap ->
                    snap.toObjects(StudyChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .reversed()
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    suspend fun sendChatMessage(
        roomId: String,
        departmentName: String?,
        academicYear: String?,
        senderRole: UserRole,
        rawText: String,
        attachment: ImageAttachmentHelper.CompressedImage?
    ): Result<Unit> {
        val safeRoomId = if (roomId.matches(Regex("^[a-zA-Z0-9_\\-]{1,128}$"))) roomId else "general"
        val msgId = "chat_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docPath = "chat_rooms/$safeRoomId/messages/$msgId"
        return try {
            val uid = requireUserId()
            ProfanityFilter.assertCleanText(rawText)
            if (InputValidator.containsInjectionPayload(rawText)) {
                return Result.failure(IllegalArgumentException("Message contains disallowed syntax."))
            }
            val cleanText = InputValidator.sanitizeText(rawText).take(800)
            if (cleanText.isBlank() && attachment == null) {
                return Result.failure(IllegalArgumentException("Enter a message or attach an image."))
            }

            val senderName = InputValidator.sanitizeText(auth.currentUser?.displayName ?: "UniCore User")
                .take(60)
                .ifBlank { "UniCore User" }
                .let { if (it.length < 2) "User" else it }

            val payload = mutableMapOf<String, Any>(
                "roomId" to safeRoomId,
                "senderUid" to uid,
                "senderName" to senderName,
                "senderRole" to senderRole.name,
                "text" to cleanText,
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (!departmentName.isNullOrBlank()) {
                payload["departmentName"] = InputValidator.sanitizeText(departmentName).take(80)
            }
            if (!academicYear.isNullOrBlank()) {
                payload["academicYear"] = InputValidator.sanitizeText(academicYear).take(40)
            }
            if (attachment != null) {
                payload["attachmentBase64"] = attachment.base64
                payload["attachmentMimeType"] = attachment.mimeType
                payload["attachmentName"] = attachment.fileName
            }

            db.collection("chat_rooms").document(safeRoomId).collection("messages").document(msgId)
                .set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteChatMessage(roomId: String, messageId: String): Result<Unit> {
        val safeRoomId = if (roomId.matches(Regex("^[a-zA-Z0-9_\\-]{1,128}$"))) roomId else "general"
        val docPath = "chat_rooms/$safeRoomId/messages/$messageId"
        return try {
            requireUserId()
            db.collection("chat_rooms").document(safeRoomId).collection("messages").document(messageId)
                .delete().await()
            logAuditEvent(
                action = "CHAT_MESSAGE_DELETED",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Deleted chat message."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun sendNotification(
        title: String,
        body: String,
        category: NotificationCategory,
        targetUid: String
    ): Result<Unit> {
        val cleanTitle = when (val r = InputValidator.validateBoundedText(title, "Notification title", 2, 100)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val cleanBody = when (val r = InputValidator.validateBoundedText(body, "Notification message", 2, 400)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        val safeTarget = if (targetUid == "ALL") {
            "ALL"
        } else {
            when (val r = InputValidator.validateId(targetUid)) {
                is InputValidator.ValidationResult.Valid -> r.value
                is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
        }
        val notifId = "notif_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docPath = "notifications/$notifId"
        return try {
            val uid = requireUserId()
            val senderName = InputValidator.sanitizeText(auth.currentUser?.displayName ?: "UniCore Admin")
                .take(60)
                .ifBlank { "UniCore Admin" }
                .let { if (it.length < 2) "Admin" else it }

            db.collection("notifications").document(notifId).set(
                mapOf(
                    "title" to cleanTitle,
                    "body" to cleanBody,
                    "category" to category.name,
                    "targetUid" to safeTarget,
                    "senderUid" to uid,
                    "senderName" to senderName,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()

            logAuditEvent(
                action = "NOTIFICATION_SENT",
                severity = AuditSeverity.INFO,
                targetPath = docPath,
                details = "Sent ${category.name} notification to $safeTarget."
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
        zeroCostLockActive: Boolean,
        tutorialVideoUrl: String? = null,
        tutorialVideoTitle: String? = null
    ): Result<Unit> {
        val cleanTutorialUrl = when (val r = InputValidator.validateOptionalHttpsUrl(tutorialVideoUrl)) {
            is InputValidator.ValidationResult.Valid -> r.value
            is InputValidator.ValidationResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        if (!tutorialVideoTitle.isNullOrBlank() && InputValidator.containsInjectionPayload(tutorialVideoTitle)) {
            return Result.failure(IllegalArgumentException("Tutorial title contains disallowed characters."))
        }
        val cleanTutorialTitle = tutorialVideoTitle?.let { InputValidator.sanitizeText(it).take(120) }?.takeIf { it.isNotBlank() }

        val docPath = "system_settings/global"
        return try {
            val uid = requireUserId()
            val updates = mutableMapOf<String, Any>(
                "dailyAiLimitPerUser" to dailyAiLimit.coerceIn(1L, 100L),
                "dailyWriteLimitPerUser" to dailyWriteLimit.coerceIn(10L, 500L),
                "aiEnabled" to aiEnabled,
                "zeroCostLockActive" to zeroCostLockActive,
                "updatedBy" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            updates["tutorialVideoUrl"] = cleanTutorialUrl ?: FieldValue.delete()
            updates["tutorialVideoTitle"] = cleanTutorialTitle ?: FieldValue.delete()

            db.collection("system_settings").document("global").update(updates).await()

            logAuditEvent(
                action = if (cleanTutorialUrl != null) "TUTORIAL_UPDATED" else "SETTINGS_CHANGED",
                severity = AuditSeverity.HIGH,
                targetPath = docPath,
                details = "Super Admin updated settings: aiLimit=$dailyAiLimit, aiEnabled=$aiEnabled, lock=$zeroCostLockActive, hasTutorial=${cleanTutorialUrl != null}"
            )
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
