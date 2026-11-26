package com.example.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compresses image URIs picked via Photo Picker into verified JPEG Base64 strings
 * within Spark-plan Firestore limits without requesting storage permissions.
 */
object ImageAttachmentHelper {

    data class CompressedImage(
        val base64: String,
        val mimeType: String,
        val fileName: String
    )

    fun compressUriToBase64(
        context: Context,
        uri: Uri,
        maxBase64Chars: Int = 640_000,
        maxDimensionPx: Int = 1280
    ): Result<CompressedImage> {
        return try {
            val resolver = context.contentResolver
            val rawName = resolveDisplayName(context, uri)
            val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalArgumentException("Unable to read selected image."))

            if (rawBytes.isEmpty()) {
                return Result.failure(IllegalArgumentException("Selected image is empty."))
            }

            val isPng = SecureFileValidator.matchesMagicBytes("image/png", rawBytes)
            val isJpeg = SecureFileValidator.matchesMagicBytes("image/jpeg", rawBytes)
            if (!isPng && !isJpeg) {
                return Result.failure(IllegalArgumentException("Only PNG and JPEG images are supported."))
            }

            val rawB64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
            if (rawB64.length <= maxBase64Chars) {
                val safeName = InputValidator.sanitizeText(rawName).take(140).ifBlank {
                    if (isPng) "image.png" else "image.jpg"
                }
                return Result.success(
                    CompressedImage(
                        base64 = rawB64,
                        mimeType = if (isPng) "image/png" else "image/jpeg",
                        fileName = safeName
                    )
                )
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val maxOrigDim = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sampleSize = 1
            while (maxOrigDim / sampleSize > maxDimensionPx * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return Result.failure(IllegalArgumentException("Could not decode image."))

            val scale = (maxDimensionPx.toFloat() / max(decoded.width, decoded.height).coerceAtLeast(1)).coerceAtMost(1f)
            val scaledBitmap = if (scale < 0.99f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).roundToInt().coerceAtLeast(1),
                    (decoded.height * scale).roundToInt().coerceAtLeast(1),
                    true
                ).also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }

            try {
                for (quality in listOf(82, 68, 52, 38, 28)) {
                    val out = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    if (encoded.length <= maxBase64Chars) {
                        val baseTitle = InputValidator.sanitizeText(rawName).substringBeforeLast('.').ifBlank { "image" }
                        return Result.success(
                            CompressedImage(
                                base64 = encoded,
                                mimeType = "image/jpeg",
                                fileName = "$baseTitle.jpg".take(140)
                            )
                        )
                    }
                }
                Result.failure(IllegalArgumentException("Image is too large even after compression."))
            } finally {
                scaledBitmap.recycle()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: "image.jpg"
                }
            }
        return uri.lastPathSegment ?: "image.jpg"
    }
}
