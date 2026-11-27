package com.example.data.repository

import com.example.data.model.Course
import com.example.data.model.DriveSyncedFile
import com.example.data.model.VerifiedFileRecord
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Matches user study prompts against uploaded Courses, synced Google Drive files,
 * and saved Vault files using Arabic + English normalization and token scoring.
 */
object StudyMaterialMatcher {

    const val SOURCE_AUTO = "AUTO"
    const val SOURCE_NONE = "NONE"
    const val MAX_INLINE_FILE_BYTES = 10 * 1024 * 1024 // 10 MB per file
    const val MAX_TOTAL_INLINE_BYTES = 12 * 1024 * 1024 // 12 MB combined (under Gemini 20 MB limit)
    const val MAX_EXTRACTED_TEXT_CHARS = 28_000

    sealed interface Candidate {
        val sourceId: String
        val displayTitle: String
        val fileName: String
        val contextSubtitle: String
        val hasBinaryFile: Boolean

        data class CourseMaterial(val course: Course) : Candidate {
            override val sourceId: String = "course_${course.id}"
            override val displayTitle: String = course.title
            override val fileName: String = course.attachmentName?.takeIf { it.isNotBlank() } ?: course.title
            override val contextSubtitle: String = listOfNotNull(
                course.departmentName?.takeIf { it.isNotBlank() },
                course.academicYear?.takeIf { it.isNotBlank() },
                course.description.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            override val hasBinaryFile: Boolean =
                course.hasAttachment && course.chunkCount > 0L && !course.attachmentSha256.isNullOrBlank()
        }

        data class DriveMaterial(val file: DriveSyncedFile) : Candidate {
            override val sourceId: String = "drive_${file.fileId}"
            override val displayTitle: String = file.name
            override val fileName: String = file.name
            override val contextSubtitle: String = listOfNotNull(
                file.subFolderPath.takeIf { it.isNotBlank() },
                file.rootFolderTitle.takeIf { it.isNotBlank() } ?: file.folderTitle.takeIf { it.isNotBlank() }
            ).distinct().joinToString(" • ")
            override val hasBinaryFile: Boolean = !file.isSubFolder
        }

        data class VaultMaterial(val record: VerifiedFileRecord) : Candidate {
            override val sourceId: String = "vault_${record.id}"
            override val displayTitle: String = record.originalName
            override val fileName: String = record.originalName
            override val contextSubtitle: String = "My Files Vault"
            override val hasBinaryFile: Boolean = true
        }
    }

    data class ScoredCandidate(
        val candidate: Candidate,
        val score: Int
    )

    private val ARABIC_DIACRITICS = Regex("[\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")
    private val PUNCTUATION = Regex("[^\\p{L}\\p{Nd}\\s]")

    private val STOP_WORDS = setOf(
        // Arabic prompt & filler words
        "اشرح", "اشرحلي", "شرح", "فهمني", "افهم", "لخص", "لخصلي", "ملخص", "تلخيص",
        "عايز", "اريد", "محتاج", "ممكن", "لو", "سمحت", "هات", "جيب", "جيبلي", "اعطني", "اديني",
        "اكتب", "قول", "قولي", "اتكلم", "تكلم", "من", "في", "عن", "علي", "على", "الي", "إلى", "الى",
        "لي", "لك", "ده", "دي", "دا", "هذا", "هذه", "تلك", "ذلك", "اللي", "التي", "الذي",
        "ماده", "مادة", "الماده", "المادة", "ملف", "ملفات", "الملف", "المرفوع", "المرفوعه", "المرفوعة",
        "درس", "محاضره", "محاضرة", "مذكره", "مذكرة", "فصل", "باب", "جزء", "قسم",
        "ايه", "ما", "هو", "هي", "هم", "كيف", "لماذا", "ليش", "ازاي", "فين", "امته",
        "اسئله", "اسئلة", "تدريبيه", "تدريبية", "المفاهيم", "الاساسيه", "الأساسية", "نقاط",
        "ببساطه", "ببساطة", "مثال", "عملي", "جدا", "كل", "حاجه", "حاجة", "الكل", "بس", "يا",
        // English prompt & filler words
        "explain", "summarize", "summary", "please", "from", "the", "a", "an", "in", "on", "of",
        "to", "for", "with", "about", "what", "is", "are", "how", "why", "give", "me", "tell",
        "file", "files", "uploaded", "course", "courses", "material", "materials", "lecture",
        "chapter", "pdf", "notes", "document", "docs", "simply", "example", "practice",
        "questions", "key", "concepts", "bullet", "points", "this", "that", "everything"
    )

