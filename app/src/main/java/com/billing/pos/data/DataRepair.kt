package com.billing.pos.data

import android.content.Context

/**
 * Post-hoc cleanup for duplicates left behind by repeated cloud-sync merge cycles. Every entity
 * except diary entries is already matched by name/number on the way in during a merge (see
 * FullBackup.mergeInto), so this is a safety net for whatever slipped through before that — plus
 * diary entries, which genuinely aren't deduped on merge (a note pulled and merged on every sync
 * cycle keeps re-inserting itself), and their own attachments/blocks as a result.
 */
object DataRepair {

    data class RepairResult(val recordsMerged: Int, val attachmentsMerged: Int)

    suspend fun repair(context: Context): RepairResult {
        val records = mergeDuplicateDiaryEntries(context)
        val attachments = mergeDuplicateAttachments(context)
        return RepairResult(records, attachments)
    }

    /** Groups diary entries that are the same note re-inserted by repeated merges (same title,
     *  remarks, createdAt and customer), keeps the most recently updated one per group, moves
     *  every duplicate's blocks/attachments onto it first (so nothing typed or attached is lost
     *  even if a copy isn't byte-identical), then deletes the duplicate entries. */
    private suspend fun mergeDuplicateDiaryEntries(context: Context): Int {
        val dao = AppDatabase.get(context).diaryDao()
        val groups = dao.allEntries().groupBy {
            arrayOf(it.title.trim().lowercase(), it.remarks.trim().lowercase(), it.createdAt, it.customerId).toList()
        }
        var removed = 0
        groups.values.filter { it.size > 1 }.forEach { dupes ->
            val keeper = dupes.maxByOrNull { it.updatedAt } ?: return@forEach
            dupes.filter { it.id != keeper.id }.forEach { dup ->
                dao.blocksFor(dup.id).forEach { b -> dao.insertBlock(b.copy(id = 0, entryId = keeper.id)) }
                dao.attachmentsFor(dup.id).forEach { a -> dao.insertAttachment(a.copy(id = 0, entryId = keeper.id)) }
                dao.deleteBlocksFor(dup.id)
                dao.deleteAttachmentsFor(dup.id)
                dao.delete(dup)
                removed++
            }
        }
        return removed
    }

    /** Removes duplicate attachments (same parent record + same file name) across every
     *  attachment table in the app, keeping one copy of each. */
    private suspend fun mergeDuplicateAttachments(context: Context): Int {
        val db = AppDatabase.get(context)
        var removed = 0

        fun <T> countDupes(all: List<T>, parentId: (T) -> Long, name: (T) -> String): List<T> {
            val toDelete = ArrayList<T>()
            all.groupBy(parentId).values.forEach { group ->
                val seen = HashSet<String>()
                group.forEach { a -> if (!seen.add(name(a))) toDelete.add(a) }
            }
            return toDelete
        }

        db.diaryDao().let { dao ->
            countDupes(dao.allAttachments(), { it.entryId }, { it.name }).forEach { dao.deleteAttachment(it); removed++ }
        }
        db.itemAttachmentDao().let { dao ->
            countDupes(dao.all(), { it.itemId }, { it.name }).forEach { dao.delete(it); removed++ }
        }
        db.billAttachmentDao().let { dao ->
            countDupes(dao.all(), { it.billId }, { it.name }).forEach { dao.delete(it); removed++ }
        }
        db.expenseAttachmentDao().let { dao ->
            countDupes(dao.all(), { it.expenseId }, { it.name }).forEach { dao.deleteById(it.id); removed++ }
        }
        db.customerAttachmentDao().let { dao ->
            countDupes(dao.all(), { it.customerId }, { it.name }).forEach { dao.deleteById(it.id); removed++ }
        }
        db.receiptAttachmentDao().let { dao ->
            countDupes(dao.all(), { it.receiptId }, { it.name }).forEach { dao.deleteById(it.id); removed++ }
        }
        db.quickNoteAttachmentDao().let { dao ->
            countDupes(dao.all(), { it.noteId }, { it.name }).forEach { dao.delete(it); removed++ }
        }
        db.purchaseAttachmentDao().let { dao ->
            countDupes(dao.all(), { it.purchaseId }, { it.name }).forEach { dao.delete(it); removed++ }
        }

        return removed
    }
}
