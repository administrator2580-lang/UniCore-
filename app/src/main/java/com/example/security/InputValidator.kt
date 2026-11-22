package com.example.security

import com.example.BuildConfig
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

    /**
     * Masks internal stack traces, Firestore collection paths, and sensitive tokens in UI errors.
     */
    fun safeUserErrorMessage(error: Throwable?, fallback: String = "Operation could not be completed."): String {
        val msg = error?.localizedMessage ?: return fallback
        if (BuildConfig.DEBUG) {
            // Even in debug UI, avoid dumping raw internal stack traces to end-user banners
            if (msg.contains("PERMISSION_DENIED", ignoreCase = true)) {
                return "Access denied by server security rules."
            }
            if (msg.contains("Quota exceeded", ignoreCase = true) ||
                msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
            ) {
                return "Free-tier quota reached. Feature paused safely to maintain zero cost."
            }
        }
        return when {
            msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                "Access denied: insufficient permissions for this action."
            msg.contains("UNAUTHENTICATED", ignoreCase = true) ->
                "Please sign in again to continue."
            msg.contains("Quota", ignoreCase = true) ||
                msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
                "Free-tier daily limit reached. Paused safely with zero charges."
            msg.contains("network", ignoreCase = true) ||
                msg.contains("unavailable", ignoreCase = true) ->
                "Network unavailable. Check your HTTPS connection and retry."
            else -> fallback
        }
    }
}
