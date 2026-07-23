package com.billing.pos.marketing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * Files chosen for a WhatsApp marketing broadcast.
 *
 * Picked files are copied into the app's cache so they can be shared as content:// URIs,
 * and sent to one contact at a time — WhatsApp requires a person to tap send for each,
 * which is exactly the guided, one-by-one flow the broadcast uses.
 */
object MarketingMedia {

    private fun dir(context: Context): File =
        File(context.cacheDir, "marketing").apply { mkdirs() }

    /** Copies a picked file in and returns its cache Uri, or null on failure. */
    fun copyIn(context: Context, uri: Uri): Uri? = runCatching {
        val name = displayName(context, uri) ?: ("file_" + System.nanoTime())
        val safe = name.take(48).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir(context), System.nanoTime().toString() + "_" + safe)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        FileProvider.getUriForFile(context, context.packageName + ".provider", target)
    }.getOrNull()

    /**
     * Opens a WhatsApp chat to [phone] with [files] attached and [text] as the caption below
     * them, ready to send. Two things make the attachment actually go through rather than a
     * text-only message: the intent's MIME is set to the real media type (a wildcard type
     * makes WhatsApp fall back to text only), and the caption is also copied to the clipboard
     * so it can be pasted if WhatsApp drops it on a multi-image send.
     */
    fun sendToWhatsApp(context: Context, phone: String, text: String, files: List<Uri>) {
        val digits = phone.filter { it.isDigit() }

        // With no files it is a plain text chat.
        if (files.isEmpty()) {
            if (digits.isNotBlank()) runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(text)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }

        // Keep the caption on the clipboard as a fallback for multi-image sends.
        if (text.isNotBlank()) runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        }

        val mime = mimeFor(context, files)
        val base = if (files.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
        base.apply {
            type = mime
            if (files.size == 1) putExtra(Intent.EXTRA_STREAM, files[0])
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            if (digits.isNotBlank()) putExtra("jid", digits + "@s.whatsapp.net")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val direct = Intent(base).setPackage(pkg)
            if (direct.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(direct) }.onSuccess { return }
            }
        }
        // No WhatsApp installed — offer the media through the general share sheet.
        runCatching {
            context.startActivity(Intent.createChooser(base, "Send").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** A MIME that WhatsApp will treat as media: the shared type if all files match, else images. */
    private fun mimeFor(context: Context, files: List<Uri>): String {
        val types = files.mapNotNull { context.contentResolver.getType(it) }
        val allImages = types.isNotEmpty() && types.all { it.startsWith("image/") }
        val allVideos = types.isNotEmpty() && types.all { it.startsWith("video/") }
        return when {
            files.size == 1 -> types.firstOrNull() ?: "image/*"
            allImages -> "image/*"
            allVideos -> "video/*"
            else -> "*/*"
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
