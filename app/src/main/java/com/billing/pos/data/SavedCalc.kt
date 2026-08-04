package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A saved calculator tape.
 *
 * The amounts are kept as a comma-separated list rather than a second table: a tape is
 * only ever read and written whole, and this keeps it trivially portable in a backup.
 * Minus entries are stored negative, exactly as they are on screen.
 *
 * [labels] is an optional per-entry name (e.g. "Fuel", "Labour"), index-aligned with
 * [amountList]. Packed as JSON rather than comma-joined like the amounts, since free-typed
 * label text can itself contain commas. Entries saved with label mode off simply have a
 * blank label at their index.
 */
@Entity(tableName = "saved_calcs")
data class SavedCalc(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMillis: Long,
    val amounts: String,
    val total: Double,
    val title: String = "",
    val customerId: Long = 0,
    /** Kept by name as well, so a tape still reads correctly if the customer is renamed. */
    val customerName: String = DEFAULT_CUSTOMER,
    val narration: String = "",
    val labels: String = ""
) {
    val amountList: List<Double>
        get() = amounts.split(',').mapNotNull { it.trim().toDoubleOrNull() }

    val labelList: List<String>
        get() = runCatching {
            val arr = org.json.JSONArray(labels)
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_CUSTOMER = "Cash Customer"
        fun pack(values: List<Double>) = values.joinToString(",")
        fun packLabels(values: List<String>) = org.json.JSONArray(values).toString()
    }
}

@Dao
interface SavedCalcDao {
    @Query("SELECT * FROM saved_calcs ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<SavedCalc>>

    @Query("SELECT * FROM saved_calcs ORDER BY dateMillis DESC")
    suspend fun all(): List<SavedCalc>

    @Insert suspend fun insert(c: SavedCalc): Long

    @Update suspend fun update(c: SavedCalc)

    @Query("DELETE FROM saved_calcs WHERE id = :id")
    suspend fun delete(id: Long)
}
