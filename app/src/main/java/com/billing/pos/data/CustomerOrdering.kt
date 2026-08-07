package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One item in the online shop's catalog, as fetched from [AppPrefs.onlineCatalogUrl] and cached
 * on the customer's phone so browsing still works offline after the first fetch.
 */
@Entity(tableName = "shop_catalog_items")
data class ShopCatalogItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which shop this item belongs to (matches [AppPrefs.shopCode]) — items from every shop
     *  this device has ever connected to are kept side by side, so switching shops can show the
     *  cached list instantly instead of forcing a refetch. */
    val shop: String = "",
    /** The shop's own item id, as sent by the server — kept so a re-fetch can tell items apart. */
    val serverId: String,
    val name: String,
    val category: String = "",
    val price: Double,
    val unit: String = "",
    val imageUrl: String = "",
    val description: String = "",
    /** Optional link(s) to a photo/catalog for this item — see [Item.driveLink]; same
     *  comma-separated format, carried through as-is from the server's catalog JSON. */
    val driveLink: String = ""
)

@Dao
interface ShopCatalogDao {
    @Query("SELECT * FROM shop_catalog_items WHERE shop = :shop ORDER BY category COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    fun observeForShop(shop: String): Flow<List<ShopCatalogItem>>

    @Query("SELECT COUNT(*) FROM shop_catalog_items WHERE shop = :shop")
    suspend fun countForShop(shop: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ShopCatalogItem>)

    @Query("DELETE FROM shop_catalog_items WHERE shop = :shop")
    suspend fun deleteForShop(shop: String)

    /** Replaces just [shop]'s items atomically — every other shop's cached catalog is left alone,
     *  so switching back to them still shows their last-fetched data. */
    @Transaction
    suspend fun replaceForShop(shop: String, items: List<ShopCatalogItem>) {
        deleteForShop(shop)
        insertAll(items)
    }
}

/**
 * A customer order fetched from the server (shop owner side) — the app's own permanent copy,
 * since the server only ever holds orders briefly (see pos_online_catalog.php's do=orders, which
 * clears them once fetched). [itemsJson] is a packed `[{"name":"","qty":0,"price":0.0}, ...]`
 * array, same pack/unpack style as [SavedCalc.labels] elsewhere in this file's neighborhood.
 */
@Entity(tableName = "online_orders")
data class OnlineOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    /** The matched-or-created [Customer] this order was attributed to. */
    val customerId: Long,
    val customerName: String,
    val customerPhone: String,
    val itemsJson: String,
    val total: Double,
    val receivedAt: String,
    val fetchedAt: Long,
    /** PENDING / DELIVERED / CANCELLED — shop owner manages this locally. */
    val status: String = "PENDING",
    /** `https://maps.google.com/?q=lat,lng`, or blank if the customer didn't share one. */
    val location: String = "",
    /** Optional delivery address the customer typed in, separate from the map link. */
    val customerAddress: String = "",
    /** Free-text note from the customer (e.g. "no onions", or a prescription description). */
    val note: String = "",
    /** Superseded by [attachmentImages] (a shop-owner customer can now attach several photos,
     *  e.g. multiple pages of a prescription) — kept only so an old already-fetched order isn't
     *  silently blanked out by the migration; never written to by new fetches. */
    val attachmentImage: String = "",
    /** Premium-shop only: compressed base64 data URIs the customer attached (e.g. prescription
     *  photos), packed the same way [itemsJson] packs order lines. Empty for a non-premium shop
     *  or when the customer didn't attach anything. */
    val attachmentImages: String = "",
    /** "UPI" when the customer paid at order time (self-reported by their UPI app's own
     *  response — see [com.billing.pos.customer.OrderSubmit]); blank means Cash on
     *  delivery/unpaid. */
    val paymentStatus: String = ""
) {
    data class Line(val name: String, val qty: Int, val price: Double)

    val items: List<Line> get() = runCatching {
        val arr = org.json.JSONArray(itemsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Line(o.optString("name"), o.optInt("qty", 1), o.optDouble("price", 0.0))
        }
    }.getOrDefault(emptyList())

    val attachments: List<String> get() = runCatching {
        val arr = org.json.JSONArray(attachmentImages)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    companion object {
        fun packItems(items: List<Line>) = org.json.JSONArray().apply {
            items.forEach { line ->
                put(org.json.JSONObject().apply {
                    put("name", line.name); put("qty", line.qty); put("price", line.price)
                })
            }
        }.toString()

        fun packAttachments(dataUris: List<String>) = org.json.JSONArray(dataUris).toString()
    }
}

enum class OnlineOrderStatus(val label: String) {
    PENDING("Pending"), ACCEPTED("Accepted"), REJECTED("Rejected"),
    OUT_FOR_DELIVERY("Out for delivery"), DELIVERED("Delivered"), CANCELLED("Cancelled")
}

@Dao
interface OnlineOrderDao {
    @Query("SELECT * FROM online_orders ORDER BY fetchedAt DESC")
    fun observeAll(): Flow<List<OnlineOrder>>

    @Query("SELECT serverId FROM online_orders")
    suspend fun allServerIds(): List<String>

    @Insert
    suspend fun insert(order: OnlineOrder): Long

    @Query("UPDATE online_orders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /** Flips a still-pending order's payment status once the customer reports paying via UPI
     *  (see [com.billing.pos.customer.ShopMessagesFetch]) — a no-op if this order was already
     *  Accepted/converted (and so no longer in this table) or deleted; that's fine, the shop owner
     *  will have already seen the "paid" chat message either way. */
    @Query("UPDATE online_orders SET paymentStatus = :status WHERE serverId = :serverId")
    suspend fun updatePaymentStatus(serverId: String, status: String)

    @Delete
    suspend fun delete(order: OnlineOrder)
}

/**
 * Customer install: this device's own record of what it has ordered — so "Order History" has
 * something to show and "Re-order" has something to repeat from. Purely local; the server never
 * sends this back, it's written the moment [com.billing.pos.customer.OrderSubmit] succeeds.
 */
@Entity(tableName = "customer_order_history")
data class CustomerOrderHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemsJson: String,
    val total: Double,
    val placedAt: Long,
    val location: String = "",
    /** The server's id for this order (see pos_online_catalog.php's do=order response), so a
     *  status-change notification for this order can be matched back to it. Blank on an old row
     *  saved before the server started returning one. */
    val serverId: String = "",
    /** What the customer typed (and/or OCR'd) instead of — or alongside — picking catalog items. */
    val note: String = "",
    /** "UPI" when this order was paid at order time (self-reported); blank for Cash on delivery. */
    val paymentStatus: String = ""
) {
    /** [serverId] is the catalog item's id, so Re-order can find it again even if its name changed. */
    data class Line(val serverId: String, val name: String, val qty: Int, val price: Double)

    val items: List<Line> get() = runCatching {
        val arr = org.json.JSONArray(itemsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Line(o.optString("serverId"), o.optString("name"), o.optInt("qty", 1), o.optDouble("price", 0.0))
        }
    }.getOrDefault(emptyList())

    companion object {
        fun packItems(items: List<Line>) = org.json.JSONArray().apply {
            items.forEach { line ->
                put(org.json.JSONObject().apply {
                    put("serverId", line.serverId); put("name", line.name)
                    put("qty", line.qty); put("price", line.price)
                })
            }
        }.toString()
    }
}

@Dao
interface CustomerOrderHistoryDao {
    @Query("SELECT * FROM customer_order_history ORDER BY placedAt DESC")
    fun observeAll(): Flow<List<CustomerOrderHistory>>

    @Insert
    suspend fun insert(order: CustomerOrderHistory): Long

    /** Flips this device's own record of an order to paid, once a "Pay via UPI now" (from a
     *  shop's billed-amount notification) reports success — a no-op if this order isn't in
     *  history at all (e.g. history was cleared), which is fine, it's just local record-keeping. */
    @Query("UPDATE customer_order_history SET paymentStatus = 'UPI' WHERE serverId = :serverId")
    suspend fun markPaid(serverId: String)
}

/**
 * Customer install: a status update (or a plain message) the shop sent about one of this
 * customer's orders — see [com.billing.pos.customer.NotificationsFetch] (pulls from the server's
 * do=notifications, a short-lived queue, same read-once convention as orders/catalog) and
 * [com.billing.pos.ui.online.OnlineOrdersScreen] on the shop owner side for where these originate.
 */
@Entity(tableName = "customer_notifications")
data class CustomerNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The order this is about, matching [CustomerOrderHistory.serverId] — blank for a message
     *  that isn't tied to a specific order. */
    val orderId: String = "",
    /** One of [OnlineOrderStatus]'s names, or blank for a plain message with no status change. */
    val status: String = "",
    val message: String = "",
    /** Which shop this came from (matches [com.billing.pos.customer.ShopSwitch.Shop.shop]) — a
     *  customer can be connected to several shops, so this is what tells them apart in the list
     *  and lets a reply route back to the right one instead of whichever shop is currently
     *  active. Blank on rows from before this field existed. */
    val shop: String = "",
    val shopName: String = "",
    val receivedAt: Long,
    val read: Boolean = false,
    /** A bill amount the shop is asking to be paid (e.g. after quoting a note/prescription order
     *  that had no fixed price at order time) — see [com.billing.pos.customer.OrderStatusPush].
     *  0.0 (the default) means no amount attached, so no "Pay via UPI now" button shows. */
    val amount: Double = 0.0
)

@Dao
interface CustomerNotificationDao {
    @Query("SELECT * FROM customer_notifications ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<CustomerNotification>>

    @Query("SELECT COUNT(*) FROM customer_notifications WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Insert
    suspend fun insert(notification: CustomerNotification): Long

    @Query("UPDATE customer_notifications SET read = 1")
    suspend fun markAllRead()

    @Delete
    suspend fun delete(notification: CustomerNotification)

    @Query("DELETE FROM customer_notifications")
    suspend fun deleteAll()
}
