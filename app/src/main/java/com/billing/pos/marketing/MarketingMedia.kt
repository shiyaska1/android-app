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
     * Opens a WhatsApp chat to [phone] with [text] and [files] attached, ready to send.
     * The number goes in as a jid so WhatsApp lands directly on that contact.
     */
    fun sendToWhatsApp(context: Context, phone: String, text: String, files: List<Uri>) {
        val digits = phone.filter { it.isDigit() }
        val base = if (files.size <= 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
        base.apply {
            type = "*/*"
            if (files.size == 1) putExtra(Intent.EXTRA_STREAM, files[0])
            else if (files.isNotEmpty()) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
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
        // No WhatsApp — fall back to a text-only chat link if there is a number.
        if (digits.isNotBlank()) runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(text)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
