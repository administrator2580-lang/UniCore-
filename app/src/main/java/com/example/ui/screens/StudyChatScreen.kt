package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AcademicDepartment
import com.example.data.model.AcademicScopeFilter
import com.example.data.model.SectionMessage
import com.example.data.model.SectionMessageType
import com.example.data.model.StudyChatMessage
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.security.InputValidator
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StudyChatScreen(
    currentUserId: String,
    userRole: UserRole,
    userProfile: UserProfile? = null,
    departmentsState: UiState<List<AcademicDepartment>>,
    selectedRoomId: String,
    messagesState: UiState<List<StudyChatMessage>>,
    sectionMessagesState: UiState<List<SectionMessage>> = UiState.Success(emptyList()),
    onSelectRoom: (String?, Int?) -> Unit,
    onSendMessage: (String, String?, String?, String, Uri?) -> Unit,
    onDeleteMessage: (String, String) -> Unit,
    onSendSectionMessage: (String?, String, String, String, SectionMessageType, Boolean, String, String?) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onDeleteSectionMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val departments = (departmentsState as? UiState.Success)?.data.orEmpty()

    var showSectionFeed by remember { mutableStateOf(false) }
    var selectedDeptId by remember { mutableStateOf<String?>(null) }
    var selectedYearIdx by remember { mutableStateOf<Int?>(null) }
    var messageInput by remember { mutableStateOf("") }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    var zoomedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Section feed composer states
    var sectionInput by remember { mutableStateOf("") }
    var sectionVideoUrl by remember { mutableStateOf("") }
    var targetSectionName by remember(userProfile?.sectionName) {
        mutableStateOf(userProfile?.sectionName?.takeIf { it.isNotBlank() } ?: "سكشن 1")
    }
    var sectionMsgPinned by remember { mutableStateOf(false) }

    val selectedDept = remember(departments, selectedDeptId) {
        departments.firstOrNull { it.id == selectedDeptId }
    }
    val selectedYearLabel = remember(selectedDept, selectedYearIdx) {
        val idx = selectedYearIdx ?: return@remember null
        selectedDept?.years?.getOrNull(idx)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) attachedImageUri = uri
    }

    val listState = rememberLazyListState()
    val messages = (messagesState as? UiState.Success)?.data.orEmpty()
    LaunchedEffect(messages.size, selectedRoomId) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Segmented Toggle: Dept Rooms vs Section Feed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !showSectionFeed,
                onClick = { showSectionFeed = false },
                leadingIcon = { Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(strings.departmentRoomsTab) },
                modifier = Modifier.testTag("chat_tab_rooms")
            )
            FilterChip(
                selected = showSectionFeed,
                onClick = { showSectionFeed = true },
                leadingIcon = { Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(strings.sectionMessagesTab) },
                modifier = Modifier.testTag("chat_tab_section_feed")
            )
        }

        if (showSectionFeed) {
            SectionMessagesFeedContent(
                currentUserId = currentUserId,
                userRole = userRole,
                userProfile = userProfile,
                departments = departments,
                sectionMessagesState = sectionMessagesState,
                targetSectionName = targetSectionName,
                onTargetSectionChange = { targetSectionName = it },
                sectionInput = sectionInput,
                onSectionInputChange = { sectionInput = it },
                sectionVideoUrl = sectionVideoUrl,
                onSectionVideoUrlChange = { sectionVideoUrl = it },
                pinned = sectionMsgPinned,
                onPinnedChange = { sectionMsgPinned = it },
                onSendSectionMessage = { deptId, deptName, yearLabel, secName ->
                    val type = if (userRole != UserRole.STUDENT) SectionMessageType.ANNOUNCEMENT else SectionMessageType.MESSAGE
                    onSendSectionMessage(
                        deptId,
                        deptName,
                        yearLabel,
                        secName,
                        type,
                        sectionMsgPinned && userRole != UserRole.STUDENT,
                        sectionInput,
                        sectionVideoUrl.trim().ifBlank { null }
                    )
                    sectionInput = ""
                    sectionVideoUrl = ""
                    sectionMsgPinned = false
                },
                onDeleteSectionMessage = onDeleteSectionMessage,
                onOpenUrl = { url ->
                    val valid = InputValidator.validateOptionalHttpsUrl(url)
                    if (valid is InputValidator.ValidationResult.Valid && valid.value != null) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(valid.value)))
                        }.onFailure {
                            Toast.makeText(context, "No viewer app found.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            return@Column
        }

        // Room Selector Header
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = strings.chatTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.chatSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Department room selector
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedDeptId == null,
                        onClick = {
                            selectedDeptId = null
                            selectedYearIdx = null
                            onSelectRoom(null, null)
                        },
                        label = { Text(strings.chatGeneralRoom) },
                        modifier = Modifier.testTag("chat_room_general")
                    )
                    departments.forEach { dept ->
                        FilterChip(
                            selected = selectedDeptId == dept.id,
                            onClick = {
                                selectedDeptId = dept.id
                                val nextYear = if (dept.years.isNotEmpty()) 0 else null
                                selectedYearIdx = nextYear
                                onSelectRoom(if (nextYear != null) dept.id else null, nextYear)
                            },
                            label = { Text(dept.name) },
                            modifier = Modifier.testTag("chat_dept_${dept.id}")
                        )
                    }
                }

                // Academic year selector inside selected department
                val years = selectedDept?.years.orEmpty()
                if (selectedDept != null && years.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        years.forEachIndexed { idx, yearLabel ->
                            FilterChip(
                                selected = selectedYearIdx == idx,
                                onClick = {
                                    selectedYearIdx = idx
                                    onSelectRoom(selectedDept.id, idx)
                                },
                                label = { Text(yearLabel) }
                            )
                        }
                    }
                }
            }
        }

        // Messages List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (messagesState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> ErrorBannerCard(messagesState.message)
                is UiState.Success -> {
                    if (messages.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyStateCard(
                                icon = Icons.Default.Forum,
                                title = if (selectedDept != null && selectedYearLabel != null) {
                                    "${selectedDept.name} • $selectedYearLabel"
                                } else {
                                    strings.chatGeneralRoom
                                },
                                subtitle = strings.noChatMessages
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                val isMine = msg.senderUid == currentUserId
                                val canDelete = isMine || userRole != UserRole.STUDENT
                                val roleEnum = UserRole.fromRaw(msg.senderRole)
                                val roleLabel = when (roleEnum) {
                                    UserRole.SUPER_ADMIN -> strings.roleSuperAdmin
                                    UserRole.ADMIN -> strings.roleAdmin
                                    UserRole.STUDENT -> strings.roleStudent
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                                ) {
                                    Surface(
                                        color = if (isMine) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                        },
                                        shape = MaterialTheme.shapes.medium,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth(0.86f)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                                    Text(
                                                        text = msg.senderName,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Surface(
                                                        color = when (roleEnum) {
                                                            UserRole.SUPER_ADMIN -> MaterialTheme.colorScheme.tertiaryContainer
                                                            UserRole.ADMIN -> MaterialTheme.colorScheme.secondaryContainer
                                                            UserRole.STUDENT -> MaterialTheme.colorScheme.surface
                                                        },
                                                        shape = MaterialTheme.shapes.extraSmall
                                                    ) {
                                                        Text(
                                                            text = roleLabel,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                if (canDelete) {
                                                    IconButton(
                                                        onClick = { onDeleteMessage(selectedRoomId, msg.id) },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = strings.deleteLabel,
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            if (msg.text.isNotBlank()) {
                                                Text(
                                                    text = msg.text,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }

                                            val bmp by produceState<Bitmap?>(
                                                initialValue = null,
                                                key1 = msg.id,
                                                key2 = msg.attachmentBase64
                                            ) {
                                                val b64 = msg.attachmentBase64
                                                value = if (b64.isNullOrBlank()) {
                                                    null
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                        }.getOrNull()
                                                    }
                                                }
                                            }
                                            bmp?.let { decodedBmp ->
                                                Image(
                                                    bitmap = decodedBmp.asImageBitmap(),
                                                    contentDescription = msg.attachmentName ?: "Chat attachment",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 220.dp)
                                                        .clip(MaterialTheme.shapes.small)
                                                        .clickable { zoomedImageBitmap = decodedBmp }
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

        // Composer Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (attachedImageUri != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(
                                    text = attachedImageUri?.lastPathSegment ?: strings.attachImageButton,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { attachedImageUri = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = strings.clearLabel, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("chat_attach_image")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = strings.attachImageButton)
                    }
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        placeholder = { Text(strings.chatPlaceholder) },
                        maxLines = 3,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )
                    FilledIconButton(
                        onClick = {
                            val textToSend = messageInput
                            val imageToSend = attachedImageUri
                            if (textToSend.isNotBlank() || imageToSend != null) {
                                onSendMessage(
                                    selectedRoomId,
                                    selectedDept?.name,
                                    selectedYearLabel,
                                    textToSend,
                                    imageToSend
                                )
                                messageInput = ""
                                attachedImageUri = null
                            }
                        },
                        enabled = messageInput.isNotBlank() || attachedImageUri != null,
                        modifier = Modifier.testTag("chat_send_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.sendAction)
                    }
                }
            }
        }
    }

    val zoomed = zoomedImageBitmap
    if (zoomed != null) {
        AlertDialog(
            onDismissRequest = { zoomedImageBitmap = null },
            text = {
                Image(
                    bitmap = zoomed.asImageBitmap(),
                    contentDescription = "Zoomed attachment",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { zoomedImageBitmap = null }) {
                    Text(strings.cancelAction)
                }
            }
        )
    }
}

@Composable
private fun SectionMessagesFeedContent(
    currentUserId: String,
    userRole: UserRole,
    userProfile: UserProfile?,
    departments: List<AcademicDepartment>,
    sectionMessagesState: UiState<List<SectionMessage>>,
    targetSectionName: String,
    onTargetSectionChange: (String) -> Unit,
    sectionInput: String,
    onSectionInputChange: (String) -> Unit,
    sectionVideoUrl: String,
    onSectionVideoUrlChange: (String) -> Unit,
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    onSendSectionMessage: (String?, String, String, String) -> Unit,
    onDeleteSectionMessage: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    val canPin = userRole != UserRole.STUDENT
    var chosenDeptId by remember(userProfile?.departmentId, departments) {
        mutableStateOf(userProfile?.departmentId?.takeIf { it.isNotBlank() } ?: departments.firstOrNull()?.id)
    }
    val chosenDept = departments.firstOrNull { it.id == chosenDeptId }
    var chosenYear by remember(userProfile?.academicYear, chosenDept) {
        mutableStateOf(
            userProfile?.academicYear?.takeIf { it.isNotBlank() }
                ?: chosenDept?.years?.firstOrNull()
                ?: "الفرقة الأولى"
        )
    }

    val activeDeptId = if (userRole == UserRole.STUDENT) userProfile?.departmentId else chosenDept?.id
    val activeDeptName = if (userRole == UserRole.STUDENT) {
        userProfile?.departmentName?.takeIf { it.isNotBlank() } ?: chosenDept?.name ?: "عام"
    } else {
        chosenDept?.name ?: userProfile?.departmentName ?: "عام"
    }
    val activeYear = if (userRole == UserRole.STUDENT) {
        userProfile?.academicYear?.takeIf { it.isNotBlank() } ?: chosenYear
    } else {
        chosenYear
    }
    val activeSection = if (userRole == UserRole.STUDENT) {
        userProfile?.sectionName?.takeIf { it.isNotBlank() } ?: targetSectionName
    } else {
        targetSectionName.ifBlank { "سكشن 1" }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(strings.sectionMessagesTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "$activeDeptName • $activeYear • $activeSection",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (canPin && departments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        departments.forEach { dept ->
                            FilterChip(
                                selected = chosenDeptId == dept.id,
                                onClick = {
                                    chosenDeptId = dept.id
                                    chosenYear = dept.years.firstOrNull() ?: chosenYear
                                },
                                label = { Text(dept.name) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        chosenDept?.years.orEmpty().forEach { yr ->
                            FilterChip(
                                selected = chosenYear == yr,
                                onClick = { chosenYear = yr },
                                label = { Text(yr) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = targetSectionName,
                        onValueChange = onTargetSectionChange,
                        label = { Text(strings.sectionLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (sectionMessagesState) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> ErrorBannerCard(sectionMessagesState.message)
                is UiState.Success -> {
                    val scoped = sectionMessagesState.data.filter { msg ->
                        if (userRole == UserRole.STUDENT) {
                            AcademicScopeFilter.matchesStudentScope(
                                userProfile, userRole, msg.departmentId, msg.departmentName, msg.academicYear, msg.sectionName
                            )
                        } else {
                            (chosenDept == null || msg.departmentId == chosenDept.id || msg.departmentName == chosenDept.name)
                        }
                    }
                    if (scoped.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyStateCard(
                                icon = Icons.Default.Groups,
                                title = strings.sectionMessagesTitle,
                                subtitle = strings.noSectionMessages
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(scoped, key = { it.id }) { msg ->
                                val canDelete = msg.senderUid == currentUserId || userRole != UserRole.STUDENT
                                Surface(
                                    color = if (msg.pinned) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                                if (msg.pinned) {
                                                    Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                }
                                                Text(msg.senderName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                                                    Text(
                                                        text = "${msg.academicYear} • ${msg.sectionName}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (canDelete) {
                                                IconButton(onClick = { onDeleteSectionMessage(msg.id) }, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Default.Delete, contentDescription = strings.deleteLabel, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                        Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                                        if (!msg.videoUrl.isNullOrBlank()) {
                                            FilledTonalButton(onClick = { onOpenUrl(msg.videoUrl) }) {
                                                Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
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

        // Section message composer
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (canPin) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = sectionVideoUrl,
                            onValueChange = onSectionVideoUrlChange,
                            placeholder = { Text(strings.videoUrlLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = pinned, onCheckedChange = onPinnedChange)
                            Text(strings.sectionPinnedBadge, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sectionInput,
                        onValueChange = onSectionInputChange,
                        placeholder = { Text(strings.chatPlaceholder) },
                        maxLines = 3,
                        modifier = Modifier.weight(1f).testTag("section_message_input")
                    )
                    FilledIconButton(
                        onClick = {
                            if (sectionInput.isNotBlank()) {
                                onSendSectionMessage(activeDeptId, activeDeptName, activeYear, activeSection)
                            }
                        },
                        enabled = sectionInput.isNotBlank(),
                        modifier = Modifier.testTag("section_send_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.sendAction)
                    }
                }
            }
        }
    }
}
