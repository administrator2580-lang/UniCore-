package com.example.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.model.DriveFolderLink
import com.example.data.model.DriveSyncedFile
import com.example.security.InputValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Zero-cost Google Drive shared folder sync & file downloader using embeddedfolderview HTML parsing
 * and direct HTTPS uc?export=download streaming into app-private cache shared via FileProvider.
 */
class GoogleDriveSyncRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private const val MAX_DRIVE_DOWNLOAD_BYTES = 15L * 1024L * 1024L // 15 MB safety cap
        private val BLOCKED_FILE_EXTENSIONS = setOf(
            "exe", "apk", "dex", "jar", "so", "sh", "bat", "cmd", "js", "vbs", "ps1", "msi", "elf"
        )
    }

    suspend fun syncDriveFolders(folders: List<DriveFolderLink>): Result<List<DriveSyncedFile>> =
        withContext(Dispatchers.IO) {
            if (folders.isEmpty()) return@withContext Result.success(emptyList())
            val collected = mutableListOf<DriveSyncedFile>()
            var lastError: Throwable? = null

            for (folder in folders) {
                try {
                    val items = fetchFolderFiles(folder)
                    collected.addAll(items)
                } catch (e: Exception) {
                    lastError = e
                }
            }

            if (collected.isEmpty() && lastError != null) {
                Result.failure(lastError)
            } else {
                Result.success(collected)
            }
        }

    private fun fetchFolderFiles(folder: DriveFolderLink): List<DriveSyncedFile> {
        val url = "https://drive.google.com/embeddedfolderview?id=${folder.folderId}#list"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            .get()
            .build()

        val html = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Drive folder '${folder.title}' returned HTTP ${response.code}. Ensure sharing is set to 'Anyone with the link'.")
            }
            response.body?.string().orEmpty()
        }

        if (html.isBlank()) return emptyList()
        return parseEmbeddedFolderHtml(html, folder)
    }

    internal fun parseEmbeddedFolderHtml(html: String, folder: DriveFolderLink): List<DriveSyncedFile> {
        val results = mutableListOf<DriveSyncedFile>()
        val seenIds = mutableSetOf<String>()

        // Split by flip-entry containers used by Google Drive embeddedfolderview
        val entryRegex = Regex(
            """<div[^>]*class="[^"]*flip-entry[^"]*"[^>]*id="entry-([a-zA-Z0-9_-]{10,128})"[^>]*>(.*?)(?=<div[^>]*class="[^"]*flip-entry|$)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val titleRegex = Regex("""<div[^>]*class="[^"]*flip-entry-title[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        val hrefRegex = Regex("""href="(https://[^"]+)"""")

        entryRegex.findAll(html).forEach { match ->
            val itemId = match.groupValues[1]
            val block = match.groupValues[2]
            if (itemId == folder.folderId || !seenIds.add(itemId)) return@forEach

            val rawTitle = titleRegex.find(block)?.groupValues?.get(1)?.let { stripHtmlTags(it) } ?: "Drive Item"
            val cleanTitle = InputValidator.sanitizeText(unescapeHtml(rawTitle)).take(140).ifBlank { "Drive Item" }
            val href = hrefRegex.find(block)?.groupValues?.get(1)?.let { unescapeHtml(it) }.orEmpty()

            val isSubFolder = href.contains("/folders/", ignoreCase = true) ||
                block.contains("folder-icon", ignoreCase = true)

            val viewUrl = when {
                isSubFolder -> "https://drive.google.com/drive/folders/$itemId"
                href.startsWith("https://") -> href
                else -> "https://drive.google.com/file/d/$itemId/view"
            }

            val downloadUrl = if (href.contains("docs.google.com/document/d/", ignoreCase = true)) {
                "https://docs.google.com/document/d/$itemId/export?format=pdf"
            } else if (href.contains("docs.google.com/presentation/d/", ignoreCase = true)) {
                "https://docs.google.com/presentation/d/$itemId/export/pdf"
            } else if (href.contains("docs.google.com/spreadsheets/d/", ignoreCase = true)) {
                "https://docs.google.com/spreadsheets/d/$itemId/export?format=pdf"
            } else {
                "https://drive.google.com/uc?export=download&id=$itemId"
            }

            val mimeHint = inferMimeHint(cleanTitle, isSubFolder, href)
            results.add(
                DriveSyncedFile(
                    fileId = itemId,
                    folderId = folder.folderId,
                    folderTitle = folder.title,
                    name = cleanTitle,
                    isSubFolder = isSubFolder,
                    mimeHint = mimeHint,
                    viewUrl = viewUrl,
                    downloadUrl = downloadUrl
                )
            )
        }

        // Secondary fallback regex if Drive uses anchor-only layout without flip-entry IDs
        if (results.isEmpty()) {
            val linkRegex = Regex(
                """https://drive\.google\.com/file/d/([a-zA-Z0-9_-]{10,128})/view""",
                RegexOption.IGNORE_CASE
            )
            linkRegex.findAll(html).forEach { match ->
                val itemId = match.groupValues[1]
                if (itemId != folder.folderId && seenIds.add(itemId)) {
                    results.add(
                        DriveSyncedFile(
                            fileId = itemId,
                            folderId = folder.folderId,
                            folderTitle = folder.title,
                            name = "Drive File (${itemId.take(8)})",
                            isSubFolder = false,
                            mimeHint = "application/pdf",
                            viewUrl = "https://drive.google.com/file/d/$itemId/view",
                            downloadUrl = "https://drive.google.com/uc?export=download&id=$itemId"
                        )
                    )
                }
            }
        }

        return results
    }

    sealed interface DriveOpenOutcome {
        data class LocalFileReady(val file: File, val mimeType: String) : DriveOpenOutcome
        data class FallbackToBrowser(val url: String) : DriveOpenOutcome
    }

    suspend fun downloadDriveFile(context: Context, item: DriveSyncedFile): Result<DriveOpenOutcome> =
        withContext(Dispatchers.IO) {
            if (item.isSubFolder) {
                return@withContext Result.success(DriveOpenOutcome.FallbackToBrowser(item.viewUrl))
            }
            val ext = item.name.substringAfterLast('.', "").lowercase()
            if (ext in BLOCKED_FILE_EXTENSIONS) {
                return@withContext Result.failure(SecurityException("Blocked executable extension '.$ext' from Google Drive."))
            }

            val cacheDir = File(context.filesDir, "course_cache").apply { mkdirs() }
            val safeName = item.name.replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF ]"), "_").take(80)
                .ifBlank { "drive_file_${item.fileId.take(8)}" }

            // Return existing non-empty cached copy matching ID prefix if present
            val existing = cacheDir.listFiles()?.firstOrNull {
                it.name.startsWith("gdrive_${item.fileId}_") && it.length() > 0L
            }
            if (existing != null) {
                val cachedMime = inferMimeFromExtension(existing.extension, item.mimeHint)
                return@withContext Result.success(DriveOpenOutcome.LocalFileReady(existing, cachedMime))
            }

            try {
                val request = Request.Builder()
                    .url(item.downloadUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.success(DriveOpenOutcome.FallbackToBrowser(item.viewUrl))
                    }
                    val body = response.body ?: return@withContext Result.success(
                        DriveOpenOutcome.FallbackToBrowser(item.viewUrl)
                    )
                    val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    if (contentType.startsWith("text/html")) {
                        // Drive returned confirmation/login HTML page (e.g., large file warning or private link)
                        return@withContext Result.success(DriveOpenOutcome.FallbackToBrowser(item.viewUrl))
                    }

                    val resolvedMime = when {
                        contentType.isNotBlank() && contentType != "application/octet-stream" -> contentType
                        else -> item.mimeHint
                    }
                    val resolvedExt = when {
                        safeName.contains('.') -> safeName.substringAfterLast('.').lowercase()
                        resolvedMime == "application/pdf" -> "pdf"
                        resolvedMime == "image/png" -> "png"
                        resolvedMime == "image/jpeg" -> "jpg"
                        else -> "pdf"
                    }
                    val finalFileName = if (safeName.contains('.')) {
                        "gdrive_${item.fileId}_$safeName"
                    } else {
                        "gdrive_${item.fileId}_$safeName.$resolvedExt"
                    }
                    val targetFile = File(cacheDir, finalFileName)
                    if (!targetFile.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)) {
                        return@withContext Result.failure(SecurityException("Invalid cache destination path."))
                    }

                    val tmpFile = File(cacheDir, "$finalFileName.tmp")
                    var totalBytes = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                totalBytes += read
                                if (totalBytes > MAX_DRIVE_DOWNLOAD_BYTES) {
                                    tmpFile.delete()
                                    return@withContext Result.success(DriveOpenOutcome.FallbackToBrowser(item.viewUrl))
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    if (totalBytes <= 0L) {
                        tmpFile.delete()
                        return@withContext Result.success(DriveOpenOutcome.FallbackToBrowser(item.viewUrl))
                    }

                    if (targetFile.exists()) targetFile.delete()
                    tmpFile.renameTo(targetFile)
                    Result.success(DriveOpenOutcome.LocalFileReady(targetFile, resolvedMime))
                }
            } catch (_: Exception) {
                Result.success(DriveOpenOutcome.FallbackToBrowser(item.viewUrl))
            }
        }

    fun openLocalFileWithIntent(context: Context, file: File, mimeType: String): Result<Unit> {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, file.name).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun stripHtmlTags(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun unescapeHtml(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")

    private fun inferMimeHint(name: String, isSubFolder: Boolean, href: String): String {
        if (isSubFolder) return "inode/directory"
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") || href.contains("docs.google.com/") -> "application/pdf"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "application/vnd.ms-powerpoint"
            lower.endsWith(".doc") || lower.endsWith(".docx") -> "application/msword"
            else -> "application/pdf"
        }
    }

    private fun inferMimeFromExtension(ext: String, fallback: String): String = when (ext.lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> fallback
    }
}
