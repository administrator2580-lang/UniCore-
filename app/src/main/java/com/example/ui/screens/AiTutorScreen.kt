package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.repository.StudyMaterialMatcher
import com.example.security.ZeroCostQuotaManager
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.AiUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiTutorScreen(
    aiState: AiUiState,
    quotaSnapshot: ZeroCostQuotaManager.QuotaSnapshot,
    availableSources: List<StudyMaterialMatcher.Candidate>,
    onAskTutor: (String, String) -> Unit,
    onCancelTutor: () -> Unit,
    onClearTutor: () -> Unit
) {
    val strings = LocalAppStrings.current
    val clipboard = LocalClipboardManager.current
    var prompt by remember { mutableStateOf("") }
    var selectedSourceId by remember { mutableStateOf(StudyMaterialMatcher.SOURCE_AUTO) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(MaterialTheme.shapes.large)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_ai_tutor_banner),
                contentDescription = strings.tutorBannerTitle,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC090E1A))))
                    .padding(16.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column {
                    Text(
                        text = strings.tutorBannerSubtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = strings.tutorBannerTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(strings.dailyAiQuota, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${quotaSnapshot.aiUsedToday} / ${quotaSnapshot.aiDailyCap}",
                        style = MaterialTheme.typography.labelMedium,
                        color = when (quotaSnapshot.aiHealth) {
                            ZeroCostQuotaManager.QuotaHealth.HEALTHY -> MaterialTheme.colorScheme.secondary
                            ZeroCostQuotaManager.QuotaHealth.WARNING_80_PERCENT -> MaterialTheme.colorScheme.tertiary
                            ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED -> MaterialTheme.colorScheme.error
                        }
                    )
                }
                LinearProgressIndicator(
                    progress = { quotaSnapshot.aiRatio },
                    modifier = Modifier.fillMaxWidth(),
                    color = when (quotaSnapshot.aiHealth) {
                        ZeroCostQuotaManager.QuotaHealth.HEALTHY -> MaterialTheme.colorScheme.secondary
                        ZeroCostQuotaManager.QuotaHealth.WARNING_80_PERCENT -> MaterialTheme.colorScheme.tertiary
                        ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED -> MaterialTheme.colorScheme.error
                    }
                )
            }
        }

        if (availableSources.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = strings.aiSourcesPickerTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedSourceId == StudyMaterialMatcher.SOURCE_AUTO,
                        onClick = { selectedSourceId = StudyMaterialMatcher.SOURCE_AUTO },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(strings.aiSourceAuto, maxLines = 1) },
                        modifier = Modifier.testTag("ai_source_auto_chip")
                    )

                    availableSources.take(20).forEach { source ->
                        val chipTitle = source.fileName.ifBlank { source.displayTitle }
                        FilterChip(
                            selected = selectedSourceId == source.sourceId,
                            onClick = { selectedSourceId = source.sourceId },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = chipTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }

                    FilterChip(
                        selected = selectedSourceId == StudyMaterialMatcher.SOURCE_NONE,
                        onClick = { selectedSourceId = StudyMaterialMatcher.SOURCE_NONE },
                        label = { Text(strings.aiSourceNone, maxLines = 1) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(strings.quickPrompt1, strings.quickPrompt2, strings.quickPrompt3).forEach { chipPrompt ->
                AssistChip(
                    onClick = { prompt = chipPrompt },
                    label = { Text(chipPrompt) }
                )
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text(strings.askPromptLabel) },
            placeholder = { Text(strings.askPromptPlaceholder) },
            minLines = 3,
            trailingIcon = {
                if (prompt.isNotEmpty()) {
                    IconButton(onClick = {
                        prompt = ""
                        onClearTutor()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = strings.clearLabel)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_prompt_input")
        )

        val isBusy = aiState is AiUiState.Loading || aiState is AiUiState.Streaming
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onAskTutor(prompt, selectedSourceId) },
                enabled = !isBusy &&
                    quotaSnapshot.aiHealth != ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("ask_ai_button")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(strings.tutorConnecting)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(strings.askTutorButton)
                }
            }

            if (isBusy) {
                OutlinedButton(
                    onClick = onCancelTutor,
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("cancel_ai_button")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(strings.stopAiAction)
                }
            }
        }

        when (aiState) {
            is AiUiState.Idle -> {
                EmptyStateCard(
                    icon = Icons.Default.AutoAwesome,
                    title = strings.tutorIdleTitle,
                    subtitle = strings.tutorIdleSub
                )
            }
            is AiUiState.Loading -> {
                Text(
                    text = aiState.statusDetail ?: strings.tutorConnecting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is AiUiState.Streaming -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (aiState.usedSources.isNotEmpty()) {
                            UsedSourcesRow(strings.aiSourcesUsedPrefix, aiState.usedSources)
                        }
                        Text(
                            text = aiState.text,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            is AiUiState.Success -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
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
                            Box(modifier = Modifier.weight(1f)) {
                                if (aiState.usedSources.isNotEmpty()) {
                                    UsedSourcesRow(strings.aiSourcesUsedPrefix, aiState.usedSources)
                                }
                            }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(aiState.text)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = strings.copyAnswer)
                            }
                        }
                        Text(
                            text = aiState.text,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            is AiUiState.PausedQuota -> {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PauseCircle, contentDescription = null)
                        Text(text = aiState.reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            is AiUiState.Error -> {
                ErrorBannerCard(aiState.message)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsedSourcesRow(prefix: String, sources: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        sources.forEach { label ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "$prefix: $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
