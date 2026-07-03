package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY isDefault DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Customer>>

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun count(): Int

    @Insert
    suspend fun insert(customer: Customer): Long
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Item>>

    @Insert
    suspend fun insert(item: Item): Long
}

@Dao
interface BillDao {
    @Query("SELECT COUNT(*) FROM bills")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: Bill): Long

    @Insert
    suspend fun insertLines(lines: List<BillItem>)

    @Transaction
    suspend fun saveBill(bill: Bill, lines: List<BillItem>): Long {
        val billId = insertBill(bill)
        insertLines(lines.map { it.copy(billId = billId) })
        return billId
    }

    @Query("SELECT * FROM bills WHERE dateMillis BETWEEN :from AND :to ORDER BY dateMillis DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<Bill>>

    @Query("SELECT * FROM bill_items WHERE billId = :billId")
    suspend fun linesFor(billId: Long): List<BillItem>
}
