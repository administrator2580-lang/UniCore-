package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OfflinePin
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AcademicDepartment
import com.example.data.model.Course
import com.example.data.model.DriveFolderLink
import com.example.data.model.DriveSyncedFile
import com.example.data.model.MaterialType
import com.example.data.model.UserRole
import com.example.data.model.VerifiedFileRecord
import com.example.security.InputValidator
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.InAppFilePreview
import com.example.ui.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun CoursesScreen(
    coursesState: UiState<List<Course>>,
    departmentsState: UiState<List<AcademicDepartment>>,
    driveFoldersState: UiState<List<DriveFolderLink>>,
    driveFilesState: UiState<List<DriveSyncedFile>>,
    isDriveRefreshing: Boolean,
    downloadedDriveFileIds: Set<String> = emptySet(),
    downloadingOfflineIds: Set<String> = emptySet(),
    isBatchDownloadingOffline: Boolean = false,
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
    onUpdateDriveFolder: (String, String, String, String?, String?, String?) -> Unit,
    onUnlinkDriveFolder: (String) -> Unit,
    onRefreshDrive: () -> Unit,
    onOpenDriveFile: (DriveSyncedFile) -> Unit,
    onSaveDriveFileOffline: (DriveSyncedFile) -> Unit = {},
    onRemoveDriveFileOffline: (DriveSyncedFile) -> Unit = {},
    onDownloadAllOffline: (List<DriveSyncedFile>) -> Unit = {}
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    var showStorageManagerDialog by remember { mutableStateOf(false) }
    var showDriveDialog by remember { mutableStateOf(false) }
    var editingDriveFolder by remember { mutableStateOf<DriveFolderLink?>(null) }
    var showDepartmentDialog by remember { mutableStateOf(false) }
    var editingDepartment by remember { mutableStateOf<AcademicDepartment?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<MaterialType?>(null) }
    var selectedDepartmentId by remember { mutableStateOf<String?>(null) }
    var selectedYear by remember { mutableStateOf<String?>(null) }

    val canManage = userRole != UserRole.STUDENT
    val isSuperAdmin = userRole == UserRole.SUPER_ADMIN
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

        val allowedFolderIds = remember(visibleDriveFolders) {
            visibleDriveFolders.map { it.folderId }.toSet()
        }
        val scopedDriveFilesState = when (driveFilesState) {
            is UiState.Success -> {
                val actualFiles = driveFilesState.data.filterNot { it.isSubFolder }
                if (selectedDept == null && selectedYear == null) {
                    UiState.Success(actualFiles)
                } else {
                    UiState.Success(actualFiles.filter { it.folderId in allowedFolderIds })
                }
            }
            else -> driveFilesState
        }
        val hasVisibleLibraryFiles = (scopedDriveFilesState as? UiState.Success)?.data?.isNotEmpty() == true
        val showLibraryCard = isSuperAdmin || isDriveRefreshing || hasVisibleLibraryFiles ||
            (isSuperAdmin && driveFilesState is UiState.Error)

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (showLibraryCard) {
                item(key = "study_library_card") {
                    StudyLibrarySectionCard(
                        hasConfiguredFolders = allDriveFolders.isNotEmpty(),
                        driveFilesState = scopedDriveFilesState,
                        isRefreshing = isDriveRefreshing,
                        downloadedDriveFileIds = downloadedDriveFileIds,
                        downloadingOfflineIds = downloadingOfflineIds,
                        isBatchDownloadingOffline = isBatchDownloadingOffline,
                        openingDriveFileId = openingDriveFileId,
                        isSuperAdmin = isSuperAdmin,
                        searchQuery = searchQuery,
                        onOpenStorageManager = { showStorageManagerDialog = true },
                        onRefreshDrive = onRefreshDrive,
                        onOpenDriveFile = onOpenDriveFile,
                        onSaveDriveFileOffline = onSaveDriveFileOffline,
                        onRemoveDriveFileOffline = onRemoveDriveFileOffline,
                        onDownloadAllOffline = onDownloadAllOffline
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

    if (showStorageManagerDialog && isSuperAdmin) {
        StorageVaultManagerDialog(
            folders = allDriveFolders,
            onDismiss = { showStorageManagerDialog = false },
            onAddNew = {
                editingDriveFolder = null
                showDriveDialog = true
            },
            onEditFolder = { folder ->
                editingDriveFolder = folder
                showDriveDialog = true
            },
            onDeleteFolder = onUnlinkDriveFolder
        )
    }

    if (showDriveDialog && isSuperAdmin) {
        val currentEdit = editingDriveFolder
        LinkDriveFolderDialog(
            departments = departments,
            existing = currentEdit,
            initialDepartmentId = currentEdit?.departmentId ?: selectedDept?.id,
            initialYear = currentEdit?.academicYear ?: selectedYear,
            onDismiss = {
                showDriveDialog = false
                editingDriveFolder = null
            },
            onConfirm = { title, url, deptId, deptName, year ->
                if (currentEdit == null) {
                    onLinkDriveFolder(title, url, deptId, deptName, year)
                } else {
                    onUpdateDriveFolder(currentEdit.folderId, title, url, deptId, deptName, year)
                }
                showDriveDialog = false
                editingDriveFolder = null
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
private fun StudyLibrarySectionCard(
    hasConfiguredFolders: Boolean,
    driveFilesState: UiState<List<DriveSyncedFile>>,
    isRefreshing: Boolean,
    downloadedDriveFileIds: Set<String>,
    downloadingOfflineIds: Set<String>,
    isBatchDownloadingOffline: Boolean,
    openingDriveFileId: String?,
    isSuperAdmin: Boolean,
    searchQuery: String,
    onOpenStorageManager: () -> Unit,
    onRefreshDrive: () -> Unit,
    onOpenDriveFile: (DriveSyncedFile) -> Unit,
    onSaveDriveFileOffline: (DriveSyncedFile) -> Unit,
    onRemoveDriveFileOffline: (DriveSyncedFile) -> Unit,
    onDownloadAllOffline: (List<DriveSyncedFile>) -> Unit
) {
    val strings = LocalAppStrings.current
    var selectedSection by remember { mutableStateOf<String?>(null) }
    var offlineOnly by remember { mutableStateOf(false) }

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
                        Icons.Default.Folder,
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
                    if (hasConfiguredFolders) {
                        IconButton(onClick = onRefreshDrive, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = strings.refreshDrive)
                            }
                        }
                    }
                    if (isSuperAdmin) {
                        FilledTonalButton(
                            onClick = onOpenStorageManager,
                            modifier = Modifier.testTag("manage_storage_vault_button")
                        ) {
                            Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(strings.linkDriveFolder, maxLines = 1)
                        }
                    }
                }
            }

            when (driveFilesState) {
                is UiState.Loading -> CircularProgressIndicator(modifier = Modifier.size(22.dp))
                is UiState.Error -> {
                    if (isSuperAdmin) {
                        Text(
                            text = driveFilesState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                is UiState.Success -> {
                    val allFiles = driveFilesState.data
                    val offlineCount = remember(allFiles, downloadedDriveFileIds) {
                        allFiles.count { it.fileId in downloadedDriveFileIds }
                    }
                    val sections = remember(allFiles) {
                        allFiles
                            .map { it.subFolderPath.ifBlank { it.rootFolderTitle.ifBlank { it.folderTitle } } }
                            .filter { it.isNotBlank() }
                            .distinct()
                    }
                    LaunchedEffect(sections) {
                        if (selectedSection != null && selectedSection !in sections) {
                            selectedSection = null
                        }
                    }

                    if (allFiles.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = selectedSection == null && !offlineOnly,
                                onClick = {
                                    selectedSection = null
                                    offlineOnly = false
                                },
                                label = { Text(strings.allTypes) }
                            )
                            FilterChip(
                                selected = offlineOnly,
                                onClick = { offlineOnly = !offlineOnly },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.OfflinePin,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text("${strings.offlineOnlyFilter} ($offlineCount)") },
                                modifier = Modifier.testTag("drive_offline_filter_chip")
                            )
                            if (sections.size > 1) {
                                sections.forEach { section ->
                                    FilterChip(
                                        selected = selectedSection == section,
                                        onClick = {
                                            selectedSection = if (selectedSection == section) null else section
                                        },
                                        label = { Text(section) }
                                    )
                                }
                            }
                        }
                    }

                    val filteredFiles = remember(allFiles, searchQuery, selectedSection, offlineOnly, downloadedDriveFileIds) {
                        allFiles.filter { fileItem ->
                            val itemSection = fileItem.subFolderPath.ifBlank {
                                fileItem.rootFolderTitle.ifBlank { fileItem.folderTitle }
                            }
                            val matchesSection = selectedSection == null || itemSection == selectedSection
                            val matchesOffline = !offlineOnly || fileItem.fileId in downloadedDriveFileIds
                            val q = searchQuery.trim()
                            val matchesQuery = q.isBlank() ||
                                fileItem.name.contains(q, ignoreCase = true) ||
                                itemSection.contains(q, ignoreCase = true)
                            matchesSection && matchesOffline && matchesQuery
                        }
                    }

                    val pendingOfflineCount = remember(filteredFiles, downloadedDriveFileIds) {
                        filteredFiles.count { !it.isSubFolder && it.fileId !in downloadedDriveFileIds }
                    }
                    if (pendingOfflineCount > 0) {
                        FilledTonalButton(
                            onClick = { onDownloadAllOffline(filteredFiles) },
                            enabled = !isBatchDownloadingOffline,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("drive_download_all_offline_button")
                        ) {
                            if (isBatchDownloadingOffline) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text(strings.downloadingLabel)
                            } else {
                                Icon(Icons.Default.DownloadForOffline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("${strings.saveAllOfflineAction} ($pendingOfflineCount)")
                            }
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
                                val itemSection = fileItem.subFolderPath.ifBlank {
                                    fileItem.rootFolderTitle.ifBlank { fileItem.folderTitle }
                                }
                                val isOffline = fileItem.fileId in downloadedDriveFileIds
                                val isSavingOffline = fileItem.fileId in downloadingOfflineIds
                                val isOpening = openingDriveFileId == fileItem.fileId

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
                                                imageVector = if (fileItem.mimeHint.startsWith("image/")) {
                                                    Icons.Default.Image
                                                } else {
                                                    Icons.Default.Description
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = fileItem.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    if (itemSection.isNotBlank()) {
                                                        Text(
                                                            text = itemSection,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                    }
                                                    if (isOffline) {
                                                        Surface(
                                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                                            shape = MaterialTheme.shapes.extraSmall
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.OfflinePin,
                                                                    contentDescription = null,
                                                                    tint = MaterialTheme.colorScheme.secondary,
                                                                    modifier = Modifier.size(12.dp)
                                                                )
                                                                Text(
                                                                    text = strings.offlineReadyBadge,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    if (isOffline) {
                                                        onRemoveDriveFileOffline(fileItem)
                                                    } else {
                                                        onSaveDriveFileOffline(fileItem)
                                                    }
                                                },
                                                enabled = !isSavingOffline && !isOpening,
                                                modifier = Modifier.testTag("drive_offline_${fileItem.fileId}")
                                            ) {
                                                when {
                                                    isSavingOffline -> CircularProgressIndicator(
                                                        modifier = Modifier.size(18.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    isOffline -> Icon(
                                                        Icons.Default.DeleteOutline,
                                                        contentDescription = strings.removeOfflineAction,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    else -> Icon(
                                                        Icons.Default.DownloadForOffline,
                                                        contentDescription = strings.saveOfflineAction,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = { onOpenDriveFile(fileItem) },
                                                enabled = !isOpening,
                                                modifier = Modifier.testTag("drive_open_${fileItem.fileId}")
                                            ) {
                                                if (isOpening) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Icon(
                                                        if (isOffline) Icons.Default.CloudDone else Icons.Default.Download,
                                                        contentDescription = if (isOffline) strings.openOfflineAction else strings.downloadAndOpenFile,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.size(4.dp))
                                                    Text(
                                                        text = if (isOffline) strings.openOfflineAction else strings.downloadAndOpenFile,
                                                        maxLines = 1
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
            }
        }
    }
}

@Composable
private fun StorageVaultManagerDialog(
    folders: List<DriveFolderLink>,
    onDismiss: () -> Unit,
    onAddNew: () -> Unit,
    onEditFolder: (DriveFolderLink) -> Unit,
    onDeleteFolder: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.manageStorageVault) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.storageVaultSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onAddNew,
                    modifier = Modifier.fillMaxWidth().testTag("add_storage_source_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(strings.addStorageSource)
                }

                if (folders.isEmpty()) {
                    Text(
                        text = strings.noStorageSourcesYet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    folders.forEach { folder ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val meta = listOfNotNull(folder.departmentName, folder.academicYear)
                                        .joinToString(" • ")
                                    if (meta.isNotBlank()) {
                                        Text(
                                            text = meta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = folder.folderUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row {
                                    IconButton(
                                        onClick = { onEditFolder(folder) },
                                        modifier = Modifier.testTag("edit_storage_${folder.folderId}")
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = strings.editStorageSource,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteFolder(folder.folderId) },
                                        modifier = Modifier.testTag("delete_storage_${folder.folderId}")
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
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
        }
    )
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
    existing: DriveFolderLink? = null,
    initialDepartmentId: String?,
    initialYear: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?, String?, String?) -> Unit
) {
    val strings = LocalAppStrings.current
    var folderTitle by remember(existing?.folderId) { mutableStateOf(existing?.title.orEmpty()) }
    var folderUrl by remember(existing?.folderId) { mutableStateOf(existing?.folderUrl.orEmpty()) }
    var chosenDeptId by remember(existing?.folderId) { mutableStateOf(initialDepartmentId) }
    var chosenYear by remember(existing?.folderId) { mutableStateOf(initialYear) }
    val chosenDept = departments.firstOrNull { it.id == chosenDeptId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) strings.addStorageSource else strings.editStorageSource) },
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
    onFilePicked: (Uri) -> Unit,
    onDeleteFile: (VerifiedFileRecord) -> Unit
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
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
                                    IconButton(
                                        onClick = { onDeleteFile(file) },
                                        modifier = Modifier.testTag("delete_file_${file.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = strings.deleteLabel,
                                            tint = MaterialTheme.colorScheme.error
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
}

@Composable
fun InAppFilePreviewDialog(
    preview: InAppFilePreview,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit
) {
    val strings = LocalAppStrings.current
    var imageBitmap by remember(preview.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var pdfPageBitmap by remember(preview.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var pageIndex by remember(preview.file.absolutePath) { mutableIntStateOf(0) }
    var pageCount by remember(preview.file.absolutePath) { mutableIntStateOf(0) }
    var previewError by remember(preview.file.absolutePath) { mutableStateOf<String?>(null) }

    val isPdf = preview.mimeType == "application/pdf"

    val pageCache = remember(preview.file.absolutePath) {
        object : LinkedHashMap<Int, Bitmap>(6, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean = size > 5
        }
    }
    val renderMutex = remember(preview.file.absolutePath) { Mutex() }
    val pdfSession = remember(preview.file.absolutePath, isPdf) {
        if (!isPdf) null
        else runCatching {
            val pfd = ParcelFileDescriptor.open(preview.file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            pfd to renderer
        }.getOrNull()
    }

    LaunchedEffect(preview.file.absolutePath, pageIndex) {
        previewError = null
        if (isPdf) {
            val cached = pageCache[pageIndex]
            if (cached != null) {
                pdfPageBitmap = cached
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    val session = pdfSession ?: error("Could not open PDF renderer.")
                    val renderer = session.second
                    val count = renderer.pageCount
                    pageCount = count
                    if (count > 0) {
                        val safeIndex = pageIndex.coerceIn(0, count - 1)
                        val rendered = renderMutex.withLock {
                            pageCache[safeIndex] ?: renderer.openPage(safeIndex).use { page ->
                                val targetWidth = (page.width * 2).coerceIn(360, 1280)
                                val targetHeight = ((page.height.toFloat() / page.width.coerceAtLeast(1)) * targetWidth)
                                    .toInt()
                                    .coerceIn(400, 1920)
                                val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pageCache[safeIndex] = bmp
                                bmp
                            }
                        }
                        pdfPageBitmap = rendered

                        // Preload next page smoothly for instant Next navigation
                        val nextIdx = safeIndex + 1
                        if (nextIdx < count && !pageCache.containsKey(nextIdx)) {
                            renderMutex.withLock {
                                if (!pageCache.containsKey(nextIdx)) {
                                    renderer.openPage(nextIdx).use { nextPage ->
                                        val w = (nextPage.width * 2).coerceIn(360, 1280)
                                        val h = ((nextPage.height.toFloat() / nextPage.width.coerceAtLeast(1)) * w)
                                            .toInt()
                                            .coerceIn(400, 1920)
                                        val nextBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                        nextBmp.eraseColor(android.graphics.Color.WHITE)
                                        nextPage.render(nextBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        pageCache[nextIdx] = nextBmp
                                    }
                                }
                            }
                        }
                    }
                }.onFailure { err ->
                    previewError = err.message ?: "Could not render PDF preview."
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = BitmapFactory.decodeFile(preview.file.absolutePath)
                    if (bmp == null) {
                        previewError = "Could not decode image."
                    } else {
                        imageBitmap = bmp
                    }
                }.onFailure { err ->
                    previewError = err.message ?: "Could not load image."
                }
            }
        }
    }

    DisposableEffect(preview.file.absolutePath) {
        onDispose {
            runCatching { pdfSession?.second?.close() }
            runCatching { pdfSession?.first?.close() }
            pageCache.clear()
            imageBitmap = null
            pdfPageBitmap = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(min = 320.dp, max = 720.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.inAppPreviewTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = preview.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = strings.cancelAction)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 240.dp, max = 520.dp)
                        .background(Color.Black.copy(alpha = 0.06f), MaterialTheme.shapes.medium)
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    val activeBmp = if (isPdf) pdfPageBitmap else imageBitmap
                    when {
                        previewError != null -> Text(
                            text = previewError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                        activeBmp != null -> Image(
                            bitmap = activeBmp.asImageBitmap(),
                            contentDescription = preview.title,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                        )
                        else -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                    }
                }

                if (isPdf && pageCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (pageIndex > 0) pageIndex-- },
                            enabled = pageIndex > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = strings.prevQuestion)
                        }
                        Text(
                            text = "${pageIndex + 1} / $pageCount",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        IconButton(
                            onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                            enabled = pageIndex < pageCount - 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = strings.nextQuestion)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onOpenExternal) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(strings.openExternallyAction, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = onDismiss) {
                        Text(strings.cancelAction)
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
