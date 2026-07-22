package com.billing.pos.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.io.File

/** A photo or document filed against a customer — an ID copy, an agreement, a licence. */
@Entity(tableName = "customer_attachments")
data class CustomerAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val path: String,
    val name: String,
    val mime: String
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

@Dao
interface CustomerAttachmentDao {
    @Query("SELECT * FROM customer_attachments WHERE customerId = :customerId ORDER BY id ASC")
    suspend fun forCustomer(customerId: Long): List<CustomerAttachment>

    @Query("SELECT * FROM customer_attachments") suspend fun all(): List<CustomerAttachment>

    @Insert suspend fun insert(a: CustomerAttachment): Long

    @Query("DELETE FROM customer_attachments WHERE customerId = :customerId")
    suspend fun deleteForCustomer(customerId: Long)
}

/** Files attached to customers are copied in here, so they survive the original going away. */
object CustomerAttachmentStore {

    fun dir(context: Context): File =
        File(context.filesDir, "customer_attachments").apply { mkdirs() }

    /** Copies a picked file in and returns an unsaved row (customerId is filled in on save). */
    fun copyIn(context: Context, uri: Uri): CustomerAttachment? = runCatching {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = displayName(context, uri) ?: "attachment_${System.currentTimeMillis()}"
        val safe = name.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir(context), "att_${System.nanoTime()}_$safe")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        CustomerAttachment(customerId = 0, path = target.absolutePath, name = name, mime = mime)
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
