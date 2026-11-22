package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.security.ZeroCostQuotaManager
import com.example.ui.viewmodel.AiUiState

@Composable
fun AiTutorScreen(
    aiState: AiUiState,
    quotaSnapshot: ZeroCostQuotaManager.QuotaSnapshot,
    onAskTutor: (String) -> Unit
) {
    var prompt by remember { mutableStateOf("") }

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
                .height(140.dp)
                .clip(MaterialTheme.shapes.large)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_ai_tutor_banner),
                contentDescription = "UniCore Zero-Cost AI Tutor Banner",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0B1120))))
                    .padding(16.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column {
                    Text(
                        text = "Gemini 3.8 Flash • Free Tier",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "UniCore Study Tutor",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
            }
        }

        // Zero-Cost Quota Guard Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Daily Free-Tier AI Quota", style = MaterialTheme.typography.titleMedium)
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
                Text(
                    text = when (quotaSnapshot.aiHealth) {
                        ZeroCostQuotaManager.QuotaHealth.HEALTHY ->
                            "App Check attested • No billing required on Spark Plan."
                        ZeroCostQuotaManager.QuotaHealth.WARNING_80_PERCENT ->
                            "Warning: Approaching 80% of daily free-tier cap."
                        ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED ->
                            "Paused safely: Free-tier cap reached or locked by Super Admin."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Ask a course question (max 600 chars)") },
            placeholder = { Text("Explain how Firestore Security Rules prevent privilege escalation...") },
            minLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_prompt_input")
        )

        Button(
            onClick = { onAskTutor(prompt) },
            enabled = aiState !is AiUiState.Loading &&
                quotaSnapshot.aiHealth != ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("ask_ai_button")
        ) {
            if (aiState is AiUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Ask Zero-Cost Tutor")
            }
        }

        when (aiState) {
            is AiUiState.Idle -> {
                EmptyStateCard(
                    icon = Icons.Default.AutoAwesome,
                    title = "Ready to Help",
                    subtitle = "All requests are authenticated via App Check and sanitized before inference."
                )
            }
            is AiUiState.Loading -> {
                Text("Connecting via App Check...", style = MaterialTheme.typography.bodyMedium)
            }
            is AiUiState.Streaming -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = aiState.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            is AiUiState.Success -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = aiState.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp)
                    )
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
                        Icon(Icons.Default.PauseCircle, contentDescription = "Paused")
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
