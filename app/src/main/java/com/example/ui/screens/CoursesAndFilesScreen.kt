package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AcademicDepartment
import com.example.data.model.Course
import com.example.data.model.DriveFolderLink
import com.example.data.model.DriveSyncedFile
import com.example.data.model.MaterialType
import com.example.data.model.UserRole
import com.example.data.model.VerifiedFileRecord
import com.example.security.InputValidator
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState

@Composable
fun CoursesScreen(
    coursesState: UiState<List<Course>>,
    departmentsState: UiState<List<AcademicDepartment>>,
    driveFoldersState: UiState<List<DriveFolderLink>>,
    driveFilesState: UiState<List<DriveSyncedFile>>,
    isDriveRefreshing: Boolean,
    openingCourseId: String?,
    openingDriveFileId: String?,
    userRole: UserRole,
    onCreateCourseWithFile: (String, String, String, MaterialType, Uri?, String?, String?, String?) -> Unit,
    onUpdateCourseDetails: (String, String, String, String?, String?, String?) -> Unit,
    onDeleteCourse: (String) -> Unit,
    onOpenCourseAttachment: (Course) -> Unit,
    onSaveDepartment: (String?, String, List<String>) -> Unit,
    onDeleteDepartment: (String) -> Unit,
    onLinkDriveFolder: (String, String, String?, String?, String?) -> Unit,
    onUnlinkDriveFolder: (String) -> Unit,
    onRefreshDrive: () -> Unit,
    onOpenDriveFile: (DriveSyncedFile) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    var showDriveDialog by remember { mutableStateOf(false) }
    var showDepartmentDialog by remember { mutableStateOf(false) }
    var editingDepartment by remember { mutableStateOf<AcademicDepartment?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<MaterialType?>(null) }
    var selectedDepartmentId by remember { mutableStateOf<String?>(null) }
    var selectedYear by remember { mutableStateOf<String?>(null) }

    val canManage = userRole != UserRole.STUDENT
    val departments = (departmentsState as? UiState.Success)?.data.orEmpty()
    val selectedDept = remember(departments, selectedDepartmentId) {
        departments.firstOrNull { it.id == selectedDepartmentId }
    }
    val allDriveFolders = (driveFoldersState as? UiState.Success)?.data.orEmpty()
    val visibleDriveFolders = remember(allDriveFolders, selectedDept, selectedYear) {
        allDriveFolders.filter { folder ->
            val matchesDept = selectedDept == null ||
                folder.departmentId == selectedDept.id ||
                folder.departmentName.equals(selectedDept.name, ignoreCase = true)
            val matchesYear = selectedYear == null || folder.academicYear == selectedYear
            matchesDept && matchesYear
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search + Department/Year Filter + Admin Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(strings.searchCoursesHint) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )
                if (canManage) {
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.testTag("add_course_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(strings.publishCourse)
                    }
                }
            }

            // Academic Departments & Years Selector (Only Admin & SuperAdmin can create/edit/delete)
            if (departments.isNotEmpty() || canManage) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.departmentsTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (canManage) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (selectedDept != null) {
                                TextButton(
                                    onClick = {
                                        editingDepartment = selectedDept
                                        showDepartmentDialog = true
                                    },
                                    modifier = Modifier.testTag("edit_department_button")
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text(strings.editCourseAction)
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    editingDepartment = null
                                    showDepartmentDialog = true
                                },
                                modifier = Modifier.testTag("add_department_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(strings.addDepartmentAction)
                            }
                        }
                    }
                }

                if (departments.isEmpty() && canManage) {
                    OutlinedButton(
                        onClick = {
                            onSaveDepartment(
                                null,
                                "طاقة متجددة",
                                listOf("الفرقة الأولى", "الفرقة الثانية", "الفرقة الثالثة", "الفرقة الرابعة")
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("quick_renewable_dept_preset")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(strings.quickRenewablePreset)
                    }
                }

                if (departments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedDepartmentId == null,
                            onClick = {
                                selectedDepartmentId = null
                                selectedYear = null
                            },
                            label = { Text(strings.allDepartments) },
                            modifier = Modifier.testTag("dept_chip_all")
                        )
                        departments.forEach { dept ->
                            FilterChip(
                                selected = selectedDepartmentId == dept.id,
                                onClick = {
                                    if (selectedDepartmentId == dept.id) {
                                        selectedDepartmentId = null
                                        selectedYear = null
                                    } else {
                                        selectedDepartmentId = dept.id
                                        selectedYear = null
                                    }
                                },
                                label = { Text(dept.name) },
                                modifier = Modifier.testTag("dept_chip_${dept.id}")
                            )
                        }
                    }
                }

                val yearsForSelectedDept = selectedDept?.years.orEmpty()
                if (yearsForSelectedDept.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedYear == null,
                            onClick = { selectedYear = null },
                            label = { Text(strings.allYears) }
                        )
                        yearsForSelectedDept.forEach { yearLabel ->
                            FilterChip(
                                selected = selectedYear == yearLabel,
                                onClick = {
                                    selectedYear = if (selectedYear == yearLabel) null else yearLabel
                                },
                                label = { Text(yearLabel) }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text(strings.allTypes) }
                )
                MaterialType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedFilter == type,
                        onClick = { selectedFilter = if (selectedFilter == type) null else type },
                        label = { Text(type.name.replace("_", " ")) }
                    )
                }
            }
        }

        val filteredCourses = when (coursesState) {
            is UiState.Success -> remember(coursesState.data, searchQuery, selectedFilter, selectedDept, selectedYear) {
                coursesState.data.filter { course ->
                    val matchesFilter = selectedFilter == null || course.materialType == selectedFilter?.name
                    val matchesDept = selectedDept == null ||
                        course.departmentId == selectedDept.id ||
                        course.departmentName.equals(selectedDept.name, ignoreCase = true)
                    val matchesYear = selectedYear == null || course.academicYear == selectedYear
                    val matchesQuery = searchQuery.isBlank() ||
                        course.title.contains(searchQuery.trim(), ignoreCase = true) ||
                        course.description.contains(searchQuery.trim(), ignoreCase = true) ||
                        (course.attachmentName?.contains(searchQuery.trim(), ignoreCase = true) == true) ||
                        (course.departmentName?.contains(searchQuery.trim(), ignoreCase = true) == true) ||
                        (course.academicYear?.contains(searchQuery.trim(), ignoreCase = true) == true)
                    matchesFilter && matchesDept && matchesYear && matchesQuery
                }
            }
            else -> emptyList()
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Google Drive Live Sync Card (Filtered by Department/Year when selected)
            if (canManage || allDriveFolders.isNotEmpty()) {
                item(key = "drive_sync_card") {
                    val allowedFolderIds = remember(visibleDriveFolders) {
                        visibleDriveFolders.map { it.folderId }.toSet()
                    }
                    val scopedDriveFilesState = when (driveFilesState) {
                        is UiState.Success -> {
                            if (selectedDept == null && selectedYear == null) {
                                driveFilesState
                            } else {
                                UiState.Success(driveFilesState.data.filter { it.folderId in allowedFolderIds })
                            }
                        }
                        else -> driveFilesState
                    }
                    DriveSyncSectionCard(
                        folders = visibleDriveFolders,
                        driveFilesState = scopedDriveFilesState,
                        isRefreshing = isDriveRefreshing,
                        openingDriveFileId = openingDriveFileId,
                        canManage = canManage,
                        searchQuery = searchQuery,
                        onOpenLinkDialog = { showDriveDialog = true },
                        onUnlinkFolder = onUnlinkDriveFolder,
                        onRefreshDrive = onRefreshDrive,
                        onOpenDriveFile = onOpenDriveFile,
                        onOpenFolderUrl = { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }.onFailure {
                                Toast.makeText(context, "No browser app found.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            when (coursesState) {
                is UiState.Loading -> {
                    item(key = "courses_loading") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is UiState.Error -> {
                    item(key = "courses_error") { ErrorBannerCard(coursesState.message) }
                }
                is UiState.Success -> {
                    if (filteredCourses.isEmpty()) {
                        item(key = "courses_empty") {
                            EmptyStateCard(
                                icon = Icons.Default.School,
                                title = strings.noCoursesTitle,
                                subtitle = if (userRole == UserRole.STUDENT) {
                                    strings.noCoursesStudentSub
                                } else {
                                    strings.noCoursesAdminSub
                                }
                            )
                        }
                    } else {
                        items(filteredCourses, key = { it.id }) { course ->
                            CourseCard(
                                course = course,
                                canManage = canManage,
                                isDownloading = openingCourseId == course.id,
                                onEdit = { editingCourse = course },
                                onDelete = { onDeleteCourse(course.id) },
                                onOpenAttachment = { onOpenCourseAttachment(course) },
                                onOpenLink = { url ->
                                    val valid = InputValidator.validateOptionalHttpsUrl(url)
                                    if (valid is InputValidator.ValidationResult.Valid && valid.value != null) {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(valid.value)))
                                        }.onFailure {
                                            Toast.makeText(context, "No browser app found to open link.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCourseDialog(
            departments = departments,
            initialDepartmentId = selectedDept?.id,
            initialYear = selectedYear,
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, desc, link, type, fileUri, deptId, deptName, year ->
                onCreateCourseWithFile(title, desc, link, type, fileUri, deptId, deptName, year)
                showCreateDialog = false
            }
        )
    }

    val targetCourseEdit = editingCourse
    if (targetCourseEdit != null) {
        EditCourseDialog(
            course = targetCourseEdit,
            departments = departments,
            onDismiss = { editingCourse = null },
            onConfirm = { title, desc, deptId, deptName, year ->
                onUpdateCourseDetails(targetCourseEdit.id, title, desc, deptId, deptName, year)
                editingCourse = null
            }
        )
    }

    if (showDriveDialog) {
        LinkDriveFolderDialog(
            departments = departments,
            initialDepartmentId = selectedDept?.id,
            initialYear = selectedYear,
            onDismiss = { showDriveDialog = false },
            onConfirm = { title, url, deptId, deptName, year ->
                onLinkDriveFolder(title, url, deptId, deptName, year)
                showDriveDialog = false
            }
        )
    }

    if (showDepartmentDialog) {
        DepartmentEditorDialog(
            existing = editingDepartment,
            onDismiss = {
                showDepartmentDialog = false
                editingDepartment = null
            },
            onSave = { name, years ->
                onSaveDepartment(editingDepartment?.id, name, years)
                showDepartmentDialog = false
                editingDepartment = null
            },
            onDelete = editingDepartment?.let { dept ->
                {
                    if (selectedDepartmentId == dept.id) {
                        selectedDepartmentId = null
                        selectedYear = null
                    }
                    onDeleteDepartment(dept.id)
                    showDepartmentDialog = false
                    editingDepartment = null
                }
            }
        )
    }
}

@Composable
private fun DriveSyncSectionCard(
    folders: List<DriveFolderLink>,
    driveFilesState: UiState<List<DriveSyncedFile>>,
    isRefreshing: Boolean,
    openingDriveFileId: String?,
    canManage: Boolean,
    searchQuery: String,
    onOpenLinkDialog: () -> Unit,
    onUnlinkFolder: (String) -> Unit,
    onRefreshDrive: () -> Unit,
    onOpenDriveFile: (DriveSyncedFile) -> Unit,
    onOpenFolderUrl: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = strings.driveSyncTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.driveSyncSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folders.isNotEmpty()) {
                        IconButton(onClick = onRefreshDrive, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = strings.refreshDrive)
                            }
                        }
                    }
                    if (canManage) {
                        FilledTonalButton(
                            onClick = onOpenLinkDialog,
                            modifier = Modifier.testTag("link_drive_folder_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(strings.linkDriveFolder, maxLines = 1)
                        }
                    }
                }
            }

            if (folders.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    folders.forEach { folder ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = folder.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                IconButton(
                                    onClick = { onOpenFolderUrl(folder.folderUrl) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = folder.title,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                if (canManage) {
                                    IconButton(
                                        onClick = { onUnlinkFolder(folder.folderId) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = strings.deleteLabel,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                when (driveFilesState) {
                    is UiState.Loading -> CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    is UiState.Error -> {
                        Text(
                            text = driveFilesState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is UiState.Success -> {
                        val filteredFiles = remember(driveFilesState.data, searchQuery) {
                            if (searchQuery.isBlank()) driveFilesState.data
                            else driveFilesState.data.filter {
                                it.name.contains(searchQuery.trim(), ignoreCase = true) ||
                                    it.folderTitle.contains(searchQuery.trim(), ignoreCase = true)
                            }
                        }
                        if (filteredFiles.isEmpty() && !isRefreshing) {
                            Text(
                                text = strings.noDriveFilesYet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                filteredFiles.forEach { fileItem ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = MaterialTheme.shapes.small,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = if (fileItem.isSubFolder) Icons.Default.Folder else Icons.Default.Description,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Column {
                                                    Text(
                                                        text = fileItem.name,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = fileItem.folderTitle,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = { onOpenDriveFile(fileItem) },
                                                enabled = openingDriveFileId != fileItem.fileId
                                            ) {
                                                if (openingDriveFileId == fileItem.fileId) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Icon(
                                                        Icons.Default.Download,
                                                        contentDescription = strings.downloadAndOpenFile,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.size(4.dp))
                                                    Text(strings.downloadAndOpenFile, maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseCard(
    course: Course,
    canManage: Boolean,
    isDownloading: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenAttachment: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("course_card_${course.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = course.materialType.replace("_", " "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (!course.departmentName.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            val deptTag = buildString {
                                append(course.departmentName)
                                if (!course.academicYear.isNullOrBlank()) {
                                    append(" • ")
                                    append(course.academicYear)
                                }
                            }
                            Text(
                                text = deptTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${strings.byInstructor} ${course.instructorName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (canManage) {
                        IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_course_${course.id}")) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = strings.editCourseAction,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = strings.deleteLabel,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            Text(
                text = course.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = course.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (course.hasAttachment && !course.attachmentName.isNullOrBlank()) {
                val kb = (course.attachmentSizeBytes / 1024L).coerceAtLeast(1L)
                Button(
                    onClick = onOpenAttachment,
                    enabled = !isDownloading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_course_file_${course.id}")
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(strings.downloadingLabel)
                    } else {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = strings.downloadAndOpenFile,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "${strings.downloadAndOpenFile}: ${course.attachmentName} (${kb} KB)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (!course.materialLink.isNullOrBlank()) {
                OutlinedButton(
                    onClick = { onOpenLink(course.materialLink) },
                    modifier = Modifier.testTag("open_course_link_${course.id}")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = strings.openResource,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(strings.openResource, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DepartmentYearPickerBlock(
    departments: List<AcademicDepartment>,
    selectedDeptId: String?,
    selectedYear: String?,
    onSelectDept: (AcademicDepartment?) -> Unit,
    onSelectYear: (String?) -> Unit
) {
    val strings = LocalAppStrings.current
    if (departments.isEmpty()) return
    val currentDept = departments.firstOrNull { it.id == selectedDeptId }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.selectDepartmentLabel, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedDeptId == null,
                onClick = { onSelectDept(null) },
                label = { Text(strings.unassignedDept) }
            )
            departments.forEach { dept ->
                FilterChip(
                    selected = selectedDeptId == dept.id,
                    onClick = { onSelectDept(dept) },
                    label = { Text(dept.name) }
                )
            }
        }
        val years = currentDept?.years.orEmpty()
        if (years.isNotEmpty()) {
            Text(strings.selectYearLabel, style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedYear == null,
                    onClick = { onSelectYear(null) },
                    label = { Text(strings.allYears) }
                )
                years.forEach { yearLabel ->
                    FilterChip(
                        selected = selectedYear == yearLabel,
                        onClick = { onSelectYear(yearLabel) },
                        label = { Text(yearLabel) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateCourseDialog(
    departments: List<AcademicDepartment>,
    initialDepartmentId: String?,
    initialYear: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, MaterialType, Uri?, String?, String?, String?) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(MaterialType.FILE) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var chosenDeptId by remember { mutableStateOf(initialDepartmentId) }
    var chosenYear by remember { mutableStateOf(initialYear) }

    fun handlePickedUri(uri: Uri?) {
        if (uri == null) return
        selectedUri = uri
        selectedType = MaterialType.FILE
        var resolvedName = uri.lastPathSegment ?: "document"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) resolvedName = cursor.getString(idx) ?: resolvedName
            }
        }
        selectedFileName = resolvedName
        if (title.isBlank()) {
            title = resolvedName.substringBeforeLast('.').take(60)
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> handlePickedUri(uri) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> handlePickedUri(uri) }

    val chosenDept = departments.firstOrNull { it.id == chosenDeptId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.publishCourse) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.uploadFileHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.weight(1f).testTag("course_pick_pdf")
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(strings.attachPdfButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f).testTag("course_pick_image")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(strings.attachImageButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (selectedFileName != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(
                                    text = selectedFileName.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    selectedUri = null
                                    selectedFileName = null
                                    if (selectedType == MaterialType.FILE) selectedType = MaterialType.NOTES
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = strings.clearLabel, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(strings.courseTitleLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("course_title_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(strings.courseDescLabel) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("course_desc_input")
                )
                DepartmentYearPickerBlock(
                    departments = departments,
                    selectedDeptId = chosenDeptId,
                    selectedYear = chosenYear,
                    onSelectDept = { dept ->
                        chosenDeptId = dept?.id
                        chosenYear = if (dept != null && chosenYear in dept.years) chosenYear else null
                    },
                    onSelectYear = { chosenYear = it }
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(strings.optionalLinkAndNotes) },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("course_link_input")
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MaterialType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.name.replace("_", " "), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        title,
                        description,
                        link,
                        selectedType,
                        selectedUri,
                        chosenDept?.id,
                        chosenDept?.name,
                        chosenYear
                    )
                },
                modifier = Modifier.testTag("confirm_create_course")
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
private fun EditCourseDialog(
    course: Course,
    departments: List<AcademicDepartment>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?, String?, String?) -> Unit
) {
    val strings = LocalAppStrings.current
    var title by remember(course.id) { mutableStateOf(course.title) }
    var description by remember(course.id) { mutableStateOf(course.description) }
    var chosenDeptId by remember(course.id) { mutableStateOf(course.departmentId) }
    var chosenYear by remember(course.id) { mutableStateOf(course.academicYear) }
    val chosenDept = departments.firstOrNull { it.id == chosenDeptId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.editCourseAction) },
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
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(strings.courseDescLabel) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                DepartmentYearPickerBlock(
                    departments = departments,
                    selectedDeptId = chosenDeptId,
                    selectedYear = chosenYear,
                    onSelectDept = { dept ->
                        chosenDeptId = dept?.id
                        chosenYear = if (dept != null && chosenYear in dept.years) chosenYear else null
                    },
                    onSelectYear = { chosenYear = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        title,
                        description,
                        chosenDept?.id,
                        chosenDept?.name,
                        chosenYear
                    )
                },
                enabled = title.isNotBlank() && description.isNotBlank()
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
private fun LinkDriveFolderDialog(
    departments: List<AcademicDepartment>,
    initialDepartmentId: String?,
    initialYear: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?, String?, String?) -> Unit
) {
    val strings = LocalAppStrings.current
    var folderTitle by remember { mutableStateOf("") }
    var folderUrl by remember { mutableStateOf("") }
    var chosenDeptId by remember { mutableStateOf(initialDepartmentId) }
    var chosenYear by remember { mutableStateOf(initialYear) }
    val chosenDept = departments.firstOrNull { it.id == chosenDeptId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.linkDriveFolder) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.driveSharingNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = folderTitle,
                    onValueChange = { folderTitle = it },
                    label = { Text(strings.driveFolderTitleLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("drive_folder_title_input")
                )
                OutlinedTextField(
                    value = folderUrl,
                    onValueChange = { folderUrl = it },
                    label = { Text(strings.driveFolderUrlLabel) },
                    placeholder = { Text("https://drive.google.com/drive/folders/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("drive_folder_url_input")
                )
                DepartmentYearPickerBlock(
                    departments = departments,
                    selectedDeptId = chosenDeptId,
                    selectedYear = chosenYear,
                    onSelectDept = { dept ->
                        chosenDeptId = dept?.id
                        chosenYear = if (dept != null && chosenYear in dept.years) chosenYear else null
                    },
                    onSelectYear = { chosenYear = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        folderTitle,
                        folderUrl,
                        chosenDept?.id,
                        chosenDept?.name,
                        chosenYear
                    )
                },
                enabled = folderTitle.isNotBlank() && folderUrl.isNotBlank(),
                modifier = Modifier.testTag("confirm_link_drive_folder")
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
private fun DepartmentEditorDialog(
    existing: AcademicDepartment?,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
    onDelete: (() -> Unit)?
) {
    val strings = LocalAppStrings.current
    var deptName by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var yearsText by remember(existing?.id) {
        mutableStateOf(
            existing?.years?.joinToString("\n")
                ?: listOf("الفرقة الأولى", "الفرقة الثانية", "الفرقة الثالثة", "الفرقة الرابعة").joinToString("\n")
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existing == null) strings.addDepartmentAction else strings.editDepartmentAction)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = deptName,
                    onValueChange = { deptName = it },
                    label = { Text(strings.departmentNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("dept_name_input")
                )
                OutlinedTextField(
                    value = yearsText,
                    onValueChange = { yearsText = it },
                    label = { Text(strings.studyYearsLabel) },
                    placeholder = { Text(strings.studyYearsHint) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("dept_years_input")
                )
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_department_button")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(strings.deleteLabel, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            val parsedYears = yearsText
                .split(Regex("[,،\n]+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            Button(
                onClick = { onSave(deptName, parsedYears) },
                enabled = deptName.isNotBlank() && parsedYears.isNotEmpty(),
                modifier = Modifier.testTag("confirm_save_department")
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
fun SecureFilesVaultScreen(
    filesState: UiState<List<VerifiedFileRecord>>,
    onFilePicked: (Uri) -> Unit
) {
    val strings = LocalAppStrings.current
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.filesVaultTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = strings.filesVaultSub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.testTag("pick_pdf_button")
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(strings.verifyPdf)
                    }
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("pick_image_button")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(strings.verifyImage)
                    }
                }
            }
        }

        when (filesState) {
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Error -> ErrorBannerCard(filesState.message)
            is UiState.Success -> {
                if (filesState.data.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Default.AttachFile,
                        title = strings.noFilesTitle,
                        subtitle = strings.noFilesSub
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filesState.data, key = { it.id }) { file ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Verified,
                                            contentDescription = file.originalName,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = file.originalName,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "${file.mimeType} • ${(file.sizeBytes / 1024).coerceAtLeast(1)} KB",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorBannerCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun EmptyStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
