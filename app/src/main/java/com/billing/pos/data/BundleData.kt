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
 * A named combo of several items sold as one line — its own name/unit/price, but made up of
 * many real items in given quantities. Picking a bundle in Sales/Quotation expands it into its
 * real component item lines (each keeping its own item's price), so stock, tax and every
 * existing report work unchanged — the bundle itself carries no stock of its own.
 */
@Entity(tableName = "item_bundles")
data class ItemBundle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val unit: String = "",
    val price: Double = 0.0,
    val remarks: String = ""
)

@Entity(tableName = "item_bundle_components")
data class ItemBundleComponent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bundleId: Long,
    val itemId: Long = 0,
    val name: String,
    /** Quantity of this item in ONE bundle unit. */
    val qty: Double,
    val unit: String = ""
)

@Dao
interface ItemBundleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(b: ItemBundle): Long
    @Query("SELECT * FROM item_bundles WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun byName(name: String): ItemBundle?
    @Insert suspend fun insertComponents(lines: List<ItemBundleComponent>)
    @Update suspend fun updateHeader(b: ItemBundle)
    @Query("DELETE FROM item_bundle_components WHERE bundleId = :id") suspend fun deleteComponents(id: Long)
    @Delete suspend fun deleteHeader(b: ItemBundle)

    @Transaction
    suspend fun save(b: ItemBundle, components: List<ItemBundleComponent>): Long {
        val id = insertHeader(b); insertComponents(components.map { it.copy(id = 0, bundleId = id) }); return id
    }
    @Transaction
    suspend fun update(b: ItemBundle, components: List<ItemBundleComponent>) {
        updateHeader(b); deleteComponents(b.id); insertComponents(components.map { it.copy(id = 0, bundleId = b.id) })
    }
    @Transaction
    suspend fun delete(b: ItemBundle) { deleteComponents(b.id); deleteHeader(b) }

    @Query("SELECT * FROM item_bundles ORDER BY name ASC") fun observeAll(): Flow<List<ItemBundle>>
    @Query("SELECT * FROM item_bundles") suspend fun all(): List<ItemBundle>
    @Query("SELECT * FROM item_bundles WHERE id = :id LIMIT 1") suspend fun byId(id: Long): ItemBundle?
    @Query("SELECT * FROM item_bundle_components WHERE bundleId = :id") suspend fun componentsFor(id: Long): List<ItemBundleComponent>
    @Query("SELECT * FROM item_bundle_components") suspend fun allComponents(): List<ItemBundleComponent>
}
