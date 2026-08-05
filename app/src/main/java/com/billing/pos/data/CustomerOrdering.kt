package com.billing.pos.data

import androidx.room.Dao
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
    /** The shop's own item id, as sent by the server — kept so a re-fetch can tell items apart. */
    val serverId: String,
    val name: String,
    val category: String = "",
    val price: Double,
    val unit: String = "",
    val imageUrl: String = "",
    val description: String = ""
)

@Dao
interface ShopCatalogDao {
    @Query("SELECT * FROM shop_catalog_items ORDER BY category COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ShopCatalogItem>>

    @Query("SELECT * FROM shop_catalog_items")
    suspend fun all(): List<ShopCatalogItem>

    @Query("SELECT COUNT(*) FROM shop_catalog_items")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ShopCatalogItem>)

    @Query("DELETE FROM shop_catalog_items")
    suspend fun deleteAll()

    /** Replaces the whole catalog atomically, so a screen reading it mid-fetch never sees a
     *  half-empty table. */
    @Transaction
    suspend fun replaceAll(items: List<ShopCatalogItem>) {
        deleteAll()
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
    val status: String = "PENDING"
) {
    data class Line(val name: String, val qty: Int, val price: Double)

    val items: List<Line> get() = runCatching {
        val arr = org.json.JSONArray(itemsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Line(o.optString("name"), o.optInt("qty", 1), o.optDouble("price", 0.0))
        }
    }.getOrDefault(emptyList())

    companion object {
        fun packItems(items: List<Line>) = org.json.JSONArray().apply {
            items.forEach { line ->
                put(org.json.JSONObject().apply {
                    put("name", line.name); put("qty", line.qty); put("price", line.price)
                })
            }
        }.toString()
    }
}

enum class OnlineOrderStatus(val label: String) {
    PENDING("Pending"), DELIVERED("Delivered"), CANCELLED("Cancelled")
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
}
