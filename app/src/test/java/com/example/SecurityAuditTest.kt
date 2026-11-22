package com.example

import com.example.data.model.AuditSeverity
import com.example.data.model.MaterialType
import com.example.data.model.UserRole
import com.example.security.InputValidator
import com.example.security.RateLimitEngine
import com.example.security.RateLimiter
import com.example.security.SecureFileValidator
import com.example.security.ZeroCostQuotaManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SecurityAuditTest {

    @Test
    fun `input validator blocks XSS SQLi NoSQL injection and unsafe URLs`() {
        val xss = InputValidator.validateBoundedText("<script>alert(1)</script>", "Title", 3, 100)
        assertTrue(xss is InputValidator.ValidationResult.Invalid)

        val nosql = InputValidator.validateBoundedText("{\"${'$'}gt\": \"\"}", "Prompt", 3, 100)
        assertTrue(nosql is InputValidator.ValidationResult.Invalid)

        val sqli = InputValidator.validateBoundedText("' OR '1'='1", "Name", 3, 100)
        assertTrue(sqli is InputValidator.ValidationResult.Invalid)

        val jsUrl = InputValidator.validateOptionalHttpsUrl("javascript:alert(1)")
        assertTrue(jsUrl is InputValidator.ValidationResult.Invalid)

        val httpUrl = InputValidator.validateOptionalHttpsUrl("http://example.com/notes.pdf")
        assertTrue(httpUrl is InputValidator.ValidationResult.Invalid)

        val ssrfUrl = InputValidator.validateOptionalHttpsUrl("https://169.254.169.254/latest/meta-data/")
        assertTrue(ssrfUrl is InputValidator.ValidationResult.Invalid)

        val validUrl = InputValidator.validateOptionalHttpsUrl("https://onedrive.live.com/view/notes")
        assertTrue(validUrl is InputValidator.ValidationResult.Valid)
    }

    @Test
    fun `error sanitizer hides internal collection paths and stack traces`() {
        val internal = RuntimeException("PERMISSION_DENIED at /quizzes/q1/private/answer_key")
        val safe = InputValidator.safeUserErrorMessage(internal)
        assertFalse(safe.contains("answer_key"))
        assertFalse(safe.contains("/quizzes"))
    }

    @Test
    fun `rate limit engine enforces sliding window and progressive exponential lockout`() {
        var now = 1_000_000L
        var state = RateLimitEngine.State()

        repeat(5) {
            val eval = RateLimitEngine.evaluate(state, RateLimiter.Bucket.AUTH, now)
            assertTrue(eval.decision is RateLimiter.Decision.Allowed)
            state = eval.nextState
        }

        // 6th attempt within window triggers 30s lockout
        val firstBlock = RateLimitEngine.evaluate(state, RateLimiter.Bucket.AUTH, now)
        assertTrue(firstBlock.decision is RateLimiter.Decision.Blocked)
        assertEquals(30L, (firstBlock.decision as RateLimiter.Decision.Blocked).retryAfterSeconds)
        state = firstBlock.nextState

        // 2nd strike after 30s lockout triggers 60s lockout
        now += 31_000L
        val secondBlock = RateLimitEngine.evaluate(state, RateLimiter.Bucket.AUTH, now)
        assertTrue(secondBlock.decision is RateLimiter.Decision.Blocked)
        assertEquals(60L, (secondBlock.decision as RateLimiter.Decision.Blocked).retryAfterSeconds)
    }

    @Test
    fun `file validator magic bytes and sandbox storage reject malware and store safe randomized PDF`() {
        val tempDir = Files.createTempDirectory("unicore_sandbox_test").toFile()
        try {
            // Spoofed .exe masquerading as PDF
            val exeBytes = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)
            val rejectedExe = SecureFileValidator.validateBytesAndStore(
                displayName = "../../evil.exe",
                rawMimeType = "application/pdf",
                inputBytes = exeBytes,
                sandboxDir = tempDir
            )
            assertTrue(rejectedExe is SecureFileValidator.FileValidationResult.Rejected)

            // Oversized payload > maxBytes
            val oversized = "%PDF-1.7\n".toByteArray() + ByteArray(2048)
            val rejectedSize = SecureFileValidator.validateBytesAndStore(
                displayName = "large.pdf",
                rawMimeType = "application/pdf",
                inputBytes = oversized,
                sandboxDir = tempDir,
                maxBytes = 1024
            )
            assertTrue(rejectedSize is SecureFileValidator.FileValidationResult.Rejected)

            // Valid PDF stored with randomized UUID name and SHA-256
            val validPdf = "%PDF-1.7\nHello UniCore".toByteArray()
            val stored = SecureFileValidator.validateBytesAndStore(
                displayName = "../notes.pdf",
                rawMimeType = "application/pdf",
                inputBytes = validPdf,
                sandboxDir = tempDir
            )
            assertTrue(stored is SecureFileValidator.FileValidationResult.Success)
            val file = (stored as SecureFileValidator.FileValidationResult.Success).file
            assertNotEquals("notes.pdf", file.storedName)
            assertTrue(file.storedName.startsWith("uc_") && file.storedName.endsWith(".pdf"))
            assertEquals(64, file.sha256Hex.length)
            assertTrue(file.sandboxPath.startsWith(tempDir.canonicalPath))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `file validator magic bytes reject APK ELF and shell scripts while accepting PNG JPEG`() {
        assertFalse(SecureFileValidator.matchesMagicBytes("image/png", byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(SecureFileValidator.matchesMagicBytes("application/pdf", byteArrayOf(0x7F, 0x45, 0x4C, 0x46)))
        assertFalse(SecureFileValidator.matchesMagicBytes("application/pdf", "#!/bin/sh".toByteArray()))
        assertTrue(
            SecureFileValidator.matchesMagicBytes(
                "image/png",
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        )
        assertTrue(
            SecureFileValidator.matchesMagicBytes(
                "image/jpeg",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
            )
        )
    }

    @Test
    fun `zero cost quota manager warns at 80 percent and safely pauses at 100 percent`() {
        val healthy = ZeroCostQuotaManager.QuotaSnapshot(5, 20, 10, 100, true, false)
        assertEquals(ZeroCostQuotaManager.QuotaHealth.HEALTHY, healthy.aiHealth)

        val warning = ZeroCostQuotaManager.QuotaSnapshot(16, 20, 80, 100, true, false)
        assertEquals(ZeroCostQuotaManager.QuotaHealth.WARNING_80_PERCENT, warning.aiHealth)
        assertEquals(ZeroCostQuotaManager.QuotaHealth.WARNING_80_PERCENT, warning.writeHealth)

        val paused = ZeroCostQuotaManager.QuotaSnapshot(20, 20, 100, 100, true, false)
        assertEquals(ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED, paused.aiHealth)
        assertEquals(ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED, paused.writeHealth)

        val superLocked = ZeroCostQuotaManager.QuotaSnapshot(0, 20, 0, 100, true, true)
        assertEquals(ZeroCostQuotaManager.QuotaHealth.PAUSED_LIMIT_REACHED, superLocked.aiHealth)
    }

    @Test
    fun `enum deserialization safely falls back on unknown schema drift`() {
        assertEquals(UserRole.STUDENT, UserRole.fromRaw("UNKNOWN_ROLE"))
        assertEquals(MaterialType.NOTES, MaterialType.fromRaw("LEGACY_TYPE"))
        assertEquals(AuditSeverity.INFO, AuditSeverity.fromRaw(null))
    }
}
