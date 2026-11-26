package com.example.security

import java.net.URI

/**
 * Validates and sanitizes untrusted user inputs before sending to Firestore or AI Logic,
 * maintaining 1:1 parity with firestore.rules constraints and blocking injection payloads.
 */
object InputValidator {

    private val DANGEROUS_PATTERNS = listOf(
        Regex("<\\s*script", RegexOption.IGNORE_CASE),
        Regex("javascript:", RegexOption.IGNORE_CASE),
        Regex("data:text/html", RegexOption.IGNORE_CASE),
        Regex("onerror\\s*=", RegexOption.IGNORE_CASE),
        Regex("onload\\s*=", RegexOption.IGNORE_CASE),
        Regex("\\{\\s*\"\\$(gt|gte|lt|lte|ne|in|nin|where|regex)\"", RegexOption.IGNORE_CASE),
        Regex("('\\s*OR\\s*'1'\\s*=\\s*'1|;\\s*DROP\\s+TABLE|--\\s*$)", RegexOption.IGNORE_CASE)
    )

    private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_\\-]{1,128}$")
    private val HTTPS_URL_REGEX = Regex("^https://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$")
    private val BLOCKED_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "169.254.169.254", "::1")

    sealed interface ValidationResult<out T> {
        data class Valid<T>(val value: T) : ValidationResult<T>
        data class Invalid(val reason: String) : ValidationResult<Nothing>
    }

    fun containsInjectionPayload(raw: String): Boolean {
        return DANGEROUS_PATTERNS.any { it.containsMatchIn(raw) }
    }

    fun sanitizeText(raw: String): String {
        return raw
            .replace("\u0000", "")
            .replace("<", "")
            .replace(">", "")
            .trim()
    }

    fun validateId(id: String): ValidationResult<String> {
        val trimmed = id.trim()
        return if (SAFE_ID_REGEX.matches(trimmed)) {
            ValidationResult.Valid(trimmed)
        } else {
            ValidationResult.Invalid("Identifier contains invalid characters or exceeds 128 chars.")
        }
    }

    fun validateBoundedText(
        raw: String,
        fieldLabel: String,
        minLen: Int,
        maxLen: Int
    ): ValidationResult<String> {
        if (containsInjectionPayload(raw)) {
            return ValidationResult.Invalid("$fieldLabel contains disallowed script or injection syntax.")
        }
        if (ProfanityFilter.containsProfanity(raw)) {
            return ValidationResult.Invalid("تم حظر الإرسال لاحتواء النص على ألفاظ غير لائقة. يرجى الالتزام بآداب الحوار الجامعي.")
        }
        val cleaned = sanitizeText(raw)
        return when {
            cleaned.length < minLen ->
                ValidationResult.Invalid("$fieldLabel must be at least $minLen characters.")
            cleaned.length > maxLen ->
                ValidationResult.Invalid("$fieldLabel must be at most $maxLen characters.")
            else -> ValidationResult.Valid(cleaned)
        }
    }

    fun validateOptionalHttpsUrl(raw: String?): ValidationResult<String?> {
        if (raw.isNullOrBlank()) return ValidationResult.Valid(null)
        val trimmed = raw.trim()
        if (containsInjectionPayload(trimmed)) {
            return ValidationResult.Invalid("URL contains unsafe characters.")
        }
        if (!trimmed.startsWith("https://", ignoreCase = false)) {
            return ValidationResult.Invalid("Only secure HTTPS URLs (https://) are permitted.")
        }
        if (trimmed.length < 10 || trimmed.length > 500 || !HTTPS_URL_REGEX.matches(trimmed)) {
            return ValidationResult.Invalid("Please enter a valid HTTPS domain URL (10–500 chars).")
        }
        return try {
            val host = URI(trimmed).host?.lowercase() ?: ""
            if (host in BLOCKED_HOSTS ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                host.startsWith("172.16.") ||
                host.endsWith(".local")
            ) {
                ValidationResult.Invalid("Private or loopback hosts are blocked for security.")
            } else {
                ValidationResult.Valid(trimmed)
            }
        } catch (_: Exception) {
            ValidationResult.Invalid("Malformed HTTPS URL.")
        }
    }

    private val DRIVE_FOLDER_ID_REGEX = Regex("^[a-zA-Z0-9_-]{10,128}$")
    private val DRIVE_URL_FOLDER_REGEX = Regex("^https://drive\\.google\\.com/(?:drive/(?:u/\\d+/)?folders/|open\\?id=)([a-zA-Z0-9_-]{10,128}).*$")

    /**
     * Extracts and validates a Google Drive folder ID and canonical HTTPS URL.
     */
    fun extractGoogleDriveFolder(rawInput: String): ValidationResult<Pair<String, String>> {
        val trimmed = rawInput.trim()
        if (containsInjectionPayload(trimmed)) {
            return ValidationResult.Invalid("Drive link contains disallowed characters.")
        }
        val match = DRIVE_URL_FOLDER_REGEX.matchEntire(trimmed)
        if (match != null) {
            val folderId = match.groupValues[1]
            return ValidationResult.Valid(folderId to "https://drive.google.com/drive/folders/$folderId")
        }
        if (DRIVE_FOLDER_ID_REGEX.matches(trimmed)) {
            return ValidationResult.Valid(trimmed to "https://drive.google.com/drive/folders/$trimmed")
        }
        return ValidationResult.Invalid("Paste a valid Google Drive folder link (https://drive.google.com/drive/folders/...).")
    }

    /**
     * Masks internal stack traces, Firestore collection paths, and sensitive tokens in UI errors
     * while preserving actionable root-cause categories (e.g., App Check, Quota, Network).
     */
    fun safeUserErrorMessage(error: Throwable?, fallback: String = "Operation could not be completed."): String {
        val msg = generateSequence(error) { it.cause }
            .mapNotNull { it.localizedMessage ?: it.message }
            .joinToString(" | ")
            .ifBlank { return fallback }

        return when {
            msg.contains("App Check", ignoreCase = true) ||
                msg.contains("appcheck", ignoreCase = true) ||
                msg.contains("attestation", ignoreCase = true) ||
                msg.contains("403") ->
                "App Check attestation failed (403). Debug APKs on a phone require an App Check debug token or unenforced AI Logic in Firebase Console."
            msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                "Access denied: insufficient permissions for this action."
            msg.contains("UNAUTHENTICATED", ignoreCase = true) ->
                "Please sign in again to continue."
            msg.contains("Quota", ignoreCase = true) ||
                msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                msg.contains("429") ->
                "Free-tier daily limit or rate limit reached. Please retry shortly."
            msg.contains("404") ||
                msg.contains("not found", ignoreCase = true) ||
                msg.contains("API has not been used", ignoreCase = true) ->
                "AI service or model endpoint is not enabled yet for this Firebase project."
            msg.contains("blocked", ignoreCase = true) ||
                msg.contains("SAFETY", ignoreCase = true) ->
                "Response was filtered for safety. Try rephrasing your study question."
            msg.contains("timed out", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true) ->
                "Connection timed out. Check internet speed or try a shorter prompt."
            msg.contains("network", ignoreCase = true) ||
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("unavailable", ignoreCase = true) ->
                "Network unavailable. Check your internet connection and retry."
            else -> fallback
        }
    }
}
