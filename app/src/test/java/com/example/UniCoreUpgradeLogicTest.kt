package com.example

import com.example.data.model.AcademicScopeFilter
import com.example.data.model.AssignmentItem
import com.example.data.model.InstructionPriority
import com.example.data.model.ScheduleItemType
import com.example.data.model.SectionMessageType
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.security.InputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniCoreUpgradeLogicTest {

    @Test
    fun userRole_fromRaw_normalizesLowercaseAndHyphenatedSuperAdmin() {
        assertEquals(UserRole.SUPER_ADMIN, UserRole.fromRaw("super_admin"))
        assertEquals(UserRole.SUPER_ADMIN, UserRole.fromRaw("SUPER-ADMIN"))
        assertEquals(UserRole.SUPER_ADMIN, UserRole.fromRaw("superadmin"))
        assertEquals(UserRole.ADMIN, UserRole.fromRaw("admin"))
        assertEquals(UserRole.STUDENT, UserRole.fromRaw("student"))
        assertEquals(UserRole.STUDENT, UserRole.fromRaw(null))
    }

    @Test
    fun academicScopeFilter_isolatesDepartmentsYearsAndSectionsWhileAllowingGlobals() {
        val studentProfile = UserProfile(
            userId = "stu1",
            displayName = "Ahmed",
            role = "STUDENT",
            departmentId = "dept_cs",
            departmentName = "علوم الحاسب",
            academicYear = "الفرقة الثانية",
            sectionName = "سكشن 3",
            placementCompleted = true
        )

        // Exact match passes
        assertTrue(
            AcademicScopeFilter.matchesStudentScope(
                userProfile = studentProfile,
                userRole = UserRole.STUDENT,
                itemDeptId = "dept_cs",
                itemDeptName = "علوم الحاسب",
                itemYear = "الفرقة الثانية",
                itemSection = "سكشن 3"
            )
        )

        // Global / unscoped item passes
        assertTrue(
            AcademicScopeFilter.matchesStudentScope(
                userProfile = studentProfile,
                userRole = UserRole.STUDENT,
                itemDeptId = "ALL",
                itemDeptName = null,
                itemYear = "ALL",
                itemSection = "ALL"
            )
        )

        // Same year, different section blocked
        assertFalse(
            AcademicScopeFilter.matchesStudentScope(
                userProfile = studentProfile,
                userRole = UserRole.STUDENT,
                itemDeptId = "dept_cs",
                itemDeptName = "علوم الحاسب",
                itemYear = "الفرقة الثانية",
                itemSection = "سكشن 1"
            )
        )

        // Different year blocked
        assertFalse(
            AcademicScopeFilter.matchesStudentScope(
                userProfile = studentProfile,
                userRole = UserRole.STUDENT,
                itemDeptId = "dept_cs",
                itemDeptName = "علوم الحاسب",
                itemYear = "الفرقة الثالثة",
                itemSection = null
            )
        )

        // Different department blocked
        assertFalse(
            AcademicScopeFilter.matchesStudentScope(
                userProfile = studentProfile,
                userRole = UserRole.STUDENT,
                itemDeptId = "dept_ee",
                itemDeptName = "كهرباء",
                itemYear = "الفرقة الثانية",
                itemSection = null
            )
        )

        // Admins bypass student filtering so they can manage everything
        val adminProfile = studentProfile.copy(role = "ADMIN")
        assertTrue(
            AcademicScopeFilter.matchesStudentScope(
                userProfile = adminProfile,
                userRole = UserRole.ADMIN,
                itemDeptId = "dept_ee",
                itemDeptName = "كهرباء",
                itemYear = "الفرقة الرابعة",
                itemSection = "سكشن 9"
            )
        )
    }

    @Test
    fun assignmentStatus_identifiesDoneDueSoonOverdueAndUpcoming() {
        val now = 1_700_000_000_000L
        val sixHours = 6L * 60L * 60L * 1000L
        val threeDays = 3L * 24L * 60L * 60L * 1000L

        val upcoming = AssignmentItem(id = "a1", dueAtMillis = now + threeDays)
        assertFalse(upcoming.isOverdue(now))
        assertFalse(upcoming.isDueSoon(now))

        val dueSoon = AssignmentItem(id = "a2", dueAtMillis = now + sixHours)
        assertFalse(dueSoon.isOverdue(now))
        assertTrue(dueSoon.isDueSoon(now))

        val overdue = AssignmentItem(id = "a3", dueAtMillis = now - 60_000L)
        assertTrue(overdue.isOverdue(now))
        assertFalse(overdue.isDueSoon(now))
    }

    @Test
    fun enumHelpers_safelyDeserializeCampusEnums() {
        assertEquals(ScheduleItemType.LECTURE, ScheduleItemType.fromRaw("LECTURE"))
        assertEquals(ScheduleItemType.SECTION, ScheduleItemType.fromRaw("SECTION"))
        assertEquals(ScheduleItemType.EXAM, ScheduleItemType.fromRaw("EXAM"))
        assertEquals(InstructionPriority.PINNED, InstructionPriority.fromRaw("PINNED"))
        assertEquals(SectionMessageType.NOTICE, SectionMessageType.fromRaw("NOTICE"))
    }

    @Test
    fun httpsUrlValidation_acceptsYoutubeAndZoomLinksWhileBlockingUnsafeUrls() {
        assertTrue(InputValidator.validateOptionalHttpsUrl("https://youtu.be/dQw4w9WgXcQ") is InputValidator.ValidationResult.Valid)
        assertTrue(InputValidator.validateOptionalHttpsUrl("https://zoom.us/j/123456789") is InputValidator.ValidationResult.Valid)
        assertTrue(InputValidator.validateOptionalHttpsUrl("https://classroom.google.com/c/abc") is InputValidator.ValidationResult.Valid)
        assertTrue(InputValidator.validateOptionalHttpsUrl("http://zoom.us/j/123456789") is InputValidator.ValidationResult.Invalid)
        assertTrue(InputValidator.validateOptionalHttpsUrl("javascript:alert(1)") is InputValidator.ValidationResult.Invalid)
    }
}
