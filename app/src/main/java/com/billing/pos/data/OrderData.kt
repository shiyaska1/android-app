package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A customer order — an exact copy of what was billed, kept separately so it never touches
 * stock. It exists to record what a customer asked for, with an optional remark, attachments
 * and a captured map location.
 */
@Entity(tableName = "cust_orders")
data class CustOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val remark: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val grandTotal: Double,
    /** [License.deviceId] of the phone the order was created on, so reports can attribute it to a salesman. */
    val deviceId: String = "",
    /** "UPI" when an online order was paid at order time (see [com.billing.pos.data.OnlineOrder]);
     *  blank for a plain order or Cash on delivery. Only meaningful when [isOnlineOrder] is true —
     *  carried through to the sale bill created from this order, see
     *  [com.billing.pos.ui.billing.OrderToBillLink]. */
    val paymentStatus: String = "",
    /** True only for an order created by accepting an online order (see
     *  [com.billing.pos.ui.online.OnlineOrdersViewModel]) — lets "Convert to Sale" tell those
     *  apart from a plain order entered directly here, so [paymentStatus] only auto-fills the
     *  bill's payment method for orders it's actually meaningful for. */
    val isOnlineOrder: Boolean = false
)

/** Delivery status of one order line. Stored as [name] in the DB; [PENDING] is the default for anything not yet touched. */
enum class OrderStatus(val label: String) {
    PENDING("Pending"), DELIVERED("Delivered"), PARTIAL("Partial Delivered"), CANCELLED("Cancel");
    companion object {
        fun from(s: String): OrderStatus = runCatching { valueOf(s) }.getOrDefault(PENDING)
        /** All-same -> that status; any mix (e.g. some lines delivered, some not) -> Partial Delivered. */
        fun rollup(statuses: Collection<OrderStatus>): OrderStatus {
            val distinct = statuses.toSet()
            return if (distinct.size == 1) distinct.first() else PARTIAL
        }
    }
}

@Entity(tableName = "cust_order_items")
data class CustOrderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val itemId: Long = 0,
    val name: String,
    val qty: Double,
    val price: Double,
    val lineTotal: Double,
    val unit: String = "",
    val status: String = "PENDING"
)

@Entity(tableName = "cust_order_attachments")
data class CustOrderAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val path: String,
    val name: String,
    val mime: String
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

data class CustOrderWithItems(val order: CustOrder, val items: List<CustOrderItem>)

@Dao
interface CustOrderDao {
    @Query("SELECT COUNT(*) FROM cust_orders") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(o: CustOrder): Long
    @Insert suspend fun insertLines(lines: List<CustOrderItem>)
    @Update suspend fun updateHeader(o: CustOrder)
    @Query("DELETE FROM cust_order_items WHERE orderId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(o: CustOrder)

    @Transaction
    suspend fun save(o: CustOrder, lines: List<CustOrderItem>): Long {
        val id = insertHeader(o); insertLines(lines.map { it.copy(id = 0, orderId = id) }); return id
    }
    @Transaction
    suspend fun update(o: CustOrder, lines: List<CustOrderItem>) {
        updateHeader(o); deleteLines(o.id); insertLines(lines.map { it.copy(id = 0, orderId = o.id) })
    }
    @Transaction
    suspend fun delete(o: CustOrder) { deleteLines(o.id); deleteHeader(o) }

    @Query("SELECT * FROM cust_orders ORDER BY dateMillis DESC") fun observeAll(): Flow<List<CustOrder>>
    @Query("SELECT * FROM cust_orders") suspend fun all(): List<CustOrder>
    @Query("SELECT * FROM cust_orders WHERE id = :id") suspend fun byId(id: Long): CustOrder?
    @Query("SELECT * FROM cust_orders WHERE deviceId = :deviceId AND orderNo = :orderNo LIMIT 1")
    suspend fun byDeviceAndNo(deviceId: String, orderNo: String): CustOrder?

    /** Fallback match by number alone, for records with no deviceId (older data, imports) —
     *  without this, merge/sync re-inserts them as new duplicates every cycle. */
    @Query("SELECT * FROM cust_orders WHERE orderNo = :orderNo LIMIT 1")
    suspend fun byNo(orderNo: String): CustOrder?
    @Query("SELECT * FROM cust_order_items WHERE orderId = :id ORDER BY id ASC") suspend fun linesFor(id: Long): List<CustOrderItem>
    @Query("SELECT * FROM cust_order_items") suspend fun allLines(): List<CustOrderItem>
    @Query("SELECT * FROM cust_order_items") fun observeAllLines(): Flow<List<CustOrderItem>>

    @Insert suspend fun insertAttachment(a: CustOrderAttachment): Long
    @Query("SELECT * FROM cust_order_attachments WHERE orderId = :id") suspend fun attachmentsFor(id: Long): List<CustOrderAttachment>
    @Query("SELECT * FROM cust_order_attachments") suspend fun allAttachments(): List<CustOrderAttachment>
    @Query("DELETE FROM cust_order_attachments") suspend fun deleteAllAttachments()
    @Query("DELETE FROM cust_order_attachments WHERE orderId = :id") suspend fun deleteAttachments(id: Long)

    @Query("UPDATE cust_order_items SET status = :status WHERE id = :id") suspend fun updateItemStatus(id: Long, status: String)
    @Query("UPDATE cust_order_items SET status = :status WHERE id IN (:ids)") suspend fun updateItemStatusBulk(ids: List<Long>, status: String)
}

/** Files attached to a customer order, copied in so they survive the source going away. */
object OrderAttachmentStore {
    fun dir(context: android.content.Context): java.io.File =
        java.io.File(context.filesDir, "order_attachments").apply { mkdirs() }

    fun copyIn(context: android.content.Context, uri: android.net.Uri): CustOrderAttachment? = runCatching {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = displayName(context, uri) ?: ("attachment_" + System.currentTimeMillis())
        val safe = name.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = java.io.File(dir(context), "att_" + System.nanoTime() + "_" + safe)
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        CustOrderAttachment(orderId = 0, path = target.absolutePath, name = name, mime = mime)
    }.getOrNull()

    private fun displayName(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
