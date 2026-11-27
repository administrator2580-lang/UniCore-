package com.example.data.repository

import android.util.Log
import com.example.data.preferences.AppLanguage
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
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
 * Grounded on uploaded Course files, synced Google Drive PDFs/docs, and Vault materials
 * via multimodal inlineData + extracted text context.
 */
class AiTutorRepository {

    data class AiMaterialPart(
        val sourceLabel: String,
        val mimeType: String,
        val inlineBytes: ByteArray? = null,
        val extractedText: String? = null,
        val courseSummary: String? = null
    )

    private val systemPrompt = content {
        text(
            "You are UniCore Academic Tutor. Give clear, concise, practical study answers " +
                "in the user's language (Arabic if prompted in Arabic or requested). " +
                "When uploaded course files (PDFs, images, or notes) are attached, ALWAYS treat those " +
                "attached files as the primary authoritative textbook/source and extract the explanation " +
                "directly from their contents. " +
                "CRITICAL FORMATTING RULE: Never output raw LaTeX, TeX dollar signs ($ or $$), " +
                "backslashes (such as \\Omega, \\frac, \\text, \\times), or raw Markdown clutter (### or **). " +
                "Always write math expressions using clear Unicode symbols (Ω, ×, ÷, =, √, ², ³, °) " +
                "and simple bullet points (•) so students can read formulas effortlessly."
        )
    }

    private val fastConfig = generationConfig {
        temperature = 0.35f
        maxOutputTokens = 2560
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
        preferredLanguage: AppLanguage = AppLanguage.ENGLISH,
        materials: List<AiMaterialPart> = emptyList(),
        availableCatalogSummary: String = ""
    ): Flow<String> = flow {
        val requestContent = buildMultimodalContent(
            prompt = prompt,
            preferredLanguage = preferredLanguage,
            materials = materials,
            availableCatalogSummary = availableCatalogSummary
        )
        val hasBinaryAttachment = materials.any { it.inlineBytes != null && it.inlineBytes.isNotEmpty() }
        val primaryTimeoutMs = if (hasBinaryAttachment) 55_000L else 30_000L
        val fallbackTimeoutMs = if (hasBinaryAttachment) 60_000L else 35_000L

        // Attempt 1: Fast Gemini 3.5 Flash-Lite stream
        val fastSuccess = runStreamOrFallback(
            model = fastModel,
            modelName = "gemini-3.5-flash-lite",
            requestContent = requestContent,
            timeoutMs = primaryTimeoutMs
        ) { chunk ->
            emit(formatReadableTutorOutput(chunk))
        }
        if (fastSuccess != null) {
            return@flow
        }

        // Attempt 2: Balanced Gemini 3.8 Flash fallback
        val balancedText = try {
            withTimeout(fallbackTimeoutMs) {
                val accumulated = StringBuilder()
                balancedModel.generateContentStream(requestContent).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(formatReadableTutorOutput(accumulated.toString()))
                    }
                }
                if (accumulated.isBlank()) {
                    balancedModel.generateContent(requestContent).text.orEmpty().also { text ->
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

    private fun buildMultimodalContent(
        prompt: String,
        preferredLanguage: AppLanguage,
        materials: List<AiMaterialPart>,
        availableCatalogSummary: String
    ): Content = content {
        val promptBuilder = StringBuilder()

        if (materials.isNotEmpty()) {
            if (preferredLanguage == AppLanguage.ARABIC) {
                promptBuilder.append(
                    "اعتمد أولاً وبشكل أساسي على الملفات والمواد الدراسية المرفقة أدناه لاستخراج الشرح والتعريفات والأمثلة المطلوبة مباشرة من محتواها، واذكر اسم المصدر في إجابتك. إذا لم تجد الجزئية داخل الملف وضّح ذلك بوضوح.\n\n"
                )
            } else {
                promptBuilder.append(
                    "Base your explanation primarily on the uploaded course materials attached below. Extract definitions, laws, and examples directly from these files and cite the source file name. If the topic is not covered inside the file, say so clearly.\n\n"
                )
            }

            materials.forEach { part ->
                val bytes = part.inlineBytes
                if (bytes != null && bytes.isNotEmpty()) {
                    inlineData(bytes = bytes, mimeType = part.mimeType)
                    promptBuilder.append("[Attached File: ${part.sourceLabel}]\n")
                }
                if (!part.extractedText.isNullOrBlank()) {
                    promptBuilder.append(
                        "<uploaded_file_text name=\"${part.sourceLabel}\">\n${part.extractedText}\n</uploaded_file_text>\n\n"
                    )
                }
                if (!part.courseSummary.isNullOrBlank()) {
                    promptBuilder.append(
                        "<course_info name=\"${part.sourceLabel}\">\n${part.courseSummary}\n</course_info>\n\n"
                    )
                }
            }
        } else if (availableCatalogSummary.isNotBlank()) {
            promptBuilder.append(
                "<available_uploaded_library>\n$availableCatalogSummary\n</available_uploaded_library>\n"
            )
            if (preferredLanguage == AppLanguage.ARABIC) {
                promptBuilder.append(
                    "(لم يطابق السؤال ملفاً محدداً بالاسم؛ إذا طلب الطالب مادة من القائمة أعلاه أرشده لاختيار الملف من القائمة، وأجب على سؤاله الدراسي بشكل عام ومنظم).\n\n"
                )
            } else {
                promptBuilder.append(
                    "(No exact file name matched the query. Answer helpfully and remind the student they can select a specific file above if needed.)\n\n"
                )
            }
        }

        if (preferredLanguage == AppLanguage.ARABIC) {
            promptBuilder.append(
                "أجب باللغة العربية الواضحة وبأسلوب تعليمي منظم بدون استخدام رموز LaTeX أو علامات \$ أو ** أو ### (استخدم الرموز المباشرة مثل Ω و × و = و √ فقط):\n"
            )
        } else {
            promptBuilder.append(
                "Answer clearly without raw LaTeX (\$ or \$\$) or Markdown hashes (### or **). Use clean Unicode symbols (Ω, ×, ÷, √, ², =):\n"
            )
        }
        promptBuilder.append(prompt)

        text(promptBuilder.toString())
    }

    private suspend fun runStreamOrFallback(
        model: GenerativeModel,
        modelName: String,
        requestContent: Content,
        timeoutMs: Long,
        onPartial: suspend (String) -> Unit
    ): String? {
        return try {
            withTimeout(timeoutMs) {
                val accumulated = StringBuilder()
                model.generateContentStream(requestContent).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        onPartial(accumulated.toString())
                    }
                }
                if (accumulated.isNotBlank()) {
                    accumulated.toString()
                } else {
                    val unary = model.generateContent(requestContent).text.orEmpty()
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
