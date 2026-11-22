package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class UserRole {
    STUDENT,
    ADMIN,
    SUPER_ADMIN;

    companion object {
        fun fromRaw(raw: String?): UserRole {
            return entries.firstOrNull { it.name == raw } ?: STUDENT
        }
    }
}

enum class MaterialType {
    NOTES,
    PDF_LINK,
    ONEDRIVE_LINK;

    companion object {
        fun fromRaw(raw: String?): MaterialType {
            return entries.firstOrNull { it.name == raw } ?: NOTES
        }
    }
}

enum class AuditSeverity {
    INFO,
    WARN,
    HIGH,
    CRITICAL;

    companion object {
        fun fromRaw(raw: String?): AuditSeverity {
            return entries.firstOrNull { it.name == raw } ?: INFO
        }
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val role: String = UserRole.STUDENT.name,
    val aiCallsToday: Long = 0L,
    val writesToday: Long = 0L,
    val quotaDayKey: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SystemSettings(
    @DocumentId val id: String = "",
    val dailyAiLimitPerUser: Long = 20L,
    val dailyWriteLimitPerUser: Long = 100L,
    val maxFileUploadBytes: Long = 5_242_880L,
    val aiEnabled: Boolean = true,
    val zeroCostLockActive: Boolean = false,
    val updatedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AdminRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val grantedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Course(
    @DocumentId val id: String = "",
    val title: String = "",
    val description: String = "",
    val instructorId: String = "",
    val instructorName: String = "",
    val materialLink: String? = null,
    val materialType: String = MaterialType.NOTES.name,
    val published: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Quiz(
    @DocumentId val id: String = "",
    val courseId: String = "",
    val title: String = "",
    val durationMinutes: Long = 15L,
    val questionPrompts: List<String> = emptyList(),
    val optionBlocks: List<String> = emptyList(),
    val published: Boolean = true,
    val createdBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class QuizAnswerKey(
    @DocumentId val id: String = "",
    val quizId: String = "",
    val correctIndices: List<Long> = emptyList(),
    val passingScore: Long = 70L,
    val updatedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class QuizAttempt(
    @DocumentId val id: String = "",
    val userId: String = "",
    val studentName: String = "",
    val quizId: String = "",
    val quizTitle: String = "",
    val selectedIndices: List<Long> = emptyList(),
    val status: String = "SUBMITTED",
    val score: Long = 0L,
    val graded: Boolean = false,
    val gradedBy: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VerifiedFileRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val originalName: String = "",
    val storedName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val sha256Hex: String = "",
    val status: String = "VERIFIED",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val actorUid: String = "",
    val actorLabel: String = "",
    val action: String = "",
    val severity: String = AuditSeverity.INFO.name,
    val targetPath: String = "",
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

enum class InviteStatus {
    PENDING,
    CLAIMED;

    companion object {
        fun fromRaw(raw: String?): InviteStatus {
            return entries.firstOrNull { it.name == raw } ?: PENDING
        }
    }
}

enum class NotificationCategory {
    COURSE,
    QUIZ,
    GRADE,
    ROLE,
    SECURITY,
    ANNOUNCEMENT;

    companion object {
        fun fromRaw(raw: String?): NotificationCategory {
            return entries.firstOrNull { it.name == raw } ?: ANNOUNCEMENT
        }
    }
}

@Keep
@IgnoreExtraProperties
data class RoleInvite(
    @DocumentId val id: String = "",
    val email: String = "",
    val displayNameHint: String = "",
    val assignedRole: String = UserRole.STUDENT.name,
    val invitedBy: String = "",
    val invitedByLabel: String = "",
    val status: String = InviteStatus.PENDING.name,
    val claimedByUid: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UniNotification(
    @DocumentId val id: String = "",
    val title: String = "",
    val body: String = "",
    val category: String = NotificationCategory.ANNOUNCEMENT.name,
    val targetUid: String = "ALL",
    val senderUid: String = "",
    val senderName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

