package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CostCenterDao {
    @Query("SELECT * FROM cost_centers ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CostCenter>>

    @Query("SELECT * FROM cost_centers")
    suspend fun all(): List<CostCenter>

    @Insert
    suspend fun insert(costCenter: CostCenter): Long

    @Update
    suspend fun update(costCenter: CostCenter)

    @Delete
    suspend fun delete(costCenter: CostCenter)
}
