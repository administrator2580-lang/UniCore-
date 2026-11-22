package com.example.data.repository

import android.util.Log
import com.example.data.preferences.AppLanguage
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout

/**
 * Zero-Cost AI Tutor powered by Firebase AI Logic.
 * Uses ultra-fast gemini-3.5-flash-lite primarily (minimizing latency on mobile),
 * with automatic fallback to gemini-3.8-flash and unary generation if streaming stalls.
 */
class AiTutorRepository {

    private val systemPrompt = content {
        text(
            "You are UniCore Academic Tutor. Give clear, concise, practical study answers " +
                "in the user's language (Arabic if prompted in Arabic or requested). " +
                "Keep answers structured and well-formatted."
        )
    }

    private val fastConfig = generationConfig {
        temperature = 0.4f
        maxOutputTokens = 1024
    }

    private val fastModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = fastConfig,
            systemInstruction = systemPrompt
        )
    }

    private val balancedModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = fastConfig,
            systemInstruction = systemPrompt
        )
    }

    fun streamStudyAnswer(
        prompt: String,
        preferredLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Flow<String> = flow {
        val localizedPrompt = when (preferredLanguage) {
            AppLanguage.ARABIC -> "أجب باللغة العربية الواضحة وبأسلوب تعليمي مختصر ومنظم:\n$prompt"
            AppLanguage.ENGLISH -> prompt
        }

        // Attempt 1: Fast Gemini 3.5 Flash-Lite stream (25s timeout)
        val fastSuccess = runStreamOrFallback(fastModel, "gemini-3.5-flash-lite", localizedPrompt) { chunk ->
            emit(chunk)
        }
        if (fastSuccess != null) {
            return@flow
        }

        // Attempt 2: Balanced Gemini 3.8 Flash fallback (30s timeout)
        val balancedText = try {
            withTimeout(30_000L) {
                val accumulated = StringBuilder()
                balancedModel.generateContentStream(localizedPrompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(accumulated.toString())
                    }
                }
                if (accumulated.isBlank()) {
                    balancedModel.generateContent(localizedPrompt).text.orEmpty().also { text ->
                        if (text.isNotBlank()) emit(text)
                    }
                } else {
                    accumulated.toString()
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
        if (balancedText.isBlank()) {
            throw IllegalStateException("Tutor returned an empty response.")
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun runStreamOrFallback(
        model: GenerativeModel,
        modelName: String,
        prompt: String,
        onPartial: suspend (String) -> Unit
    ): String? {
        return try {
            withTimeout(25_000L) {
                val accumulated = StringBuilder()
                model.generateContentStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        onPartial(accumulated.toString())
                    }
                }
                if (accumulated.isNotBlank()) {
                    accumulated.toString()
                } else {
                    // Quick unary check if stream completed with no text parts
                    val unary = model.generateContent(prompt).text.orEmpty()
                    if (unary.isNotBlank()) {
                        onPartial(unary)
                        unary
                    } else {
                        null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            // Fail fast on deterministic security/auth/quota blocks instead of waiting twice
            if (msg.contains("403") || msg.contains("app check") || msg.contains("attestation") ||
                msg.contains("429") || msg.contains("quota") || msg.contains("safety")
            ) {
                throw e
            }
            Log.w(TAG, "Primary model $modelName failed or timed out, falling back to gemini-3.8-flash", e)
            null
        }
    }

    private companion object {
        const val TAG = "AiTutorRepo"
    }
}
