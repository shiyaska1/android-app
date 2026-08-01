package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A quick text note, optionally with a one-time or daily reminder. */
@Entity(tableName = "quick_notes")
data class QuickNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val reminderEnabled: Boolean = false,
    /** For one-time reminders: exact date+time. For daily: the time-of-day used. */
    val reminderAt: Long = 0,
    val reminderDaily: Boolean = false
)

@Dao
interface QuickNoteDao {
    @Query("SELECT * FROM quick_notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<QuickNote>>

    @Query("SELECT * FROM quick_notes WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): QuickNote?

    @Query("SELECT * FROM quick_notes WHERE reminderEnabled = 1")
    suspend fun allWithReminder(): List<QuickNote>

    @Insert
    suspend fun insert(note: QuickNote): Long

    @Update
    suspend fun update(note: QuickNote)

    @Query("DELETE FROM quick_notes WHERE id = :id")
    suspend fun delete(id: Long)
}

/** A photo attached to a quick note (camera or gallery). */
@Entity(tableName = "quick_note_attachments")
data class QuickNoteAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val path: String,
    val name: String = "",
    val mime: String = "image/jpeg"
)

@Dao
interface QuickNoteAttachmentDao {
    @Query("SELECT * FROM quick_note_attachments WHERE noteId = :noteId")
    suspend fun forNote(noteId: Long): List<QuickNoteAttachment>

    @Insert
    suspend fun insert(att: QuickNoteAttachment): Long

    @Delete
    suspend fun delete(att: QuickNoteAttachment)

    @Query("DELETE FROM quick_note_attachments WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long)
}
