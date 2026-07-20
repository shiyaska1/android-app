package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query(
        "SELECT * FROM diary_entries " +
            "WHERE title LIKE '%' || :q || '%' OR remarks LIKE '%' || :q || '%' " +
            "ORDER BY updatedAt DESC"
    )
    fun search(q: String): Flow<List<DiaryEntry>>

    /**
     * List order: entries with a reminder/notification first, then newest first.
     * [from]..[to] bounds the date; use [searchAll] when the date filter is bypassed.
     */
    @Query(
        "SELECT * FROM diary_entries " +
            "WHERE (title LIKE '%' || :q || '%' OR remarks LIKE '%' || :q || '%') " +
            "AND updatedAt BETWEEN :from AND :to " +
            "ORDER BY reminderEnabled DESC, updatedAt DESC"
    )
    fun searchBetween(q: String, from: Long, to: Long): Flow<List<DiaryEntry>>

    @Query(
        "SELECT * FROM diary_entries " +
            "WHERE title LIKE '%' || :q || '%' OR remarks LIKE '%' || :q || '%' " +
            "ORDER BY reminderEnabled DESC, updatedAt DESC"
    )
    fun searchAll(q: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): DiaryEntry?

    @Query("SELECT * FROM diary_entries WHERE reminderEnabled = 1")
    suspend fun allWithReminder(): List<DiaryEntry>

    @Query("SELECT * FROM diary_entries")
    suspend fun allEntries(): List<DiaryEntry>

    @Query("SELECT * FROM diary_attachments")
    suspend fun allAttachments(): List<DiaryAttachment>

    @Insert
    suspend fun insert(entry: DiaryEntry): Long

    @Update
    suspend fun update(entry: DiaryEntry)

    @Delete
    suspend fun delete(entry: DiaryEntry)

    @Query("SELECT * FROM diary_attachments ORDER BY id ASC")
    fun observeAllAttachments(): Flow<List<DiaryAttachment>>

    @Query("SELECT * FROM diary_attachments WHERE entryId = :entryId")
    suspend fun attachmentsFor(entryId: Long): List<DiaryAttachment>

    @Insert
    suspend fun insertAttachment(attachment: DiaryAttachment): Long

    @Delete
    suspend fun deleteAttachment(attachment: DiaryAttachment)

    @Query("DELETE FROM diary_attachments WHERE entryId = :entryId")
    suspend fun deleteAttachmentsFor(entryId: Long)

    // ---- body blocks (ordered text / image / voice / …) ----
    @Query("SELECT * FROM diary_blocks WHERE entryId = :entryId ORDER BY position ASC")
    suspend fun blocksFor(entryId: Long): List<DiaryBlock>

    @Query("SELECT * FROM diary_blocks")
    suspend fun allBlocks(): List<DiaryBlock>

    @Query("SELECT * FROM diary_blocks")
    fun observeAllBlocks(): Flow<List<DiaryBlock>>

    @Insert
    suspend fun insertBlock(block: DiaryBlock): Long

    @Query("DELETE FROM diary_blocks WHERE entryId = :entryId")
    suspend fun deleteBlocksFor(entryId: Long)
}
