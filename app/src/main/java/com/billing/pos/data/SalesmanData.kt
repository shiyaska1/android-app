package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Maps a phone's [License.deviceId] to a human salesman name, so reports can show who took an
 * order without needing a login/user account per salesman. Configured in Settings.
 */
@Entity(tableName = "salesman_map")
data class SalesmanMap(
    @PrimaryKey val deviceId: String,
    val name: String
)

@Dao
interface SalesmanMapDao {
    @Query("SELECT * FROM salesman_map ORDER BY name ASC") fun observeAll(): Flow<List<SalesmanMap>>
    @Query("SELECT * FROM salesman_map ORDER BY name ASC") suspend fun all(): List<SalesmanMap>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: SalesmanMap)
    @Query("DELETE FROM salesman_map WHERE deviceId = :deviceId") suspend fun delete(deviceId: String)
}
