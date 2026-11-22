package com.example.data.repository

import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.example.data.model.AdminRecord
import com.example.data.model.AuditLog
import com.example.data.model.AuditSeverity
import com.example.data.model.NotificationCategory
import com.example.data.model.RoleInvite
import com.example.data.model.SystemSettings
import com.example.data.model.UniNotification
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.security.InputValidator
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

    suspend fun inviteAccountByEmail(
        rawEmail: String,
        rawNameHint: String,
        assignedRole: UserRole
    ): Result<Unit> {
        val email = rawEmail.trim().lowercase(Locale.ROOT)
        if (email.length !in 3..120 || !emailRegex.matches(email)) {
            return Result.failure(IllegalArgumentException("Enter a valid Google email address."))
        }
        if (assignedRole == UserRole.SUPER_ADMIN) {
            return Result.failure(IllegalArgumentException("Super Admin role cannot be assigned via invite."))
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
