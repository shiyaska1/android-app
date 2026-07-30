package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** One invoice covered by a multi-invoice receipt, and how much of the receipt applies to it. */
@Entity(tableName = "receipt_allocations")
data class ReceiptAllocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val billId: Long,
    val billNo: String,
    val amount: Double
)

@Dao
interface ReceiptAllocationDao {
    @Query("SELECT * FROM receipt_allocations WHERE receiptId = :id") suspend fun forReceipt(id: Long): List<ReceiptAllocation>
    @Insert suspend fun insertAll(list: List<ReceiptAllocation>)
    @Query("DELETE FROM receipt_allocations WHERE receiptId = :id") suspend fun deleteForReceipt(id: Long)
}

/** One purchase covered by a multi-purchase payment, and how much of the payment applies to it. */
@Entity(tableName = "expense_allocations")
data class ExpenseAllocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val purchaseId: Long,
    val purchaseNo: String,
    val amount: Double
)

@Dao
interface ExpenseAllocationDao {
    @Query("SELECT * FROM expense_allocations WHERE expenseId = :id") suspend fun forExpense(id: Long): List<ExpenseAllocation>
    @Insert suspend fun insertAll(list: List<ExpenseAllocation>)
    @Query("DELETE FROM expense_allocations WHERE expenseId = :id") suspend fun deleteForExpense(id: Long)
}