    fun normalizeForSearch(raw: String): String {
        if (raw.isBlank()) return ""
        val sb = StringBuilder(raw.length)
        val noMarks = ARABIC_DIACRITICS.replace(raw, "")
        for (ch in noMarks) {
            val mapped = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                else -> ch.lowercaseChar()
            }
            sb.append(mapped)
        }
        return PUNCTUATION.replace(sb.toString(), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeTokenVariants(token: String): List<String> {
        val t = token.trimEnd('ء')
        if (t.length < 2) return emptyList()
        val variants = linkedSetOf(t)
        val stripped = when {
            t.startsWith("وال") && t.length > 4 -> t.removePrefix("وال")
            t.startsWith("بال") && t.length > 4 -> t.removePrefix("بال")
            t.startsWith("لال") && t.length > 4 -> t.removePrefix("لال")
            t.startsWith("كال") && t.length > 4 -> t.removePrefix("كال")
            t.startsWith("فال") && t.length > 4 -> t.removePrefix("فال")
            t.startsWith("ال") && t.length > 3 -> t.removePrefix("ال")
            (t.startsWith("و") || t.startsWith("ب") || t.startsWith("ل")) && t.length > 4 -> t.substring(1)
            else -> t
        }.trimEnd('ء')
        if (stripped.length >= 2) {
            variants.add(stripped)
            if (stripped.endsWith("يه") && stripped.length > 4) {
                variants.add(stripped.dropLast(2))
            } else if (stripped.endsWith("ي") && stripped.length > 4) {
                variants.add(stripped.dropLast(1))
            }
        }
        return variants.toList()
    }

    fun extractMeaningfulTokens(prompt: String): List<String> {
        val norm = normalizeForSearch(prompt)
        if (norm.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        for (rawWord in norm.split(' ')) {
            val w = rawWord.trimEnd('ء')
            if (w.length < 2 || w in STOP_WORDS) continue
            val withoutAl = w.removePrefix("ال")
            if (withoutAl.isNotBlank() && withoutAl in STOP_WORDS) continue
            normalizeTokenVariants(w).forEach { variant ->
                if (variant !in STOP_WORDS && variant.length >= 2) {
                    out.add(variant)
                }
            }
        }
        return out.toList()
    }

    fun buildCandidateList(
        courses: List<Course>,
        driveFiles: List<DriveSyncedFile>,
        vaultFiles: List<VerifiedFileRecord>
    ): List<Candidate> {
        val list = mutableListOf<Candidate>()
        courses.forEach { list.add(Candidate.CourseMaterial(it)) }
        driveFiles.filterNot { it.isSubFolder }.forEach { list.add(Candidate.DriveMaterial(it)) }
        vaultFiles.forEach { list.add(Candidate.VaultMaterial(it)) }
        return list
    }

    fun findBestMatches(
        prompt: String,
        candidates: List<Candidate>,
        pinnedSourceId: String = SOURCE_AUTO,
        maxResults: Int = 2
    ): List<Candidate> {
        if (candidates.isEmpty() || pinnedSourceId == SOURCE_NONE) return emptyList()

        if (pinnedSourceId != SOURCE_AUTO) {
            return candidates.filter { it.sourceId == pinnedSourceId }.take(1)
        }

        val tokens = extractMeaningfulTokens(prompt)
        val normPrompt = normalizeForSearch(prompt)

        if (tokens.isEmpty()) {
            // Generic prompt (e.g., quick-action chip): if only 1 downloadable file exists, use it automatically
            val downloadable = candidates.filter { it.hasBinaryFile }
            return if (downloadable.size == 1) downloadable else emptyList()
        }

        val scored = candidates.mapNotNull { candidate ->
            val fileNorm = normalizeForSearch(candidate.fileName)
            val titleNorm = normalizeForSearch(candidate.displayTitle)
            val descNorm = normalizeForSearch(candidate.contextSubtitle)

            val fileWords = fileNorm.split(' ').flatMap { normalizeTokenVariants(it) }.toSet()
            val titleWords = titleNorm.split(' ').flatMap { normalizeTokenVariants(it) }.toSet()
            val descWords = descNorm.split(' ').flatMap { normalizeTokenVariants(it) }.toSet()

            var score = 0
            for (token in tokens) {
                when {
                    token in fileWords || token in titleWords -> score += 18
                    token.length >= 3 && (fileNorm.contains(token) || titleNorm.contains(token)) -> score += 12
                    token.length >= 4 && (
                        fileWords.any { it.startsWith(token) || token.startsWith(it) } ||
                            titleWords.any { it.startsWith(token) || token.startsWith(it) }
                        ) -> score += 9
                    token in descWords -> score += 6
                    token.length >= 3 && descNorm.contains(token) -> score += 4
                }
            }

            if (score > 0 && titleNorm.length >= 3 && normPrompt.contains(titleNorm)) {
                score += 15
            }
            if (score > 0 && candidate.hasBinaryFile) {
                score += 4
            }

            if (score > 0) ScoredCandidate(candidate, score) else null
        }.sortedWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenByDescending { it.candidate.hasBinaryFile }
        )

        return scored.take(maxResults).map { it.candidate }
    }

    fun isSupportedInlineMediaMime(mimeType: String): Boolean {
        val m = mimeType.lowercase(Locale.ROOT)
        return m == "application/pdf" ||
            m == "image/png" ||
            m == "image/jpeg" ||
            m == "image/webp"
    }

    fun extractReadableTextFromDocument(file: File, mimeType: String): String? {
        val lowerMime = mimeType.lowercase(Locale.ROOT)
        val ext = file.extension.lowercase(Locale.ROOT)
        return runCatching {
            when {
                lowerMime.startsWith("text/") || ext in setOf("txt", "md", "csv") -> {
                    file.readText(Charsets.UTF_8)
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(MAX_EXTRACTED_TEXT_CHARS)
                        .ifBlank { null }
                }
                ext in setOf("docx", "pptx", "xlsx") ||
                    lowerMime.contains("wordprocessingml") ||
                    lowerMime.contains("presentationml") ||
                    lowerMime.contains("spreadsheetml") -> {
                    extractOfficeXmlText(file).take(MAX_EXTRACTED_TEXT_CHARS).ifBlank { null }
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun extractOfficeXmlText(file: File): String {
        val builder = StringBuilder()
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase(Locale.ROOT)
                val isTextXml = !entry.isDirectory && (
                    name == "word/document.xml" ||
                        (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) ||
                        name == "xl/sharedstrings.xml"
                    )
                if (isTextXml) {
                    val xml = zip.bufferedReader(Charsets.UTF_8).readText()
                    val plain = xml
                        .replace(Regex("<w:p[^>]*>|<a:p[^>]*>"), "\n")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&apos;", "'")
                        .replace(Regex("[ \\t]+"), " ")
                        .trim()
                    if (plain.isNotBlank()) {
                        if (builder.isNotEmpty()) builder.append("\n\n")
                        builder.append(plain)
                    }
                    if (builder.length >= MAX_EXTRACTED_TEXT_CHARS) break
                }
                entry = zip.nextEntry
            }
        }
        return builder.toString()
    }
}
