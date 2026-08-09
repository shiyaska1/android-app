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
    val labels: String = "",
    /** The quick-due Bill this calculation created for its customer (addQuickInvoice), if any —
     *  0 = none, or the customer was the walk-in placeholder. Lets deleting this calculation
     *  clean up the due it created instead of leaving it stranded on the customer, and lets
     *  re-saving keep that same due's amount in sync instead of creating a fresh one every time. */
    val linkedBillId: Long = 0,
    /** The customer-attachment PDF this calculation filed (same content as [linkedBillId]'s
     *  amount), if any. Re-saving replaces it with a fresh one instead of leaving a stale copy
     *  behind, same reasoning as [linkedBillId]. */
    val linkedAttachmentId: Long = 0
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

    @Query("SELECT * FROM saved_calcs WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): SavedCalc?

    @Insert suspend fun insert(c: SavedCalc): Long

    @Update suspend fun update(c: SavedCalc)

    @Query("DELETE FROM saved_calcs WHERE id = :id")
    suspend fun delete(id: Long)
}
