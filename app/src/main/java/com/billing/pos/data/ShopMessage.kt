package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Shop owner's permanent local copy of the chat with one customer — both directions, so
 * "Messages" (see com.billing.pos.ui.messages) shows a real thread instead of just outgoing
 * status pushes. [direction] is "IN" (customer -> shop, see
 * com.billing.pos.customer.ShopMessagesFetch) or "OUT" (shop -> customer, see
 * com.billing.pos.customer.OrderStatusPush). The server only ever queues a message briefly
 * (pos_online_catalog.php's do=message / do=customerMessages) — this table is the permanent copy.
 */
@Entity(tableName = "shop_messages")
data class ShopMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerPhone: String,
    val customerName: String = "",
    /** The order this is about, if any — blank for a message not tied to a specific order. */
    val orderId: String = "",
    val direction: String, // "IN" or "OUT"
    val text: String,
    val sentAt: Long,
    /** Only meaningful for "IN" — an outgoing message is implicitly already read (the owner
     *  just sent it). Drives the unread red-dot on the Messages entry point / thread rows. */
    val read: Boolean = false
)

@Dao
interface ShopMessageDao {
    @Query("SELECT * FROM shop_messages ORDER BY sentAt DESC")
    fun observeAll(): Flow<List<ShopMessage>>

    @Query("SELECT * FROM shop_messages WHERE customerPhone = :phone ORDER BY sentAt ASC")
    fun observeForCustomer(phone: String): Flow<List<ShopMessage>>

    @Query("SELECT COUNT(*) FROM shop_messages WHERE direction = 'IN' AND read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Insert
    suspend fun insert(message: ShopMessage): Long

    @Query("UPDATE shop_messages SET read = 1 WHERE customerPhone = :phone AND direction = 'IN'")
    suspend fun markReadForCustomer(phone: String)
}
