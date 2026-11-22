package com.example.security

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Validates selected files via Android Photo Picker / SAF without dangerous storage permissions.
 * Checks extension, MIME type, actual file magic bytes, executable blocking, max size,
 * SHA-256 digest, and copies into isolated app-private storage with randomized UUID names.
 */
object SecureFileValidator {

    const val DEFAULT_MAX_BYTES = 5 * 1024 * 1024 // 5 MB

    private val ALLOWED_MIME_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "image/png" to "png",
        "image/jpeg" to "jpg"
    )

    private val DANGEROUS_EXTENSIONS = setOf(
        "exe", "apk", "dex", "jar", "so", "sh", "bat", "cmd", "js", "vbs", "ps1", "msi", "elf", "html", "htm"
    )

    data class ValidatedFile(
        val originalName: String,
        val storedName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256Hex: String,
        val sandboxPath: String
    )

    sealed interface FileValidationResult {
        data class Success(val file: ValidatedFile) : FileValidationResult
        data class Rejected(val reason: String) : FileValidationResult
    }

    fun validateAndStoreInSandbox(
        context: Context,
        uri: Uri,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): FileValidationResult {
        val resolver = context.contentResolver
        val rawMime = resolver.getType(uri)?.lowercase() ?: ""
        val displayName = resolveDisplayName(context, uri)

        val inputBytes = try {
            resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(maxBytes + 1)
                var totalRead = 0
                while (totalRead < buffer.size) {
                    val read = stream.read(buffer, totalRead, buffer.size - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                if (totalRead > maxBytes) {
                    return FileValidationResult.Rejected(
                        "File exceeds maximum allowed size (${maxBytes / (1024 * 1024)} MB)."
                    )
                }
                if (totalRead <= 0) {
                    return FileValidationResult.Rejected("Empty file rejected.")
                }
                buffer.copyOf(totalRead)
            } ?: return FileValidationResult.Rejected("Unable to read selected file.")
        } catch (_: Exception) {
            return FileValidationResult.Rejected("Failed to read selected file safely.")
        }

        val sandboxDir = File(context.filesDir, "secure_uploads").apply { mkdirs() }
        return validateBytesAndStore(
            displayName = displayName,
            rawMimeType = rawMime,
            inputBytes = inputBytes,
            sandboxDir = sandboxDir,
            maxBytes = maxBytes
        )
    }

    internal fun validateBytesAndStore(
        displayName: String,
        rawMimeType: String,
        inputBytes: ByteArray,
        sandboxDir: File,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): FileValidationResult {
        val rawMime = rawMimeType.lowercase()
        val expectedExt = ALLOWED_MIME_EXTENSIONS[rawMime]
            ?: return FileValidationResult.Rejected("Unsupported MIME type '$rawMime'. Only PDF, PNG, and JPEG are allowed.")

        if (inputBytes.isEmpty()) {
            return FileValidationResult.Rejected("Empty file rejected.")
        }
        if (inputBytes.size > maxBytes) {
            return FileValidationResult.Rejected(
                "File exceeds maximum allowed size (${maxBytes / (1024 * 1024)} MB)."
            )
        }

        val sanitizedOriginal = InputValidator.sanitizeText(displayName)
            .replace("..", "")
            .replace("/", "_")
            .replace("\\", "_")
            .take(120)
            .ifBlank { "document.$expectedExt" }

        val originalExt = sanitizedOriginal.substringAfterLast('.', "").lowercase()
        if (originalExt in DANGEROUS_EXTENSIONS) {
            return FileValidationResult.Rejected("Executable or script file extension '.$originalExt' is blocked.")
        }

        if (!matchesMagicBytes(rawMime, inputBytes)) {
            return FileValidationResult.Rejected(
                "File signature mismatch: actual binary header does not match '$rawMime'."
            )
        }

        val sha256Hex = MessageDigest.getInstance("SHA-256")
            .digest(inputBytes)
            .joinToString("") { "%02x".format(it) }

        val randomName = "uc_${UUID.randomUUID().toString().replace("-", "")}.$expectedExt"
        sandboxDir.mkdirs()
        val targetFile = File(sandboxDir, randomName)
        if (!targetFile.canonicalPath.startsWith(sandboxDir.canonicalPath + File.separator)) {
            return FileValidationResult.Rejected("Invalid storage path.")
        }
        targetFile.writeBytes(inputBytes)

        return FileValidationResult.Success(
            ValidatedFile(
                originalName = sanitizedOriginal,
                storedName = randomName,
                mimeType = rawMime,
                sizeBytes = inputBytes.size.toLong(),
                sha256Hex = sha256Hex,
                sandboxPath = targetFile.absolutePath
            )
        )
    }

    internal fun matchesMagicBytes(mimeType: String, bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val isExeMz = bytes[0] == 0x4D.toByte() && bytes[1] == 0x5A.toByte()
        val isZipApk = bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        val isElf = bytes[0] == 0x7F.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0x4C.toByte() && bytes[3] == 0x46.toByte()
        val isShebang = bytes[0] == 0x23.toByte() && bytes[1] == 0x21.toByte()
        if (isExeMz || isZipApk || isElf || isShebang) return false

        return when (mimeType) {
            "application/pdf" ->
                bytes[0] == 0x25.toByte() &&
                    bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x44.toByte() &&
                    bytes[3] == 0x46.toByte()
            "image/png" ->
                bytes.size >= 8 &&
                    bytes[0] == 0x89.toByte() &&
                    bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() &&
                    bytes[3] == 0x47.toByte()
            "image/jpeg" ->
                bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte()
            else -> false
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: "upload"
                }
            }
        return uri.lastPathSegment ?: "upload"
    }
}
