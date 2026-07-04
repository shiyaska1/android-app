package com.billing.pos.data

import android.content.Context
import com.billing.pos.diary.AttachmentStore
import kotlinx.coroutines.flow.Flow

class DiaryRepository(context: Context) {
    private val dao = AppDatabase.get(context).diaryDao()

    fun search(query: String): Flow<List<DiaryEntry>> = dao.search(query)
    val allAttachments: Flow<List<DiaryAttachment>> = dao.observeAllAttachments()

    suspend fun byId(id: Long): DiaryEntry? = dao.byId(id)
    suspend fun attachmentsFor(entryId: Long): List<DiaryAttachment> = dao.attachmentsFor(entryId)

    suspend fun upsert(entry: DiaryEntry): Long =
        if (entry.id == 0L) dao.insert(entry) else { dao.update(entry); entry.id }

    suspend fun insertAttachment(attachment: DiaryAttachment): Long = dao.insertAttachment(attachment)

    suspend fun deleteAttachment(attachment: DiaryAttachment) {
        AttachmentStore.delete(attachment)
        dao.deleteAttachment(attachment)
    }

    /** Deletes the entry, its attachment rows, and their files. */
    suspend fun deleteEntry(entry: DiaryEntry) {
        dao.attachmentsFor(entry.id).forEach { AttachmentStore.delete(it) }
        dao.deleteAttachmentsFor(entry.id)
        dao.delete(entry)
    }
}
