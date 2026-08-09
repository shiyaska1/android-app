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
        val records = mergeDuplicateDiaryEntries(context) + mergeDuplicateDocuments(context) +
            mergeDuplicateAuxDocuments(context) + mergeDuplicateSavedCalcs(context)
        val attachments = mergeDuplicateAttachments(context) + mergeDuplicateDiaryBlocks(context)
        return RepairResult(records, attachments)
    }

    /** Removes duplicate blocks (same type/text/path/name) *within* a single diary entry —
     *  content sitting inside one entry that's already duplicated, regardless of how it got
     *  there (an earlier run of this same repair before it deduped on the way in is one way).
     *  Separate from [mergeDuplicateDiaryEntries], which only dedupes across entries. */
    private suspend fun mergeDuplicateDiaryBlocks(context: Context): Int {
        val dao = AppDatabase.get(context).diaryDao()
        var removed = 0
        dao.allEntries().forEach { entry ->
            val seen = HashSet<List<Any?>>()
            dao.blocksFor(entry.id).forEach { b ->
                val key = listOf(b.type, b.text, b.path, b.name)
                if (!seen.add(key)) { dao.deleteBlock(b.id); removed++ }
            }
        }
        return removed
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

    /** Same "same document number appears more than once" cleanup as [mergeDuplicateDocuments],
     *  for the rest of the vouchers that share the same bug: sales/purchase returns, purchase
     *  orders (LPO), purchase quotations, material receipts/out, hire invoices/returns, lab bills
     *  and production runs — plus the few masters (lab tests, patients, production procedures,
     *  item bundles) that have no number field and are matched by name instead. */
    private suspend fun mergeDuplicateAuxDocuments(context: Context): Int {
        val db = AppDatabase.get(context)
        val repo = Repository(context)
        var removed = 0

        fun <T, K> dupesToDropBy(all: List<T>, key: (T) -> K, id: (T) -> Long): List<T> {
            val drop = ArrayList<T>()
            all.groupBy(key).values.filter { it.size > 1 }.forEach { group ->
                val keepId = group.minOf(id)
                group.forEach { if (id(it) != keepId) drop.add(it) }
            }
            return drop
        }
        fun <T> dupesToDropByNo(all: List<T>, no: (T) -> String, id: (T) -> Long): List<T> =
            dupesToDropBy(all.filter { no(it).isNotBlank() }, no, id)

        dupesToDropByNo(db.salesReturnDao().all(), { it.returnNo }, { it.id }).forEach { repo.deleteSalesReturn(it); removed++ }
        dupesToDropByNo(db.purchaseReturnDao().all(), { it.returnNo }, { it.id }).forEach { repo.deletePurchaseReturn(it); removed++ }
        dupesToDropByNo(db.purchaseQuotationDao().all(), { it.lpoNo }, { it.id }).forEach { db.purchaseQuotationDao().delete(it); removed++ }
        dupesToDropByNo(db.purchaseQuoteDao().all(), { it.quoteNo }, { it.id }).forEach { db.purchaseQuoteDao().delete(it); removed++ }
        dupesToDropByNo(db.materialReceiptDao().all(), { it.receiptNo }, { it.id }).forEach { repo.deleteMaterialReceipt(it); removed++ }
        dupesToDropByNo(db.materialOutDao().all(), { it.voucherNo }, { it.id }).forEach { repo.deleteMaterialOut(it); removed++ }
        dupesToDropByNo(db.hireInvoiceDao().all(), { it.hireNo }, { it.id }).forEach { repo.deleteHireInvoice(it); removed++ }
        dupesToDropByNo(db.hireReturnDao().all(), { it.returnNo }, { it.id }).forEach { repo.deleteHireReturn(it); removed++ }
        dupesToDropByNo(db.labBillDao().allBills(), { it.billNo }, { it.id }).forEach { repo.deleteLabBill(it); removed++ }
        dupesToDropByNo(db.productionDao().allRuns(), { it.runNo }, { it.id }).forEach { repo.deleteProductionRun(it); removed++ }

        dupesToDropBy(db.labTestDao().allTests(), { it.name.trim().lowercase() }, { it.id }).forEach { db.labTestDao().delete(it); removed++ }
        dupesToDropBy(db.patientDao().all(), { it.name.trim().lowercase() to it.phone.trim() }, { it.id }).forEach { db.patientDao().delete(it); removed++ }
        dupesToDropBy(db.productionDao().allProcedures(), { it.name.trim().lowercase() }, { it.id }).forEach { db.productionDao().deleteProcedure(it); removed++ }
        dupesToDropBy(db.itemBundleDao().all(), { it.name.trim().lowercase() }, { it.id }).forEach { db.itemBundleDao().delete(it); removed++ }

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
            // The keeper's own blocks/attachments — a duplicate's copies of the *same* content
            // (the common case: the whole entry was re-inserted, blocks included) must be skipped
            // here, not blindly appended, or merging N duplicate entries leaves N copies of every
            // paragraph sitting inside the one surviving entry.
            fun blockKey(b: DiaryBlock) = listOf(b.type, b.text, b.path, b.name)
            val keptBlockKeys = dao.blocksFor(keeper.id).map(::blockKey).toMutableSet()
            val keptAttNames = dao.attachmentsFor(keeper.id).map { it.name }.toMutableSet()
            dupes.filter { it.id != keeper.id }.forEach { dup ->
                dao.blocksFor(dup.id).forEach { b ->
                    if (keptBlockKeys.add(blockKey(b))) dao.insertBlock(b.copy(id = 0, entryId = keeper.id))
                }
                dao.attachmentsFor(dup.id).forEach { a ->
                    if (keptAttNames.add(a.name)) dao.insertAttachment(a.copy(id = 0, entryId = keeper.id))
                }
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
