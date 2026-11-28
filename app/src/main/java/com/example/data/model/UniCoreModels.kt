package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

enum class UserRole {
    STUDENT,
    ADMIN,
    SUPER_ADMIN;

    companion object {
        fun fromRaw(raw: String?): UserRole {
            val normalized = raw?.trim()?.uppercase()?.replace('-', '_')
            return when (normalized) {
                "SUPER_ADMIN", "SUPERADMIN" -> SUPER_ADMIN
                else -> entries.firstOrNull { it.name == normalized } ?: STUDENT
            }
        }
    }
}

enum class MaterialType {
    FILE,
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

enum class ScheduleItemType {
    LECTURE,
    SECTION,
    ASSIGNMENT,
    EXAM;

    companion object {
        fun fromRaw(raw: String?): ScheduleItemType {
            return entries.firstOrNull { it.name == raw } ?: LECTURE
        }
    }
}

enum class InstructionPriority {
    NORMAL,
    IMPORTANT,
    PINNED;

    companion object {
        fun fromRaw(raw: String?): InstructionPriority {
            return entries.firstOrNull { it.name == raw } ?: NORMAL
        }
    }
}

enum class SectionMessageType {
    NOTICE,
    MESSAGE;

    companion object {
        fun fromRaw(raw: String?): SectionMessageType {
            return entries.firstOrNull { it.name == raw } ?: MESSAGE
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
    val accountDisabled: Boolean = false,
    val banUntilMillis: Long = 0L,
    val banReason: String = "",
    val departmentId: String? = null,
    val departmentName: String? = null,
    val academicYear: String? = null,
    val sectionName: String? = null,
    val placementCompleted: Boolean = false,
    val favoriteCourseIds: List<String> = emptyList(),
    val completedAssignmentIds: List<String> = emptyList(),
    val classroomConnected: Boolean = false,
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
    val tutorialVideoUrl: String? = null,
    val tutorialVideoTitle: String? = null,
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
    val hasAttachment: Boolean = false,
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentSizeBytes: Long = 0L,
    val chunkCount: Long = 0L,
    val attachmentSha256: String? = null,
    val departmentId: String? = null,
    val departmentName: String? = null,
    val academicYear: String? = null,
    val sectionName: String? = null,
    val videoUrl: String? = null,
    val videoTitle: String? = null,
    val published: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DriveFolderLink(
    @DocumentId val id: String = "",
    val folderId: String = "",
    val title: String = "",
    val folderUrl: String = "",
    val departmentId: String? = null,
    val departmentName: String? = null,
    val academicYear: String? = null,
    val addedBy: String = "",
    val addedByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AcademicDepartment(
    @DocumentId val id: String = "",
    val name: String = "",
    val years: List<String> = emptyList(),
    val createdBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class DriveSyncedFile(
    val fileId: String,
    val folderId: String,
    val folderTitle: String,
    val name: String,
    val isSubFolder: Boolean = false,
    val mimeHint: String = "application/octet-stream",
    val viewUrl: String,
    val downloadUrl: String,
    val subFolderPath: String = "",
    val rootFolderTitle: String = folderTitle
)

@Keep
@IgnoreExtraProperties
data class AssignmentItem(
    @DocumentId val id: String = "",
    val title: String = "",
    val description: String = "",
    val courseId: String? = null,
    val courseTitle: String? = null,
    val departmentId: String? = null,
    val departmentName: String? = null,
    val academicYear: String? = null,
    val sectionName: String? = null,
    val dueAtMillis: Long = 0L,
    val classroomLink: String? = null,
    val videoUrl: String? = null,
    val sourceType: String = "APP",
    val ownerUid: String = "ALL",
    val published: Boolean = true,
    val createdBy: String = "",
    val createdByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isOverdue(nowMillis: Long = System.currentTimeMillis()): Boolean =
        dueAtMillis > 0L && nowMillis > dueAtMillis

    fun isDueSoon(nowMillis: Long = System.currentTimeMillis()): Boolean =
        dueAtMillis > 0L && dueAtMillis >= nowMillis && (dueAtMillis - nowMillis) <= 24L * 60L * 60L * 1000L
}

@Keep
@IgnoreExtraProperties
data class ScheduleItem(
    @DocumentId val id: String = "",
    val title: String = "",
    val itemType: String = ScheduleItemType.LECTURE.name,
    val dayOfWeek: Long = 0L,
    val startTimeText: String = "",
    val endTimeText: String = "",
    val locationOrMode: String = "",
    val instructorName: String? = null,
    val departmentId: String? = null,
    val departmentName: String? = null,
    val academicYear: String? = null,
    val sectionName: String? = null,
    val courseId: String? = null,
    val videoUrl: String? = null,
    val dueAtMillis: Long = 0L,
    val published: Boolean = true,
    val createdBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ZoomSession(
    @DocumentId val id: String = "",
    val title: String = "",
    val topic: String? = null,
    val zoomUrl: String = "",
    val scheduledAtMillis: Long = 0L,
    val durationMinutes: Long = 60L,
    val hostName: String = "",
    val departmentId: String? = null,
    val departmentName: String? = null,
    val academicYear: String? = null,
    val sectionName: String? = null,
    val courseId: String? = null,
    val published: Boolean = true,
    val createdBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class InstructionNotice(
    @DocumentId val id: String = "",
    val title: String = "",
    val body: String = "",
    val priority: String = InstructionPriority.NORMAL.name,
    val departmentId: String? = null,
    val departmentName: String? = null,
    val academicYear: String? = null,
    val sectionName: String? = null,
    val published: Boolean = true,
    val createdBy: String = "",
    val createdByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SectionMessage(
    @DocumentId val id: String = "",
    val departmentId: String? = null,
    val departmentName: String = "",
    val academicYear: String = "",
    val sectionName: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val senderRole: String = UserRole.STUDENT.name,
    val messageType: String = SectionMessageType.MESSAGE.name,
    @get:PropertyName("isPinned")
    @field:PropertyName("isPinned")
    val pinned: Boolean = false,
    val text: String = "",
    val videoUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Quiz(
    @DocumentId val id: String = "",
    val courseId: String = "",
    val title: String = "",
    val durationMinutes: Long = 15L,
    val startAtMillis: Long = 0L,
    val endAtMillis: Long = 0L,
    val hasImage: Boolean = false,
    val imageName: String = "",
    val imageMimeType: String = "",
    val chunkCount: Long = 0L,
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
    val cancellationReason: String = "",
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
    ANNOUNCEMENT,
    ASSIGNMENT,
    ZOOM;

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

enum class SupportChannel {
    STUDENT_TO_ADMIN,
    ADMIN_TO_SUPER;

    companion object {
        fun fromRaw(raw: String?): SupportChannel {
            return entries.firstOrNull { it.name == raw } ?: STUDENT_TO_ADMIN
        }
    }
}

@Keep
@IgnoreExtraProperties
data class SupportMessage(
    @DocumentId val id: String = "",
    val channel: String = SupportChannel.STUDENT_TO_ADMIN.name,
    val senderUid: String = "",
    val senderName: String = "",
    val senderRole: String = UserRole.STUDENT.name,
    val subject: String = "",
    val body: String = "",
    val attachmentBase64: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentName: String? = null,
    val status: String = "OPEN",
    val replyText: String? = null,
    val replyAttachmentBase64: String? = null,
    val replyAttachmentMimeType: String? = null,
    val replyAttachmentName: String? = null,
    val repliedByUid: String? = null,
    val repliedByName: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class StudyChatMessage(
    @DocumentId val id: String = "",
    val roomId: String = "general",
    val departmentName: String? = null,
    val academicYear: String? = null,
    val senderUid: String = "",
    val senderName: String = "",
    val senderRole: String = UserRole.STUDENT.name,
    val text: String = "",
    val attachmentBase64: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentName: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

object AcademicScopeFilter {
    fun matchesStudentScope(
        userProfile: UserProfile?,
        userRole: UserRole,
        itemDeptId: String?,
        itemDeptName: String?,
        itemYear: String?,
        itemSection: String?
    ): Boolean {
        if (userRole != UserRole.STUDENT) return true
        val profile = userProfile ?: return true
        if (!profile.placementCompleted) return true

        val deptMatches = when {
            itemDeptId.isNullOrBlank() && itemDeptName.isNullOrBlank() -> true
            itemDeptId.equals("ALL", ignoreCase = true) || itemDeptName.equals("ALL", ignoreCase = true) -> true
            profile.departmentId.isNullOrBlank() && profile.departmentName.isNullOrBlank() -> true
            else -> (!profile.departmentId.isNullOrBlank() && profile.departmentId == itemDeptId) ||
                (!profile.departmentName.isNullOrBlank() && itemDeptName.equals(profile.departmentName, ignoreCase = true))
        }
        val yearMatches = when {
            itemYear.isNullOrBlank() -> true
            itemYear.equals("ALL", ignoreCase = true) -> true
            profile.academicYear.isNullOrBlank() -> true
            else -> itemYear.equals(profile.academicYear, ignoreCase = true)
        }
        val sectionMatches = when {
            itemSection.isNullOrBlank() -> true
            itemSection.equals("ALL", ignoreCase = true) -> true
            profile.sectionName.isNullOrBlank() -> true
            else -> itemSection.equals(profile.sectionName, ignoreCase = true)
        }
        return deptMatches && yearMatches && sectionMatches
    }
}



