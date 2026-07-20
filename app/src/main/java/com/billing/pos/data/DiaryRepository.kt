package com.billing.pos.data

import android.content.Context
import com.billing.pos.diary.AttachmentStore
import kotlinx.coroutines.flow.Flow

class DiaryRepository(context: Context) {
    private val dao = AppDatabase.get(context).diaryDao()

    fun search(query: String): Flow<List<DiaryEntry>> = dao.search(query)
    fun searchBetween(query: String, from: Long, to: Long): Flow<List<DiaryEntry>> =
        dao.searchBetween(query, from, to)
    fun searchAll(query: String): Flow<List<DiaryEntry>> = dao.searchAll(query)
    val allAttachments: Flow<List<DiaryAttachment>> = dao.observeAllAttachments()
    val allBlocks: Flow<List<DiaryBlock>> = dao.observeAllBlocks()

    suspend fun byId(id: Long): DiaryEntry? = dao.byId(id)
    suspend fun attachmentsFor(entryId: Long): List<DiaryAttachment> = dao.attachmentsFor(entryId)

    suspend fun upsert(entry: DiaryEntry): Long =
        if (entry.id == 0L) dao.insert(entry) else { dao.update(entry); entry.id }

    suspend fun insertAttachment(attachment: DiaryAttachment): Long = dao.insertAttachment(attachment)

    suspend fun deleteAttachment(attachment: DiaryAttachment) {
        AttachmentStore.delete(attachment)
        dao.deleteAttachment(attachment)
    }

    // ---- body blocks ----
    suspend fun blocksFor(entryId: Long): List<DiaryBlock> = dao.blocksFor(entryId)

    /** Replaces all blocks for an entry with the given ordered list (positions reassigned). */
    suspend fun replaceBlocks(entryId: Long, blocks: List<DiaryBlock>) {
        dao.deleteBlocksFor(entryId)
        blocks.forEachIndexed { index, b ->
            dao.insertBlock(b.copy(id = 0, entryId = entryId, position = index))
        }
    }

    /** Deletes the entry, its attachment + block rows, and their files. */
    suspend fun deleteEntry(entry: DiaryEntry) {
        dao.attachmentsFor(entry.id).forEach { AttachmentStore.delete(it) }
        dao.deleteAttachmentsFor(entry.id)
        dao.blocksFor(entry.id).forEach { b -> if (b.path.isNotBlank()) runCatching { java.io.File(b.path).delete() } }
        dao.deleteBlocksFor(entry.id)
        dao.delete(entry)
    }
}
