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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AcademicDepartment
import com.example.data.model.AcademicScopeFilter
import com.example.data.model.InstructionNotice
import com.example.data.model.InstructionPriority
import com.example.data.model.ScheduleItem
import com.example.data.model.ScheduleItemType
import com.example.data.model.SystemSettings
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.ZoomSession
import com.example.data.preferences.AppLanguage
import com.example.security.InputValidator
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

enum class CampusSubTab { SCHEDULE, ZOOM, INSTRUCTIONS, TUTORIAL, FUN }

@Composable
fun CampusHubScreen(
    userRole: UserRole,
    userProfile: UserProfile?,
    systemSettings: SystemSettings,
    departmentsState: UiState<List<AcademicDepartment>>,
    scheduleItemsState: UiState<List<ScheduleItem>>,
    zoomSessionsState: UiState<List<ZoomSession>>,
    instructionsState: UiState<List<InstructionNotice>>,
    onCreateScheduleItem: (
        title: String,
        itemType: ScheduleItemType,
        dayOfWeek: Int,
        startTimeText: String,
        endTimeText: String,
        locationOrMode: String,
        instructorName: String?,
        departmentId: String?,
        departmentName: String?,
        academicYear: String?,
        sectionName: String?,
        courseId: String?,
        videoUrl: String?
    ) -> Unit,
    onDeleteScheduleItem: (String) -> Unit,
    onCreateZoomSession: (
        title: String,
        topic: String,
        zoomUrl: String,
        scheduledAtMillis: Long,
        durationMinutes: Int,
        departmentId: String?,
        departmentName: String?,
        academicYear: String?,
        sectionName: String?
    ) -> Unit,
    onDeleteZoomSession: (String) -> Unit,
    onCreateInstruction: (
        title: String,
        body: String,
        priority: InstructionPriority,
        departmentId: String?,
        departmentName: String?,
        academicYear: String?,
        sectionName: String?
    ) -> Unit,
    onDeleteInstruction: (String) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val departments = (departmentsState as? UiState.Success)?.data.orEmpty()
    val canManage = userRole != UserRole.STUDENT

    var selectedSubTab by remember { mutableStateOf(CampusSubTab.SCHEDULE) }
    var showCreateSchedule by remember { mutableStateOf(false) }
    var showCreateZoom by remember { mutableStateOf(false) }
    var showCreateInstruction by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        // Sub-Tab Pill Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedSubTab == CampusSubTab.SCHEDULE,
                onClick = { selectedSubTab = CampusSubTab.SCHEDULE },
                leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(strings.campusScheduleTab) },
                modifier = Modifier.testTag("campus_tab_schedule")
            )
            FilterChip(
                selected = selectedSubTab == CampusSubTab.ZOOM,
                onClick = { selectedSubTab = CampusSubTab.ZOOM },
                leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(strings.campusZoomTab) },
                modifier = Modifier.testTag("campus_tab_zoom")
            )
            FilterChip(
                selected = selectedSubTab == CampusSubTab.INSTRUCTIONS,
                onClick = { selectedSubTab = CampusSubTab.INSTRUCTIONS },
                leadingIcon = { Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(strings.campusInstructionsTab) },
                modifier = Modifier.testTag("campus_tab_instructions")
            )
            FilterChip(
                selected = selectedSubTab == CampusSubTab.TUTORIAL,
                onClick = { selectedSubTab = CampusSubTab.TUTORIAL },
                leadingIcon = { Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(strings.campusTutorialTab) },
                modifier = Modifier.testTag("campus_tab_tutorial")
            )
            FilterChip(
                selected = selectedSubTab == CampusSubTab.FUN,
                onClick = { selectedSubTab = CampusSubTab.FUN },
                leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(strings.campusFunTab) },
                modifier = Modifier.testTag("campus_tab_fun")
            )
        }

        when (selectedSubTab) {
            CampusSubTab.SCHEDULE -> ScheduleSectionView(
                canManage = canManage,
                userRole = userRole,
                userProfile = userProfile,
                scheduleState = scheduleItemsState,
                onAddSlot = { showCreateSchedule = true },
                onDeleteSlot = onDeleteScheduleItem,
                onOpenVideo = openHttps
            )
            CampusSubTab.ZOOM -> ZoomSectionView(
                canManage = canManage,
                userRole = userRole,
                userProfile = userProfile,
                zoomState = zoomSessionsState,
                onAddZoom = { showCreateZoom = true },
                onDeleteZoom = onDeleteZoomSession,
                onJoinZoom = openHttps
            )
            CampusSubTab.INSTRUCTIONS -> InstructionsSectionView(
                canManage = canManage,
                userRole = userRole,
                userProfile = userProfile,
                instructionsState = instructionsState,
                onAddNotice = { showCreateInstruction = true },
                onDeleteNotice = onDeleteInstruction
            )
            CampusSubTab.TUTORIAL -> TutorialSectionView(
                settings = systemSettings,
                onWatchVideo = openHttps
            )
            CampusSubTab.FUN -> ForFunSectionView()
        }
    }

    if (showCreateSchedule) {
        CreateScheduleDialog(
            departments = departments,
            onDismiss = { showCreateSchedule = false },
            onConfirm = { title, type, day, start, end, loc, inst, dept, year, sec, video ->
                onCreateScheduleItem(
                    title, type, day, start, end, loc, inst,
                    dept?.id, dept?.name, year, sec, null, video
                )
                showCreateSchedule = false
            }
        )
    }

    if (showCreateZoom) {
        CreateZoomDialog(
            departments = departments,
            onDismiss = { showCreateZoom = false },
            onConfirm = { title, topic, url, scheduledMs, durMins, dept, year, sec ->
                onCreateZoomSession(
                    title, topic, url, scheduledMs, durMins,
                    dept?.id, dept?.name, year, sec
                )
                showCreateZoom = false
            }
        )
    }

    if (showCreateInstruction) {
        CreateInstructionDialog(
            departments = departments,
            onDismiss = { showCreateInstruction = false },
            onConfirm = { title, body, priority, dept, year, sec ->
                onCreateInstruction(
                    title, body, priority, dept?.id, dept?.name, year, sec
                )
                showCreateInstruction = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSectionView(
    canManage: Boolean,
    userRole: UserRole,
    userProfile: UserProfile?,
    scheduleState: UiState<List<ScheduleItem>>,
    onAddSlot: () -> Unit,
    onDeleteSlot: (String) -> Unit,
    onOpenVideo: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    val dayNames = if (strings.language == AppLanguage.ARABIC) {
        listOf("السبت", "الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة")
    } else {
        listOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri")
    }
    var selectedDay by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.scheduleTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (canManage) {
                    Button(onClick = onAddSlot, modifier = Modifier.testTag("add_schedule_button")) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(strings.addScheduleAction)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedDay == null,
                    onClick = { selectedDay = null },
                    label = { Text(strings.allTypes) }
                )
                dayNames.forEachIndexed { idx, name ->
                    FilterChip(
                        selected = selectedDay == idx,
                        onClick = { selectedDay = if (selectedDay == idx) null else idx },
                        label = { Text(name) }
                    )
                }
            }
        }

        when (scheduleState) {
            is UiState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> item { ErrorBannerCard(scheduleState.message) }
            is UiState.Success -> {
                val filtered = scheduleState.data.filter { item ->
                    AcademicScopeFilter.matchesStudentScope(
                        userProfile, userRole, item.departmentId, item.departmentName, item.academicYear, item.sectionName
                    ) && (selectedDay == null || item.dayOfWeek.toInt() == selectedDay)
                }
                if (filtered.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.CalendarToday,
                            title = strings.noScheduleTitle,
                            subtitle = strings.noScheduleSub
                        )
                    }
                } else {
                    items(filtered, key = { it.id }) { slot ->
                        val dayLabel = dayNames.getOrElse(slot.dayOfWeek.toInt()) { dayNames.first() }
                        val typeLabel = when (ScheduleItemType.fromRaw(slot.itemType)) {
                            ScheduleItemType.LECTURE -> strings.scheduleTypeLecture
                            ScheduleItemType.SECTION -> strings.scheduleTypeSection
                            ScheduleItemType.ASSIGNMENT -> strings.scheduleTypeAssignment
                            ScheduleItemType.EXAM -> strings.scheduleTypeExam
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                                            Text(dayLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                            Text(typeLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                        val meta = listOfNotNull(slot.departmentName, slot.academicYear, slot.sectionName)
                                            .filter { it.isNotBlank() }.joinToString(" • ")
                                        if (meta.isNotBlank()) {
                                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                                Text(meta, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                            }
                                        }
                                    }
                                    if (canManage) {
                                        IconButton(onClick = { onDeleteSlot(slot.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = strings.deleteLabel, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                                Text(slot.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "${slot.startTimeText} — ${slot.endTimeText} • ${slot.locationOrMode}" +
                                        if (!slot.instructorName.isNullOrBlank()) " • ${slot.instructorName}" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!slot.videoUrl.isNullOrBlank()) {
                                    FilledTonalButton(onClick = { onOpenVideo(slot.videoUrl) }) {
                                        Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text(strings.watchVideoAction)
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
private fun ZoomSectionView(
    canManage: Boolean,
    userRole: UserRole,
    userProfile: UserProfile?,
    zoomState: UiState<List<ZoomSession>>,
    onAddZoom: () -> Unit,
    onDeleteZoom: (String) -> Unit,
    onJoinZoom: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.zoomTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (canManage) {
                    Button(onClick = onAddZoom, modifier = Modifier.testTag("add_zoom_button")) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(strings.createZoomAction)
                    }
                }
            }
        }

        when (zoomState) {
            is UiState.Loading -> item { CircularProgressIndicator() }
            is UiState.Error -> item { ErrorBannerCard(zoomState.message) }
            is UiState.Success -> {
                val nowMs = System.currentTimeMillis()
                val scoped = zoomState.data.filter { s ->
                    AcademicScopeFilter.matchesStudentScope(
                        userProfile, userRole, s.departmentId, s.departmentName, s.academicYear, s.sectionName
                    )
                }
                if (scoped.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.Videocam,
                            title = strings.noZoomTitle,
                            subtitle = strings.noZoomSub
                        )
                    }
                } else {
                    items(scoped, key = { it.id }) { session ->
                        val endMs = session.scheduledAtMillis + session.durationMinutes * 60_000L
                        val isLive = nowMs in session.scheduledAtMillis..endMs
                        val isEnded = nowMs > endMs
                        val formattedTime = remember(session.scheduledAtMillis) {
                            SimpleDateFormat("MMM d • hh:mm a", Locale.getDefault()).format(Date(session.scheduledAtMillis))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(
                                width = if (isLive) 1.5.dp else 1.dp,
                                color = if (isLive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = when {
                                            isLive -> MaterialTheme.colorScheme.secondaryContainer
                                            isEnded -> MaterialTheme.colorScheme.surfaceVariant
                                            else -> MaterialTheme.colorScheme.primaryContainer
                                        },
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = when {
                                                isLive -> strings.liveNowBadge
                                                isEnded -> strings.endedBadge
                                                else -> strings.upcomingBadge
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    if (canManage) {
                                        IconButton(onClick = { onDeleteZoom(session.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = strings.deleteLabel, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                                Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (!session.topic.isNullOrBlank()) {
                                    Text(session.topic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    text = "$formattedTime (${session.durationMinutes} ${strings.minutesShort}) • ${session.hostName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Button(
                                    onClick = { onJoinZoom(session.zoomUrl) },
                                    modifier = Modifier.fillMaxWidth().testTag("join_zoom_${session.id}")
                                ) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(strings.joinZoomAction)
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
private fun InstructionsSectionView(
    canManage: Boolean,
    userRole: UserRole,
    userProfile: UserProfile?,
    instructionsState: UiState<List<InstructionNotice>>,
    onAddNotice: () -> Unit,
    onDeleteNotice: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.instructionsTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (canManage) {
                    Button(onClick = onAddNotice, modifier = Modifier.testTag("add_instruction_button")) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(strings.addInstructionAction)
                    }
                }
            }
        }

        when (instructionsState) {
            is UiState.Loading -> item { CircularProgressIndicator() }
            is UiState.Error -> item { ErrorBannerCard(instructionsState.message) }
            is UiState.Success -> {
                val scoped = instructionsState.data.filter { notice ->
                    AcademicScopeFilter.matchesStudentScope(
                        userProfile, userRole, notice.departmentId, notice.departmentName, notice.academicYear, notice.sectionName
                    )
                }
                if (scoped.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.Campaign,
                            title = strings.noInstructionsTitle,
                            subtitle = strings.noInstructionsSub
                        )
                    }
                } else {
                    items(scoped, key = { it.id }) { notice ->
                        val priority = InstructionPriority.fromRaw(notice.priority)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = when (priority) {
                                    InstructionPriority.PINNED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    InstructionPriority.IMPORTANT -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                                    InstructionPriority.NORMAL -> MaterialTheme.colorScheme.surface
                                }
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (priority == InstructionPriority.PINNED) {
                                            Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        }
                                        Surface(
                                            color = when (priority) {
                                                InstructionPriority.PINNED -> MaterialTheme.colorScheme.primaryContainer
                                                InstructionPriority.IMPORTANT -> MaterialTheme.colorScheme.tertiaryContainer
                                                InstructionPriority.NORMAL -> MaterialTheme.colorScheme.secondaryContainer
                                            },
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                text = when (priority) {
                                                    InstructionPriority.PINNED -> strings.priorityPinned
                                                    InstructionPriority.IMPORTANT -> strings.priorityImportant
                                                    InstructionPriority.NORMAL -> strings.priorityNormal
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        val scopeMeta = listOfNotNull(notice.departmentName, notice.academicYear, notice.sectionName)
                                            .filter { it.isNotBlank() }.joinToString(" • ")
                                        if (scopeMeta.isNotBlank()) {
                                            Text(scopeMeta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    if (canManage) {
                                        IconButton(onClick = { onDeleteNotice(notice.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = strings.deleteLabel, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                                Text(notice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(notice.body, style = MaterialTheme.typography.bodyMedium)
                                if (notice.createdByName.isNotBlank()) {
                                    Text("${strings.byInstructor} ${notice.createdByName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun TutorialSectionView(
    settings: SystemSettings,
    onWatchVideo: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    val isAr = strings.language == AppLanguage.ARABIC
    val steps = if (isAr) {
        listOf(
            "1. حدّد التخصص والفرقة والسكشن لتظهر لك موادك وجدولك فقط.",
            "2. افتح تبويب «اللي عليا» لمتابعة الأسينمنتات ومزامنة Google Classroom مع تنبيه قبل الموعد بـ 24 ساعة.",
            "3. من «المقررات» يمكنك تفعيل المفضلة، تنزيل الملفات بدون نت، ومشاهدة فيديوهات المحاضرات.",
            "4. استخدم «الجامعة» لمتابعة الجدول، بث Zoom المباشر، تعليمات للكل، واستراحة الطلاب."
        )
    } else {
        listOf(
            "1. Set your Department, Academic Year & Section so your workspace filters cleanly.",
            "2. Check 'Due' ('اللي عليا') for coursework deadlines & Google Classroom sync with 24h auto-reminders.",
            "3. Star favorite courses, save Drive study materials offline, and watch attached lecture videos.",
            "4. Visit 'Campus' for your Section Schedule, Live Zoom rooms, Notices, and Student Break Zone."
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.tutorialTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(strings.tutorialSubtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val videoUrl = settings.tutorialVideoUrl
                    if (!videoUrl.isNullOrBlank()) {
                        Button(
                            onClick = { onWatchVideo(videoUrl) },
                            modifier = Modifier.fillMaxWidth().testTag("watch_tutorial_video_button")
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(settings.tutorialVideoTitle?.takeIf { it.isNotBlank() } ?: strings.watchTutorialButton)
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = strings.tutorialNotSetYet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
        items(steps) { stepText ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = stepText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

private data class TriviaCardItem(
    val qAr: String,
    val qEn: String,
    val optionsAr: List<String>,
    val optionsEn: List<String>,
    val correctIdx: Int
)

@Composable
private fun ForFunSectionView() {
    val strings = LocalAppStrings.current
    val isAr = strings.language == AppLanguage.ARABIC

    var remainingSeconds by remember { mutableIntStateOf(25 * 60) }
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        while (isRunning && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        if (remainingSeconds == 0) isRunning = false
    }

    val mins = remainingSeconds / 60
    val secs = remainingSeconds % 60
    val formattedClock = String.format(Locale.US, "%02d:%02d", mins, secs)

    val triviaBank = remember {
        listOf(
            TriviaCardItem(
                qAr = "أي قانون يربط بين الجهد (V) والتيار (I) والمقاومة (R)؟",
                qEn = "Which law relates Voltage (V), Current (I), and Resistance (R)?",
                optionsAr = listOf("قانون أوم (V = I×R)", "قانون كيرشوف فقط", "قانون بويل"),
                optionsEn = listOf("Ohm's Law (V = I×R)", "Boyle's Law", "Kepler's Law"),
                correctIdx = 0
            ),
            TriviaCardItem(
                qAr = "ما مصدر الطاقة الأكثر وفرة على كوكب الأرض؟",
                qEn = "What is the most abundant renewable energy source on Earth?",
                optionsAr = listOf("الفحم", "الطاقة الشمسية", "المد والجزر"),
                optionsEn = listOf("Coal", "Solar Energy", "Tidal"),
                correctIdx = 1
            ),
            TriviaCardItem(
                qAr = "كم عدد الدقائق الموصى بها في جلسة بومودورو الواحدة للتركيز العميق؟",
                qEn = "How long is a classic Pomodoro deep-focus sprint?",
                optionsAr = listOf("25 دقيقة", "90 دقيقة", "5 دقائق"),
                optionsEn = listOf("25 minutes", "90 minutes", "5 minutes"),
                correctIdx = 0
            ),
            TriviaCardItem(
                qAr = "أي وحدة تقاس بها القدرة الكهربائية الفعالة؟",
                qEn = "Which unit measures active electrical power?",
                optionsAr = listOf("الأوم (Ω)", "الفولت (V)", "الواط (Watt)"),
                optionsEn = listOf("Ohm (Ω)", "Volt (V)", "Watt (W)"),
                correctIdx = 2
            )
        )
    }

    var questionIdx by remember { mutableIntStateOf(0) }
    var selectedAnswer by remember { mutableStateOf<Int?>(null) }
    var score by remember { mutableIntStateOf(0) }
    val currentQ = triviaBank[questionIdx % triviaBank.size]

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(strings.pomodoroTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = formattedClock,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { isRunning = !isRunning },
                            modifier = Modifier.testTag("pomodoro_toggle_button")
                        ) {
                            Icon(if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(if (isRunning) strings.pomodoroPause else strings.pomodoroStart)
                        }
                        OutlinedButton(
                            onClick = {
                                isRunning = false
                                remainingSeconds = 25 * 60
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(strings.pomodoroReset)
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.triviaTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                text = "${strings.triviaScoreLabel}: $score",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = if (isAr) currentQ.qAr else currentQ.qEn,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    val opts = if (isAr) currentQ.optionsAr else currentQ.optionsEn
                    opts.forEachIndexed { idx, optLabel ->
                        val picked = selectedAnswer == idx
                        val isCorrectOption = idx == currentQ.correctIdx
                        OutlinedButton(
                            onClick = {
                                if (selectedAnswer == null) {
                                    selectedAnswer = idx
                                    if (idx == currentQ.correctIdx) score++
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("trivia_option_$idx")
                        ) {
                            Text(
                                text = when {
                                    selectedAnswer != null && isCorrectOption -> "✓ $optLabel"
                                    picked && !isCorrectOption -> "✗ $optLabel"
                                    else -> optLabel
                                },
                                color = when {
                                    selectedAnswer != null && isCorrectOption -> MaterialTheme.colorScheme.secondary
                                    picked && !isCorrectOption -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }

                    if (selectedAnswer != null) {
                        FilledTonalButton(
                            onClick = {
                                questionIdx++
                                selectedAnswer = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.triviaNextQuestion)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateScheduleDialog(
    departments: List<AcademicDepartment>,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        type: ScheduleItemType,
        dayOfWeek: Int,
        start: String,
        end: String,
        location: String,
        instructor: String?,
        dept: AcademicDepartment?,
        year: String?,
        section: String?,
        videoUrl: String?
    ) -> Unit
) {
    val strings = LocalAppStrings.current
    val dayNames = if (strings.language == AppLanguage.ARABIC) {
        listOf("السبت", "الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة")
    } else {
        listOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri")
    }
    var title by remember { mutableStateOf("") }
    var itemType by remember { mutableStateOf(ScheduleItemType.LECTURE) }
    var dayOfWeek by remember { mutableIntStateOf(0) }
    var startTime by remember { mutableStateOf("09:00 AM") }
    var endTime by remember { mutableStateOf("11:00 AM") }
    var location by remember { mutableStateOf("") }
    var instructor by remember { mutableStateOf("") }
    var chosenDeptId by remember { mutableStateOf<String?>(null) }
    var chosenYear by remember { mutableStateOf<String?>(null) }
    var sectionName by remember { mutableStateOf("") }
    var videoUrl by remember { mutableStateOf("") }
    val chosenDept = departments.firstOrNull { it.id == chosenDeptId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addScheduleAction) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(strings.courseTitleLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleItemType.entries.forEach { t ->
                        FilterChip(selected = itemType == t, onClick = { itemType = t }, label = { Text(t.name) })
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayNames.forEachIndexed { idx, label ->
                        FilterChip(selected = dayOfWeek == idx, onClick = { dayOfWeek = idx }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = startTime, onValueChange = { startTime = it }, label = { Text(strings.startTimeLabel) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = endTime, onValueChange = { endTime = it }, label = { Text(strings.endTimeLabel) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text(strings.locationHallLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = instructor, onValueChange = { instructor = it }, label = { Text(strings.byInstructor) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DepartmentYearPickerBlock(
                    departments = departments,
                    selectedDeptId = chosenDeptId,
                    selectedYear = chosenYear,
                    onSelectDept = { chosenDeptId = it?.id; chosenYear = null },
                    onSelectYear = { chosenYear = it }
                )
                OutlinedTextField(value = sectionName, onValueChange = { sectionName = it }, label = { Text(strings.sectionLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = videoUrl, onValueChange = { videoUrl = it }, label = { Text(strings.videoUrlLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        title, itemType, dayOfWeek, startTime, endTime,
                        location.ifBlank { "Online" }, instructor.ifBlank { null },
                        chosenDept, chosenYear, sectionName.trim().ifBlank { null },
                        videoUrl.trim().ifBlank { null }
                    )
                },
                enabled = title.trim().length >= 2
            ) {
                Text(strings.publishAction)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancelAction) } }
    )
}

@Composable
private fun CreateZoomDialog(
    departments: List<AcademicDepartment>,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        topic: String,
        url: String,
        scheduledMs: Long,
        durationMins: Int,
        dept: AcademicDepartment?,
        year: String?,
        section: String?
    ) -> Unit
) {
    val strings = LocalAppStrings.current
    var title by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://zoom.us/j/") }
    var startOffsetHours by remember { mutableStateOf("0") }
    var durationMins by remember { mutableStateOf("60") }
    var chosenDeptId by remember { mutableStateOf<String?>(null) }
    var chosenYear by remember { mutableStateOf<String?>(null) }
    var sectionName by remember { mutableStateOf("") }
    val chosenDept = departments.firstOrNull { it.id == chosenDeptId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createZoomAction) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(strings.courseTitleLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text(strings.zoomTopicLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(strings.zoomUrlLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = startOffsetHours, onValueChange = { startOffsetHours = it.filter(Char::isDigit).take(3) }, label = { Text(strings.dueHoursFromNowLabel) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = durationMins, onValueChange = { durationMins = it.filter(Char::isDigit).take(3) }, label = { Text(strings.durationLabel) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                DepartmentYearPickerBlock(
                    departments = departments,
                    selectedDeptId = chosenDeptId,
                    selectedYear = chosenYear,
                    onSelectDept = { chosenDeptId = it?.id; chosenYear = null },
                    onSelectYear = { chosenYear = it }
                )
                OutlinedTextField(value = sectionName, onValueChange = { sectionName = it }, label = { Text(strings.sectionLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val hrs = (startOffsetHours.toLongOrNull() ?: 0L).coerceIn(0L, 720L)
                    val dur = (durationMins.toIntOrNull() ?: 60).coerceIn(5, 360)
                    onConfirm(
                        title, topic, url,
                        System.currentTimeMillis() + hrs * 3_600_000L, dur,
                        chosenDept, chosenYear, sectionName.trim().ifBlank { null }
                    )
                },
                enabled = title.trim().length >= 2 && url.startsWith("https://")
            ) {
                Text(strings.publishAction)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancelAction) } }
    )
}

@Composable
private fun CreateInstructionDialog(
    departments: List<AcademicDepartment>,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        body: String,
        priority: InstructionPriority,
        dept: AcademicDepartment?,
        year: String?,
        section: String?
    ) -> Unit
) {
    val strings = LocalAppStrings.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(InstructionPriority.IMPORTANT) }
    var chosenDeptId by remember { mutableStateOf<String?>(null) }
    var chosenYear by remember { mutableStateOf<String?>(null) }
    var sectionName by remember { mutableStateOf("") }
    val chosenDept = departments.firstOrNull { it.id == chosenDeptId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addInstructionAction) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(strings.supportSubjectLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(strings.supportBodyLabel) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InstructionPriority.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = {
                                Text(
                                    when (p) {
                                        InstructionPriority.NORMAL -> strings.priorityNormal
                                        InstructionPriority.IMPORTANT -> strings.priorityImportant
                                        InstructionPriority.PINNED -> strings.priorityPinned
                                    }
                                )
                            }
                        )
                    }
                }
                DepartmentYearPickerBlock(
                    departments = departments,
                    selectedDeptId = chosenDeptId,
                    selectedYear = chosenYear,
                    onSelectDept = { chosenDeptId = it?.id; chosenYear = null },
                    onSelectYear = { chosenYear = it }
                )
                OutlinedTextField(value = sectionName, onValueChange = { sectionName = it }, label = { Text(strings.sectionLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, body, priority, chosenDept, chosenYear, sectionName.trim().ifBlank { null }) },
                enabled = title.trim().length >= 2 && body.trim().length >= 2
            ) {
                Text(strings.publishAction)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancelAction) } }
    )
}
