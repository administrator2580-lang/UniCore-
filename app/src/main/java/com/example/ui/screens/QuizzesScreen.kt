package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Course
import com.example.data.model.Quiz
import com.example.data.model.QuizAttempt
import com.example.data.model.UserRole
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.UiState

private data class QuestionDraft(
    val prompt: String = "",
    val optA: String = "",
    val optB: String = "",
    val optC: String = "",
    val correctIdx: Int = 0
)

@Composable
fun QuizzesScreen(
    quizzesState: UiState<List<Quiz>>,
    attemptsState: UiState<List<QuizAttempt>>,
    coursesState: UiState<List<Course>>,
    userRole: UserRole,
    onCreateQuiz: (String, String, Int, List<String>, List<String>, List<Int>) -> Unit,
    onDeleteQuiz: (String) -> Unit,
    onSubmitAttempt: (Quiz, List<Int>) -> Unit,
    onGradeAttempt: (QuizAttempt) -> Unit
) {
    val strings = LocalAppStrings.current
    var activeQuizToTake by remember { mutableStateOf<Quiz?>(null) }
    var showCreateQuizDialog by remember { mutableStateOf(false) }

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
                            val alreadySubmitted = attempts.any { it.quizId == quiz.id }
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
                                    Text(
                                        text = quiz.questionPrompts.firstOrNull().orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { activeQuizToTake = quiz },
                                        enabled = !alreadySubmitted,
                                        modifier = Modifier.testTag("take_quiz_${quiz.id}")
                                    ) {
                                        Text(if (alreadySubmitted) strings.submittedLocked else strings.takeQuizAction)
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (attempt.graded) {
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

                            if (userRole != UserRole.STUDENT && !attempt.graded) {
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
            onDismiss = { activeQuizToTake = null },
            onSubmit = { answers ->
                onSubmitAttempt(quiz, answers)
                activeQuizToTake = null
            }
        )
    }

    if (showCreateQuizDialog && courses.isNotEmpty()) {
        CreateQuizDialog(
            courses = courses,
            onDismiss = { showCreateQuizDialog = false },
            onConfirm = { courseId, title, duration, prompts, optionBlocks, correctIndices ->
                onCreateQuiz(courseId, title, duration, prompts, optionBlocks, correctIndices)
                showCreateQuizDialog = false
            }
        )
    }
}

@Composable
private fun TakeQuizDialog(
    quiz: Quiz,
    onDismiss: () -> Unit,
    onSubmit: (List<Int>) -> Unit
) {
    val strings = LocalAppStrings.current
    val totalQuestions = quiz.questionPrompts.size.coerceAtLeast(1)
    var currentIndex by remember { mutableIntStateOf(0) }
    val selectedAnswers = remember(quiz) {
        mutableStateListOf<Int>().apply { repeat(totalQuestions) { add(0) } }
    }

    val prompt = quiz.questionPrompts.getOrElse(currentIndex) { "" }
    val options = remember(quiz, currentIndex) {
        quiz.optionBlocks.getOrNull(currentIndex)?.split("|").orEmpty().ifEmpty { listOf("A", "B") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    onClick = { onSubmit(selectedAnswers.toList()) },
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
                TextButton(onClick = onDismiss) { Text(strings.cancelAction) }
            }
        }
    )
}

@Composable
private fun CreateQuizDialog(
    courses: List<Course>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, List<String>, List<String>, List<Int>) -> Unit
) {
    val strings = LocalAppStrings.current
    var selectedCourseId by remember { mutableStateOf(courses.first().id) }
    var title by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(15) }
    val drafts = remember { mutableStateListOf(QuestionDraft()) }

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
                            OutlinedTextField(
                                value = draft.optA,
                                onValueChange = { drafts[qIdx] = draft.copy(optA = it) },
                                label = { Text("A") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = draft.optB,
                                onValueChange = { drafts[qIdx] = draft.copy(optB = it) },
                                label = { Text("B") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = draft.optC,
                                onValueChange = { drafts[qIdx] = draft.copy(optC = it) },
                                label = { Text("C") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                listOf(0 to "A", 1 to "B", 2 to "C").forEach { (optIdx, label) ->
                                    RadioButton(
                                        selected = draft.correctIdx == optIdx,
                                        onClick = { drafts[qIdx] = draft.copy(correctIdx = optIdx) }
                                    )
                                    Text(label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                if (drafts.size < 5) {
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
                        listOf(d.optA, d.optB, d.optC).filter { it.isNotBlank() }.joinToString("|")
                    }
                    val corrects = drafts.map { it.correctIdx }
                    onConfirm(selectedCourseId, title, duration, prompts, blocks, corrects)
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
