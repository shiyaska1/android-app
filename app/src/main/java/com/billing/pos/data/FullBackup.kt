package com.billing.pos.data

import android.content.Context
import android.net.Uri
import com.billing.pos.diary.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Complete backup of the whole app (all tables incl. users, diary + attachment
 * files, and settings) into a single .zip, and a full restore that replaces
 * everything — used to move to a new device or recover after a reinstall.
 */
object FullBackup {

    // Serializes every create()/restore() call app-wide (manual backup, Google Drive, cloud
    // Push/Pull, the auto-sync loop) — they all read/write the same cache file paths, so two
    // running at once would race and could produce a truncated zip.
    private val ioGate = Mutex()

    /**
     * Business-level settings carried by every backup/restore/cloud-sync round-trip, so a second
     * device for the same business ends up configured the same way. Deliberately excludes
     * anything device-local: login session, this device's printer/Bluetooth/logo file paths,
     * licensing, and the sync connection settings themselves (syncing those could point a device
     * at a different server or start a sync loop).
     */
    private fun settingsJson(prefs: AppPrefs) = JSONObject()
        .put("companyName", prefs.companyName)
        .put("companyAddress", prefs.companyAddress)
        .put("companyPhone", prefs.companyPhone)
        .put("companyGstin", prefs.companyGstin)
        .put("gstEnabled", prefs.gstEnabled)
        .put("compositionScheme", prefs.compositionScheme)
        .put("priceIncludesTax", prefs.priceIncludesTax)
        .put("cessEnabled", prefs.cessEnabled)
        .put("noTaxInvoiceEnabled", prefs.noTaxInvoiceEnabled)
        .put("noTaxInvoicePrefix", prefs.noTaxInvoicePrefix)
        .put("upiId", prefs.upiId)
        .put("upiName", prefs.upiName)
        .put("showUpiQrOnPrint", prefs.showUpiQrOnPrint)
        .put("requireItemBatch", prefs.requireItemBatch)
        .put("fifoAutoPickBatch", prefs.fifoAutoPickBatch)
        .put("businessType", prefs.businessType)
        .put("expiryAlert", prefs.expiryAlert)
        .put("expiryAlertDays", prefs.expiryAlertDays)
        .put("smsGatewayUrl", prefs.smsGatewayUrl)
        .put("smsGatewayMethod", prefs.smsGatewayMethod)
        .put("smsApiKey", prefs.smsApiKey)
        .put("smsSenderId", prefs.smsSenderId)
        .put("smsBalanceUrl", prefs.smsBalanceUrl)
        .put("smsChannel", prefs.smsChannel)
        .put("smsJsonBody", prefs.smsJsonBody)
        .put("smsBearer", prefs.smsBearer)
        .put("gymSlots", prefs.gymSlots.joinToString("|"))
        .put("customerTypes", prefs.customerTypes.joinToString("|"))

