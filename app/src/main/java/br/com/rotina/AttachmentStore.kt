package br.com.rotina

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

object AttachmentStore {
    private const val BUFFER = 64 * 1024

    suspend fun import(context: Context, taskId: String, uri: Uri): TaskAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri) ?: "anexo-${System.currentTimeMillis()}"
        val mime = resolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(dir, "$id-${safeName(displayName)}")
        val hash = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        try {
            resolver.openInputStream(uri)?.buffered(BUFFER)?.use { input ->
                FileOutputStream(file).buffered(BUFFER).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                        bytes += count
                    }
                }
            } ?: error("Não foi possível ler o arquivo selecionado.")
            TaskAttachment(id, taskId, displayName, mime, "attachments/${file.name}", bytes, hash.digest().hex())
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun open(context: Context, attachment: TaskAttachment) {
        val file = resolve(context, attachment.relativePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Abrir ${attachment.fileName}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun thumbnail(file: File, maxDimension: Int = 280): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    fun resolve(context: Context, relativePath: String): File {
        require(relativePath.startsWith("attachments/") && !relativePath.contains("..")) { "Caminho de anexo inválido." }
        val root = File(context.filesDir, "attachments").canonicalFile
        val result = File(context.filesDir, relativePath).canonicalFile
        require(result.parentFile == root) { "Caminho de anexo inválido." }
        return result
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun safeName(name: String) = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "arquivo" }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
