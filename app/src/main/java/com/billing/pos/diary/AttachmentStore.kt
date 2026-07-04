package com.billing.pos.diary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.billing.pos.data.AttachmentType
import com.billing.pos.data.DiaryAttachment
import java.io.File

/** Copies picked/recorded files into app-private storage for the diary. */
object AttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "diary").apply { mkdirs() }

    /** Copies [uri] into app storage and returns a (not-yet-persisted) attachment. */
    fun copyIn(context: Context, uri: Uri): DiaryAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri) ?: "file_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "att_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            DiaryAttachment(
                entryId = 0,
                path = target.absolutePath,
                name = displayName,
                mime = mime,
                type = typeOf(mime)
            )
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** Registers an already-written file (e.g. a voice recording) as an attachment. */
    fun fromFile(file: File, name: String, mime: String): DiaryAttachment =
        DiaryAttachment(entryId = 0, path = file.absolutePath, name = name, mime = mime, type = typeOf(mime))

    fun delete(attachment: DiaryAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    /** A content:// Uri for opening the attachment in another app. */
    fun uriFor(context: Context, attachment: DiaryAttachment): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(attachment.path))

    fun typeOf(mime: String): AttachmentType = when {
        mime.startsWith("image/") -> AttachmentType.IMAGE
        mime.startsWith("video/") -> AttachmentType.VIDEO
        mime.startsWith("audio/") -> AttachmentType.AUDIO
        else -> AttachmentType.DOCUMENT
    }

    private fun extFromMime(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "video/mp4" -> "mp4"
        "audio/mp4", "audio/aac" -> "m4a"; "application/pdf" -> "pdf"; else -> ""
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }
}
