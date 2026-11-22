package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.Course
import com.example.data.model.Quiz
import com.example.data.model.QuizAttempt
import com.example.data.model.UserRole
import com.example.ui.viewmodel.UiState

@Composable
fun QuizzesScreen(
    quizzesState: UiState<List<Quiz>>,
    attemptsState: UiState<List<QuizAttempt>>,
    coursesState: UiState<List<Course>>,
    userRole: UserRole,
    onCreateQuiz: (String, String, Int, String, List<String>, Int) -> Unit,
    onSubmitAttempt: (Quiz, Int) -> Unit,
    onGradeAttempt: (QuizAttempt) -> Unit
) {
    var activeQuizToTake by remember { mutableStateOf<Quiz?>(null) }
    var showCreateQuizDialog by remember { mutableStateOf(false) }

    val courses = (coursesState as? UiState.Success)?.data.orEmpty()
    val attempts = (attemptsState as? UiState.Success)?.data.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        if (userRole != UserRole.STUDENT) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { showCreateQuizDialog = true },
                    enabled = courses.isNotEmpty(),
                    modifier = Modifier.testTag("create_quiz_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Create Quiz + Key")
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Protected Assessments",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Answer keys are stored in an isolated Admin-only subcollection. Attempts are single-submission and graded server-verified.",
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
                                title = "No Quizzes Published",
                                subtitle = if (userRole == UserRole.STUDENT) {
                                    "Published assessments from your instructors will appear here."
                                } else {
                                    "Publish a course first, then tap 'Create Quiz + Key'."
                                }
                            )
                        }
                    } else {
                        items(quizzesState.data, key = { it.id }) { quiz ->
                            val alreadySubmitted = attempts.any { it.quizId == quiz.id }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                        Text(text = quiz.title, style = MaterialTheme.typography.titleMedium)
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                text = "${quiz.durationMinutes} min",
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
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
                                        Text(if (alreadySubmitted) "Submitted (Locked)" else "Take Quiz")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = if (userRole == UserRole.STUDENT) "My Submissions & Grades" else "Admin Grading Queue",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (attempts.isEmpty()) {
                item {
                    Text(
                        text = "No quiz attempts recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(attempts, key = { it.id }) { attempt ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                    text = "${attempt.studentName} • Status: ${attempt.status}",
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
                                            contentDescription = "Graded",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Verified Score: ${attempt.score}%",
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
                                    Text("Auto-Grade")
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
            onSubmit = { idx ->
                onSubmitAttempt(quiz, idx)
                activeQuizToTake = null
            }
        )
    }

    if (showCreateQuizDialog && courses.isNotEmpty()) {
        CreateQuizDialog(
            courses = courses,
            onDismiss = { showCreateQuizDialog = false },
            onConfirm = { courseId, title, prompt, opts, correctIdx ->
                onCreateQuiz(courseId, title, 20, prompt, opts, correctIdx)
                showCreateQuizDialog = false
            }
        )
    }
}

@Composable
private fun TakeQuizDialog(
    quiz: Quiz,
    onDismiss: () -> Unit,
    onSubmit: (Int) -> Unit
) {
    val options = remember(quiz) {
        quiz.optionBlocks.firstOrNull()?.split("|").orEmpty().ifEmpty { listOf("Option A", "Option B") }
    }
    var selectedIdx by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(quiz.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = quiz.questionPrompts.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodyLarge
                )
                options.forEachIndexed { idx, opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIdx = idx }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedIdx == idx, onClick = { selectedIdx = idx })
                        Text(text = opt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selectedIdx) },
                modifier = Modifier.testTag("submit_quiz_confirm")
            ) {
                Text("Submit Attempt")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CreateQuizDialog(
    courses: List<Course>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, List<String>, Int) -> Unit
) {
    var selectedCourseId by remember { mutableStateOf(courses.first().id) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var optA by remember { mutableStateOf("") }
    var optB by remember { mutableStateOf("") }
    var optC by remember { mutableStateOf("") }
    var correctIdx by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Protected Quiz") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Course: ${courses.firstOrNull { it.id == selectedCourseId }?.title ?: ""}",
                    style = MaterialTheme.typography.labelMedium
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Quiz Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Question Prompt") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = optA,
                    onValueChange = { optA = it },
                    label = { Text("Option 1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = optB,
                    onValueChange = { optB = it },
                    label = { Text("Option 2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = optC,
                    onValueChange = { optC = it },
                    label = { Text("Option 3 (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Correct Option: ", style = MaterialTheme.typography.labelMedium)
                    listOf(0, 1, 2).forEach { idx ->
                        RadioButton(selected = correctIdx == idx, onClick = { correctIdx = idx })
                        Text("${idx + 1}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val opts = listOf(optA, optB, optC).filter { it.isNotBlank() }
                    onConfirm(selectedCourseId, title, prompt, opts, correctIdx)
                }
            ) {
                Text("Save Quiz")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
