package com.example.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.model.DriveFolderLink
import com.example.data.model.DriveSyncedFile
import com.example.security.InputValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Zero-cost Google Drive shared folder sync & direct in-app file downloader.
 * - Uses drive.usercontent.google.com/download + HTML confirmation form extraction so files
 *   download locally instead of bouncing straight to browser links.
 * - Saves downloaded files with their real names into Downloads/UniCore (via MediaStore on API 29+)
 *   as well as app-private cache for instant in-app preview.
 */
class GoogleDriveSyncRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(14, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private const val MAX_DRIVE_DOWNLOAD_BYTES = 25L * 1024L * 1024L // 25 MB safety cap
        private val BLOCKED_FILE_EXTENSIONS = setOf(
            "exe", "apk", "dex", "jar", "so", "sh", "bat", "cmd", "js", "vbs", "ps1", "msi", "elf"
        )
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
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
            .header("User-Agent", BROWSER_UA)
            .get()
            .build()

        val html = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Google Drive folder '${folder.title}' returned HTTP ${response.code}. Ensure sharing is set to 'Anyone with the link'."
                )
            }
            response.body?.string().orEmpty()
        }

        if (html.isBlank()) return emptyList()
        return parseEmbeddedFolderHtml(html, folder)
    }

    internal fun parseEmbeddedFolderHtml(html: String, folder: DriveFolderLink): List<DriveSyncedFile> {
        val results = mutableListOf<DriveSyncedFile>()
        val seenIds = mutableSetOf<String>()

        val entryStartRegex = Regex(
            """<div\b[^>]*\bid=["']entry-([a-zA-Z0-9_-]{10,128})["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val entryMatches = entryStartRegex.findAll(html).toList()
        val hrefRegex = Regex("""href=["'](https://[^"']+)["']""", RegexOption.IGNORE_CASE)

        for (index in entryMatches.indices) {
            val match = entryMatches[index]
            val itemId = match.groupValues[1]
            if (itemId == folder.folderId || !seenIds.add(itemId)) continue

            val blockStart = match.range.first
            val blockEnd = if (index + 1 < entryMatches.size) entryMatches[index + 1].range.first else html.length
            val block = html.substring(blockStart, blockEnd)

            val href = hrefRegex.find(block)?.groupValues?.get(1)?.let { unescapeHtml(it) }.orEmpty()
            val cleanTitle = extractTitleFromEntryBlock(block, itemId)

            val isSubFolder = href.contains("/folders/", ignoreCase = true) ||
                block.contains("folder-icon", ignoreCase = true) ||
                block.contains("flip-folder", ignoreCase = true)

            val viewUrl = when {
                isSubFolder -> "https://drive.google.com/drive/folders/$itemId"
                href.startsWith("https://") -> href
                else -> "https://drive.google.com/file/d/$itemId/view"
            }

            val downloadUrl = when {
                href.contains("docs.google.com/document/d/", ignoreCase = true) ->
                    "https://docs.google.com/document/d/$itemId/export?format=pdf"
                href.contains("docs.google.com/presentation/d/", ignoreCase = true) ->
                    "https://docs.google.com/presentation/d/$itemId/export/pdf"
                href.contains("docs.google.com/spreadsheets/d/", ignoreCase = true) ->
                    "https://docs.google.com/spreadsheets/d/$itemId/export?format=pdf"
                else -> "https://drive.usercontent.google.com/download?id=$itemId&export=download&confirm=t"
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

        if (results.isEmpty()) {
            val anchorRegex = Regex(
                """<a\b([^>]*href=["'](https://(?:drive|docs)\.google\.com/(?:file/d|document/d|presentation/d|spreadsheets/d|drive/folders)/([a-zA-Z0-9_-]{10,128})[^"']*)["'][^>]*)>(.*?)</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            anchorRegex.findAll(html).forEach { match ->
                val attrs = match.groupValues[1]
                val href = unescapeHtml(match.groupValues[2])
                val itemId = match.groupValues[3]
                val inner = stripHtmlTags(unescapeHtml(match.groupValues[4]))
                val attrTitle = Regex("""\b(?:title|aria-label)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.let { unescapeHtml(it) }.orEmpty()
                val title = InputValidator.sanitizeText(inner.ifBlank { attrTitle })
                    .take(140)
                    .ifBlank { "Drive File (${itemId.take(8)})" }
                if (itemId != folder.folderId && seenIds.add(itemId)) {
                    val isSub = href.contains("/folders/", ignoreCase = true)
                    results.add(
                        DriveSyncedFile(
                            fileId = itemId,
                            folderId = folder.folderId,
                            folderTitle = folder.title,
                            name = title,
                            isSubFolder = isSub,
                            mimeHint = inferMimeHint(title, isSub, href),
                            viewUrl = href,
                            downloadUrl = "https://drive.usercontent.google.com/download?id=$itemId&export=download&confirm=t"
                        )
                    )
                }
            }
        }

        return results
    }

    sealed interface DriveOpenOutcome {
        data class LocalFileReady(
            val file: File,
            val mimeType: String,
            val savedToDownloads: Boolean
        ) : DriveOpenOutcome

        data class FallbackToBrowser(val url: String) : DriveOpenOutcome
    }

    suspend fun downloadDriveFile(context: Context, item: DriveSyncedFile): Result<DriveOpenOutcome> =
        withContext(Dispatchers.IO) {
            if (item.isSubFolder) {
                return@withContext Result.success(DriveOpenOutcome.FallbackToBrowser(item.viewUrl))
            }
            val safeFileId = item.fileId.replace(Regex("[^a-zA-Z0-9_-]"), "").take(128)
                .ifBlank { return@withContext Result.failure(SecurityException("Invalid Drive file identifier.")) }

            val initialExt = item.name.substringAfterLast('.', "").lowercase()
            if (initialExt in BLOCKED_FILE_EXTENSIONS) {
                return@withContext Result.failure(
                    SecurityException("Blocked executable extension '.$initialExt' from Google Drive.")
                )
            }

            val cacheRoot = File(context.filesDir, "course_cache").apply { mkdirs() }
            cacheRoot.listFiles()?.filter { it.isFile && it.name.startsWith("gdrive_${safeFileId}_") }
                ?.forEach { runCatching { it.delete() } }

            val itemCacheDir = File(cacheRoot, "gdrive_$safeFileId").apply { mkdirs() }
            if (!itemCacheDir.canonicalPath.startsWith(cacheRoot.canonicalPath + File.separator)) {
                return@withContext Result.failure(SecurityException("Invalid cache folder path."))
            }

            val cachedValid = itemCacheDir.listFiles()?.firstOrNull { file ->
                file.isFile &&
                    !file.name.endsWith(".tmp") &&
                    !file.name.startsWith("Drive Item", ignoreCase = true) &&
                    !file.name.startsWith("Drive File", ignoreCase = true) &&
                    file.length() > 0L
            }
            if (cachedValid != null) {
                val cachedMime = inferMimeFromExtension(cachedValid.extension, item.mimeHint)
                val exported = exportFileToDownloadsFolder(context, cachedValid, cachedMime)
                return@withContext Result.success(
                    DriveOpenOutcome.LocalFileReady(cachedValid, cachedMime, exported)
                )
            }

            val candidateUrls = listOf(
                item.downloadUrl,
                "https://drive.usercontent.google.com/download?id=$safeFileId&export=download&confirm=t",
                "https://drive.google.com/uc?export=download&id=$safeFileId&confirm=t"
            ).distinct()

            for (candidateUrl in candidateUrls) {
                val result = tryDownloadUrl(context, itemCacheDir, item, safeFileId, candidateUrl, allowHtmlFollowUp = true)
                if (result != null) {
                    return@withContext result
                }
            }

            Result.success(DriveOpenOutcome.FallbackToBrowser(item.viewUrl))
        }

    private fun tryDownloadUrl(
        context: Context,
        itemCacheDir: File,
        item: DriveSyncedFile,
        safeFileId: String,
        targetUrl: String,
        allowHtmlFollowUp: Boolean
    ): Result<DriveOpenOutcome>? {
        return try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", BROWSER_UA)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()

                if (contentType.startsWith("text/html")) {
                    if (!allowHtmlFollowUp) return null
                    val html = body.string()
                    val confirmUrl = extractConfirmDownloadUrl(html, targetUrl)
                    if (confirmUrl != null && isAllowedGoogleDownloadUrl(confirmUrl)) {
                        return tryDownloadUrl(
                            context,
                            itemCacheDir,
                            item,
                            safeFileId,
                            confirmUrl,
                            allowHtmlFollowUp = false
                        )
                    }
                    return null
                }

                val headerFileName = extractFileNameFromContentDisposition(response.header("Content-Disposition"))
                val chosenRawName = when {
                    !headerFileName.isNullOrBlank() &&
                        !headerFileName.equals("download", ignoreCase = true) &&
                        !headerFileName.startsWith("Drive Item", ignoreCase = true) -> headerFileName
                    else -> item.name
                }

                val sanitizedBaseName = sanitizeDownloadFileName(
                    chosenRawName,
                    fallback = "drive_file_${safeFileId.take(8)}"
                )
                val detectedExt = sanitizedBaseName.substringAfterLast('.', "").lowercase()
                if (detectedExt in BLOCKED_FILE_EXTENSIONS) {
                    return Result.failure(
                        SecurityException("Blocked executable extension '.$detectedExt' from Google Drive.")
                    )
                }

                val resolvedMime = when {
                    contentType.isNotBlank() && contentType != "application/octet-stream" -> contentType
                    else -> inferMimeFromExtension(detectedExt, item.mimeHint)
                }
                val resolvedExt = when {
                    sanitizedBaseName.contains('.') && detectedExt.isNotBlank() && detectedExt.length <= 8 -> detectedExt
                    resolvedMime == "application/pdf" -> "pdf"
                    resolvedMime == "image/png" -> "png"
                    resolvedMime == "image/jpeg" -> "jpg"
                    resolvedMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                    resolvedMime == "application/msword" -> "doc"
                    resolvedMime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                    resolvedMime == "application/vnd.ms-powerpoint" -> "ppt"
                    resolvedMime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
                    else -> "pdf"
                }
                val finalFileName = if (sanitizedBaseName.contains('.') && detectedExt.length in 1..8) {
                    sanitizedBaseName
                } else {
                    "$sanitizedBaseName.$resolvedExt"
                }

                val targetFile = File(itemCacheDir, finalFileName)
                if (!targetFile.canonicalPath.startsWith(itemCacheDir.canonicalPath + File.separator)) {
                    return Result.failure(SecurityException("Invalid cache destination path."))
                }

                val tmpFile = File(itemCacheDir, "$finalFileName.tmp")
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
                                return null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }

                if (totalBytes <= 0L) {
                    tmpFile.delete()
                    return null
                }

                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile)

                val exported = exportFileToDownloadsFolder(context, targetFile, resolvedMime)
                Result.success(DriveOpenOutcome.LocalFileReady(targetFile, resolvedMime, exported))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Copies downloaded course/Drive file into public Downloads/UniCore on Android 10+ (API 29+)
     * via MediaStore without requiring any storage permissions (Play Policy compliant).
     */
    fun exportFileToDownloadsFolder(context: Context, sourceFile: File, mimeType: String): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/UniCore")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    true
                } catch (_: Exception) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    false
                }
            } else {
                // Below API 29, keep file inside app-private Downloads subfolder without requesting WRITE_EXTERNAL_STORAGE
                val appDownloads = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "UniCore").apply { mkdirs() }
                val dest = File(appDownloads, sourceFile.name)
                sourceFile.inputStream().use { inp ->
                    FileOutputStream(dest).use { out -> inp.copyTo(out) }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun extractConfirmDownloadUrl(html: String, baseUrl: String): String? {
        val formMatch = Regex(
            """<form\b[^>]*id=["']download-form["'][^>]*action=["']([^"']+)["'][^>]*>([\s\S]*?)</form>""",
            RegexOption.IGNORE_CASE
        ).find(html)
        if (formMatch != null) {
            val action = unescapeHtml(formMatch.groupValues[1])
            val body = formMatch.groupValues[2]
            val params = mutableListOf<String>()
            val inputRegex = Regex("""<input\b([^>]+)>""", RegexOption.IGNORE_CASE)
            inputRegex.findAll(body).forEach { m ->
                val attrs = m.groupValues[1]
                val name = Regex("""name=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
                val value = Regex("""value=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1) ?: ""
                if (!name.isNullOrBlank()) {
                    params.add("${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(unescapeHtml(value), "UTF-8")}")
                }
            }
            val resolvedBase = baseUrl.toHttpUrlOrNull()?.resolve(action)?.toString() ?: action
            val sep = if (resolvedBase.contains('?')) "&" else "?"
            return if (params.isEmpty()) resolvedBase else "$resolvedBase$sep${params.joinToString("&")}"
        }

        val hrefMatch = Regex("""href=["']([^"']*confirm=[^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
        if (hrefMatch != null) {
            val raw = unescapeHtml(hrefMatch.groupValues[1])
            return baseUrl.toHttpUrlOrNull()?.resolve(raw)?.toString() ?: raw
        }
        return null
    }

    private fun isAllowedGoogleDownloadUrl(rawUrl: String): Boolean {
        val host = rawUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == "drive.google.com" ||
            host == "drive.usercontent.google.com" ||
            host == "docs.google.com" ||
            host.endsWith(".googleusercontent.com") ||
            host.endsWith(".usercontent.google.com")
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

    private fun extractTitleFromEntryBlock(block: String, itemId: String): String {
        val titlePatterns = listOf(
            Regex("""<(?:div|span|p)\b[^>]*class=["'][^"']*\bflip-entry-title\b[^"']*["'][^>]*>(.*?)</(?:div|span|p)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            Regex("""<a\b[^>]*href=["']https://[^"']+["'][^>]*\b(?:title|aria-label)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<a\b[^>]*href=["']https://[^"']+["'][^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        )
        for (pattern in titlePatterns) {
            pattern.findAll(block).forEach { candidateMatch ->
                val raw = stripHtmlTags(unescapeHtml(candidateMatch.groupValues[1]))
                val clean = InputValidator.sanitizeText(raw).take(140)
                if (clean.isNotBlank() && !clean.equals("Open", ignoreCase = true)) {
                    return clean
                }
            }
        }
        return "Drive File (${itemId.take(8)})"
    }

    internal fun extractFileNameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf8Match = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;\r\n]+)""", RegexOption.IGNORE_CASE).find(header)
        if (utf8Match != null) {
            val decoded = runCatching {
                java.net.URLDecoder.decode(utf8Match.groupValues[1].trim().trim('"', '\''), "UTF-8")
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        val quotedMatch = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(header)
        if (quotedMatch != null && quotedMatch.groupValues[1].isNotBlank()) {
            return quotedMatch.groupValues[1].trim()
        }
        val plainMatch = Regex("""filename\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE).find(header)
        if (plainMatch != null && plainMatch.groupValues[1].isNotBlank()) {
            return plainMatch.groupValues[1].trim().trim('"', '\'')
        }
        return null
    }

    private fun sanitizeDownloadFileName(rawName: String, fallback: String): String {
        val noPath = rawName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = noPath
            .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
            .take(100)
        return cleaned.ifBlank { fallback }
    }

    private fun stripHtmlTags(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    internal fun unescapeHtml(raw: String): String {
        val named = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
        val decReplaced = Regex("&#(\\d+);").replace(named) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return Regex("&#x([0-9a-fA-F]+);").replace(decReplaced) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
        }
    }

    private fun inferMimeHint(name: String, isSubFolder: Boolean, href: String): String {
        if (isSubFolder) return "inode/directory"
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") || href.contains("docs.google.com/") -> "application/pdf"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "application/vnd.ms-powerpoint"
            lower.endsWith(".doc") || lower.endsWith(".docx") -> "application/msword"
            lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "application/vnd.ms-excel"
            else -> "application/pdf"
        }
    }

    private fun inferMimeFromExtension(ext: String, fallback: String): String = when (ext.lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls", "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> fallback
    }
}
