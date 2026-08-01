package com.billing.pos.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class QuickNoteRepository(context: Context) {
    private val dao = AppDatabase.get(context).quickNoteDao()
    private val attachmentDao = AppDatabase.get(context).quickNoteAttachmentDao()

    val notes: Flow<List<QuickNote>> = dao.observeAll()

    suspend fun byId(id: Long): QuickNote? = dao.byId(id)
    suspend fun allWithReminder(): List<QuickNote> = dao.allWithReminder()

    suspend fun upsert(note: QuickNote): Long =
        if (note.id == 0L) dao.insert(note) else { dao.update(note); note.id }

    /** Deletes the note and its attachment rows + files. */
    suspend fun delete(id: Long) {
        attachmentDao.forNote(id).forEach { com.billing.pos.quicknote.QuickNoteAttachmentStore.delete(it) }
        attachmentDao.deleteForNote(id)
        dao.delete(id)
    }

    // ---- photo attachments ----
    suspend fun attachmentsFor(noteId: Long): List<QuickNoteAttachment> = attachmentDao.forNote(noteId)
    suspend fun addAttachment(att: QuickNoteAttachment): Long = attachmentDao.insert(att)
    suspend fun deleteAttachment(att: QuickNoteAttachment) {
        com.billing.pos.quicknote.QuickNoteAttachmentStore.delete(att)
        attachmentDao.delete(att)
    }
}
