package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChequeDao {
    @Query("SELECT * FROM cheque_entries ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<ChequeEntry>>

    @Insert
    suspend fun insert(cheque: ChequeEntry): Long

    @Update
    suspend fun update(cheque: ChequeEntry)

    @Delete
    suspend fun delete(cheque: ChequeEntry)
}
