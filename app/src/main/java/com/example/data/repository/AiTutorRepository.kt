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
 * Also converts raw LaTeX/Markdown clutter into clean, human-readable Unicode math & Arabic formatting.
 */
class AiTutorRepository {

    private val systemPrompt = content {
        text(
            "You are UniCore Academic Tutor. Give clear, concise, practical study answers " +
                "in the user's language (Arabic if prompted in Arabic or requested). " +
                "CRITICAL FORMATTING RULE: Never output raw LaTeX, TeX dollar signs ($ or $$), " +
                "backslashes (such as \\Omega, \\frac, \\text, \\times), or raw Markdown clutter (### or **). " +
                "Always write math expressions using clear Unicode symbols (Ω, ×, ÷, =, √, ², ³, °) " +
                "and simple bullet points (•) so students can read formulas effortlessly."
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
            AppLanguage.ARABIC ->
                "أجب باللغة العربية الواضحة وبأسلوب تعليمي مختصر ومنظم بدون استخدام رموز LaTeX أو علامات \$ أو *** أو ### (استخدم الرموز المباشرة مثل Ω و × و = فقط):\n$prompt"
            AppLanguage.ENGLISH ->
                "Answer clearly without raw LaTeX (\$ or \$\$) or Markdown hashes (### or **). Use clean Unicode symbols (Ω, ×, ÷, √, ², =):\n$prompt"
        }

        // Attempt 1: Fast Gemini 3.5 Flash-Lite stream (25s timeout)
        val fastSuccess = runStreamOrFallback(fastModel, "gemini-3.5-flash-lite", localizedPrompt) { chunk ->
            emit(formatReadableTutorOutput(chunk))
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
                        emit(formatReadableTutorOutput(accumulated.toString()))
                    }
                }
                if (accumulated.isBlank()) {
                    balancedModel.generateContent(localizedPrompt).text.orEmpty().also { text ->
                        if (text.isNotBlank()) emit(formatReadableTutorOutput(text))
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
            if (msg.contains("403") || msg.contains("app check") || msg.contains("attestation") ||
                msg.contains("429") || msg.contains("quota") || msg.contains("safety")
            ) {
                throw e
            }
            Log.w(TAG, "Primary model $modelName failed or timed out, falling back to gemini-3.8-flash", e)
            null
        }
    }

    companion object {
        private const val TAG = "AiTutorRepo"

        fun formatReadableTutorOutput(raw: String): String {
            if (raw.isBlank()) return ""
            var text = raw

            // Strip LaTeX delimiters
            text = text
                .replace(Regex("""\$\$([\s\S]*?)\$\$"""), "$1")
                .replace(Regex("""\$([^$\n]+?)\$"""), "$1")
                .replace(Regex("""\\\[([\s\S]*?)\\\]"""), "$1")
                .replace(Regex("""\\\(([\s\S]*?)\\\)"""), "$1")

            // Convert fractions, roots, and \text blocks iteratively
            repeat(3) {
                text = text
                    .replace(Regex("""\\(?:d?frac)\{([^{}]+)\}\{([^{}]+)\}"""), "($1 ÷ $2)")
                    .replace(Regex("""\\sqrt\{([^{}]+)\}"""), "√($1)")
                    .replace(Regex("""\\(?:text|mathrm|mathbf|mathit|operatorname)\{([^{}]*)\}"""), "$1")
            }

            // Convert LaTeX symbols to clean Unicode equivalents
            text = text
                .replace(Regex("""\\Omega\b"""), "Ω")
                .replace(Regex("""\\mu\b"""), "μ")
                .replace(Regex("""\\alpha\b"""), "α")
                .replace(Regex("""\\beta\b"""), "β")
                .replace(Regex("""\\gamma\b"""), "γ")
                .replace(Regex("""\\theta\b"""), "θ")
                .replace(Regex("""\\phi\b"""), "φ")
                .replace(Regex("""\\pi\b"""), "π")
                .replace(Regex("""\\Delta\b"""), "Δ")
                .replace(Regex("""\\lambda\b"""), "λ")
                .replace(Regex("""\\times\b"""), "×")
                .replace(Regex("""\\cdot\b"""), "·")
                .replace(Regex("""\\div\b"""), "÷")
                .replace(Regex("""\\pm\b"""), "±")
                .replace(Regex("""\\approx\b"""), "≈")
                .replace(Regex("""\\neq\b"""), "≠")
                .replace(Regex("""\\leq\b"""), "≤")
                .replace(Regex("""\\geq\b"""), "≥")
                .replace(Regex("""\\degree\b|\^\\circ"""), "°")
                .replace(Regex("""\\to\b|\\rightarrow\b"""), "←")
                .replace(Regex("""\^\{2\}|\^2\b"""), "²")
                .replace(Regex("""\^\{3\}|\^3\b"""), "³")
                .replace(Regex("""\\[,;:! ]"""), " ")
                .replace(Regex("""\\left\b|\\right\b"""), "")
                .replace(Regex("""([A-Za-z0-9])_\{([^{}]+)\}"""), "$1($2)")
                .replace(Regex("""([A-Za-z])_([A-Za-z0-9])\b"""), "$1($2)")
                .replace(Regex("""\{([^{}\n]+)\}"""), "$1")

            // Clean Markdown clutter (### headers, **bold**, --- separators, * bullets)
            text = text
                .replace(Regex("""^[ \t]*#{1,6}[ \t]*(.+)$""", RegexOption.MULTILINE), "$1")
                .replace(Regex("""\*\*([^*\n]+)\*\*"""), "$1")
                .replace(Regex("""__([^_\n]+)__"""), "$1")
                .replace(Regex("""`([^`\n]+)`"""), "$1")
                .replace(Regex("""^[ \t]*[-*_]{3,}[ \t]*$""", RegexOption.MULTILINE), "")
                .replace(Regex("""^[ \t]*\*[ \t]+""", RegexOption.MULTILINE), "• ")
                .replace(Regex("""[ \t]{2,}"""), " ")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()

            return text
        }
    }
}
