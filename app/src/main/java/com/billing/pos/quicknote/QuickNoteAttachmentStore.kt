package com.billing.pos.quicknote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.billing.pos.data.QuickNoteAttachment
import java.io.File

/** Copies picked/captured photos into app-private storage for quick notes. */
object QuickNoteAttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "quicknotes").apply { mkdirs() }

    /** Copies [uri] into app storage and returns a (not-yet-persisted) attachment. */
    fun copyIn(context: Context, uri: Uri): QuickNoteAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val displayName = queryName(context, uri) ?: "photo_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "qn_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            QuickNoteAttachment(noteId = 0, path = target.absolutePath, name = displayName, mime = mime)
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** Registers an already-written file (a camera capture) as an attachment. */
    fun fromFile(file: File, name: String, mime: String): QuickNoteAttachment =
        QuickNoteAttachment(noteId = 0, path = file.absolutePath, name = name, mime = mime)

    fun delete(attachment: QuickNoteAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    private fun extFromMime(mime: String): String = when (mime) {
        "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg"
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
}
