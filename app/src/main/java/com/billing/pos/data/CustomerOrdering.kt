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
