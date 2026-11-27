package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AcademicDepartment
import com.example.data.model.AcademicScopeFilter
import com.example.data.model.AssignmentItem
import com.example.data.model.Course
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.security.InputValidator
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AssignmentFilterTab { ALL, PENDING, DONE, CLASSROOM }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyDueAssignmentsScreen(
    currentUserId: String,
    userRole: UserRole,
    userProfile: UserProfile?,
    assignmentsState: UiState<List<AssignmentItem>>,
    coursesState: UiState<List<Course>>,
    departmentsState: UiState<List<AcademicDepartment>>,
    onToggleCompleted: (String) -> Unit,
    onCreateAssignment: (
        title: String,
        description: String,
        dueAtMillis: Long,
        courseId: String?,
        courseTitle: String?,
        departmentId: String?,
        departmentName: String?,
        academicYear: String?,
        sectionName: String?,
        classroomLink: String?,
        videoUrl: String?
    ) -> Unit,
    onImportClassroomTask: (title: String, courseTitle: String, dueAtMillis: Long, classroomUrl: String?) -> Unit,
    onDeleteAssignment: (String) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val canManage = userRole != UserRole.STUDENT
    val completedIds = remember(userProfile?.completedAssignmentIds) {
        userProfile?.completedAssignmentIds?.toSet() ?: emptySet()
    }
    val courses = (coursesState as? UiState.Success)?.data.orEmpty()
    val departments = (departmentsState as? UiState.Success)?.data.orEmpty()

    var filterTab by remember { mutableStateOf(AssignmentFilterTab.ALL) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showClassroomDialog by remember { mutableStateOf(false) }
    var selectedDeptFilterId by remember { mutableStateOf<String?>(null) }

    val openHttps: (String) -> Unit = { rawUrl ->
        val valid = InputValidator.validateOptionalHttpsUrl(rawUrl)
        if (valid is InputValidator.ValidationResult.Valid && valid.value != null) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(valid.value)))
            }.onFailure {
                Toast.makeText(context, "No viewer app found.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero Header + Classroom Sync + Admin Add
        item(key = "assignments_header") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.assignmentsTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = strings.assignmentsSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (userProfile?.classroomConnected == true) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = strings.classroomConnectedBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { showClassroomDialog = true },
                            modifier = Modifier.testTag("sync_classroom_button")
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(strings.connectClassroomAction)
                        }
                        if (canManage) {
                            Button(
                                onClick = { showCreateDialog = true },
                                modifier = Modifier.testTag("add_assignment_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(strings.addAssignmentAction)
                            }
                        }
                    }
                }
            }
        }

        // Filter Chips
        item(key = "assignments_filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filterTab == AssignmentFilterTab.ALL,
                        onClick = { filterTab = AssignmentFilterTab.ALL },
                        label = { Text(strings.allAssignmentsFilter) },
                        modifier = Modifier.testTag("filter_assignments_all")
                    )
                    FilterChip(
                        selected = filterTab == AssignmentFilterTab.PENDING,
                        onClick = { filterTab = AssignmentFilterTab.PENDING },
                        label = { Text(strings.pendingAssignmentsFilter) },
                        modifier = Modifier.testTag("filter_assignments_pending")
                    )
                    FilterChip(
                        selected = filterTab == AssignmentFilterTab.DONE,
                        onClick = { filterTab = AssignmentFilterTab.DONE },
                        label = { Text(strings.doneAssignmentsFilter) },
                        modifier = Modifier.testTag("filter_assignments_done")
                    )
                    FilterChip(
                        selected = filterTab == AssignmentFilterTab.CLASSROOM,
                        onClick = { filterTab = AssignmentFilterTab.CLASSROOM },
                        label = { Text(strings.classroomFilter) },
                        modifier = Modifier.testTag("filter_assignments_classroom")
                    )
                }

                if (canManage && departments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedDeptFilterId == null,
                            onClick = { selectedDeptFilterId = null },
                            label = { Text(strings.allDepartments) }
                        )
                        departments.forEach { dept ->
                            FilterChip(
                                selected = selectedDeptFilterId == dept.id,
                                onClick = {
                                    selectedDeptFilterId = if (selectedDeptFilterId == dept.id) null else dept.id
                                },
                                label = { Text(dept.name) }
                            )
                        }
                    }
                }
            }
        }

        when (assignmentsState) {
            is UiState.Loading -> item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> item { ErrorBannerCard(assignmentsState.message) }
            is UiState.Success -> {
                val nowMs = System.currentTimeMillis()
                val scopedList = assignmentsState.data.filter { item ->
                    val inUserScope = AcademicScopeFilter.matchesStudentScope(
                        userProfile = userProfile,
                        userRole = userRole,
                        itemDeptId = item.departmentId,
                        itemDeptName = item.departmentName,
                        itemYear = item.academicYear,
                        itemSection = item.sectionName
                    )
                    val matchesAdminDept = selectedDeptFilterId == null || item.departmentId == selectedDeptFilterId
                    val isDone = item.id in completedIds
                    val matchesTab = when (filterTab) {
                        AssignmentFilterTab.ALL -> true
                        AssignmentFilterTab.PENDING -> !isDone
                        AssignmentFilterTab.DONE -> isDone
                        AssignmentFilterTab.CLASSROOM -> item.sourceType == "CLASSROOM"
                    }
                    inUserScope && matchesAdminDept && matchesTab
                }

                if (scopedList.isEmpty()) {
                    item(key = "assignments_empty") {
                        EmptyStateCard(
                            icon = Icons.Default.Assignment,
                            title = strings.noAssignmentsTitle,
                            subtitle = strings.noAssignmentsSub
                        )
                    }
                } else {
                    items(scopedList, key = { it.id }) { item ->
                        val isDone = item.id in completedIds
                        val isOverdue = !isDone && item.dueAtMillis > 0L && item.dueAtMillis < nowMs
                        val isDueSoon = !isDone && !isOverdue && item.dueAtMillis > nowMs &&
                            (item.dueAtMillis - nowMs) <= 24 * 60 * 60 * 1000L
                        val canDelete = canManage || (item.sourceType == "CLASSROOM" && item.ownerUid == currentUserId)

                        AssignmentCard(
                            item = item,
                            isDone = isDone,
                            isOverdue = isOverdue,
                            isDueSoon = isDueSoon,
                            canDelete = canDelete,
                            onToggleDone = { onToggleCompleted(item.id) },
                            onOpenLink = openHttps,
                            onDelete = { onDeleteAssignment(item.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateAssignmentDialog(
            courses = courses,
            departments = departments,
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, desc, dueMs, course, dept, year, section, videoUrl ->
                onCreateAssignment(
                    title,
                    desc,
                    dueMs,
                    course?.id,
                    course?.title,
                    dept?.id,
                    dept?.name,
                    year,
                    section,
                    null,
                    videoUrl
                )
                showCreateDialog = false
            }
        )
    }

    if (showClassroomDialog) {
        ClassroomImportDialog(
            onDismiss = { showClassroomDialog = false },
            onOpenClassroomWeb = { openHttps("https://classroom.google.com") },
            onConfirm = { title, courseName, dueMs, link ->
                onImportClassroomTask(title, courseName, dueMs, link)
                showClassroomDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignmentCard(
    item: AssignmentItem,
    isDone: Boolean,
    isOverdue: Boolean,
    isDueSoon: Boolean,
    canDelete: Boolean,
    onToggleDone: () -> Unit,
    onOpenLink: (String) -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current
    val dueText = remember(item.dueAtMillis) {
        if (item.dueAtMillis > 0L) {
            SimpleDateFormat("MMM d • hh:mm a", Locale.getDefault()).format(Date(item.dueAtMillis))
        } else {
            ""
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("assignment_card_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isDueSoon || isOverdue) 1.5.dp else 1.dp,
            color = when {
                isOverdue -> MaterialTheme.colorScheme.error
                isDueSoon -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = if (item.sourceType == "CLASSROOM") {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = if (item.sourceType == "CLASSROOM") "Google Classroom" else (item.courseTitle ?: "UniCore"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    val statusColor = when {
                        isDone -> MaterialTheme.colorScheme.secondaryContainer
                        isOverdue -> MaterialTheme.colorScheme.errorContainer
                        isDueSoon -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val statusLabel = when {
                        isDone -> strings.completedBadge
                        isOverdue -> strings.overdueBadge
                        isDueSoon -> strings.dueSoonBadge
                        else -> strings.pendingAssignmentsFilter
                    }
                    Surface(color = statusColor, shape = MaterialTheme.shapes.small) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    val scopeMeta = listOfNotNull(item.departmentName, item.academicYear, item.sectionName)
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                    if (scopeMeta.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = scopeMeta,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (canDelete) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_assignment_${item.id}")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = strings.deleteLabel,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None
            )

            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (dueText.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${strings.dueInPrefix}: $dueText",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = onToggleDone,
                    modifier = Modifier.testTag("toggle_done_assignment_${item.id}")
                ) {
                    Icon(
                        imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (isDone) strings.markPendingAction else strings.markDoneAction)
                }

                if (!item.videoUrl.isNullOrBlank()) {
                    OutlinedButton(onClick = { onOpenLink(item.videoUrl) }) {
                        Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(strings.watchVideoAction, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (!item.classroomLink.isNullOrBlank()) {
                    OutlinedButton(onClick = { onOpenLink(item.classroomLink) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(strings.openInClassroom, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateAssignmentDialog(
    courses: List<Course>,
    departments: List<AcademicDepartment>,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        description: String,
        dueAtMillis: Long,
        course: Course?,
        dept: AcademicDepartment?,
        year: String?,
        section: String?,
        videoUrl: String?
    ) -> Unit
) {
    val strings = LocalAppStrings.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var hoursAheadText by remember { mutableStateOf("48") }
    var selectedCourseId by remember { mutableStateOf<String?>(null) }
    var selectedDeptId by remember { mutableStateOf<String?>(null) }
    var selectedYear by remember { mutableStateOf<String?>(null) }
    var sectionName by remember { mutableStateOf("") }
    var videoUrl by remember { mutableStateOf("") }

    val selectedCourse = courses.firstOrNull { it.id == selectedCourseId }
    val selectedDept = departments.firstOrNull { it.id == selectedDeptId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addAssignmentAction) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(strings.courseTitleLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("assignment_title_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(strings.courseDescLabel) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("assignment_desc_input")
                )
                OutlinedTextField(
                    value = hoursAheadText,
                    onValueChange = { hoursAheadText = it.filter(Char::isDigit).take(4) },
                    label = { Text(strings.dueHoursFromNowLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("assignment_hours_input")
                )
                if (courses.isNotEmpty()) {
                    Text(strings.selectCourseLabel, style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCourseId == null,
                            onClick = { selectedCourseId = null },
                            label = { Text(strings.unassignedDept) }
                        )
                        courses.take(12).forEach { c ->
                            FilterChip(
                                selected = selectedCourseId == c.id,
                                onClick = { selectedCourseId = c.id },
                                label = { Text(c.title) }
                            )
                        }
                    }
                }
                if (departments.isNotEmpty()) {
                    Text(strings.selectDepartmentLabel, style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedDeptId == null,
                            onClick = {
                                selectedDeptId = null
                                selectedYear = null
                            },
                            label = { Text(strings.allDepartments) }
                        )
                        departments.forEach { dept ->
                            FilterChip(
                                selected = selectedDeptId == dept.id,
                                onClick = {
                                    selectedDeptId = dept.id
                                    selectedYear = null
                                },
                                label = { Text(dept.name) }
                            )
                        }
                    }
                    val years = selectedDept?.years.orEmpty()
                    if (years.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = selectedYear == null,
                                onClick = { selectedYear = null },
                                label = { Text(strings.allYears) }
                            )
                            years.forEach { yr ->
                                FilterChip(
                                    selected = selectedYear == yr,
                                    onClick = { selectedYear = yr },
                                    label = { Text(yr) }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = sectionName,
                    onValueChange = { sectionName = it },
                    label = { Text(strings.sectionLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = videoUrl,
                    onValueChange = { videoUrl = it },
                    label = { Text(strings.videoUrlLabel) },
                    placeholder = { Text("https://youtube.com/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val hrs = (hoursAheadText.toLongOrNull() ?: 48L).coerceIn(1L, 720L)
                    val dueMs = System.currentTimeMillis() + hrs * 3_600_000L
                    onConfirm(
                        title,
                        description,
                        dueMs,
                        selectedCourse,
                        selectedDept,
                        selectedYear,
                        sectionName.trim().ifBlank { null },
                        videoUrl.trim().ifBlank { null }
                    )
                },
                enabled = title.trim().length >= 2,
                modifier = Modifier.testTag("confirm_create_assignment")
            ) {
                Text(strings.publishAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
        }
    )
}

@Composable
private fun ClassroomImportDialog(
    onDismiss: () -> Unit,
    onOpenClassroomWeb: () -> Unit,
    onConfirm: (title: String, courseName: String, dueAtMillis: Long, classroomUrl: String?) -> Unit
) {
    val strings = LocalAppStrings.current
    var taskTitle by remember { mutableStateOf("") }
    var courseName by remember { mutableStateOf("") }
    var classroomUrl by remember { mutableStateOf("https://classroom.google.com") }
    var hoursAheadText by remember { mutableStateOf("24") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.importClassroomAction) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.classroomImportHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onOpenClassroomWeb,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(strings.openInClassroom)
                }
                OutlinedTextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = { Text(strings.classroomCourseNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("classroom_course_input")
                )
                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = { taskTitle = it },
                    label = { Text(strings.courseTitleLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("classroom_task_input")
                )
                OutlinedTextField(
                    value = hoursAheadText,
                    onValueChange = { hoursAheadText = it.filter(Char::isDigit).take(4) },
                    label = { Text(strings.dueHoursFromNowLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = classroomUrl,
                    onValueChange = { classroomUrl = it },
                    label = { Text("Classroom URL (https://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val hrs = (hoursAheadText.toLongOrNull() ?: 24L).coerceIn(1L, 720L)
                    onConfirm(
                        taskTitle,
                        courseName,
                        System.currentTimeMillis() + hrs * 3_600_000L,
                        classroomUrl.trim().ifBlank { null }
                    )
                },
                enabled = taskTitle.trim().length >= 2,
                modifier = Modifier.testTag("confirm_classroom_import")
            ) {
                Text(strings.importClassroomAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
        }
    )
}
