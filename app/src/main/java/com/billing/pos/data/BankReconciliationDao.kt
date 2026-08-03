package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BankReconciliationDao {
    @Query("SELECT * FROM bank_reconciliations WHERE headId = :headId")
    fun observeForHead(headId: Long): Flow<List<BankReconciliation>>

    @Insert
    suspend fun insert(r: BankReconciliation): Long

    @Query(
        "DELETE FROM bank_reconciliations WHERE headId = :headId AND vch = :vch " +
            "AND dateMillis = :dateMillis AND amount = :amount"
    )
    suspend fun unmark(headId: Long, vch: String, dateMillis: Long, amount: Double)
}
