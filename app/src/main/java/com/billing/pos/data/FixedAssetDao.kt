package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FixedAssetDao {
    @Query("SELECT * FROM fixed_assets ORDER BY purchaseDate DESC")
    fun observeAll(): Flow<List<FixedAsset>>

    @Query("SELECT * FROM fixed_assets")
    suspend fun all(): List<FixedAsset>

    @Insert
    suspend fun insert(asset: FixedAsset): Long

    @Update
    suspend fun update(asset: FixedAsset)

    @Delete
    suspend fun delete(asset: FixedAsset)
}
