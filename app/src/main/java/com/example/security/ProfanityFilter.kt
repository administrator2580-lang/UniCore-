package com.example.security

/**
 * Normalizes Arabic, Franco-Arab, and English text and detects profanity / abusive terms
 * with identical coverage to desktop-web's validators.js.
 */
object ProfanityFilter {

    private val PROFANITY_REGEX_LIST = listOf(
        // English explicit slurs & profanity (word boundaries prevent false positives on benign words)
        Regex(
            """\b(f+u+c+k+(?:ing|er|ed|s)?|s+h+i+t+(?:ty|s)?|b+i+t+c+h+(?:es|ing)?|a+s+s+h+o+l+e+s?|b+a+s+t+a+r+d+s?|d+i+c+k+(?:head|s)?|p+u+s+s+y|c+u+n+t+s?|w+h+o+r+e+s?|s+l+u+t+s?|m+o+t+h+e+r+f+u+c+k+e*r*|n+i+g+g+(?:a|er)s?|f+a+g+g*o*t*s?)\b""",
            RegexOption.IGNORE_CASE
        ),
        // Franco-Arab common insults
        Regex(
            """\b(k+o+s+o+m+(?:a+k|o+k|h+o+m)?|a+h+b+a+|s+h+a+r+m+o+t+a*|m+e+t+n+a+k+|a+r+s+|m+a+3+r+a+s+|k+h+a+w+a+l+|z+e+b+y*|t+e+z+a+k+|y+a+b+n\s*e+l\s*(?:w+e+s+k+h+a+|a+h+b+a+|m+e+t+n+a+k+a+))\b""",
            RegexOption.IGNORE_CASE
        ),
        // Normalized Arabic roots/phrases
        Regex(
            """(?:^|\s|[،.؟!:\-_])(كس\s*امك|كسمك|كس\s*اختك|شرموط|شرموطه|قحب|قحبه|منيك|متناك|متناكه|ابن\s*الوسخه|ابن\s*الكلب|ابن\s*الحرام|يلعن|يلعن\s*دين|خول|معرص|عرص|زبي|زبك|طيز|طيزك|حيوان|حمار|وسخ|زباله|نجس|كسم|لعنه\s*الله)(?=$|\s|[،.؟!:\-_])""",
            RegexOption.IGNORE_CASE
        )
    )

    internal fun normalizeForCheck(raw: String): String {
        return raw
            .lowercase()
            .replace(Regex("[\u064B-\u065F\u0670\u0640]"), "") // Arabic harakat + tatweel
            .replace(Regex("[أإآ]"), "ا")
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace('@', 'a')
            .replace('$', 's')
            .replace('!', 'i')
            .replace(Regex("(.)\\1{2,}"), "$1$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun containsProfanity(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val normalized = normalizeForCheck(raw)
        if (normalized.isBlank()) return false
        return PROFANITY_REGEX_LIST.any { it.containsMatchIn(normalized) }
    }

    fun assertCleanText(raw: String?, customMessage: String? = null) {
        if (containsProfanity(raw)) {
            throw IllegalArgumentException(
                customMessage
                    ?: "تم حظر الإرسال لاحتواء النص على ألفاظ غير لائقة. يرجى الالتزام بآداب الحوار الجامعي."
            )
        }
    }
}
