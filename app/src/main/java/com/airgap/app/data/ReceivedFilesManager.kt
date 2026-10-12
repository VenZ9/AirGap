package com.airgap.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class ReceivedFileItem(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val contentUri: Uri?
)

class ReceivedFilesManager(private val context: Context) {

    private val receivedDir: File
        get() {
            val dir = File(context.filesDir, "received")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    suspend fun saveReceivedFile(name: String, data: ByteArray): File = withContext(Dispatchers.IO) {
        val safeName = sanitizeFileName(name)
        var targetFile = File(receivedDir, safeName)
        if (targetFile.exists()) {
            val base = safeName.substringBeforeLast('.')
            val ext = if (safeName.contains('.')) "." + safeName.substringAfterLast('.') else ""
            var counter = 1
            while (targetFile.exists()) {
                targetFile = File(receivedDir, "${base}_$counter$ext")
                counter++
            }
        }

        FileOutputStream(targetFile).use { out ->
            out.write(data)
            out.flush()
        }
        targetFile
    }

    suspend fun copyStreamToReceivedFile(name: String, stream: InputStream): File = withContext(Dispatchers.IO) {
        val safeName = sanitizeFileName(name)
        var targetFile = File(receivedDir, safeName)
        if (targetFile.exists()) {
            val base = safeName.substringBeforeLast('.')
            val ext = if (safeName.contains('.')) "." + safeName.substringAfterLast('.') else ""
            var counter = 1
            while (targetFile.exists()) {
                targetFile = File(receivedDir, "${base}_$counter$ext")
                counter++
            }
        }

        targetFile.outputStream().use { out ->
            stream.copyTo(out)
            out.flush()
        }
        targetFile
    }

    suspend fun getReceivedFiles(): List<ReceivedFileItem> = withContext(Dispatchers.IO) {
        val files = receivedDir.listFiles() ?: return@withContext emptyList()
        files.filter { it.isFile && it.length() > 0 }.map { f ->
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }.getOrNull()
            ReceivedFileItem(
                file = f,
                name = f.name,
                size = f.length(),
                lastModified = f.lastModified(),
                mimeType = detectMimeType(f.name),
                contentUri = uri
            )
        }.sortedByDescending { it.lastModified }
    }

    suspend fun deleteFile(file: File): Boolean = withContext(Dispatchers.IO) {
        if (file.exists() && file.parentFile?.canonicalPath == receivedDir.canonicalPath) {
            file.delete()
        } else false
    }

    suspend fun clearAllFiles(): Int = withContext(Dispatchers.IO) {
        val files = receivedDir.listFiles() ?: return@withContext 0
        var count = 0
        for (f in files) {
            if (f.delete()) count++
        }
        count
    }

    fun createViewIntent(item: ReceivedFileItem): Intent? {
        val uri = item.contentUri ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createShareIntent(item: ReceivedFileItem): Intent? {
        val uri = item.contentUri ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        return trimmed.take(180).ifBlank { "airgap_received_${System.currentTimeMillis()}.bin" }
    }

    private fun detectMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }
}