    /** Applies a [settingsJson] object to [prefs]. Missing keys (older backups) keep the current
     *  local value — only keys actually present overwrite. */
    private fun applySettingsJson(prefs: AppPrefs, s: JSONObject) {
        prefs.companyName = s.optString("companyName", prefs.companyName)
        prefs.companyAddress = s.optString("companyAddress", prefs.companyAddress)
        prefs.companyPhone = s.optString("companyPhone", prefs.companyPhone)
        prefs.companyGstin = s.optString("companyGstin", prefs.companyGstin)
        prefs.gstEnabled = s.optBoolean("gstEnabled", prefs.gstEnabled)
        prefs.compositionScheme = s.optBoolean("compositionScheme", prefs.compositionScheme)
        prefs.priceIncludesTax = s.optBoolean("priceIncludesTax", prefs.priceIncludesTax)
        prefs.cessEnabled = s.optBoolean("cessEnabled", prefs.cessEnabled)
        prefs.noTaxInvoiceEnabled = s.optBoolean("noTaxInvoiceEnabled", prefs.noTaxInvoiceEnabled)
        prefs.noTaxInvoicePrefix = s.optString("noTaxInvoicePrefix", prefs.noTaxInvoicePrefix)
        prefs.upiId = s.optString("upiId", prefs.upiId)
        prefs.upiName = s.optString("upiName", prefs.upiName)
        prefs.showUpiQrOnPrint = s.optBoolean("showUpiQrOnPrint", prefs.showUpiQrOnPrint)
        prefs.requireItemBatch = s.optBoolean("requireItemBatch", prefs.requireItemBatch)
        prefs.fifoAutoPickBatch = s.optBoolean("fifoAutoPickBatch", prefs.fifoAutoPickBatch)
        prefs.businessType = s.optString("businessType", prefs.businessType)
        prefs.expiryAlert = s.optBoolean("expiryAlert", prefs.expiryAlert)
        prefs.expiryAlertDays = s.optInt("expiryAlertDays", prefs.expiryAlertDays)
        prefs.smsGatewayUrl = s.optString("smsGatewayUrl", prefs.smsGatewayUrl)
        prefs.smsGatewayMethod = s.optString("smsGatewayMethod", prefs.smsGatewayMethod)
        prefs.smsApiKey = s.optString("smsApiKey", prefs.smsApiKey)
        prefs.smsSenderId = s.optString("smsSenderId", prefs.smsSenderId)
        prefs.smsBalanceUrl = s.optString("smsBalanceUrl", prefs.smsBalanceUrl)
        prefs.smsChannel = s.optString("smsChannel", prefs.smsChannel)
        prefs.smsJsonBody = s.optString("smsJsonBody", prefs.smsJsonBody)
        prefs.smsBearer = s.optBoolean("smsBearer", prefs.smsBearer)
        if (s.has("gymSlots")) prefs.gymSlots = s.optString("gymSlots", "").split("|").map { it.trim() }.filter { it.isNotBlank() }
        if (s.has("customerTypes")) prefs.customerTypes = s.optString("customerTypes", "").split("|").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * @param pushWindowDays When > 0, only bills/purchases/receipts/expenses/quotations/estimates/
     * journal entries created or edited in the last N days are included (and their line items,
     * to actually shrink the payload) — everything else (masters, returns, LPOs, ...) is always
     * included in full. 0 (the default) keeps today's behavior: everything, every time.
     */
    suspend fun create(context: Context, includeAttachments: Boolean = true, pushWindowDays: Int = 0): File = ioGate.withLock {
        val db = AppDatabase.get(context)
        val prefs = AppPrefs(context)
        val cutoff = if (pushWindowDays > 0) System.currentTimeMillis() - pushWindowDays * 24L * 60 * 60 * 1000 else null

        val root = JSONObject()
        root.put("app", "pos-billing-full")
        root.put("version", 1)
        root.put("createdAt", System.currentTimeMillis())
        root.put("settings", settingsJson(prefs))

        root.put("customers", JSONArray().apply { db.customerDao().all().forEach { put(custJson(it)) } })
        root.put("items", JSONArray().apply { db.itemDao().all().forEach { put(itemJson(it)) } })
        val bills = db.billDao().all().let { all -> if (cutoff == null) all else all.filter { it.updatedAt >= cutoff } }
        val billIds = bills.map { it.id }.toSet()
        root.put("bills", JSONArray().apply { bills.forEach { put(billJson(it)) } })
        root.put("billItems", JSONArray().apply {
            db.billDao().allLines().filter { cutoff == null || it.billId in billIds }.forEach { put(lineJson(it)) }
        })
        val receipts = db.receiptDao().all().let { all -> if (cutoff == null) all else all.filter { it.updatedAt >= cutoff } }
        root.put("receipts", JSONArray().apply { receipts.forEach { put(receiptJson(it)) } })
        val expenses = db.expenseDao().all().let { all -> if (cutoff == null) all else all.filter { it.updatedAt >= cutoff } }
        root.put("expenses", JSONArray().apply { expenses.forEach { put(expenseJson(it)) } })
        root.put("users", JSONArray().apply { db.userDao().all().forEach { put(userJson(it)) } })
        root.put("diaryEntries", JSONArray().apply { db.diaryDao().allEntries().forEach { put(entryJson(it)) } })
        root.put("diaryTypes", JSONArray().apply { db.diaryTypeDao().all().forEach { t -> put(JSONObject().put("id", t.id).put("name", t.name)) } })
        val attachments = db.diaryDao().allAttachments()
        root.put("diaryAttachments", JSONArray().apply { attachments.forEach { put(attJson(it)) } })
        val diaryBlocks = db.diaryDao().allBlocks()
        root.put("diaryBlocks", JSONArray().apply { diaryBlocks.forEach { put(blockJson(it)) } })

        root.put("suppliers", JSONArray().apply { db.supplierDao().all().forEach { put(supplierJson(it)) } })
        val purchases = db.purchaseDao().all().let { all -> if (cutoff == null) all else all.filter { it.updatedAt >= cutoff } }
        val purchaseIds = purchases.map { it.id }.toSet()
        root.put("purchases", JSONArray().apply { purchases.forEach { put(purchaseJson(it)) } })
        root.put("purchaseItems", JSONArray().apply {
            db.purchaseDao().allLines().filter { cutoff == null || it.purchaseId in purchaseIds }.forEach { put(pLineJson(it)) }
        })
        root.put("accountGroups", JSONArray().apply { db.accountDao().allGroups().forEach { put(groupJson(it)) } })
        root.put("accountHeads", JSONArray().apply { db.accountDao().allHeads().forEach { put(headJson(it)) } })
        val jEntries = db.journalDao().allEntries().let { all -> if (cutoff == null) all else all.filter { it.updatedAt >= cutoff } }
        val jEntryIds = jEntries.map { it.id }.toSet()
        root.put("journalEntries", JSONArray().apply { jEntries.forEach { put(jEntryJson(it)) } })
        root.put("journalLines", JSONArray().apply {
            db.journalDao().allLines().filter { cutoff == null || it.entryId in jEntryIds }.forEach { put(jLineJson(it)) }
        })
        val itemAtts = db.itemAttachmentDao().all()
        root.put("itemAttachments", JSONArray().apply { itemAtts.forEach { put(itemAttJson(it)) } })
        root.put("itemBatches", JSONArray().apply { db.itemBatchDao().all().forEach { put(batchJson(it)) } })
        root.put("itemSizes", JSONArray().apply { db.itemSizeDao().all().forEach { put(sizeJson(it)) } })
        val quotations = db.quotationDao().all().let { all -> if (cutoff == null) all else all.filter { it.updatedAt >= cutoff } }
        val quotationIds = quotations.map { it.id }.toSet()
        root.put("quotations", JSONArray().apply { quotations.forEach { put(quotationJson(it)) } })
        root.put("quotationItems", JSONArray().apply {
            db.quotationDao().allLines().filter { cutoff == null || it.quotationId in quotationIds }.forEach { put(qItemJson(it)) }
        })
        val estimates = db.estimateDao().all().let { all -> if (cutoff == null) all else all.filter { it.updatedAt >= cutoff } }
        val estimateIds = estimates.map { it.id }.toSet()
        root.put("estimates", JSONArray().apply { estimates.forEach { put(estimateJson(it)) } })
        root.put("estimateItems", JSONArray().apply {
            db.estimateDao().allLines().filter { cutoff == null || it.estimateId in estimateIds }.forEach { put(eItemJson(it)) }
        })
        root.put("salesReturns", JSONArray().apply { db.salesReturnDao().all().forEach { put(salesReturnJson(it)) } })
        root.put("salesReturnItems", JSONArray().apply { db.salesReturnDao().allLines().forEach { put(srItemJson(it)) } })
        root.put("purchaseReturns", JSONArray().apply { db.purchaseReturnDao().all().forEach { put(purchaseReturnJson(it)) } })
        root.put("purchaseReturnItems", JSONArray().apply { db.purchaseReturnDao().allLines().forEach { put(prItemJson(it)) } })
        root.put("purchaseQuotations", JSONArray().apply { db.purchaseQuotationDao().all().forEach { put(lpoJson(it)) } })
        root.put("purchaseQuotationItems", JSONArray().apply { db.purchaseQuotationDao().allLines().forEach { put(lpoItemJson(it)) } })
        root.put("hireInvoices", JSONArray().apply { db.hireInvoiceDao().all().forEach { put(hireJson(it)) } })
        root.put("hireInvoiceItems", JSONArray().apply { db.hireInvoiceDao().allLines().forEach { put(hireItemJson(it)) } })
        root.put("hireReturns", JSONArray().apply { db.hireReturnDao().all().forEach { put(hireRetJson(it)) } })
        root.put("hireReturnItems", JSONArray().apply { db.hireReturnDao().allLines().forEach { put(hireRetItemJson(it)) } })
        root.put("labTests", JSONArray().apply { db.labTestDao().allTests().forEach { put(labTestJson(it)) } })
        root.put("labEvaluations", JSONArray().apply { db.labTestDao().allEvaluations().forEach { put(labEvalJson(it)) } })
        root.put("patients", JSONArray().apply { db.patientDao().all().forEach { put(patientJson(it)) } })
        root.put("labBills", JSONArray().apply { db.labBillDao().allBills().forEach { put(labBillJson(it)) } })
        root.put("labBillTests", JSONArray().apply { db.labBillDao().allBillTests().forEach { put(labBillTestJson(it)) } })
        root.put("labResults", JSONArray().apply { db.labBillDao().allResults().forEach { put(labResultJson(it)) } })
        root.put("labGroups", JSONArray().apply { db.labMasterDao().allGroups().forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("labEvalMasters", JSONArray().apply { db.labMasterDao().allEvals().forEach { put(labEvalMasterJson(it)) } })
        root.put("labHeadings", JSONArray().apply { db.labMasterDao().allHeadings().forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("labReceipts", JSONArray().apply { db.labReceiptDao().all().forEach { put(labReceiptJson(it)) } })
        root.put("labDoctors", JSONArray().apply { db.labMasterDao().allDoctors().forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("materialOuts", JSONArray().apply { db.materialOutDao().all().forEach { put(matOutJson(it)) } })
        root.put("materialOutItems", JSONArray().apply { db.materialOutDao().allLines().forEach { put(matOutItemJson(it)) } })
        root.put("materialReceipts", JSONArray().apply { db.materialReceiptDao().all().forEach { put(matRecJson(it)) } })
        root.put("materialReceiptItems", JSONArray().apply { db.materialReceiptDao().allLines().forEach { put(matRecItemJson(it)) } })
        root.put("productionProcedures", JSONArray().apply { db.productionDao().allProcedures().forEach { put(procedureJson(it)) } })
        root.put("productionProcedureMaterials", JSONArray().apply { db.productionDao().allProcedureMaterials().forEach { put(procedureMaterialJson(it)) } })
        root.put("productionRuns", JSONArray().apply { db.productionDao().allRuns().forEach { put(productionRunJson(it)) } })
        root.put("itemBundles", JSONArray().apply { db.itemBundleDao().all().forEach { put(bundleJson(it)) } })
        root.put("itemBundleComponents", JSONArray().apply { db.itemBundleDao().allComponents().forEach { put(bundleComponentJson(it)) } })
        val billAtts = db.billAttachmentDao().all()
        root.put("billAttachments", JSONArray().apply { billAtts.forEach { put(billAttJson(it)) } })
        val expenseAtts = db.expenseAttachmentDao().all()
        root.put("expenseAttachments", JSONArray().apply { expenseAtts.forEach { put(expenseAttJson(it)) } })
        root.put("savedCalcs", JSONArray().apply { db.savedCalcDao().all().forEach { put(savedCalcJson(it)) } })
        root.put("purchaseQuotes", JSONArray().apply { db.purchaseQuoteDao().all().forEach { put(pQuoteJson(it)) } })
        root.put("purchaseQuoteItems", JSONArray().apply { db.purchaseQuoteDao().allLines().forEach { put(pQuoteItemJson(it)) } })
        val custAtts = db.customerAttachmentDao().all()
        root.put("customerAttachments", JSONArray().apply { custAtts.forEach { put(custAttJson(it)) } })
        root.put("custOrders", JSONArray().apply { db.custOrderDao().all().forEach { put(orderJson(it)) } })
        root.put("custOrderItems", JSONArray().apply { db.custOrderDao().allLines().forEach { put(orderItemJson(it)) } })
        val orderAtts = db.custOrderDao().allAttachments()
        root.put("custOrderAttachments", JSONArray().apply { orderAtts.forEach { put(orderAttJson(it)) } })
        root.put("salesmanMap", JSONArray().apply {
            db.salesmanMapDao().all().forEach { put(JSONObject().put("deviceId", it.deviceId).put("name", it.name)) }
        })

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zip = File(dir, "pos-full-backup.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("full-backup.json"))
            zos.write(root.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            if (includeAttachments) {
                attachments.forEach { att ->
                    val f = File(att.path)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("files/" + f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                diaryBlocks.forEach { b ->
                    if (b.path.isNotBlank()) {
                        val f = File(b.path)
                        if (f.exists()) {
                            zos.putNextEntry(ZipEntry("files/" + f.name))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                itemAtts.forEach { att ->
                    val f = File(att.path)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("itemfiles/" + f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                billAtts.forEach { att ->
                    val f = File(att.path)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("billfiles/" + f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                expenseAtts.forEach { att ->
                    val f = File(att.path)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("expensefiles/" + f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                orderAtts.forEach { att ->
                    val f = File(att.path)
                    if (f.exists()) { zos.putNextEntry(ZipEntry("orderfiles/" + f.name)); f.inputStream().use { it.copyTo(zos) }; zos.closeEntry() }
                }
                custAtts.forEach { att ->
                    val f = File(att.path)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("customerfiles/" + f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        zip
    }

    /** One row of the attachments-only zip's manifest. */
    private data class AttEntry(val type: String, val key: String, val file: String, val name: String, val mime: String, val loc: String = "")

    /**
     * A standalone zip of just the attachment files (photos/documents) across every module,
     * independent of [create]'s full-database backup. Each entry carries its parent's natural
     * key (invoice/purchase number, customer name, item name, ...) instead of a local numeric
     * id, so [restoreAttachmentsZip] can reattach it to the matching record even on a different
     * device/database where that record's local id differs.
     *
     * @param sinceMillis When > 0, only attachments whose FILE was added/changed on or after
     * this time are included (0 = every attachment) — keyed off the file's own last-modified
     * time, not the parent record's date, since adding a photo to an old bill today should count
     * as "recent" regardless of how old the bill itself is.
     */
    suspend fun createAttachmentsZip(context: Context, sinceMillis: Long = 0): File = ioGate.withLock {
        val db = AppDatabase.get(context)
        fun keep(path: String) = sinceMillis <= 0 || File(path).lastModified() >= sinceMillis

        val billNo = db.billDao().all().associate { it.id to it.billNo }
        val purchaseNo = db.purchaseDao().all().associate { it.id to it.purchaseNo }
        val customerName = db.customerDao().all().associate { it.id to it.name }
        val itemName = db.itemDao().all().associate { it.id to it.name }
        val expenseNo = db.expenseDao().all().associate { it.id to it.voucherNo }
        val receiptNo = db.receiptDao().all().associate { it.id to it.receiptNo }
        val orderNo = db.custOrderDao().all().associate { it.id to it.orderNo }
        val jobNo = db.serviceDao().allCards().associate { it.id to it.jobNo }
        val diaryTitle = db.diaryDao().allEntries().associate { it.id to it.title }
        val noteText = db.quickNoteDao().all().associate { it.id to it.text.take(60) }

        val entries = ArrayList<AttEntry>()
        db.billAttachmentDao().all().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("bill", billNo[a.billId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.purchaseAttachmentDao().all().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("purchase", purchaseNo[a.purchaseId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.customerAttachmentDao().all().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("customer", customerName[a.customerId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.itemAttachmentDao().all().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("item", itemName[a.itemId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.expenseAttachmentDao().all().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("expense", expenseNo[a.expenseId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.receiptAttachmentDao().all().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("receipt", receiptNo[a.receiptId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.custOrderDao().allAttachments().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("order", orderNo[a.orderId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.serviceDao().allAttachments().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("service", jobNo[a.cardId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.quickNoteAttachmentDao().all().filter { keep(it.path) }.forEach { a ->
            entries.add(AttEntry("quicknote", noteText[a.noteId] ?: "", File(a.path).name, a.name, a.mime))
        }
        db.diaryDao().allAttachments().filter { it.type == AttachmentType.LOCATION || keep(it.path) }.forEach { a ->
            if (a.type == AttachmentType.LOCATION) {
                entries.add(AttEntry("diary", diaryTitle[a.entryId] ?: "", "", a.name, a.mime, loc = a.path))
            } else {
                entries.add(AttEntry("diary", diaryTitle[a.entryId] ?: "", File(a.path).name, a.name, a.mime))
            }
        }

        val root = JSONObject()
        root.put("app", "pos-billing-attachments")
        root.put("version", 1)
        root.put("createdAt", System.currentTimeMillis())
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().put("type", e.type).put("key", e.key).put("file", e.file)
                    .put("name", e.name).put("mime", e.mime).put("loc", e.loc)
            )
        }
        root.put("entries", arr)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zip = File(dir, "pos-attachments.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("attachments.json"))
            zos.write(root.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            val written = HashSet<String>()
            // Re-walk each source list to stream file bytes — AttEntry (above) only kept the
            // bare filename for the manifest, so the original absolute path is looked up again
            // here rather than threaded through.
            suspend fun <T> writeAll(list: List<T>, path: (T) -> String, type: String) {
                list.forEach { item ->
                    val p = path(item)
                    if (p.isNotBlank() && keep(p)) {
                        val f = File(p)
                        if (f.exists()) {
                            val entryPath = "$type/${f.name}"
                            if (written.add(entryPath)) {
                                zos.putNextEntry(ZipEntry(entryPath))
                                f.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    }
                }
            }
            writeAll(db.billAttachmentDao().all(), { it.path }, "bill")
            writeAll(db.purchaseAttachmentDao().all(), { it.path }, "purchase")
            writeAll(db.customerAttachmentDao().all(), { it.path }, "customer")
            writeAll(db.itemAttachmentDao().all(), { it.path }, "item")
            writeAll(db.expenseAttachmentDao().all(), { it.path }, "expense")
            writeAll(db.receiptAttachmentDao().all(), { it.path }, "receipt")
            writeAll(db.custOrderDao().allAttachments(), { it.path }, "order")
            writeAll(db.serviceDao().allAttachments(), { it.path }, "service")
            writeAll(db.quickNoteAttachmentDao().all(), { it.path }, "quicknote")
            writeAll(db.diaryDao().allAttachments().filter { it.type != AttachmentType.LOCATION }, { it.path }, "diary")
        }
        zip
    }

    /** Outcome of an attachments-only restore, shown to the user afterward. */
    data class AttachmentRestoreReport(val restored: Int, val skippedNoParent: Int, val skippedDuplicate: Int)

    /**
     * Restores an attachments-only zip produced by [createAttachmentsZip]. Each entry is
     * reattached to its parent by natural key (invoice no, customer name, ...) resolved against
     * the CURRENT local data — not by the numeric id it was exported with, which may not match
     * on this device. Entries whose parent can't be found are skipped and counted, not silently
     * dropped.
     *
     * @param merge When true, keeps existing attachments and skips an incoming one if the same
     * parent already has an attachment with the same original filename (avoids duplicates on a
     * repeated restore). When false (Replace), every existing attachment of these 10 types —
     * rows and files — is deleted first, then everything in the zip is imported.
     */
    suspend fun restoreAttachmentsZip(context: Context, uri: Uri, merge: Boolean): Result<AttachmentRestoreReport> = ioGate.withLock { runCatching {
        val db = AppDatabase.get(context)

        // Stage every file into a temp holding folder first (streamed, never loaded whole),
        // then apply once the manifest is known — the zip can list files before or after the
        // manifest entry depending on how it was written.
        val stageDir = File(context.cacheDir, "attrestore_stage").apply { deleteRecursively(); mkdirs() }
        var json: String? = null
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read the file")
        ZipInputStream(input.buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    if (e.name == "attachments.json") {
                        json = zis.readBytes().toString(Charsets.UTF_8)
                    } else {
                        val out = File(stageDir, e.name.replace("/", "__"))
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
                e = zis.nextEntry
            }
        }
        val data = json ?: error("Not a valid attachments backup file")
        val root = JSONObject(data)

        if (!merge) {
            // Delete every existing attachment file across all 10 stores, then clear their DB
            // rows — Replace means these attachment tables end up containing only what's in the
            // zip, same intent as db.clearAllTables() in the full restore but scoped to just
            // these 10 tables.
            listOf(
                com.billing.pos.items.ItemAttachmentStore.dir(context),
                com.billing.pos.bills.BillAttachmentStore.dir(context),
                CustomerAttachmentStore.dir(context),
                com.billing.pos.expenses.ExpenseAttachmentStore.dir(context),
                com.billing.pos.purchase.PurchaseAttachmentStore.dir(context),
                ReceiptAttachmentStore.dir(context),
                OrderAttachmentStore.dir(context),
                ServiceAttachmentStore.dir(context),
                com.billing.pos.quicknote.QuickNoteAttachmentStore.dir(context),
                AttachmentStore.dir(context)
            ).forEach { d -> d.listFiles()?.forEach { it.delete() } }
            db.billAttachmentDao().deleteAll()
            db.purchaseAttachmentDao().deleteAll()
            db.customerAttachmentDao().deleteAll()
            db.itemAttachmentDao().deleteAll()
            db.expenseAttachmentDao().deleteAll()
            db.receiptAttachmentDao().deleteAll()
            db.custOrderDao().deleteAllAttachments()
            db.serviceDao().deleteAllAttachments()
            db.quickNoteAttachmentDao().deleteAll()
            db.diaryDao().deleteAllAttachments()
        }

        var restored = 0
        var skippedNoParent = 0
        var skippedDuplicate = 0

        val billIdByNo = db.billDao().all().associate { it.billNo to it.id }
        val purchaseIdByNo = db.purchaseDao().all().associate { it.purchaseNo to it.id }
        val customerIdByName = db.customerDao().all().associate { it.name.lowercase() to it.id }
        val itemIdByName = db.itemDao().all().associate { it.name.lowercase() to it.id }
        val expenseIdByNo = db.expenseDao().all().associate { it.voucherNo to it.id }
        val receiptIdByNo = db.receiptDao().all().associate { it.receiptNo to it.id }
        val orderIdByNo = db.custOrderDao().all().associate { it.orderNo to it.id }
        val jobIdByNo = db.serviceDao().allCards().associate { it.jobNo to it.id }
        val diaryIdByTitle = db.diaryDao().allEntries().associate { it.title to it.id }
        val noteIdByText = db.quickNoteDao().all().associate { it.text.take(60) to it.id }

        val existingBillNames = db.billAttachmentDao().all().groupBy { it.billId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingPurchaseNames = db.purchaseAttachmentDao().all().groupBy { it.purchaseId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingCustomerNames = db.customerAttachmentDao().all().groupBy { it.customerId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingItemNames = db.itemAttachmentDao().all().groupBy { it.itemId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingExpenseNames = db.expenseAttachmentDao().all().groupBy { it.expenseId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingReceiptNames = db.receiptAttachmentDao().all().groupBy { it.receiptId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingOrderNames = db.custOrderDao().allAttachments().groupBy { it.orderId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingServiceNames = db.serviceDao().allAttachments().groupBy { it.cardId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingQuickNoteNames = db.quickNoteAttachmentDao().all().groupBy { it.noteId }.mapValues { (_, l) -> l.map { it.name }.toSet() }
        val existingDiaryNames = db.diaryDao().allAttachments().groupBy { it.entryId }.mapValues { (_, l) -> l.map { it.name }.toSet() }

        fun stagedFile(type: String, file: String): File? {
            if (file.isBlank()) return null
            val f = File(stageDir, "$type/$file".replace("/", "__"))
            return if (f.exists()) f else null
        }

        val entries = root.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val o = entries.getJSONObject(i)
            val type = o.optString("type")
            val key = o.optString("key")
            val file = o.optString("file")
            val name = o.optString("name")
            val mime = o.optString("mime")
            val loc = o.optString("loc")

            when (type) {
                "bill" -> {
                    val parentId = billIdByNo[key]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingBillNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("bill", file) ?: continue
                    val dest = File(com.billing.pos.bills.BillAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.billAttachmentDao().insert(BillAttachment(billId = parentId, path = dest.absolutePath, name = name, mime = mime))
                    restored++
                }
                "purchase" -> {
                    val parentId = purchaseIdByNo[key]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingPurchaseNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("purchase", file) ?: continue
                    val dest = File(com.billing.pos.purchase.PurchaseAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.purchaseAttachmentDao().insert(PurchaseAttachment(purchaseId = parentId, path = dest.absolutePath, name = name, mime = mime))
                    restored++
                }
                "customer" -> {
                    val parentId = customerIdByName[key.lowercase()]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingCustomerNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("customer", file) ?: continue
                    val dest = File(CustomerAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.customerAttachmentDao().insert(CustomerAttachment(customerId = parentId, path = dest.absolutePath, name = name, mime = mime))
                    restored++
                }
                "item" -> {
                    val parentId = itemIdByName[key.lowercase()]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingItemNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("item", file) ?: continue
                    val dest = File(com.billing.pos.items.ItemAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.itemAttachmentDao().insert(ItemAttachment(itemId = parentId, path = dest.absolutePath, name = name, mime = mime, kind = "PHOTO"))
                    restored++
                }
                "expense" -> {
                    val parentId = expenseIdByNo[key]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingExpenseNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("expense", file) ?: continue
                    val dest = File(com.billing.pos.expenses.ExpenseAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.expenseAttachmentDao().insert(ExpenseAttachment(expenseId = parentId, path = dest.absolutePath, name = name, mime = mime))
                    restored++
                }
                "receipt" -> {
                    val parentId = receiptIdByNo[key]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingReceiptNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("receipt", file) ?: continue
                    val dest = File(ReceiptAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.receiptAttachmentDao().insertAll(listOf(ReceiptAttachment(receiptId = parentId, path = dest.absolutePath, name = name, mime = mime)))
                    restored++
                }
                "order" -> {
                    val parentId = orderIdByNo[key]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingOrderNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("order", file) ?: continue
                    val dest = File(OrderAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.custOrderDao().insertAttachment(CustOrderAttachment(orderId = parentId, path = dest.absolutePath, name = name, mime = mime))
                    restored++
                }
                "service" -> {
                    val parentId = jobIdByNo[key]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingServiceNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("service", file) ?: continue
                    val dest = File(ServiceAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.serviceDao().insertAttachments(listOf(ServiceJobAttachment(cardId = parentId, path = dest.absolutePath, name = name, mime = mime)))
                    restored++
                }
                "quicknote" -> {
                    val parentId = noteIdByText[key]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingQuickNoteNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    val staged = stagedFile("quicknote", file) ?: continue
                    val dest = File(com.billing.pos.quicknote.QuickNoteAttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                    staged.copyTo(dest, overwrite = true)
                    db.quickNoteAttachmentDao().insert(QuickNoteAttachment(noteId = parentId, path = dest.absolutePath, name = name, mime = mime))
                    restored++
                }
                "diary" -> {
                    val parentId = diaryIdByTitle[key]
                    if (parentId == null) { skippedNoParent++; continue }
                    if (merge && existingDiaryNames[parentId]?.contains(name) == true) { skippedDuplicate++; continue }
                    if (loc.isNotBlank()) {
                        db.diaryDao().insertAttachment(DiaryAttachment(entryId = parentId, path = loc, name = name, mime = mime, type = AttachmentType.LOCATION))
                        restored++
                    } else {
                        val staged = stagedFile("diary", file) ?: continue
                        val dest = File(AttachmentStore.dir(context), "restored_${System.nanoTime()}_$file")
                        staged.copyTo(dest, overwrite = true)
                        val kind = when {
                            mime.startsWith("image/") -> AttachmentType.IMAGE
                            mime.startsWith("video/") -> AttachmentType.VIDEO
                            mime.startsWith("audio/") -> AttachmentType.AUDIO
                            else -> AttachmentType.DOCUMENT
                        }
                        db.diaryDao().insertAttachment(DiaryAttachment(entryId = parentId, path = dest.absolutePath, name = name, mime = mime, type = kind))
                        restored++
                    }
                }
            }
        }
        stageDir.deleteRecursively()
        AttachmentRestoreReport(restored, skippedNoParent, skippedDuplicate)
    } }

    suspend fun restore(context: Context, uri: Uri, merge: Boolean = false): Result<String> = ioGate.withLock { runCatching {
        val filesDir = AttachmentStore.dir(context)
        val itemFilesDir = com.billing.pos.items.ItemAttachmentStore.dir(context)
        val billFilesDir = com.billing.pos.bills.BillAttachmentStore.dir(context)
        val expenseFilesDir = com.billing.pos.expenses.ExpenseAttachmentStore.dir(context)
        val customerFilesDir = CustomerAttachmentStore.dir(context)
        val orderFilesDir = OrderAttachmentStore.dir(context)
        var json: String? = null
        // Streamed, never loaded whole: a backup with photos and voice notes can be hundreds
        // of MB, and reading it into a ByteArray first is an instant OutOfMemory on a phone.
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read the file")
        ZipInputStream(input.buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    when {
                        e.name.endsWith(".json") -> json = zis.readBytes().toString(Charsets.UTF_8)
                        e.name.startsWith("itemfiles/") -> {
                            val out = File(itemFilesDir, e.name.removePrefix("itemfiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("billfiles/") -> {
                            val out = File(billFilesDir, e.name.removePrefix("billfiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("expensefiles/") -> {
                            val out = File(expenseFilesDir, e.name.removePrefix("expensefiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("customerfiles/") -> {
                            val out = File(customerFilesDir, e.name.removePrefix("customerfiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("orderfiles/") -> {
                            val out = File(orderFilesDir, e.name.removePrefix("orderfiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("files/") -> {
                            val out = File(filesDir, e.name.removePrefix("files/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                    }
                }
                e = zis.nextEntry
            }
        }
        val data = json ?: error("Not a valid backup file")
        val root = JSONObject(data)

        val db = AppDatabase.get(context)

        if (merge) {
            val report = mergeInto(context, db, root)
            MergeReport.save(context, report)
            return@runCatching "Merge complete — ${report.total} record(s) added"
        }

        withContext(Dispatchers.IO) { db.clearAllTables() }

        root.optJSONObject("settings")?.let { s -> applySettingsJson(AppPrefs(context), s) }

        root.optJSONArray("customers")?.let { for (i in 0 until it.length()) db.customerDao().insert(readCust(it.getJSONObject(i))) }
        root.optJSONArray("items")?.let { for (i in 0 until it.length()) db.itemDao().insert(readItem(it.getJSONObject(i))) }
        root.optJSONArray("bills")?.let { for (i in 0 until it.length()) db.billDao().insertBill(readBill(it.getJSONObject(i))) }
        root.optJSONArray("billItems")?.let {
            val lines = ArrayList<BillItem>()
            for (i in 0 until it.length()) lines.add(readLine(it.getJSONObject(i)))
            if (lines.isNotEmpty()) db.billDao().insertLines(lines)
        }
        root.optJSONArray("receipts")?.let { for (i in 0 until it.length()) db.receiptDao().insert(readReceipt(it.getJSONObject(i))) }
        root.optJSONArray("expenses")?.let { for (i in 0 until it.length()) db.expenseDao().insert(readExpense(it.getJSONObject(i))) }
        root.optJSONArray("users")?.let { for (i in 0 until it.length()) db.userDao().insert(readUser(it.getJSONObject(i))) }
        root.optJSONArray("diaryTypes")?.let {
            for (i in 0 until it.length()) {
                val o = it.getJSONObject(i)
                db.diaryTypeDao().insert(DiaryType(o.optLong("id"), o.optString("name")))
            }
        }
        root.optJSONArray("diaryEntries")?.let { for (i in 0 until it.length()) db.diaryDao().insert(readEntry(it.getJSONObject(i))) }
        root.optJSONArray("diaryBlocks")?.let {
            for (i in 0 until it.length()) db.diaryDao().insertBlock(readBlock(context, it.getJSONObject(i)))
        }
        root.optJSONArray("diaryAttachments")?.let {
            for (i in 0 until it.length()) db.diaryDao().insertAttachment(readAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("suppliers")?.let { for (i in 0 until it.length()) db.supplierDao().insert(readSupplier(it.getJSONObject(i))) }
        root.optJSONArray("purchases")?.let { for (i in 0 until it.length()) db.purchaseDao().insertPurchase(readPurchase(it.getJSONObject(i))) }
        root.optJSONArray("purchaseItems")?.let {
            val lines = ArrayList<PurchaseItem>()
            for (i in 0 until it.length()) lines.add(readPLine(it.getJSONObject(i)))
            if (lines.isNotEmpty()) db.purchaseDao().insertLines(lines)
        }
        root.optJSONArray("accountGroups")?.let { for (i in 0 until it.length()) db.accountDao().insertGroup(readGroup(it.getJSONObject(i))) }
        root.optJSONArray("accountHeads")?.let { for (i in 0 until it.length()) db.accountDao().insertHead(readHead(it.getJSONObject(i))) }
        root.optJSONArray("journalEntries")?.let { for (i in 0 until it.length()) db.journalDao().insertEntry(readJEntry(it.getJSONObject(i))) }
        root.optJSONArray("journalLines")?.let {
            val lines = ArrayList<JournalLine>()
            for (i in 0 until it.length()) lines.add(readJLine(it.getJSONObject(i)))
            if (lines.isNotEmpty()) db.journalDao().insertLines(lines)
        }
        root.optJSONArray("itemAttachments")?.let {
            for (i in 0 until it.length()) db.itemAttachmentDao().insert(readItemAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("itemBatches")?.let {
            for (i in 0 until it.length()) db.itemBatchDao().insert(readBatch(it.getJSONObject(i)))
        }
        root.optJSONArray("itemSizes")?.let {
            for (i in 0 until it.length()) db.itemSizeDao().insert(readSize(it.getJSONObject(i)))
        }
        root.optJSONArray("quotations")?.let { for (i in 0 until it.length()) db.quotationDao().insertHeader(readQuotation(it.getJSONObject(i))) }
        root.optJSONArray("quotationItems")?.let {
            val ls = ArrayList<QuotationItem>()
            for (i in 0 until it.length()) ls.add(readQItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.quotationDao().insertLines(ls)
        }
        root.optJSONArray("estimates")?.let { for (i in 0 until it.length()) db.estimateDao().insertHeader(readEstimate(it.getJSONObject(i))) }
        root.optJSONArray("estimateItems")?.let {
            val ls = ArrayList<EstimateItem>()
            for (i in 0 until it.length()) ls.add(readEItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.estimateDao().insertLines(ls)
        }
        root.optJSONArray("salesReturns")?.let { for (i in 0 until it.length()) db.salesReturnDao().insertHeader(readSalesReturn(it.getJSONObject(i))) }
        root.optJSONArray("salesReturnItems")?.let {
            val ls = ArrayList<SalesReturnItem>()
            for (i in 0 until it.length()) ls.add(readSRItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.salesReturnDao().insertLines(ls)
        }
        root.optJSONArray("purchaseReturns")?.let { for (i in 0 until it.length()) db.purchaseReturnDao().insertHeader(readPurchaseReturn(it.getJSONObject(i))) }
        root.optJSONArray("purchaseReturnItems")?.let {
            val ls = ArrayList<PurchaseReturnItem>()
            for (i in 0 until it.length()) ls.add(readPRItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.purchaseReturnDao().insertLines(ls)
        }
        root.optJSONArray("purchaseQuotations")?.let { for (i in 0 until it.length()) db.purchaseQuotationDao().insertHeader(readLpo(it.getJSONObject(i))) }
        root.optJSONArray("purchaseQuotationItems")?.let {
            val ls = ArrayList<PurchaseQuotationItem>()
            for (i in 0 until it.length()) ls.add(readLpoItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.purchaseQuotationDao().insertLines(ls)
        }
        root.optJSONArray("hireInvoices")?.let { for (i in 0 until it.length()) db.hireInvoiceDao().insertHeader(readHire(it.getJSONObject(i))) }
        root.optJSONArray("hireInvoiceItems")?.let {
            val ls = ArrayList<HireInvoiceItem>()
            for (i in 0 until it.length()) ls.add(readHireItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.hireInvoiceDao().insertLines(ls)
        }
        root.optJSONArray("hireReturns")?.let { for (i in 0 until it.length()) db.hireReturnDao().insertHeader(readHireRet(it.getJSONObject(i))) }
        root.optJSONArray("hireReturnItems")?.let {
            val ls = ArrayList<HireReturnItem>()
            for (i in 0 until it.length()) ls.add(readHireRetItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.hireReturnDao().insertLines(ls)
        }
        root.optJSONArray("labTests")?.let { for (i in 0 until it.length()) db.labTestDao().insertTest(readLabTest(it.getJSONObject(i))) }
        root.optJSONArray("labEvaluations")?.let {
            val ls = ArrayList<LabEvaluation>()
            for (i in 0 until it.length()) ls.add(readLabEval(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.labTestDao().insertEvaluations(ls)
        }
        root.optJSONArray("patients")?.let { for (i in 0 until it.length()) db.patientDao().insert(readPatient(it.getJSONObject(i))) }
        root.optJSONArray("labBills")?.let { for (i in 0 until it.length()) db.labBillDao().insertBill(readLabBill(it.getJSONObject(i))) }
        root.optJSONArray("labBillTests")?.let {
            val ls = ArrayList<LabBillTest>()
            for (i in 0 until it.length()) ls.add(readLabBillTest(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.labBillDao().insertTests(ls)
        }
        root.optJSONArray("labResults")?.let {
            val ls = ArrayList<LabResultValue>()
            for (i in 0 until it.length()) ls.add(readLabResult(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.labBillDao().insertResults(ls)
        }
        root.optJSONArray("labGroups")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); db.labMasterDao().insertGroup(LabGroup(o.optLong("id"), o.optString("name"))) } }
        root.optJSONArray("labEvalMasters")?.let { for (i in 0 until it.length()) db.labMasterDao().insertEval(readLabEvalMaster(it.getJSONObject(i))) }
        root.optJSONArray("labHeadings")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); db.labMasterDao().insertHeading(LabHeading(o.optLong("id"), o.optString("name"))) } }
        root.optJSONArray("labReceipts")?.let { for (i in 0 until it.length()) db.labReceiptDao().insert(readLabReceipt(it.getJSONObject(i))) }
        root.optJSONArray("labDoctors")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); db.labMasterDao().insertDoctor(LabDoctor(o.optLong("id"), o.optString("name"))) } }
        root.optJSONArray("materialOuts")?.let { for (i in 0 until it.length()) db.materialOutDao().insertHeader(readMatOut(it.getJSONObject(i))) }
        root.optJSONArray("materialOutItems")?.let {
            val ls = ArrayList<MaterialOutItem>()
            for (i in 0 until it.length()) ls.add(readMatOutItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.materialOutDao().insertLines(ls)
        }
        root.optJSONArray("billAttachments")?.let {
            for (i in 0 until it.length()) db.billAttachmentDao().insert(readBillAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("expenseAttachments")?.let {
            for (i in 0 until it.length()) db.expenseAttachmentDao().insert(readExpenseAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("savedCalcs")?.let {
            for (i in 0 until it.length()) db.savedCalcDao().insert(readSavedCalc(it.getJSONObject(i)))
        }
        root.optJSONArray("purchaseQuotes")?.let {
            for (i in 0 until it.length()) db.purchaseQuoteDao().insertHeader(readPQuote(it.getJSONObject(i)))
        }
        root.optJSONArray("purchaseQuoteItems")?.let {
            val ls = ArrayList<PurchaseQuoteItem>()
            for (i in 0 until it.length()) ls.add(readPQuoteItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.purchaseQuoteDao().insertLines(ls)
        }
        root.optJSONArray("customerAttachments")?.let {
            for (i in 0 until it.length()) db.customerAttachmentDao().insert(readCustAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("custOrders")?.let { for (i in 0 until it.length()) db.custOrderDao().insertHeader(readOrder(it.getJSONObject(i))) }
        root.optJSONArray("custOrderItems")?.let {
            val ls = ArrayList<CustOrderItem>(); for (i in 0 until it.length()) ls.add(readOrderItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.custOrderDao().insertLines(ls)
        }
        root.optJSONArray("custOrderAttachments")?.let { for (i in 0 until it.length()) db.custOrderDao().insertAttachment(readOrderAtt(context, it.getJSONObject(i))) }
        root.optJSONArray("salesmanMap")?.let {
            for (i in 0 until it.length()) {
                val o = it.getJSONObject(i)
                db.salesmanMapDao().upsert(SalesmanMap(o.optString("deviceId"), o.optString("name")))
            }
        }
        root.optJSONArray("materialReceipts")?.let {
            for (i in 0 until it.length()) db.materialReceiptDao().insertHeader(readMatRec(it.getJSONObject(i)))
        }
        root.optJSONArray("materialReceiptItems")?.let {
            val ls = ArrayList<MaterialReceiptItem>()
            for (i in 0 until it.length()) ls.add(readMatRecItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.materialReceiptDao().insertLines(ls)
        }
        root.optJSONArray("productionProcedures")?.let { for (i in 0 until it.length()) db.productionDao().insertProcedureHeader(readProcedure(it.getJSONObject(i))) }
        root.optJSONArray("productionProcedureMaterials")?.let {
            val ls = ArrayList<ProductionProcedureMaterial>()
            for (i in 0 until it.length()) ls.add(readProcedureMaterial(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.productionDao().insertProcedureMaterials(ls)
        }
        root.optJSONArray("productionRuns")?.let { for (i in 0 until it.length()) db.productionDao().insertRun(readProductionRun(it.getJSONObject(i))) }
        root.optJSONArray("itemBundles")?.let { for (i in 0 until it.length()) db.itemBundleDao().insertHeader(readBundle(it.getJSONObject(i))) }
        root.optJSONArray("itemBundleComponents")?.let {
            val ls = ArrayList<ItemBundleComponent>()
            for (i in 0 until it.length()) ls.add(readBundleComponent(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.itemBundleDao().insertComponents(ls)
        }

        "Restore complete"
    } }

    /**
     * Appends a backup into the existing data (no wipe). Masters (customers, suppliers,
     * items, account groups/heads) are reused when a same-named row already exists;
     * documents are always inserted as new rows with their parent ids remapped.
     * Users are intentionally NOT merged so local logins are preserved.
     */
    private suspend fun mergeInto(context: Context, db: AppDatabase, root: JSONObject): MergeReport {
        val log = MergeReportBuilder()
        // Settings — a merge is meant to converge every device onto the same business profile
        // too, not just add records, so this is applied unconditionally like a replace restore
        // does (previously this only happened on a full Replace-all, never on Merge — including
        // every Push/Pull cloud-sync cycle, which always merges).
        root.optJSONObject("settings")?.let { s -> applySettingsJson(AppPrefs(context), s) }
        // Customers
        val custByName = HashMap<String, Long>()
        db.customerDao().all().forEach { custByName[it.name.lowercase()] = it.id }
        val custMap = HashMap<Long, Long>()
        root.optJSONArray("customers")?.let {
            for (i in 0 until it.length()) {
                val c = readCust(it.getJSONObject(i)); val key = c.name.lowercase()
                custMap[c.id] = custByName[key]
                    ?: db.customerDao().insert(c.copy(id = 0)).also { nid -> custByName[key] = nid; log.add("customers", nid) }
            }
        }
        // Suppliers
        val suppByName = HashMap<String, Long>()
        db.supplierDao().all().forEach { suppByName[it.name.lowercase()] = it.id }
        val suppMap = HashMap<Long, Long>()
        root.optJSONArray("suppliers")?.let {
            for (i in 0 until it.length()) {
                val s = readSupplier(it.getJSONObject(i)); val key = s.name.lowercase()
                suppMap[s.id] = suppByName[key]
                    ?: db.supplierDao().insert(s.copy(id = 0)).also { nid -> suppByName[key] = nid; log.add("suppliers", nid) }
            }
        }
        // Items
        val itemByName = HashMap<String, Long>()
        db.itemDao().all().forEach { itemByName[it.name.lowercase()] = it.id }
        val itemMap = HashMap<Long, Long>()
        root.optJSONArray("items")?.let {
            for (i in 0 until it.length()) {
                val it2 = readItem(it.getJSONObject(i)); val key = it2.name.lowercase()
                itemMap[it2.id] = itemByName[key]
                    ?: db.itemDao().insert(it2.copy(id = 0)).also { nid -> itemByName[key] = nid; log.add("items", nid) }
            }
        }
        // Account groups
        val groupByName = HashMap<String, Long>()
        db.accountDao().allGroups().forEach { groupByName[it.name.lowercase()] = it.id }
        val groupMap = HashMap<Long, Long>()
        root.optJSONArray("accountGroups")?.let {
            for (i in 0 until it.length()) {
                val g = readGroup(it.getJSONObject(i)); val key = g.name.lowercase()
                groupMap[g.id] = groupByName[key] ?: db.accountDao().insertGroup(g.copy(id = 0)).also { nid -> groupByName[key] = nid }
            }
        }
        // Account heads
        val headByName = HashMap<String, Long>()
        db.accountDao().allHeads().forEach { headByName[it.name.lowercase()] = it.id }
        val headMap = HashMap<Long, Long>()
        root.optJSONArray("accountHeads")?.let {
            for (i in 0 until it.length()) {
                val h = readHead(it.getJSONObject(i)); val key = h.name.lowercase()
                headMap[h.id] = headByName[key]
                    ?: db.accountDao().insertHead(h.copy(id = 0, groupId = groupMap[h.groupId] ?: h.groupId))
                        .also { nid -> headByName[key] = nid; log.add("accountHeads", nid) }
            }
        }

        // Diary types — merged before entries, which remap their typeId onto these.
        val typeByName = HashMap<String, Long>()
        db.diaryTypeDao().all().forEach { typeByName[it.name.lowercase()] = it.id }
        val diaryTypeMap = HashMap<Long, Long>()
        root.optJSONArray("diaryTypes")?.let {
            for (i in 0 until it.length()) {
                val o = it.getJSONObject(i)
                val name = o.optString("name"); val key = name.lowercase()
                diaryTypeMap[o.optLong("id")] =
                    typeByName[key] ?: db.diaryTypeDao().insert(DiaryType(name = name)).also { nid -> typeByName[key] = nid }
            }
        }

        // Users — matched by username so the same person is not duplicated.
        val userByName = HashMap<String, Long>()
        db.userDao().all().forEach { userByName[it.username.lowercase()] = it.id }
        root.optJSONArray("users")?.let {
            for (i in 0 until it.length()) {
                val u = readUser(it.getJSONObject(i)); val key = u.username.lowercase()
                if (userByName.containsKey(key)) continue
                val nid = db.userDao().insert(u.copy(id = 0))
                userByName[key] = nid
                log.add("users", nid)
            }
        }

        // Bills + items
        val expMap = HashMap<Long, Long>()
        val billMap = HashMap<Long, Long>()
        root.optJSONArray("bills")?.let {
            for (i in 0 until it.length()) {
                val b = readBill(it.getJSONObject(i))
                val remapped = b.copy(customerId = custMap[b.customerId] ?: b.customerId)
                val existing = if (b.deviceId.isNotBlank()) db.billDao().byDeviceAndNo(b.deviceId, b.billNo) else null
                billMap[b.id] = if (existing != null) {
                    db.billDao().updateBillHeader(remapped.copy(id = existing.id))
                    db.billDao().deleteLines(existing.id)
                    existing.id
                } else {
                    db.billDao().insertBill(remapped.copy(id = 0)).also { nid -> log.add("bills", nid) }
                }
            }
        }
        root.optJSONArray("billItems")?.let {
            val lines = ArrayList<BillItem>()
            for (i in 0 until it.length()) {
                val l = readLine(it.getJSONObject(i)); val nb = billMap[l.billId] ?: continue
                lines.add(l.copy(id = 0, billId = nb))
            }
            if (lines.isNotEmpty()) db.billDao().insertLines(lines)
        }
        // Purchases + items
        val purMap = HashMap<Long, Long>()
        root.optJSONArray("purchases")?.let {
            for (i in 0 until it.length()) {
                val p = readPurchase(it.getJSONObject(i))
                val remapped = p.copy(supplierId = suppMap[p.supplierId] ?: p.supplierId)
                val existing = if (p.deviceId.isNotBlank()) db.purchaseDao().byDeviceAndNo(p.deviceId, p.purchaseNo) else null
                purMap[p.id] = if (existing != null) {
                    db.purchaseDao().updateHeader(remapped.copy(id = existing.id))
                    db.purchaseDao().deleteLines(existing.id)
                    existing.id
                } else {
                    db.purchaseDao().insertPurchase(remapped.copy(id = 0)).also { nid -> log.add("purchases", nid) }
                }
            }
        }
        root.optJSONArray("purchaseItems")?.let {
            val lines = ArrayList<PurchaseItem>()
            for (i in 0 until it.length()) {
                val l = readPLine(it.getJSONObject(i)); val np = purMap[l.purchaseId] ?: continue
                lines.add(l.copy(id = 0, purchaseId = np))
            }
            if (lines.isNotEmpty()) db.purchaseDao().insertLines(lines)
        }
        // Receipts / expenses — single-row entities, so an existing match is just updated in place.
        root.optJSONArray("receipts")?.let {
            for (i in 0 until it.length()) {
                val r = readReceipt(it.getJSONObject(i))
                val nb = if (r.billId > 0) (billMap[r.billId] ?: 0L) else 0L
                val remapped = r.copy(billId = nb)
                val existing = if (r.deviceId.isNotBlank()) db.receiptDao().byDeviceAndNo(r.deviceId, r.receiptNo) else null
                if (existing != null) db.receiptDao().update(remapped.copy(id = existing.id))
                else db.receiptDao().insert(remapped.copy(id = 0))
            }
        }
        root.optJSONArray("expenses")?.let {
            for (i in 0 until it.length()) {
                val ex = readExpense(it.getJSONObject(i))
                val np = if (ex.purchaseId > 0) (purMap[ex.purchaseId] ?: 0L) else 0L
                val remapped = ex.copy(purchaseId = np)
                val existing = if (ex.deviceId.isNotBlank()) db.expenseDao().byDeviceAndNo(ex.deviceId, ex.voucherNo) else null
                expMap[ex.id] = if (existing != null) {
                    db.expenseDao().update(remapped.copy(id = existing.id)); existing.id
                } else {
                    db.expenseDao().insert(remapped.copy(id = 0)).also { nid -> log.add("expenses", nid) }
                }
            }
        }
        // Journals
        val jMap = HashMap<Long, Long>()
        root.optJSONArray("journalEntries")?.let {
            for (i in 0 until it.length()) {
                val e = readJEntry(it.getJSONObject(i))
                jMap[e.id] = db.journalDao().insertEntry(e.copy(id = 0))
            }
        }
        root.optJSONArray("journalLines")?.let {
            val lines = ArrayList<JournalLine>()
            for (i in 0 until it.length()) {
                val l = readJLine(it.getJSONObject(i)); val ne = jMap[l.entryId] ?: continue
                lines.add(l.copy(id = 0, entryId = ne, headId = headMap[l.headId] ?: l.headId))
            }
            if (lines.isNotEmpty()) db.journalDao().insertLines(lines)
        }
        // Diary + attachments
        val diaryMap = HashMap<Long, Long>()
        root.optJSONArray("diaryEntries")?.let {
            for (i in 0 until it.length()) {
                val e = readEntry(it.getJSONObject(i))
                diaryMap[e.id] = db.diaryDao()
                    .insert(e.copy(id = 0, typeId = diaryTypeMap[e.typeId] ?: 0L))
                    .also { nid -> log.add("diaryEntries", nid) }
            }
        }
        // NOTE: diary entries themselves aren't deduped above (every merge inserts them fresh),
        // so a per-entry attachment dedup here would never trigger — the real fix is deduping
        // diaryEntries first (e.g. by title), which is a separate, riskier change.
        root.optJSONArray("diaryAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readAtt(context, it.getJSONObject(i)); val ne = diaryMap[a.entryId] ?: continue
                db.diaryDao().insertAttachment(a.copy(id = 0, entryId = ne))
            }
        }
        root.optJSONArray("diaryBlocks")?.let {
            for (i in 0 until it.length()) {
                val b = readBlock(context, it.getJSONObject(i)); val ne = diaryMap[b.entryId] ?: continue
                db.diaryDao().insertBlock(b.copy(id = 0, entryId = ne))
            }
        }
        // Item attachments — deduped per item by name, same as the bill/expense/customer
        // attachment blocks above (items themselves are already deduped by name, so unlike
        // diary entries this dedup is effective across repeated merge cycles).
        val existingItemAttNames = db.itemAttachmentDao().all().groupBy { it.itemId }
            .mapValues { (_, l) -> l.map { it.name }.toMutableSet() }
        root.optJSONArray("itemAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readItemAtt(context, it.getJSONObject(i)); val ni = itemMap[a.itemId] ?: continue
                val names = existingItemAttNames.getOrPut(ni) { mutableSetOf() }
                if (!names.add(a.name)) continue
                db.itemAttachmentDao().insert(a.copy(id = 0, itemId = ni))
            }
        }
        root.optJSONArray("itemBatches")?.let {
            for (i in 0 until it.length()) {
                val b = readBatch(it.getJSONObject(i)); val ni = itemMap[b.itemId] ?: continue
                db.itemBatchDao().insert(b.copy(id = 0, itemId = ni))
            }
        }
        root.optJSONArray("itemSizes")?.let {
            for (i in 0 until it.length()) {
                val s = readSize(it.getJSONObject(i)); val ni = itemMap[s.itemId] ?: continue
                db.itemSizeDao().insert(s.copy(id = 0, itemId = ni))
            }
        }
        // Quotations
        val quoteMap = HashMap<Long, Long>()
        root.optJSONArray("quotations")?.let {
            for (i in 0 until it.length()) {
                val q = readQuotation(it.getJSONObject(i))
                val existing = if (q.deviceId.isNotBlank()) db.quotationDao().byDeviceAndNo(q.deviceId, q.quotationNo) else null
                quoteMap[q.id] = if (existing != null) {
                    db.quotationDao().updateHeader(q.copy(id = existing.id))
                    db.quotationDao().deleteLines(existing.id)
                    existing.id
                } else db.quotationDao().insertHeader(q.copy(id = 0))
            }
        }
        root.optJSONArray("quotationItems")?.let {
            for (i in 0 until it.length()) {
                val l = readQItem(it.getJSONObject(i)); val nq = quoteMap[l.quotationId] ?: continue
                db.quotationDao().insertLines(listOf(l.copy(id = 0, quotationId = nq)))
            }
        }
        val estMap = HashMap<Long, Long>()
        root.optJSONArray("estimates")?.let {
            for (i in 0 until it.length()) {
                val e = readEstimate(it.getJSONObject(i))
                val existing = if (e.deviceId.isNotBlank()) db.estimateDao().byDeviceAndNo(e.deviceId, e.estimateNo) else null
                estMap[e.id] = if (existing != null) {
                    db.estimateDao().updateHeader(e.copy(id = existing.id))
                    db.estimateDao().deleteLines(existing.id)
                    existing.id
                } else db.estimateDao().insertHeader(e.copy(id = 0))
            }
        }
        root.optJSONArray("estimateItems")?.let {
            for (i in 0 until it.length()) {
                val l = readEItem(it.getJSONObject(i)); val ne = estMap[l.estimateId] ?: continue
                db.estimateDao().insertLines(listOf(l.copy(id = 0, estimateId = ne)))
            }
        }
        // Sales returns
        val srMap = HashMap<Long, Long>()
        root.optJSONArray("salesReturns")?.let {
            for (i in 0 until it.length()) {
                val r = readSalesReturn(it.getJSONObject(i)); srMap[r.id] = db.salesReturnDao().insertHeader(r.copy(id = 0))
            }
        }
        root.optJSONArray("salesReturnItems")?.let {
            for (i in 0 until it.length()) {
                val l = readSRItem(it.getJSONObject(i)); val nr = srMap[l.returnId] ?: continue
                db.salesReturnDao().insertLines(listOf(l.copy(id = 0, returnId = nr)))
            }
        }
        // Purchase returns
        val prMap = HashMap<Long, Long>()
        root.optJSONArray("purchaseReturns")?.let {
            for (i in 0 until it.length()) {
                val r = readPurchaseReturn(it.getJSONObject(i)); prMap[r.id] = db.purchaseReturnDao().insertHeader(r.copy(id = 0))
            }
        }
        root.optJSONArray("purchaseReturnItems")?.let {
            for (i in 0 until it.length()) {
                val l = readPRItem(it.getJSONObject(i)); val nr = prMap[l.returnId] ?: continue
                db.purchaseReturnDao().insertLines(listOf(l.copy(id = 0, returnId = nr)))
            }
        }
        // Purchase quotations (LPO)
        val lpoMap = HashMap<Long, Long>()
        root.optJSONArray("purchaseQuotations")?.let {
            for (i in 0 until it.length()) {
                val r = readLpo(it.getJSONObject(i)); lpoMap[r.id] = db.purchaseQuotationDao().insertHeader(r.copy(id = 0))
            }
        }
        root.optJSONArray("purchaseQuotationItems")?.let {
            for (i in 0 until it.length()) {
                val l = readLpoItem(it.getJSONObject(i)); val nl = lpoMap[l.lpoId] ?: continue
                db.purchaseQuotationDao().insertLines(listOf(l.copy(id = 0, lpoId = nl)))
            }
        }
        // Hire invoices
        val hireMap = HashMap<Long, Long>()
        root.optJSONArray("hireInvoices")?.let {
            for (i in 0 until it.length()) {
                val h = readHire(it.getJSONObject(i)); hireMap[h.id] = db.hireInvoiceDao().insertHeader(h.copy(id = 0))
            }
        }
        root.optJSONArray("hireInvoiceItems")?.let {
            for (i in 0 until it.length()) {
                val l = readHireItem(it.getJSONObject(i)); val nh = hireMap[l.hireId] ?: continue
                db.hireInvoiceDao().insertLines(listOf(l.copy(id = 0, hireId = nh)))
            }
        }
        // Hire returns
        val hireRetMap = HashMap<Long, Long>()
        root.optJSONArray("hireReturns")?.let {
            for (i in 0 until it.length()) {
                val r = readHireRet(it.getJSONObject(i))
                hireRetMap[r.id] = db.hireReturnDao().insertHeader(r.copy(id = 0, hireId = hireMap[r.hireId] ?: r.hireId))
            }
        }
        root.optJSONArray("hireReturnItems")?.let {
            for (i in 0 until it.length()) {
                val l = readHireRetItem(it.getJSONObject(i)); val nr = hireRetMap[l.returnId] ?: continue
                db.hireReturnDao().insertLines(listOf(l.copy(id = 0, returnId = nr)))
            }
        }
        // Lab tests + evaluations
        val labTestMap = HashMap<Long, Long>()
        root.optJSONArray("labTests")?.let {
            for (i in 0 until it.length()) {
                val t = readLabTest(it.getJSONObject(i)); labTestMap[t.id] = db.labTestDao().insertTest(t.copy(id = 0))
            }
        }
        root.optJSONArray("labEvaluations")?.let {
            for (i in 0 until it.length()) {
                val e = readLabEval(it.getJSONObject(i)); val nt = labTestMap[e.testId] ?: continue
                db.labTestDao().insertEvaluations(listOf(e.copy(id = 0, testId = nt)))
            }
        }
        // Patients
        val patientMap = HashMap<Long, Long>()
        root.optJSONArray("patients")?.let {
            for (i in 0 until it.length()) {
                val p = readPatient(it.getJSONObject(i)); patientMap[p.id] = db.patientDao().insert(p.copy(id = 0))
            }
        }
        // Lab bills + tests + results
        val labBillMap = HashMap<Long, Long>()
        root.optJSONArray("labBills")?.let {
            for (i in 0 until it.length()) {
                val b = readLabBill(it.getJSONObject(i))
                labBillMap[b.id] = db.labBillDao().insertBill(b.copy(id = 0, patientId = patientMap[b.patientId] ?: b.patientId))
            }
        }
        root.optJSONArray("labBillTests")?.let {
            for (i in 0 until it.length()) {
                val t = readLabBillTest(it.getJSONObject(i)); val nb = labBillMap[t.billId] ?: continue
                db.labBillDao().insertTests(listOf(t.copy(id = 0, billId = nb, testId = labTestMap[t.testId] ?: t.testId)))
            }
        }
        root.optJSONArray("labResults")?.let {
            for (i in 0 until it.length()) {
                val r = readLabResult(it.getJSONObject(i)); val nb = labBillMap[r.billId] ?: continue
                db.labBillDao().insertResults(listOf(r.copy(id = 0, billId = nb, testId = labTestMap[r.testId] ?: r.testId)))
            }
        }
        // Lab masters (deduped by name)
        root.optJSONArray("labGroups")?.let { for (i in 0 until it.length()) db.labMasterDao().insertGroup(LabGroup(name = it.getJSONObject(i).optString("name"))) }
        root.optJSONArray("labEvalMasters")?.let { for (i in 0 until it.length()) db.labMasterDao().insertEval(readLabEvalMaster(it.getJSONObject(i)).copy(id = 0)) }
        root.optJSONArray("labHeadings")?.let { for (i in 0 until it.length()) db.labMasterDao().insertHeading(LabHeading(name = it.getJSONObject(i).optString("name"))) }
        root.optJSONArray("labReceipts")?.let {
            for (i in 0 until it.length()) {
                val r = readLabReceipt(it.getJSONObject(i)); val nb = labBillMap[r.labBillId] ?: continue
                db.labReceiptDao().insert(r.copy(id = 0, labBillId = nb))
            }
        }
        root.optJSONArray("labDoctors")?.let { for (i in 0 until it.length()) db.labMasterDao().insertDoctor(LabDoctor(name = it.getJSONObject(i).optString("name"))) }
        // Material out
        val matOutMap = HashMap<Long, Long>()
        root.optJSONArray("materialOuts")?.let {
            for (i in 0 until it.length()) { val m = readMatOut(it.getJSONObject(i)); matOutMap[m.id] = db.materialOutDao().insertHeader(m.copy(id = 0)) }
        }
        root.optJSONArray("materialOutItems")?.let {
            for (i in 0 until it.length()) { val l = readMatOutItem(it.getJSONObject(i)); val nm = matOutMap[l.outId] ?: continue; db.materialOutDao().insertLines(listOf(l.copy(id = 0, outId = nm))) }
        }
        // Bill attachments — deduped per bill by name, so repeated cloud-sync pull/merge cycles
        // (which re-send the same attachment records every time, since push never sends the
        // files themselves) don't keep re-inserting the same attachment forever.
        val existingBillAttNames = db.billAttachmentDao().all().groupBy { it.billId }
            .mapValues { (_, l) -> l.map { it.name }.toMutableSet() }
        root.optJSONArray("billAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readBillAtt(context, it.getJSONObject(i)); val nb = billMap[a.billId] ?: continue
                val names = existingBillAttNames.getOrPut(nb) { mutableSetOf() }
                if (!names.add(a.name)) continue
                db.billAttachmentDao().insert(a.copy(id = 0, billId = nb))
            }
        }
        // Payment attachments (voice / photo / file) — same per-parent dedup as bill attachments.
        val existingExpenseAttNames = db.expenseAttachmentDao().all().groupBy { it.expenseId }
            .mapValues { (_, l) -> l.map { it.name }.toMutableSet() }
        root.optJSONArray("expenseAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readExpenseAtt(context, it.getJSONObject(i)); val ne = expMap[a.expenseId] ?: continue
                val names = existingExpenseAttNames.getOrPut(ne) { mutableSetOf() }
                if (!names.add(a.name)) continue
                db.expenseAttachmentDao().insert(a.copy(id = 0, expenseId = ne))
            }
        }
        // Customer documents follow whichever customer row they ended up attached to — same
        // per-parent dedup as bill attachments.
        val existingCustAttNames = db.customerAttachmentDao().all().groupBy { it.customerId }
            .mapValues { (_, l) -> l.map { it.name }.toMutableSet() }
        root.optJSONArray("customerAttachments")?.let {
            for (i in 0 until it.length()) {
                val a2 = readCustAtt(context, it.getJSONObject(i))
                val nc = custMap[a2.customerId] ?: continue
                val names = existingCustAttNames.getOrPut(nc) { mutableSetOf() }
                if (!names.add(a2.name)) continue
                db.customerAttachmentDao().insert(a2.copy(id = 0, customerId = nc))
            }
        }
        // Purchase quotations, with their lines following the header's new id.
        val pQuoteMap = HashMap<Long, Long>()
        root.optJSONArray("purchaseQuotes")?.let {
            for (i in 0 until it.length()) {
                val q = readPQuote(it.getJSONObject(i))
                pQuoteMap[q.id] = db.purchaseQuoteDao().insertHeader(q.copy(id = 0))
                    .also { nid -> log.add("purchaseQuotes", nid) }
            }
        }
        root.optJSONArray("purchaseQuoteItems")?.let {
            for (i in 0 until it.length()) {
                val l = readPQuoteItem(it.getJSONObject(i))
                val nq = pQuoteMap[l.quoteId] ?: continue
                db.purchaseQuoteDao().insertLines(listOf(l.copy(id = 0, quoteId = nq)))
            }
        }
        // Saved calculator tapes — standalone, so they merge by simply being added.
        root.optJSONArray("savedCalcs")?.let {
            for (i in 0 until it.length()) {
                db.savedCalcDao().insert(readSavedCalc(it.getJSONObject(i)).copy(id = 0))
                    .also { nid -> log.add("savedCalcs", nid) }
            }
        }
        // Customer orders, lines and attachments following the header's new id.
        val orderMap = HashMap<Long, Long>()
        root.optJSONArray("custOrders")?.let {
            for (i in 0 until it.length()) {
                val o = readOrder(it.getJSONObject(i))
                val existing = if (o.deviceId.isNotBlank()) db.custOrderDao().byDeviceAndNo(o.deviceId, o.orderNo) else null
                orderMap[o.id] = if (existing != null) {
                    db.custOrderDao().updateHeader(o.copy(id = existing.id))
                    db.custOrderDao().deleteLines(existing.id)
                    db.custOrderDao().deleteAttachments(existing.id)
                    existing.id
                } else {
                    db.custOrderDao().insertHeader(o.copy(id = 0)).also { nid -> log.add("custOrders", nid) }
                }
            }
        }
        root.optJSONArray("custOrderItems")?.let {
            for (i in 0 until it.length()) {
                val l = readOrderItem(it.getJSONObject(i)); val no = orderMap[l.orderId] ?: continue
                db.custOrderDao().insertLines(listOf(l.copy(id = 0, orderId = no)))
            }
        }
        root.optJSONArray("custOrderAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readOrderAtt(context, it.getJSONObject(i)); val no = orderMap[a.orderId] ?: continue
                db.custOrderDao().insertAttachment(a.copy(id = 0, orderId = no))
            }
        }
        // Salesman map — keyed by deviceId, so merging is just an upsert (same device wins with
        // whatever name the merged-in backup had, never duplicated).
        root.optJSONArray("salesmanMap")?.let {
            for (i in 0 until it.length()) {
                val o = it.getJSONObject(i)
                db.salesmanMapDao().upsert(SalesmanMap(o.optString("deviceId"), o.optString("name")))
            }
        }
        // Material receipts + lines
        val matRecMap = HashMap<Long, Long>()
        root.optJSONArray("materialReceipts")?.let {
            for (i in 0 until it.length()) {
                val m = readMatRec(it.getJSONObject(i))
                matRecMap[m.id] = db.materialReceiptDao()
                    .insertHeader(m.copy(id = 0, supplierId = suppMap[m.supplierId] ?: m.supplierId))
                    .also { nid -> log.add("materialReceipts", nid) }
            }
        }
        root.optJSONArray("materialReceiptItems")?.let {
            for (i in 0 until it.length()) {
                val l = readMatRecItem(it.getJSONObject(i)); val nm = matRecMap[l.receiptId] ?: continue
                db.materialReceiptDao().insertLines(listOf(l.copy(id = 0, receiptId = nm, itemId = itemMap[l.itemId] ?: l.itemId)))
            }
        }
        // Production procedures (recipes) + their material lines, following the header's new id.
        val procedureMap = HashMap<Long, Long>()
        root.optJSONArray("productionProcedures")?.let {
            for (i in 0 until it.length()) {
                val p = readProcedure(it.getJSONObject(i))
                procedureMap[p.id] = db.productionDao()
                    .insertProcedureHeader(p.copy(id = 0, producedItemId = itemMap[p.producedItemId] ?: p.producedItemId))
                    .also { nid -> log.add("productionProcedures", nid) }
            }
        }
        root.optJSONArray("productionProcedureMaterials")?.let {
            for (i in 0 until it.length()) {
                val m = readProcedureMaterial(it.getJSONObject(i)); val np = procedureMap[m.procedureId] ?: continue
                db.productionDao().insertProcedureMaterials(listOf(m.copy(id = 0, procedureId = np, itemId = itemMap[m.itemId] ?: m.itemId)))
            }
        }
        // Production runs — follow the procedure's new id, and the linked MaterialOut/MaterialReceipt's
        // new ids (0 if either link can't be resolved, same "no link" convention as a fresh run).
        root.optJSONArray("productionRuns")?.let {
            for (i in 0 until it.length()) {
                val r = readProductionRun(it.getJSONObject(i))
                db.productionDao().insertRun(
                    r.copy(
                        id = 0,
                        procedureId = procedureMap[r.procedureId] ?: r.procedureId,
                        producedItemId = itemMap[r.producedItemId] ?: r.producedItemId,
                        materialOutId = matOutMap[r.materialOutId] ?: 0,
                        materialReceiptId = matRecMap[r.materialReceiptId] ?: 0
                    )
                ).also { nid -> log.add("productionRuns", nid) }
            }
        }
        // Item bundles + their component lines, following the header's new id.
        val bundleMap = HashMap<Long, Long>()
        root.optJSONArray("itemBundles")?.let {
            for (i in 0 until it.length()) {
                val b = readBundle(it.getJSONObject(i))
                bundleMap[b.id] = db.itemBundleDao().insertHeader(b.copy(id = 0)).also { nid -> log.add("itemBundles", nid) }
            }
        }
        root.optJSONArray("itemBundleComponents")?.let {
            for (i in 0 until it.length()) {
                val c = readBundleComponent(it.getJSONObject(i)); val nb = bundleMap[c.bundleId] ?: continue
                db.itemBundleDao().insertComponents(listOf(c.copy(id = 0, bundleId = nb, itemId = itemMap[c.itemId] ?: c.itemId)))
            }
        }

        return log.build()
    }

    // ---- serialisers ----
    private fun custJson(c: Customer) = JSONObject().put("id", c.id).put("name", c.name)
        .put("phone", c.phone).put("address", c.address).put("gstin", c.gstin).put("isDefault", c.isDefault).put("customerType", c.customerType)
        .put("state", c.state)

    private fun itemJson(i: Item) = JSONObject().put("id", i.id).put("name", i.name)
        .put("price", i.price).put("taxPercent", i.taxPercent).put("barcode", i.barcode).put("hsn", i.hsn)
        .put("category", i.category).put("openingStock", i.openingStock).put("unit", i.unit)
        .put("secondaryUnit", i.secondaryUnit).put("conversionFactor", i.conversionFactor)
        .put("storeLocation", i.storeLocation).put("chemicalContent", i.chemicalContent)

    private fun billJson(b: Bill) = JSONObject().put("id", b.id).put("billNo", b.billNo)
        .put("dateMillis", b.dateMillis).put("customerId", b.customerId).put("customerName", b.customerName)
        .put("paymentMethod", b.paymentMethod).put("subTotal", b.subTotal).put("taxTotal", b.taxTotal)
        .put("additionalCharge", b.additionalCharge).put("discount", b.discount)
        .put("grandTotal", b.grandTotal).put("paidAmount", b.paidAmount).put("cessTotal", b.cessTotal)
        .put("customerGstin", b.customerGstin).put("customerState", b.customerState).put("source", b.source).put("remarks", b.remarks)
        .put("deviceId", b.deviceId).put("isNoTax", b.isNoTax)

    private fun lineJson(l: BillItem) = JSONObject().put("id", l.id).put("billId", l.billId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("cessPercent", l.cessPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)
        .put("unit", l.unit).put("primaryQty", l.primaryQty)

    private fun receiptJson(r: Receipt) = JSONObject().put("id", r.id).put("receiptNo", r.receiptNo)
        .put("billId", r.billId).put("billNo", r.billNo).put("customerName", r.customerName)
        .put("dateMillis", r.dateMillis).put("amount", r.amount).put("paymentMode", r.paymentMode)
        .put("payFrom", r.payFrom).put("source", r.source).put("deviceId", r.deviceId)

    private fun expenseJson(e: Expense) = JSONObject().put("id", e.id).put("voucherNo", e.voucherNo)
        .put("dateMillis", e.dateMillis).put("description", e.description).put("amount", e.amount)
        .put("paymentMode", e.paymentMode).put("purchaseId", e.purchaseId).put("purchaseNo", e.purchaseNo)
        .put("payTo", e.payTo).put("source", e.source).put("deviceId", e.deviceId)

    private fun userJson(u: User) = JSONObject().put("id", u.id).put("username", u.username)
        .put("passwordHash", u.passwordHash).put("role", u.role.name)
        .put("canCreateInvoice", u.canCreateInvoice).put("canEditInvoice", u.canEditInvoice)
        .put("canDeleteInvoice", u.canDeleteInvoice).put("canViewInvoice", u.canViewInvoice)
        .put("canCreateReceipt", u.canCreateReceipt).put("canEditReceipt", u.canEditReceipt)
        .put("canDeleteReceipt", u.canDeleteReceipt).put("canViewReceipt", u.canViewReceipt)
        .put("canCreatePayment", u.canCreatePayment).put("canEditPayment", u.canEditPayment)
        .put("canDeletePayment", u.canDeletePayment).put("canViewPayment", u.canViewPayment)
        .put("canViewCashbook", u.canViewCashbook)
        .put("canExport", u.canExport).put("canImport", u.canImport)
        .put("canManageUsers", u.canManageUsers).put("active", u.active)

    private fun entryJson(e: DiaryEntry) = JSONObject().put("id", e.id).put("title", e.title)
        .put("remarks", e.remarks).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt)
        .put("typeId", e.typeId)
        .put("reminderEnabled", e.reminderEnabled).put("reminderAt", e.reminderAt)
        .put("reminderDaily", e.reminderDaily)
        .put("titleSize", e.titleSize).put("titleColor", e.titleColor)
        .put("titleBold", e.titleBold).put("titleItalic", e.titleItalic)
        .put("bodySize", e.bodySize).put("bodyColor", e.bodyColor)
        .put("bodyBold", e.bodyBold).put("bodyItalic", e.bodyItalic)

    private fun supplierJson(s: Supplier) = JSONObject().put("id", s.id).put("name", s.name)
        .put("phone", s.phone).put("address", s.address).put("gstin", s.gstin).put("isDefault", s.isDefault)
        .put("state", s.state)

    private fun purchaseJson(p: Purchase) = JSONObject().put("id", p.id).put("purchaseNo", p.purchaseNo)
        .put("dateMillis", p.dateMillis).put("supplierId", p.supplierId).put("supplierName", p.supplierName)
        .put("paymentMethod", p.paymentMethod).put("subTotal", p.subTotal).put("taxTotal", p.taxTotal)
        .put("additionalCharge", p.additionalCharge).put("discount", p.discount).put("grandTotal", p.grandTotal)
        .put("paidAmount", p.paidAmount).put("cessTotal", p.cessTotal)
        .put("supplierGstin", p.supplierGstin).put("supplierState", p.supplierState)
        .put("source", p.source)
        .put("deviceId", p.deviceId)

    private fun pLineJson(l: PurchaseItem) = JSONObject().put("id", l.id).put("purchaseId", l.purchaseId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("cessPercent", l.cessPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)
        .put("unit", l.unit).put("primaryQty", l.primaryQty)

    private fun groupJson(g: AccountGroup) = JSONObject().put("id", g.id).put("name", g.name)
        .put("nature", g.nature.name).put("isSystem", g.isSystem)

    private fun headJson(h: AccountHead) = JSONObject().put("id", h.id).put("name", h.name)
        .put("groupId", h.groupId).put("openingBalance", h.openingBalance)
        .put("openingIsDebit", h.openingIsDebit).put("isSystem", h.isSystem)

    private fun jEntryJson(e: JournalEntry) = JSONObject().put("id", e.id).put("voucherNo", e.voucherNo)
        .put("dateMillis", e.dateMillis).put("narration", e.narration)
        .put("cashMode", e.cashMode).put("cashIsIn", e.cashIsIn).put("cashAmount", e.cashAmount)
        .put("source", e.source).put("voucherType", e.voucherType)

    private fun jLineJson(l: JournalLine) = JSONObject().put("id", l.id).put("entryId", l.entryId)
        .put("headId", l.headId).put("headName", l.headName).put("amount", l.amount).put("isDebit", l.isDebit)

    private fun batchJson(b: ItemBatch) = JSONObject().put("id", b.id).put("itemId", b.itemId)
        .put("batchNo", b.batchNo).put("expiryMillis", b.expiryMillis).put("quantity", b.quantity)

    private fun readBatch(o: JSONObject) = ItemBatch(
        id = o.optLong("id"), itemId = o.optLong("itemId"), batchNo = o.optString("batchNo"),
        expiryMillis = o.optLong("expiryMillis"), quantity = o.optDouble("quantity", 0.0)
    )

    private fun sizeJson(s: ItemSize) = JSONObject().put("id", s.id).put("itemId", s.itemId)
        .put("name", s.name).put("price", s.price)

    private fun custAttJson(a: CustomerAttachment) = JSONObject().put("id", a.id)
        .put("customerId", a.customerId).put("file", File(a.path).name)
        .put("name", a.name).put("mime", a.mime)

    private fun readCustAtt(context: Context, o: JSONObject) = CustomerAttachment(
        id = o.optLong("id"), customerId = o.optLong("customerId"),
        path = File(CustomerAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime")
    )

    private fun pQuoteJson(q: PurchaseQuote) = JSONObject().put("id", q.id).put("quoteNo", q.quoteNo)
        .put("dateMillis", q.dateMillis).put("supplierId", q.supplierId).put("supplierName", q.supplierName)
        .put("subTotal", q.subTotal).put("taxTotal", q.taxTotal).put("additionalCharge", q.additionalCharge)
        .put("discount", q.discount).put("grandTotal", q.grandTotal).put("remarks", q.remarks)

    private fun readPQuote(o: JSONObject) = PurchaseQuote(
        id = o.optLong("id"), quoteNo = o.optString("quoteNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun pQuoteItemJson(l: PurchaseQuoteItem) = JSONObject().put("id", l.id)
        .put("quoteId", l.quoteId).put("itemId", l.itemId).put("name", l.name).put("qty", l.qty)
        .put("price", l.price).put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal)
        .put("unit", l.unit)

    private fun readPQuoteItem(o: JSONObject) = PurchaseQuoteItem(
        id = o.optLong("id"), quoteId = o.optLong("quoteId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        unit = o.optString("unit")
    )

    private fun savedCalcJson(c: SavedCalc) = JSONObject().put("id", c.id)
        .put("dateMillis", c.dateMillis).put("amounts", c.amounts)
        .put("total", c.total).put("title", c.title)
        .put("customerId", c.customerId).put("customerName", c.customerName)
        .put("narration", c.narration)

    private fun readSavedCalc(o: JSONObject) = SavedCalc(
        id = o.optLong("id"), dateMillis = o.optLong("dateMillis"),
        amounts = o.optString("amounts"), total = o.optDouble("total", 0.0),
        title = o.optString("title"),
        customerId = o.optLong("customerId"),
        customerName = o.optString("customerName", SavedCalc.DEFAULT_CUSTOMER),
        narration = o.optString("narration")
    )

    private fun orderJson(o: CustOrder) = JSONObject().put("id", o.id).put("orderNo", o.orderNo)
        .put("dateMillis", o.dateMillis).put("customerId", o.customerId).put("customerName", o.customerName)
        .put("remark", o.remark).put("latitude", o.latitude).put("longitude", o.longitude).put("grandTotal", o.grandTotal)
        .put("deviceId", o.deviceId)
    private fun readOrder(o: JSONObject) = CustOrder(
        id = o.optLong("id"), orderNo = o.optString("orderNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        remark = o.optString("remark"), latitude = o.optDouble("latitude", 0.0), longitude = o.optDouble("longitude", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), deviceId = o.optString("deviceId")
    )
    private fun orderItemJson(l: CustOrderItem) = JSONObject().put("id", l.id).put("orderId", l.orderId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("lineTotal", l.lineTotal).put("unit", l.unit).put("status", l.status)
    private fun readOrderItem(o: JSONObject) = CustOrderItem(
        id = o.optLong("id"), orderId = o.optLong("orderId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        lineTotal = o.optDouble("lineTotal", 0.0), unit = o.optString("unit"),
        status = o.optString("status", "PENDING")
    )
    private fun orderAttJson(a: CustOrderAttachment) = JSONObject().put("id", a.id).put("orderId", a.orderId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime)
    private fun readOrderAtt(context: Context, o: JSONObject) = CustOrderAttachment(
        id = o.optLong("id"), orderId = o.optLong("orderId"),
        path = File(OrderAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime")
    )

    private fun quotationJson(q: Quotation) = JSONObject().put("id", q.id).put("quotationNo", q.quotationNo)
        .put("dateMillis", q.dateMillis).put("customerId", q.customerId).put("customerName", q.customerName)
        .put("subTotal", q.subTotal).put("taxTotal", q.taxTotal).put("additionalCharge", q.additionalCharge)
        .put("discount", q.discount).put("grandTotal", q.grandTotal).put("remarks", q.remarks)
        .put("terms", q.terms).put("deviceId", q.deviceId)

    private fun readQuotation(o: JSONObject) = Quotation(
        id = o.optLong("id"), quotationNo = o.optString("quotationNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks"),
        terms = o.optString("terms"), deviceId = o.optString("deviceId")
    )

    private fun estimateJson(e: Estimate) = JSONObject().put("id", e.id).put("estimateNo", e.estimateNo)
        .put("dateMillis", e.dateMillis).put("customerId", e.customerId).put("customerName", e.customerName)
        .put("paymentMethod", e.paymentMethod)
        .put("subTotal", e.subTotal).put("taxTotal", e.taxTotal).put("additionalCharge", e.additionalCharge)
        .put("discount", e.discount).put("grandTotal", e.grandTotal)
        .put("customerGstin", e.customerGstin).put("remarks", e.remarks).put("deviceId", e.deviceId)

    private fun readEstimate(o: JSONObject) = Estimate(
        id = o.optLong("id"), estimateNo = o.optString("estimateNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        paymentMethod = o.optString("paymentMethod"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0),
        customerGstin = o.optString("customerGstin"), remarks = o.optString("remarks"),
        deviceId = o.optString("deviceId")
    )

    private fun eItemJson(l: EstimateItem) = JSONObject().put("id", l.id).put("estimateId", l.estimateId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price).put("taxPercent", l.taxPercent)
        .put("lineTotal", l.lineTotal).put("unit", l.unit)

    private fun readEItem(o: JSONObject) = EstimateItem(
        id = o.optLong("id"), estimateId = o.optLong("estimateId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        unit = o.optString("unit")
    )

    private fun qItemJson(l: QuotationItem) = JSONObject().put("id", l.id).put("quotationId", l.quotationId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price).put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("unit", l.unit).put("note", l.note)

    private fun readQItem(o: JSONObject) = QuotationItem(
        id = o.optLong("id"), quotationId = o.optLong("quotationId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        unit = o.optString("unit"), note = o.optString("note")
    )

    private fun salesReturnJson(r: SalesReturn) = JSONObject().put("id", r.id).put("returnNo", r.returnNo)
        .put("dateMillis", r.dateMillis).put("customerId", r.customerId).put("customerName", r.customerName)
        .put("billNo", r.billNo).put("subTotal", r.subTotal).put("taxTotal", r.taxTotal)
        .put("additionalCharge", r.additionalCharge).put("discount", r.discount).put("grandTotal", r.grandTotal)
        .put("remarks", r.remarks)

    private fun readSalesReturn(o: JSONObject) = SalesReturn(
        id = o.optLong("id"), returnNo = o.optString("returnNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"), billNo = o.optString("billNo"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun srItemJson(l: SalesReturnItem) = JSONObject().put("id", l.id).put("returnId", l.returnId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)
        .put("unit", l.unit).put("primaryQty", l.primaryQty)

    private fun readSRItem(o: JSONObject) = SalesReturnItem(
        id = o.optLong("id"), returnId = o.optLong("returnId"), itemId = o.optLong("itemId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0), batchNo = o.optString("batchNo"),
        unit = o.optString("unit"), primaryQty = o.optDouble("primaryQty", 0.0)
    )

    private fun purchaseReturnJson(r: PurchaseReturn) = JSONObject().put("id", r.id).put("returnNo", r.returnNo)
        .put("dateMillis", r.dateMillis).put("supplierId", r.supplierId).put("supplierName", r.supplierName)
        .put("billNo", r.billNo).put("subTotal", r.subTotal).put("taxTotal", r.taxTotal)
        .put("additionalCharge", r.additionalCharge).put("discount", r.discount).put("grandTotal", r.grandTotal)
        .put("remarks", r.remarks)

    private fun readPurchaseReturn(o: JSONObject) = PurchaseReturn(
        id = o.optLong("id"), returnNo = o.optString("returnNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"), billNo = o.optString("billNo"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun prItemJson(l: PurchaseReturnItem) = JSONObject().put("id", l.id).put("returnId", l.returnId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)
        .put("unit", l.unit).put("primaryQty", l.primaryQty)

    private fun readPRItem(o: JSONObject) = PurchaseReturnItem(
        id = o.optLong("id"), returnId = o.optLong("returnId"), itemId = o.optLong("itemId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0), batchNo = o.optString("batchNo"),
        unit = o.optString("unit"), primaryQty = o.optDouble("primaryQty", 0.0)
    )

    private fun lpoJson(r: PurchaseQuotation) = JSONObject().put("id", r.id).put("lpoNo", r.lpoNo)
        .put("dateMillis", r.dateMillis).put("supplierId", r.supplierId).put("supplierName", r.supplierName)
        .put("subTotal", r.subTotal).put("taxTotal", r.taxTotal)
        .put("additionalCharge", r.additionalCharge).put("discount", r.discount).put("grandTotal", r.grandTotal)
        .put("remarks", r.remarks)

    private fun readLpo(o: JSONObject) = PurchaseQuotation(
        id = o.optLong("id"), lpoNo = o.optString("lpoNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun lpoItemJson(l: PurchaseQuotationItem) = JSONObject().put("id", l.id).put("lpoId", l.lpoId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("unit", l.unit)

    private fun readLpoItem(o: JSONObject) = PurchaseQuotationItem(
        id = o.optLong("id"), lpoId = o.optLong("lpoId"), itemId = o.optLong("itemId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        unit = o.optString("unit")
    )

    private fun hireJson(h: HireInvoice) = JSONObject().put("id", h.id).put("hireNo", h.hireNo)
        .put("dateMillis", h.dateMillis).put("startDateMillis", h.startDateMillis).put("endDateMillis", h.endDateMillis)
        .put("customerId", h.customerId).put("customerName", h.customerName)
        .put("subTotal", h.subTotal).put("taxTotal", h.taxTotal)
        .put("additionalCharge", h.additionalCharge).put("discount", h.discount).put("grandTotal", h.grandTotal)
        .put("remarks", h.remarks)

    private fun readHire(o: JSONObject) = HireInvoice(
        id = o.optLong("id"), hireNo = o.optString("hireNo"), dateMillis = o.optLong("dateMillis"),
        startDateMillis = o.optLong("startDateMillis"), endDateMillis = o.optLong("endDateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun hireItemJson(l: HireInvoiceItem) = JSONObject().put("id", l.id).put("hireId", l.hireId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("unit", l.unit)

    private fun readHireItem(o: JSONObject) = HireInvoiceItem(
        id = o.optLong("id"), hireId = o.optLong("hireId"), itemId = o.optLong("itemId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0), unit = o.optString("unit")
    )

    private fun hireRetJson(r: HireReturn) = JSONObject().put("id", r.id).put("returnNo", r.returnNo)
        .put("dateMillis", r.dateMillis).put("hireId", r.hireId).put("hireNo", r.hireNo)
        .put("customerId", r.customerId).put("customerName", r.customerName).put("remarks", r.remarks)

    private fun readHireRet(o: JSONObject) = HireReturn(
        id = o.optLong("id"), returnNo = o.optString("returnNo"), dateMillis = o.optLong("dateMillis"),
        hireId = o.optLong("hireId"), hireNo = o.optString("hireNo"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"), remarks = o.optString("remarks")
    )

    private fun hireRetItemJson(l: HireReturnItem) = JSONObject().put("id", l.id).put("returnId", l.returnId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("unit", l.unit)

    private fun readHireRetItem(o: JSONObject) = HireReturnItem(
        id = o.optLong("id"), returnId = o.optLong("returnId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), unit = o.optString("unit")
    )

    private fun labTestJson(t: LabTest) = JSONObject().put("id", t.id).put("name", t.name)
        .put("price", t.price).put("sampleType", t.sampleType).put("category", t.category)
    private fun readLabTest(o: JSONObject) = LabTest(
        id = o.optLong("id"), name = o.optString("name"), price = o.optDouble("price", 0.0),
        sampleType = o.optString("sampleType"), category = o.optString("category")
    )

    private fun labEvalJson(e: LabEvaluation) = JSONObject().put("id", e.id).put("testId", e.testId)
        .put("name", e.name).put("unit", e.unit).put("normalValue", e.normalValue)
        .put("groupName", e.groupName).put("sortOrder", e.sortOrder).put("isHeading", e.isHeading).put("isPageBreak", e.isPageBreak)
    private fun readLabEval(o: JSONObject) = LabEvaluation(
        id = o.optLong("id"), testId = o.optLong("testId"), name = o.optString("name"),
        unit = o.optString("unit"), normalValue = o.optString("normalValue"),
        groupName = o.optString("groupName"), sortOrder = o.optInt("sortOrder", 0),
        isHeading = o.optBoolean("isHeading", false), isPageBreak = o.optBoolean("isPageBreak", false)
    )

    private fun labEvalMasterJson(e: LabEvalMaster) = JSONObject().put("id", e.id).put("name", e.name)
        .put("unit", e.unit).put("normalValue", e.normalValue).put("groupName", e.groupName)
    private fun readLabEvalMaster(o: JSONObject) = LabEvalMaster(
        id = o.optLong("id"), name = o.optString("name"), unit = o.optString("unit"),
        normalValue = o.optString("normalValue"), groupName = o.optString("groupName")
    )

    private fun patientJson(p: Patient) = JSONObject().put("id", p.id).put("name", p.name)
        .put("age", p.age).put("gender", p.gender).put("phone", p.phone)
        .put("address", p.address).put("referredBy", p.referredBy)
    private fun readPatient(o: JSONObject) = Patient(
        id = o.optLong("id"), name = o.optString("name"), age = o.optString("age"),
        gender = o.optString("gender"), phone = o.optString("phone"),
        address = o.optString("address"), referredBy = o.optString("referredBy")
    )

    private fun labBillJson(b: LabBill) = JSONObject().put("id", b.id).put("billNo", b.billNo)
        .put("dateMillis", b.dateMillis).put("patientId", b.patientId).put("patientName", b.patientName)
        .put("patientPhone", b.patientPhone)
        .put("age", b.age).put("gender", b.gender).put("referredBy", b.referredBy)
        .put("subTotal", b.subTotal).put("discount", b.discount).put("grandTotal", b.grandTotal)
        .put("remarks", b.remarks).put("resultEntered", b.resultEntered).put("resultDateMillis", b.resultDateMillis)
        .put("paymentMethod", b.paymentMethod).put("paidAmount", b.paidAmount)
    private fun readLabBill(o: JSONObject) = LabBill(
        id = o.optLong("id"), billNo = o.optString("billNo"), dateMillis = o.optLong("dateMillis"),
        patientId = o.optLong("patientId"), patientName = o.optString("patientName"),
        patientPhone = o.optString("patientPhone"),
        age = o.optString("age"), gender = o.optString("gender"), referredBy = o.optString("referredBy"),
        subTotal = o.optDouble("subTotal", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks"),
        resultEntered = o.optBoolean("resultEntered", false), resultDateMillis = o.optLong("resultDateMillis"),
        paymentMethod = o.optString("paymentMethod", "Cash"), paidAmount = o.optDouble("paidAmount", 0.0)
    )

    private fun labBillTestJson(t: LabBillTest) = JSONObject().put("id", t.id).put("billId", t.billId)
        .put("testId", t.testId).put("testName", t.testName).put("price", t.price)
    private fun readLabBillTest(o: JSONObject) = LabBillTest(
        id = o.optLong("id"), billId = o.optLong("billId"), testId = o.optLong("testId"),
        testName = o.optString("testName"), price = o.optDouble("price", 0.0)
    )

    private fun labResultJson(r: LabResultValue) = JSONObject().put("id", r.id).put("billId", r.billId)
        .put("testId", r.testId).put("testName", r.testName).put("evaluationId", r.evaluationId)
        .put("evaluationName", r.evaluationName).put("groupName", r.groupName).put("unit", r.unit)
        .put("normalValue", r.normalValue).put("result", r.result).put("sortOrder", r.sortOrder).put("isHeading", r.isHeading).put("isPageBreak", r.isPageBreak)
    private fun readLabResult(o: JSONObject) = LabResultValue(
        id = o.optLong("id"), billId = o.optLong("billId"), testId = o.optLong("testId"),
        testName = o.optString("testName"), evaluationId = o.optLong("evaluationId"),
        evaluationName = o.optString("evaluationName"), groupName = o.optString("groupName"),
        unit = o.optString("unit"), normalValue = o.optString("normalValue"),
        result = o.optString("result"), sortOrder = o.optInt("sortOrder", 0),
        isHeading = o.optBoolean("isHeading", false), isPageBreak = o.optBoolean("isPageBreak", false)
    )

    private fun labReceiptJson(r: LabReceipt) = JSONObject().put("id", r.id).put("labBillId", r.labBillId)
        .put("billNo", r.billNo).put("patientName", r.patientName).put("dateMillis", r.dateMillis)
        .put("amount", r.amount).put("mode", r.mode)
    private fun readLabReceipt(o: JSONObject) = LabReceipt(
        id = o.optLong("id"), labBillId = o.optLong("labBillId"), billNo = o.optString("billNo"),
        patientName = o.optString("patientName"), dateMillis = o.optLong("dateMillis"),
        amount = o.optDouble("amount", 0.0), mode = o.optString("mode", "Cash")
    )

    private fun matOutJson(m: MaterialOut) = JSONObject().put("id", m.id).put("voucherNo", m.voucherNo)
        .put("dateMillis", m.dateMillis).put("resultRef", m.resultRef).put("resultTests", m.resultTests).put("remarks", m.remarks)
        .put("productionRunId", m.productionRunId)
    private fun readMatOut(o: JSONObject) = MaterialOut(
        id = o.optLong("id"), voucherNo = o.optString("voucherNo"), dateMillis = o.optLong("dateMillis"),
        resultRef = o.optString("resultRef"), resultTests = o.optString("resultTests"), remarks = o.optString("remarks"),
        productionRunId = o.optLong("productionRunId")
    )
    private fun matOutItemJson(l: MaterialOutItem) = JSONObject().put("id", l.id).put("outId", l.outId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("unit", l.unit)
    private fun readMatOutItem(o: JSONObject) = MaterialOutItem(
        id = o.optLong("id"), outId = o.optLong("outId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), unit = o.optString("unit")
    )

    private fun readSize(o: JSONObject) = ItemSize(
        id = o.optLong("id"), itemId = o.optLong("itemId"), name = o.optString("name"), price = o.optDouble("price", 0.0)
    )

    private fun itemAttJson(a: ItemAttachment) = JSONObject().put("id", a.id).put("itemId", a.itemId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime).put("kind", a.kind)

    private fun matRecJson(m: MaterialReceipt) = JSONObject().put("id", m.id).put("receiptNo", m.receiptNo)
        .put("dateMillis", m.dateMillis).put("supplierId", m.supplierId).put("supplierName", m.supplierName)
        .put("lpoId", m.lpoId).put("lpoNo", m.lpoNo).put("remarks", m.remarks).put("productionRunId", m.productionRunId)

    private fun readMatRec(o: JSONObject) = MaterialReceipt(
        id = o.optLong("id"), receiptNo = o.optString("receiptNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        lpoId = o.optLong("lpoId"), lpoNo = o.optString("lpoNo"), remarks = o.optString("remarks"),
        productionRunId = o.optLong("productionRunId")
    )

    private fun matRecItemJson(l: MaterialReceiptItem) = JSONObject().put("id", l.id).put("receiptId", l.receiptId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo).put("unit", l.unit)

    private fun readMatRecItem(o: JSONObject) = MaterialReceiptItem(
        id = o.optLong("id"), receiptId = o.optLong("receiptId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        batchNo = o.optString("batchNo"), unit = o.optString("unit")
    )

    private fun procedureJson(p: ProductionProcedure) = JSONObject().put("id", p.id).put("name", p.name)
        .put("producedItemId", p.producedItemId).put("producedItemName", p.producedItemName)
        .put("producedQty", p.producedQty).put("unit", p.unit).put("labourCost", p.labourCost).put("remarks", p.remarks)
    private fun readProcedure(o: JSONObject) = ProductionProcedure(
        id = o.optLong("id"), name = o.optString("name"), producedItemId = o.optLong("producedItemId"),
        producedItemName = o.optString("producedItemName"), producedQty = o.optDouble("producedQty", 0.0),
        unit = o.optString("unit"), labourCost = o.optDouble("labourCost", 0.0), remarks = o.optString("remarks")
    )
    private fun procedureMaterialJson(m: ProductionProcedureMaterial) = JSONObject().put("id", m.id).put("procedureId", m.procedureId)
        .put("itemId", m.itemId).put("name", m.name).put("qty", m.qty).put("unit", m.unit)
    private fun readProcedureMaterial(o: JSONObject) = ProductionProcedureMaterial(
        id = o.optLong("id"), procedureId = o.optLong("procedureId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), unit = o.optString("unit")
    )
    private fun productionRunJson(r: ProductionRun) = JSONObject().put("id", r.id).put("runNo", r.runNo)
        .put("dateMillis", r.dateMillis).put("procedureId", r.procedureId).put("procedureName", r.procedureName)
        .put("producedItemId", r.producedItemId).put("producedItemName", r.producedItemName)
        .put("qtyProduced", r.qtyProduced).put("unit", r.unit).put("materialCost", r.materialCost)
        .put("labourCost", r.labourCost).put("totalCost", r.totalCost).put("unitCost", r.unitCost)
        .put("remarks", r.remarks).put("materialOutId", r.materialOutId).put("materialReceiptId", r.materialReceiptId)
    private fun readProductionRun(o: JSONObject) = ProductionRun(
        id = o.optLong("id"), runNo = o.optString("runNo"), dateMillis = o.optLong("dateMillis"),
        procedureId = o.optLong("procedureId"), procedureName = o.optString("procedureName"),
        producedItemId = o.optLong("producedItemId"), producedItemName = o.optString("producedItemName"),
        qtyProduced = o.optDouble("qtyProduced", 0.0), unit = o.optString("unit"),
        materialCost = o.optDouble("materialCost", 0.0), labourCost = o.optDouble("labourCost", 0.0),
        totalCost = o.optDouble("totalCost", 0.0), unitCost = o.optDouble("unitCost", 0.0),
        remarks = o.optString("remarks"), materialOutId = o.optLong("materialOutId"), materialReceiptId = o.optLong("materialReceiptId")
    )

    private fun bundleJson(b: ItemBundle) = JSONObject().put("id", b.id).put("name", b.name)
        .put("unit", b.unit).put("price", b.price).put("remarks", b.remarks)
    private fun readBundle(o: JSONObject) = ItemBundle(
        id = o.optLong("id"), name = o.optString("name"), unit = o.optString("unit"),
        price = o.optDouble("price", 0.0), remarks = o.optString("remarks")
    )
    private fun bundleComponentJson(c: ItemBundleComponent) = JSONObject().put("id", c.id).put("bundleId", c.bundleId)
        .put("itemId", c.itemId).put("name", c.name).put("qty", c.qty).put("unit", c.unit)
    private fun readBundleComponent(o: JSONObject) = ItemBundleComponent(
        id = o.optLong("id"), bundleId = o.optLong("bundleId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), unit = o.optString("unit")
    )

    private fun expenseAttJson(a: ExpenseAttachment) = JSONObject().put("id", a.id).put("expenseId", a.expenseId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime)

    private fun readExpenseAtt(context: Context, o: JSONObject) = ExpenseAttachment(
        id = o.optLong("id"), expenseId = o.optLong("expenseId"),
        path = File(com.billing.pos.expenses.ExpenseAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime")
    )

    private fun billAttJson(a: BillAttachment) = JSONObject().put("id", a.id).put("billId", a.billId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime)

    private fun attJson(a: DiaryAttachment) = JSONObject().put("id", a.id).put("entryId", a.entryId)
        .put("file", if (a.type == AttachmentType.LOCATION) "" else File(a.path).name)
        .put("locUrl", if (a.type == AttachmentType.LOCATION) a.path else "")
        .put("name", a.name).put("mime", a.mime).put("type", a.type.name)

    private fun blockJson(b: DiaryBlock) = JSONObject().put("id", b.id).put("entryId", b.entryId)
        .put("position", b.position).put("type", b.type.name).put("text", b.text)
        .put("file", if (b.path.isBlank()) "" else File(b.path).name)
        .put("name", b.name).put("mime", b.mime).put("durationMs", b.durationMs)

    // ---- deserialisers ----
    private fun readCust(o: JSONObject) = Customer(
        id = o.optLong("id"), name = o.optString("name"), phone = o.optString("phone"),
        address = o.optString("address"), gstin = o.optString("gstin"), isDefault = o.optBoolean("isDefault", false),
        customerType = o.optString("customerType", "General"), state = o.optString("state")
    )

    private fun readItem(o: JSONObject) = Item(
        id = o.optLong("id"), name = o.optString("name"),
        price = o.optDouble("price", 0.0), taxPercent = o.optDouble("taxPercent", 0.0),
        barcode = o.optString("barcode"), hsn = o.optString("hsn"),
        category = o.optString("category"), openingStock = o.optDouble("openingStock", 0.0),
        unit = o.optString("unit", "PCS"),
        secondaryUnit = o.optString("secondaryUnit", "PCS"),
        conversionFactor = o.optDouble("conversionFactor", 1.0),
        storeLocation = o.optString("storeLocation"),
        chemicalContent = o.optString("chemicalContent")
    )

    private fun readBill(o: JSONObject) = Bill(
        id = o.optLong("id"), billNo = o.optString("billNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        paymentMethod = o.optString("paymentMethod"), subTotal = o.optDouble("subTotal", 0.0),
        taxTotal = o.optDouble("taxTotal", 0.0), additionalCharge = o.optDouble("additionalCharge", 0.0),
        discount = o.optDouble("discount", 0.0), grandTotal = o.optDouble("grandTotal", 0.0),
        paidAmount = o.optDouble("paidAmount", 0.0), cessTotal = o.optDouble("cessTotal", 0.0),
        customerGstin = o.optString("customerGstin"),
        customerState = o.optString("customerState"),
        source = o.optString("source"), remarks = o.optString("remarks"), deviceId = o.optString("deviceId"),
        isNoTax = o.optBoolean("isNoTax", false)
    )

    private fun readLine(o: JSONObject) = BillItem(
        id = o.optLong("id"), billId = o.optLong("billId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), cessPercent = o.optDouble("cessPercent", 0.0),
        lineTotal = o.optDouble("lineTotal", 0.0),
        batchNo = o.optString("batchNo"), unit = o.optString("unit"),
        primaryQty = o.optDouble("primaryQty", 0.0)
    )

    private fun readReceipt(o: JSONObject) = Receipt(
        id = o.optLong("id"), receiptNo = o.optString("receiptNo"), billId = o.optLong("billId"),
        billNo = o.optString("billNo"), customerName = o.optString("customerName"),
        dateMillis = o.optLong("dateMillis"), amount = o.optDouble("amount", 0.0),
        paymentMode = o.optString("paymentMode"), payFrom = o.optString("payFrom"), source = o.optString("source"),
        deviceId = o.optString("deviceId")
    )

    private fun readExpense(o: JSONObject) = Expense(
        id = o.optLong("id"), voucherNo = o.optString("voucherNo"), dateMillis = o.optLong("dateMillis"),
        description = o.optString("description"), amount = o.optDouble("amount", 0.0),
        paymentMode = o.optString("paymentMode"), purchaseId = o.optLong("purchaseId"),
        purchaseNo = o.optString("purchaseNo"), payTo = o.optString("payTo"), source = o.optString("source"),
        deviceId = o.optString("deviceId")
    )

    private fun readUser(o: JSONObject) = User(
        id = o.optLong("id"), username = o.optString("username"), passwordHash = o.optString("passwordHash"),
        role = runCatching { Role.valueOf(o.optString("role", "SALESMAN")) }.getOrDefault(Role.SALESMAN),
        canCreateInvoice = o.optBoolean("canCreateInvoice", true), canEditInvoice = o.optBoolean("canEditInvoice", false),
        canDeleteInvoice = o.optBoolean("canDeleteInvoice", false), canViewInvoice = o.optBoolean("canViewInvoice", true),
        canCreateReceipt = o.optBoolean("canCreateReceipt", false), canEditReceipt = o.optBoolean("canEditReceipt", false),
        canDeleteReceipt = o.optBoolean("canDeleteReceipt", false), canViewReceipt = o.optBoolean("canViewReceipt", false),
        canCreatePayment = o.optBoolean("canCreatePayment", false), canEditPayment = o.optBoolean("canEditPayment", false),
        canDeletePayment = o.optBoolean("canDeletePayment", false), canViewPayment = o.optBoolean("canViewPayment", false),
        canViewCashbook = o.optBoolean("canViewCashbook", false),
        canExport = o.optBoolean("canExport", true), canImport = o.optBoolean("canImport", false),
        canManageUsers = o.optBoolean("canManageUsers", false), active = o.optBoolean("active", true)
    )

    /** Android's SQLite CursorWindow caps a single row at ~2MB — a diary/attachment import
     * carrying a multi-megabyte text field (garbage data, a bad export, ...) would otherwise
     * make every future SELECT that touches that row crash. Capped well under that limit. */
    private fun capText(s: String, max: Int = 200000): String =
        if (s.length <= max) s else s.take(max) + " … [truncated — original was too large to store safely]"

    private fun readEntry(o: JSONObject) = DiaryEntry(
        id = o.optLong("id"), title = o.optString("title"), remarks = capText(o.optString("remarks")),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
        typeId = o.optLong("typeId", 0L),
        reminderEnabled = o.optBoolean("reminderEnabled", false), reminderAt = o.optLong("reminderAt"),
        reminderDaily = o.optBoolean("reminderDaily", false),
        titleSize = o.optInt("titleSize", 20), titleColor = o.optInt("titleColor", 0),
        titleBold = o.optBoolean("titleBold", true), titleItalic = o.optBoolean("titleItalic", false),
        bodySize = o.optInt("bodySize", 15), bodyColor = o.optInt("bodyColor", 0),
        bodyBold = o.optBoolean("bodyBold", false), bodyItalic = o.optBoolean("bodyItalic", false)
    )

    private fun readSupplier(o: JSONObject) = Supplier(
        id = o.optLong("id"), name = o.optString("name"), phone = o.optString("phone"),
        address = o.optString("address"), gstin = o.optString("gstin"), isDefault = o.optBoolean("isDefault", false),
        state = o.optString("state")
    )

    private fun readPurchase(o: JSONObject) = Purchase(
        id = o.optLong("id"), purchaseNo = o.optString("purchaseNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        paymentMethod = o.optString("paymentMethod"), subTotal = o.optDouble("subTotal", 0.0),
        taxTotal = o.optDouble("taxTotal", 0.0), additionalCharge = o.optDouble("additionalCharge", 0.0),
        discount = o.optDouble("discount", 0.0), grandTotal = o.optDouble("grandTotal", 0.0),
        paidAmount = o.optDouble("paidAmount", 0.0), cessTotal = o.optDouble("cessTotal", 0.0),
        supplierGstin = o.optString("supplierGstin"),
        supplierState = o.optString("supplierState"),
        source = o.optString("source"), deviceId = o.optString("deviceId")
    )

    private fun readPLine(o: JSONObject) = PurchaseItem(
        id = o.optLong("id"), purchaseId = o.optLong("purchaseId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), cessPercent = o.optDouble("cessPercent", 0.0),
        lineTotal = o.optDouble("lineTotal", 0.0),
        batchNo = o.optString("batchNo"), unit = o.optString("unit"),
        primaryQty = o.optDouble("primaryQty", 0.0)
    )

    private fun readGroup(o: JSONObject) = AccountGroup(
        id = o.optLong("id"), name = o.optString("name"),
        nature = runCatching { AccountNature.valueOf(o.optString("nature", "ASSET")) }.getOrDefault(AccountNature.ASSET),
        isSystem = o.optBoolean("isSystem", false)
    )

    private fun readHead(o: JSONObject) = AccountHead(
        id = o.optLong("id"), name = o.optString("name"), groupId = o.optLong("groupId"),
        openingBalance = o.optDouble("openingBalance", 0.0), openingIsDebit = o.optBoolean("openingIsDebit", true),
        isSystem = o.optBoolean("isSystem", false)
    )

    private fun readJEntry(o: JSONObject) = JournalEntry(
        id = o.optLong("id"), voucherNo = o.optString("voucherNo"), dateMillis = o.optLong("dateMillis"),
        narration = o.optString("narration"),
        cashMode = o.optString("cashMode"), cashIsIn = o.optBoolean("cashIsIn", true),
        cashAmount = o.optDouble("cashAmount", 0.0),
        source = o.optString("source"),
        voucherType = o.optString("voucherType", JournalVoucherType.JOURNAL)
    )

    private fun readItemAtt(context: Context, o: JSONObject) = ItemAttachment(
        id = o.optLong("id"), itemId = o.optLong("itemId"),
        path = File(com.billing.pos.items.ItemAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime"), kind = o.optString("kind", "PHOTO")
    )

    private fun readBillAtt(context: Context, o: JSONObject) = BillAttachment(
        id = o.optLong("id"), billId = o.optLong("billId"),
        path = File(com.billing.pos.bills.BillAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime")
    )

    private fun readJLine(o: JSONObject) = JournalLine(
        id = o.optLong("id"), entryId = o.optLong("entryId"), headId = o.optLong("headId"),
        headName = o.optString("headName"), amount = o.optDouble("amount", 0.0), isDebit = o.optBoolean("isDebit", true)
    )

    private fun readAtt(context: Context, o: JSONObject): DiaryAttachment {
        val type = runCatching { AttachmentType.valueOf(o.optString("type", "DOCUMENT")) }.getOrDefault(AttachmentType.DOCUMENT)
        val path = if (type == AttachmentType.LOCATION) o.optString("locUrl")
        else File(AttachmentStore.dir(context), o.optString("file")).absolutePath
        return DiaryAttachment(
            id = o.optLong("id"), entryId = o.optLong("entryId"), path = path,
            name = o.optString("name"), mime = o.optString("mime"), type = type
        )
    }

    private fun readBlock(context: Context, o: JSONObject): DiaryBlock {
        val type = runCatching { BlockType.valueOf(o.optString("type", "TEXT")) }.getOrDefault(BlockType.TEXT)
        val file = o.optString("file")
        val path = if (file.isBlank()) "" else File(AttachmentStore.dir(context), file).absolutePath
        return DiaryBlock(
            id = o.optLong("id"), entryId = o.optLong("entryId"), position = o.optInt("position"),
            type = type, text = capText(o.optString("text")), path = path,
            name = o.optString("name"), mime = o.optString("mime"), durationMs = o.optLong("durationMs")
        )
    }
}
