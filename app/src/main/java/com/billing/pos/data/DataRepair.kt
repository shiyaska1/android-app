package com.billing.pos.data

import android.content.Context

/**
 * Post-hoc cleanup for duplicates left behind by repeated cloud-sync merge cycles or a repeated
 * attachments-zip restore — both used to only match an incoming record against what already
 * existed in the database *before* that run started, so (a) records with no deviceId (older
 * data, imports) skipped the dedup check entirely and just kept re-inserting, and (b) two
 * identical entries within the *same* incoming batch weren't caught either. Both root causes are
 * now fixed in FullBackup itself; this is the cleanup for duplicates that already exist from
 * before that fix, run on demand from the Dashboard's repair button.
 */
object DataRepair {

    data class RepairResult(val recordsMerged: Int, val attachmentsMerged: Int)

    suspend fun repair(context: Context): RepairResult {
        val records = mergeDuplicateDiaryEntries(context) + mergeDuplicateDocuments(context) + mergeDuplicateSavedCalcs(context)
        val attachments = mergeDuplicateAttachments(context)
        return RepairResult(records, attachments)
    }

    /** Saved calculator tapes have no number field to match on, so a duplicate is identified by
     *  content instead: same date+time, amounts, total, title, customer and narration. */
    private suspend fun mergeDuplicateSavedCalcs(context: Context): Int {
        val dao = AppDatabase.get(context).savedCalcDao()
        fun key(c: SavedCalc) = listOf(c.dateMillis, c.amounts, c.total, c.title, c.customerId, c.customerName, c.narration)
        var removed = 0
        dao.all().groupBy(::key).values.filter { it.size > 1 }.forEach { group ->
            group.drop(1).forEach { dao.delete(it.id); removed++ }
        }
        return removed
    }

    /** Removes duplicate bills/purchases/receipts/expenses/quotations/estimates/orders — same
     *  document number appearing more than once — keeping the earliest (lowest id) copy. Receipts
     *  and expenses are deleted at the DAO level rather than through Repository.deleteReceipt/
     *  deleteExpense: those reduce the linked bill/purchase's paid amount, which is correct for a
     *  real receipt/payment the user deletes, but wrong here — a duplicate row from the merge bug
     *  never contributed to that paid amount in the first place, so reducing it again would make a
     *  fully-paid bill look partially unpaid. */
    private suspend fun mergeDuplicateDocuments(context: Context): Int {
        val db = AppDatabase.get(context)
        val repo = Repository(context)
        var removed = 0

        fun <T> dupesToDrop(all: List<T>, no: (T) -> String, id: (T) -> Long): List<T> {
            val drop = ArrayList<T>()
            all.filter { no(it).isNotBlank() }.groupBy(no).values.filter { it.size > 1 }.forEach { group ->
                val keepId = group.minOf(id)
                group.forEach { if (id(it) != keepId) drop.add(it) }
            }
            return drop
        }

        dupesToDrop(db.billDao().all(), { it.billNo }, { it.id }).forEach { repo.deleteBill(it); removed++ }
        dupesToDrop(db.purchaseDao().all(), { it.purchaseNo }, { it.id }).forEach { repo.deletePurchase(it); removed++ }
        dupesToDrop(db.quotationDao().all(), { it.quotationNo }, { it.id }).forEach { repo.deleteQuotation(it); removed++ }
        dupesToDrop(db.estimateDao().all(), { it.estimateNo }, { it.id }).forEach { repo.deleteEstimate(it); removed++ }
        dupesToDrop(db.custOrderDao().all(), { it.orderNo }, { it.id }).forEach { repo.deleteOrder(it); removed++ }
        dupesToDrop(db.receiptDao().all(), { it.receiptNo }, { it.id }).forEach { r ->
            db.receiptAttachmentDao().let { dao -> dao.forReceipt(r.id).forEach { ReceiptAttachmentStore.delete(it) }; dao.deleteForReceipt(r.id) }
            db.receiptDao().delete(r)
            removed++
        }
        dupesToDrop(db.expenseDao().all(), { it.voucherNo }, { it.id }).forEach { ex ->
            db.expenseAttachmentDao().let { dao -> dao.forExpense(ex.id).forEach { com.billing.pos.expenses.ExpenseAttachmentStore.delete(it) }; dao.deleteForExpense(ex.id) }
            db.expenseDao().delete(ex)
            removed++
        }

        return removed
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
