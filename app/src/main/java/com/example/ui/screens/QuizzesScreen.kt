package com.example.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.Course
import com.example.data.model.Quiz
import com.example.data.model.QuizAttempt
import com.example.data.model.UserRole
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private data class QuestionDraft(
    val prompt: String = "",
    val options: List<String> = listOf("", "", "", ""),
    val correctIdx: Int = 0
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuizzesScreen(
    currentUserId: String,
    quizzesState: UiState<List<Quiz>>,
    attemptsState: UiState<List<QuizAttempt>>,
    coursesState: UiState<List<Course>>,
    userRole: UserRole,
    onCreateQuiz: (String, String, Int, Long, Long, Uri?, List<String>, List<String>, List<Int>) -> Unit,
    onDeleteQuiz: (String) -> Unit,
    onSubmitAttempt: (Quiz, List<Int>) -> Unit,
    onCancelForViolation: (Quiz, List<Int>, String) -> Unit,
    onResetCancelledAttempt: (QuizAttempt) -> Unit,
    onGradeAttempt: (QuizAttempt) -> Unit,
    onLoadQuizImage: suspend (Quiz) -> ByteArray?
) {
    val strings = LocalAppStrings.current
    var activeQuizToTake by remember { mutableStateOf<Quiz?>(null) }
    var showCreateQuizDialog by remember { mutableStateOf(false) }
    var violationNoticeReason by remember { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val courses = (coursesState as? UiState.Success)?.data.orEmpty()
    val attempts = (attemptsState as? UiState.Success)?.data.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        if (userRole != UserRole.STUDENT) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { showCreateQuizDialog = true },
                    enabled = courses.isNotEmpty(),
                    modifier = Modifier.testTag("create_quiz_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(strings.createQuizAction)
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = strings.quizzesTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = strings.quizzesSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (quizzesState) {
                is UiState.Loading -> item { CircularProgressIndicator() }
                is UiState.Error -> item { ErrorBannerCard(quizzesState.message) }
                is UiState.Success -> {
                    if (quizzesState.data.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Default.Quiz,
                                title = strings.noQuizzesTitle,
                                subtitle = if (userRole == UserRole.STUDENT) {
                                    strings.noQuizzesStudentSub
                                } else {
                                    strings.noQuizzesAdminSub
                                }
                            )
                        }
                    } else {
                        items(quizzesState.data, key = { it.id }) { quiz ->
                            val myAttempt = attempts.firstOrNull {
                                it.quizId == quiz.id && it.userId == currentUserId
                            }
                            val isCancelledViolation = myAttempt?.status == "CANCELLED_VIOLATION"
                            val alreadySubmitted = myAttempt != null
                            val notStarted = quiz.startAtMillis > 0L && nowMillis < quiz.startAtMillis
                            val ended = quiz.endAtMillis > 0L && nowMillis > quiz.endAtMillis
                            val windowLockedForStudent = userRole == UserRole.STUDENT && (notStarted || ended)

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = quiz.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (quiz.hasImage) {
                                                Icon(
                                                    imageVector = Icons.Default.Image,
                                                    contentDescription = strings.quizAttachImageAction,
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier
                                                        .padding(end = 6.dp)
                                                        .size(18.dp)
                                                )
                                            }
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = MaterialTheme.shapes.small
                                            ) {
                                                Text(
                                                    text = "${quiz.durationMinutes} ${strings.minutesShort} • ${quiz.questionPrompts.size}Q",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                            if (userRole != UserRole.STUDENT) {
                                                IconButton(onClick = { onDeleteQuiz(quiz.id) }) {
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

                                    if (quiz.startAtMillis > 0L || quiz.endAtMillis > 0L) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (quiz.startAtMillis > 0L) {
                                                Surface(
                                                    color = if (notStarted) MaterialTheme.colorScheme.tertiaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = MaterialTheme.shapes.extraSmall
                                                ) {
                                                    Text(
                                                        text = "${strings.quizStartLabel}: ${formatQuizTime(quiz.startAtMillis)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                            if (quiz.endAtMillis > 0L) {
                                                Surface(
                                                    color = if (ended) MaterialTheme.colorScheme.errorContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = MaterialTheme.shapes.extraSmall
                                                ) {
                                                    Text(
                                                        text = "${strings.quizEndLabel}: ${formatQuizTime(quiz.endAtMillis)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = quiz.questionPrompts.firstOrNull().orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (isCancelledViolation && myAttempt != null) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = MaterialTheme.shapes.small,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "${strings.quizCancelledViolationBadge}: ${myAttempt.cancellationReason}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = { activeQuizToTake = quiz },
                                        enabled = !alreadySubmitted && !windowLockedForStudent,
                                        modifier = Modifier.testTag("take_quiz_${quiz.id}")
                                    ) {
                                        Text(
                                            when {
                                                isCancelledViolation -> strings.quizCancelledViolationBadge
                                                alreadySubmitted -> strings.submittedLocked
                                                userRole == UserRole.STUDENT && notStarted -> strings.quizNotStartedYet
                                                userRole == UserRole.STUDENT && ended -> strings.quizEndedLocked
                                                else -> strings.takeQuizAction
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = if (userRole == UserRole.STUDENT) strings.mySubmissionsTitle else strings.adminGradingTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (attempts.isEmpty()) {
                item {
                    Text(
                        text = strings.noAttemptsYet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(attempts, key = { it.id }) { attempt ->
                    val isCancelled = attempt.status == "CANCELLED_VIOLATION"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCancelled) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(text = attempt.quizTitle, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${attempt.studentName} • ${attempt.status}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isCancelled && attempt.cancellationReason.isNotBlank()) {
                                    Text(
                                        text = attempt.cancellationReason,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else if (attempt.graded) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = strings.verifiedScoreLabel,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "${strings.verifiedScoreLabel}: ${attempt.score}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }

                            if (isCancelled && userRole == UserRole.SUPER_ADMIN) {
                                OutlinedButton(
                                    onClick = { onResetCancelledAttempt(attempt) },
                                    modifier = Modifier.testTag("reset_attempt_${attempt.id}")
                                ) {
                                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text(strings.resetCancelledAttemptAction)
                                }
                            } else if (!isCancelled && userRole != UserRole.STUDENT && !attempt.graded) {
                                OutlinedButton(
                                    onClick = { onGradeAttempt(attempt) },
                                    modifier = Modifier.testTag("grade_attempt_${attempt.id}")
                                ) {
                                    Text(strings.autoGradeAction)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    activeQuizToTake?.let { quiz ->
        TakeQuizDialog(
            quiz = quiz,
            userRole = userRole,
            onLoadQuizImage = onLoadQuizImage,
            onDismiss = { activeQuizToTake = null },
            onSubmit = { answers ->
                onSubmitAttempt(quiz, answers)
                activeQuizToTake = null
            },
            onViolationAbort = { answers, reason ->
                onCancelForViolation(quiz, answers, reason)
                activeQuizToTake = null
                violationNoticeReason = reason
            }
        )
    }

    val violationReason = violationNoticeReason
    if (violationReason != null) {
        AlertDialog(
            onDismissRequest = { violationNoticeReason = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = strings.quizCancelledViolationTitle,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(violationReason) },
            confirmButton = {
                Button(
                    onClick = { violationNoticeReason = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(strings.cancelAction)
                }
            }
        )
    }

    if (showCreateQuizDialog && courses.isNotEmpty()) {
        CreateQuizDialog(
            courses = courses,
            onDismiss = { showCreateQuizDialog = false },
            onConfirm = { courseId, title, duration, startMs, endMs, imageUri, prompts, optionBlocks, correctIndices ->
                onCreateQuiz(courseId, title, duration, startMs, endMs, imageUri, prompts, optionBlocks, correctIndices)
                showCreateQuizDialog = false
            }
        )
    }
}

@Composable
private fun TakeQuizDialog(
    quiz: Quiz,
    userRole: UserRole,
    onLoadQuizImage: suspend (Quiz) -> ByteArray?,
    onDismiss: () -> Unit,
    onSubmit: (List<Int>) -> Unit,
    onViolationAbort: (List<Int>, String) -> Unit
) {
    val strings = LocalAppStrings.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val totalQuestions = quiz.questionPrompts.size.coerceAtLeast(1)
    var currentIndex by remember(quiz.id) { mutableIntStateOf(0) }
    var submittedOrClosed by remember(quiz.id) { mutableStateOf(false) }
    val selectedAnswers = remember(quiz.id) {
        mutableStateListOf<Int>().apply { repeat(totalQuestions) { add(0) } }
    }

    var quizImageBitmap by remember(quiz.id) { mutableStateOf<Bitmap?>(null) }
    var loadingImage by remember(quiz.id) { mutableStateOf(quiz.hasImage) }
    var zoomImage by remember(quiz.id) { mutableStateOf(false) }

    LaunchedEffect(quiz.id, quiz.hasImage) {
        if (quiz.hasImage) {
            loadingImage = true
            val bytes = onLoadQuizImage(quiz)
            quizImageBitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            loadingImage = false
        }
    }

    // Strict Anti-Cheat: Leaving app / backgrounding during active quiz cancels & locks attempt
    DisposableEffect(lifecycleOwner, quiz.id, userRole) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !submittedOrClosed && userRole == UserRole.STUDENT) {
                submittedOrClosed = true
                onViolationAbort(
                    selectedAnswers.toList(),
                    "تم إلغاء الاختبار بسبب الخروج من التطبيق أثناء الاختبار."
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val prompt = quiz.questionPrompts.getOrElse(currentIndex) { "" }
    val options = remember(quiz, currentIndex) {
        quiz.optionBlocks.getOrNull(currentIndex)?.split("|").orEmpty().ifEmpty { listOf("A", "B") }
    }

    AlertDialog(
        onDismissRequest = {
            if (!submittedOrClosed) {
                submittedOrClosed = true
                onDismiss()
            }
        },
        title = {
            Column {
                Text(quiz.title)
                Text(
                    text = "${strings.questionWord} ${currentIndex + 1} ${strings.ofWord} $totalQuestions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (userRole == UserRole.STUDENT) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = strings.quizAntiCheatWarning,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                if (quiz.hasImage) {
                    when {
                        loadingImage -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        quizImageBitmap != null -> {
                            val bmp = quizImageBitmap!!
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = quiz.imageName.ifBlank { "Quiz figure" },
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { zoomImage = true }
                            )
                        }
                    }
                }

                Text(text = prompt, style = MaterialTheme.typography.bodyLarge)
                options.forEachIndexed { idx, opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAnswers[currentIndex] = idx }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedAnswers[currentIndex] == idx,
                            onClick = { selectedAnswers[currentIndex] = idx }
                        )
                        Text(text = opt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (currentIndex < totalQuestions - 1) {
                Button(onClick = { currentIndex++ }) {
                    Text(strings.nextQuestion)
                }
            } else {
                Button(
                    onClick = {
                        submittedOrClosed = true
                        onSubmit(selectedAnswers.toList())
                    },
                    modifier = Modifier.testTag("submit_quiz_confirm")
                ) {
                    Text(strings.submitQuizAction)
                }
            }
        },
        dismissButton = {
            if (currentIndex > 0) {
                TextButton(onClick = { currentIndex-- }) { Text(strings.prevQuestion) }
            } else {
                TextButton(onClick = {
                    submittedOrClosed = true
                    onDismiss()
                }) { Text(strings.cancelAction) }
            }
        }
    )

    if (zoomImage && quizImageBitmap != null) {
        AlertDialog(
            onDismissRequest = { zoomImage = false },
            text = {
                Image(
                    bitmap = quizImageBitmap!!.asImageBitmap(),
                    contentDescription = quiz.imageName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { zoomImage = false }) { Text(strings.cancelAction) }
            }
        )
    }
}

@Composable
private fun CreateQuizDialog(
    courses: List<Course>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, Long, Long, Uri?, List<String>, List<String>, List<Int>) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    var selectedCourseId by remember { mutableStateOf(courses.first().id) }
    var title by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(15) }
    var startAtMillis by remember { mutableLongStateOf(0L) }
    var endAtMillis by remember { mutableLongStateOf(0L) }
    var quizImageUri by remember { mutableStateOf<Uri?>(null) }
    val drafts = remember { mutableStateListOf(QuestionDraft()) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) quizImageUri = uri
    }

    fun pickDateTime(initialMillis: Long, onSelected: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply {
            if (initialMillis > 0L) timeInMillis = initialMillis
        }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        val picked = Calendar.getInstance().apply {
                            set(year, month, dayOfMonth, hourOfDay, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onSelected(picked.timeInMillis)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createQuizAction) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(strings.selectCourseLabel, style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    courses.forEach { course ->
                        FilterChip(
                            selected = selectedCourseId == course.id,
                            onClick = { selectedCourseId = course.id },
                            label = { Text(course.title) }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(strings.courseTitleLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(strings.durationLabel, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 30, 60).forEach { mins ->
                        FilterChip(
                            selected = duration == mins,
                            onClick = { duration = mins },
                            label = { Text("$mins ${strings.minutesShort}") }
                        )
                    }
                }

                // Start & End Schedule Pickers
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { pickDateTime(startAtMillis) { startAtMillis = it } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = if (startAtMillis > 0L) {
                                    "${strings.quizStartLabel}: ${formatQuizTime(startAtMillis)}"
                                } else {
                                    "${strings.quizStartLabel}: ${strings.quizScheduleAnytime}"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (startAtMillis > 0L) {
                            IconButton(onClick = { startAtMillis = 0L }) {
                                Icon(Icons.Default.Close, contentDescription = strings.clearLabel)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { pickDateTime(endAtMillis) { endAtMillis = it } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = if (endAtMillis > 0L) {
                                    "${strings.quizEndLabel}: ${formatQuizTime(endAtMillis)}"
                                } else {
                                    "${strings.quizEndLabel}: ${strings.quizScheduleAnytime}"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (endAtMillis > 0L) {
                            IconButton(onClick = { endAtMillis = 0L }) {
                                Icon(Icons.Default.Close, contentDescription = strings.clearLabel)
                            }
                        }
                    }
                }

                // Optional Quiz Image Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = quizImageUri?.lastPathSegment ?: strings.quizAttachImageAction,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (quizImageUri != null) {
                        IconButton(onClick = { quizImageUri = null }) {
                            Icon(Icons.Default.Close, contentDescription = strings.clearLabel)
                        }
                    }
                }

                // Question Drafts with 2-6 Dynamic Options
                drafts.forEachIndexed { qIdx, draft ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${strings.questionWord} ${qIdx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedTextField(
                                value = draft.prompt,
                                onValueChange = { drafts[qIdx] = draft.copy(prompt = it) },
                                label = { Text("${strings.questionWord} ${qIdx + 1}") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            draft.options.forEachIndexed { optIdx, optVal ->
                                val letter = ('A' + optIdx).toString()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    RadioButton(
                                        selected = draft.correctIdx == optIdx,
                                        onClick = { drafts[qIdx] = draft.copy(correctIdx = optIdx) }
                                    )
                                    OutlinedTextField(
                                        value = optVal,
                                        onValueChange = { updated ->
                                            val nextOpts = draft.options.toMutableList().apply {
                                                set(optIdx, updated)
                                            }
                                            drafts[qIdx] = draft.copy(options = nextOpts)
                                        },
                                        label = { Text("${strings.optionWord} $letter") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (draft.options.size > 2) {
                                        IconButton(
                                            onClick = {
                                                val nextOpts = draft.options.toMutableList().apply {
                                                    removeAt(optIdx)
                                                }
                                                val safeCorrect = draft.correctIdx.coerceIn(0, nextOpts.lastIndex)
                                                drafts[qIdx] = draft.copy(
                                                    options = nextOpts,
                                                    correctIdx = safeCorrect
                                                )
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = strings.deleteLabel,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            if (draft.options.size < 6) {
                                TextButton(
                                    onClick = {
                                        drafts[qIdx] = draft.copy(options = draft.options + "")
                                    }
                                ) {
                                    Text(strings.addOptionAction)
                                }
                            }
                        }
                    }
                }

                if (drafts.size < 10) {
                    OutlinedButton(
                        onClick = { drafts.add(QuestionDraft()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(strings.addQuestionAction)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val prompts = drafts.map { it.prompt }
                    val blocks = drafts.map { d ->
                        val cleaned = d.options.map { it.trim() }.filter { it.isNotBlank() }
                        cleaned.joinToString("|")
                    }
                    val corrects = drafts.map { d ->
                        val cleanedIndices = d.options.mapIndexedNotNull { idx, v ->
                            if (v.isNotBlank()) idx else null
                        }
                        val mappedPos = cleanedIndices.indexOf(d.correctIdx)
                        if (mappedPos >= 0) mappedPos else 0
                    }
                    onConfirm(
                        selectedCourseId,
                        title,
                        duration,
                        startAtMillis,
                        endAtMillis,
                        quizImageUri,
                        prompts,
                        blocks,
                        corrects
                    )
                },
                enabled = title.isNotBlank() && drafts.all { d ->
                    d.prompt.isNotBlank() && d.options.count { it.isNotBlank() } >= 2
                }
            ) {
                Text(strings.saveQuizAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
        }
    )
}

private fun formatQuizTime(millis: Long): String {
    if (millis <= 0L) return ""
    return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(millis))
}
