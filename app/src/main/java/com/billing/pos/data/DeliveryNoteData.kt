package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Delivery Note (DN) — records goods physically handed over to a customer, independent of when
 * the tax invoice is raised. Delivering DECREASES stock, the mirror of a Material Receipt Note.
 */
@Entity(tableName = "delivery_notes")
data class DeliveryNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deliveryNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val remarks: String = ""
)

@Entity(tableName = "delivery_note_items")
data class DeliveryNoteItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deliveryId: Long,
    val itemId: Long = 0,
    val name: String,
    /** Quantity delivered, in the item's PRIMARY unit (for stock math). */
    val qty: Double,
    val price: Double,
    val taxPercent: Double = 0.0,
    val lineTotal: Double = 0.0,
    val batchNo: String = "",
    val unit: String = ""
)

data class DeliveryNoteWithItems(val note: DeliveryNote, val lines: List<DeliveryNoteItem>)

@Dao
interface DeliveryNoteDao {
    @Query("SELECT COUNT(*) FROM delivery_notes") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(d: DeliveryNote): Long
    @Insert suspend fun insertLines(lines: List<DeliveryNoteItem>)
    @Update suspend fun updateHeader(d: DeliveryNote)
    @Query("DELETE FROM delivery_note_items WHERE deliveryId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(d: DeliveryNote)

    @Transaction
    suspend fun save(d: DeliveryNote, lines: List<DeliveryNoteItem>): Long {
        val id = insertHeader(d); insertLines(lines.map { it.copy(id = 0, deliveryId = id) }); return id
    }
    @Transaction
    suspend fun update(d: DeliveryNote, lines: List<DeliveryNoteItem>) {
        updateHeader(d); deleteLines(d.id); insertLines(lines.map { it.copy(id = 0, deliveryId = d.id) })
    }
    @Transaction
    suspend fun delete(d: DeliveryNote) { deleteLines(d.id); deleteHeader(d) }

    @Query("SELECT * FROM delivery_notes ORDER BY dateMillis DESC") fun observeAll(): Flow<List<DeliveryNote>>
    @Query("SELECT * FROM delivery_notes WHERE id = :id LIMIT 1") suspend fun byId(id: Long): DeliveryNote?
    @Query("SELECT * FROM delivery_note_items WHERE deliveryId = :id") suspend fun linesFor(id: Long): List<DeliveryNoteItem>

    /** Total delivered quantity per item name — a stock-OUT source. */
    @Query("SELECT name AS name, SUM(qty) AS qty FROM delivery_note_items GROUP BY name COLLATE NOCASE")
    fun observeDeliveredByItem(): Flow<List<NameQty>>
}
