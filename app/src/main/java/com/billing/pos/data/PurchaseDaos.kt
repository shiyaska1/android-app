package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers ORDER BY isDefault DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Supplier>>

    @Query("SELECT * FROM suppliers")
    suspend fun all(): List<Supplier>

    @Query("SELECT * FROM suppliers WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): Supplier?

    @Query("SELECT COUNT(*) FROM suppliers")
    suspend fun count(): Int

    @Insert
    suspend fun insert(supplier: Supplier): Long

    @Update
    suspend fun update(supplier: Supplier)

    @Delete
    suspend fun delete(supplier: Supplier)
}

@Dao
interface PurchaseDao {
    @Query("SELECT COUNT(*) FROM purchases WHERE source = ''")
    suspend fun localCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPurchase(purchase: Purchase): Long

    @Insert
    suspend fun insertLines(lines: List<PurchaseItem>)

    @Update
    suspend fun updateHeader(purchase: Purchase)

    @Query("DELETE FROM purchase_items WHERE purchaseId = :purchaseId")
    suspend fun deleteLines(purchaseId: Long)

    @Delete
    suspend fun deleteHeader(purchase: Purchase)

    @Transaction
    suspend fun savePurchase(purchase: Purchase, lines: List<PurchaseItem>): Long {
        val id = insertPurchase(purchase)
        insertLines(lines.map { it.copy(purchaseId = id) })
        return id
    }

    @Transaction
    suspend fun updatePurchase(purchase: Purchase, lines: List<PurchaseItem>) {
        updateHeader(purchase)
        deleteLines(purchase.id)
        insertLines(lines.map { it.copy(id = 0, purchaseId = purchase.id) })
    }

    @Transaction
    suspend fun deletePurchase(purchase: Purchase) {
        deleteLines(purchase.id)
        deleteHeader(purchase)
    }

    @Query("SELECT * FROM purchases ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<Purchase>>

    @Query("SELECT * FROM purchases WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Purchase?

    @Query("SELECT COUNT(*) FROM purchases WHERE supplierId = :supplierId OR supplierName = :name")
    suspend fun countForSupplier(supplierId: Long, name: String): Int

    @Query("SELECT * FROM purchase_items WHERE purchaseId = :purchaseId")
    suspend fun linesFor(purchaseId: Long): List<PurchaseItem>
}
