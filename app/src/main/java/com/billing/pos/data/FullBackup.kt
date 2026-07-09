package com.billing.pos.data

import android.content.Context
import android.net.Uri
import com.billing.pos.diary.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
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

    suspend fun create(context: Context): File {
        val db = AppDatabase.get(context)
        val prefs = AppPrefs(context)

        val root = JSONObject()
        root.put("app", "pos-billing-full")
        root.put("version", 1)
        root.put("createdAt", System.currentTimeMillis())
        root.put(
            "settings",
            JSONObject()
                .put("companyName", prefs.companyName)
                .put("companyAddress", prefs.companyAddress)
                .put("companyPhone", prefs.companyPhone)
        )

        root.put("customers", JSONArray().apply { db.customerDao().all().forEach { put(custJson(it)) } })
        root.put("items", JSONArray().apply { db.itemDao().all().forEach { put(itemJson(it)) } })
        root.put("bills", JSONArray().apply { db.billDao().all().forEach { put(billJson(it)) } })
        root.put("billItems", JSONArray().apply { db.billDao().allLines().forEach { put(lineJson(it)) } })
        root.put("receipts", JSONArray().apply { db.receiptDao().all().forEach { put(receiptJson(it)) } })
        root.put("expenses", JSONArray().apply { db.expenseDao().all().forEach { put(expenseJson(it)) } })
        root.put("users", JSONArray().apply { db.userDao().all().forEach { put(userJson(it)) } })
        root.put("diaryEntries", JSONArray().apply { db.diaryDao().allEntries().forEach { put(entryJson(it)) } })
        val attachments = db.diaryDao().allAttachments()
        root.put("diaryAttachments", JSONArray().apply { attachments.forEach { put(attJson(it)) } })
        val diaryBlocks = db.diaryDao().allBlocks()
        root.put("diaryBlocks", JSONArray().apply { diaryBlocks.forEach { put(blockJson(it)) } })

        root.put("suppliers", JSONArray().apply { db.supplierDao().all().forEach { put(supplierJson(it)) } })
        root.put("purchases", JSONArray().apply { db.purchaseDao().all().forEach { put(purchaseJson(it)) } })
        root.put("purchaseItems", JSONArray().apply { db.purchaseDao().allLines().forEach { put(pLineJson(it)) } })
        root.put("accountGroups", JSONArray().apply { db.accountDao().allGroups().forEach { put(groupJson(it)) } })
        root.put("accountHeads", JSONArray().apply { db.accountDao().allHeads().forEach { put(headJson(it)) } })
        root.put("journalEntries", JSONArray().apply { db.journalDao().allEntries().forEach { put(jEntryJson(it)) } })
        root.put("journalLines", JSONArray().apply { db.journalDao().allLines().forEach { put(jLineJson(it)) } })
        val itemAtts = db.itemAttachmentDao().all()
        root.put("itemAttachments", JSONArray().apply { itemAtts.forEach { put(itemAttJson(it)) } })
        root.put("itemBatches", JSONArray().apply { db.itemBatchDao().all().forEach { put(batchJson(it)) } })
        val billAtts = db.billAttachmentDao().all()
        root.put("billAttachments", JSONArray().apply { billAtts.forEach { put(billAttJson(it)) } })

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zip = File(dir, "pos-full-backup.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("full-backup.json"))
            zos.write(root.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
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
        }
        return zip
    }

    suspend fun restore(context: Context, uri: Uri, merge: Boolean = false): Result<String> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot read the file")

        val filesDir = AttachmentStore.dir(context)
        val itemFilesDir = com.billing.pos.items.ItemAttachmentStore.dir(context)
        val billFilesDir = com.billing.pos.bills.BillAttachmentStore.dir(context)
        var json: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
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
            mergeInto(context, db, root)
            return@runCatching "Merge complete — backup data appended"
        }

        withContext(Dispatchers.IO) { db.clearAllTables() }

        root.optJSONObject("settings")?.let { s ->
            val prefs = AppPrefs(context)
            prefs.companyName = s.optString("companyName", "My Shop")
            prefs.companyAddress = s.optString("companyAddress", "")
            prefs.companyPhone = s.optString("companyPhone", "")
        }

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
        root.optJSONArray("billAttachments")?.let {
            for (i in 0 until it.length()) db.billAttachmentDao().insert(readBillAtt(context, it.getJSONObject(i)))
        }

        "Restore complete"
    }

    /**
     * Appends a backup into the existing data (no wipe). Masters (customers, suppliers,
     * items, account groups/heads) are reused when a same-named row already exists;
     * documents are always inserted as new rows with their parent ids remapped.
     * Users are intentionally NOT merged so local logins are preserved.
     */
    private suspend fun mergeInto(context: Context, db: AppDatabase, root: JSONObject) {
        // Customers
        val custByName = HashMap<String, Long>()
        db.customerDao().all().forEach { custByName[it.name.lowercase()] = it.id }
        val custMap = HashMap<Long, Long>()
        root.optJSONArray("customers")?.let {
            for (i in 0 until it.length()) {
                val c = readCust(it.getJSONObject(i)); val key = c.name.lowercase()
                custMap[c.id] = custByName[key] ?: db.customerDao().insert(c.copy(id = 0)).also { nid -> custByName[key] = nid }
            }
        }
        // Suppliers
        val suppByName = HashMap<String, Long>()
        db.supplierDao().all().forEach { suppByName[it.name.lowercase()] = it.id }
        val suppMap = HashMap<Long, Long>()
        root.optJSONArray("suppliers")?.let {
            for (i in 0 until it.length()) {
                val s = readSupplier(it.getJSONObject(i)); val key = s.name.lowercase()
                suppMap[s.id] = suppByName[key] ?: db.supplierDao().insert(s.copy(id = 0)).also { nid -> suppByName[key] = nid }
            }
        }
        // Items
        val itemByName = HashMap<String, Long>()
        db.itemDao().all().forEach { itemByName[it.name.lowercase()] = it.id }
        val itemMap = HashMap<Long, Long>()
        root.optJSONArray("items")?.let {
            for (i in 0 until it.length()) {
                val it2 = readItem(it.getJSONObject(i)); val key = it2.name.lowercase()
                itemMap[it2.id] = itemByName[key] ?: db.itemDao().insert(it2.copy(id = 0)).also { nid -> itemByName[key] = nid }
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
                    ?: db.accountDao().insertHead(h.copy(id = 0, groupId = groupMap[h.groupId] ?: h.groupId)).also { nid -> headByName[key] = nid }
            }
        }

        // Bills + items
        val billMap = HashMap<Long, Long>()
        root.optJSONArray("bills")?.let {
            for (i in 0 until it.length()) {
                val b = readBill(it.getJSONObject(i))
                billMap[b.id] = db.billDao().insertBill(b.copy(id = 0, customerId = custMap[b.customerId] ?: b.customerId))
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
                purMap[p.id] = db.purchaseDao().insertPurchase(p.copy(id = 0, supplierId = suppMap[p.supplierId] ?: p.supplierId))
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
        // Receipts / expenses
        root.optJSONArray("receipts")?.let {
            for (i in 0 until it.length()) {
                val r = readReceipt(it.getJSONObject(i))
                val nb = if (r.billId > 0) (billMap[r.billId] ?: 0L) else 0L
                db.receiptDao().insert(r.copy(id = 0, billId = nb))
            }
        }
        root.optJSONArray("expenses")?.let {
            for (i in 0 until it.length()) {
                val ex = readExpense(it.getJSONObject(i))
                val np = if (ex.purchaseId > 0) (purMap[ex.purchaseId] ?: 0L) else 0L
                db.expenseDao().insert(ex.copy(id = 0, purchaseId = np))
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
                diaryMap[e.id] = db.diaryDao().insert(e.copy(id = 0))
            }
        }
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
        // Item attachments
        root.optJSONArray("itemAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readItemAtt(context, it.getJSONObject(i)); val ni = itemMap[a.itemId] ?: continue
                db.itemAttachmentDao().insert(a.copy(id = 0, itemId = ni))
            }
        }
        root.optJSONArray("itemBatches")?.let {
            for (i in 0 until it.length()) {
                val b = readBatch(it.getJSONObject(i)); val ni = itemMap[b.itemId] ?: continue
                db.itemBatchDao().insert(b.copy(id = 0, itemId = ni))
            }
        }
        // Bill attachments
        root.optJSONArray("billAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readBillAtt(context, it.getJSONObject(i)); val nb = billMap[a.billId] ?: continue
                db.billAttachmentDao().insert(a.copy(id = 0, billId = nb))
            }
        }
    }

    // ---- serialisers ----
    private fun custJson(c: Customer) = JSONObject().put("id", c.id).put("name", c.name)
        .put("phone", c.phone).put("address", c.address).put("gstin", c.gstin).put("isDefault", c.isDefault)

    private fun itemJson(i: Item) = JSONObject().put("id", i.id).put("name", i.name)
        .put("price", i.price).put("taxPercent", i.taxPercent).put("barcode", i.barcode).put("hsn", i.hsn)
        .put("category", i.category).put("openingStock", i.openingStock).put("unit", i.unit)
        .put("storeLocation", i.storeLocation)

    private fun billJson(b: Bill) = JSONObject().put("id", b.id).put("billNo", b.billNo)
        .put("dateMillis", b.dateMillis).put("customerId", b.customerId).put("customerName", b.customerName)
        .put("paymentMethod", b.paymentMethod).put("subTotal", b.subTotal).put("taxTotal", b.taxTotal)
        .put("additionalCharge", b.additionalCharge).put("discount", b.discount)
        .put("grandTotal", b.grandTotal).put("paidAmount", b.paidAmount)
        .put("customerGstin", b.customerGstin).put("source", b.source).put("remarks", b.remarks)

    private fun lineJson(l: BillItem) = JSONObject().put("id", l.id).put("billId", l.billId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)

    private fun receiptJson(r: Receipt) = JSONObject().put("id", r.id).put("receiptNo", r.receiptNo)
        .put("billId", r.billId).put("billNo", r.billNo).put("customerName", r.customerName)
        .put("dateMillis", r.dateMillis).put("amount", r.amount).put("paymentMode", r.paymentMode)
        .put("payFrom", r.payFrom).put("source", r.source)

    private fun expenseJson(e: Expense) = JSONObject().put("id", e.id).put("voucherNo", e.voucherNo)
        .put("dateMillis", e.dateMillis).put("description", e.description).put("amount", e.amount)
        .put("paymentMode", e.paymentMode).put("purchaseId", e.purchaseId).put("purchaseNo", e.purchaseNo)
        .put("payTo", e.payTo).put("source", e.source)

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
        .put("reminderEnabled", e.reminderEnabled).put("reminderAt", e.reminderAt)
        .put("reminderDaily", e.reminderDaily)
        .put("titleSize", e.titleSize).put("titleColor", e.titleColor)
        .put("titleBold", e.titleBold).put("titleItalic", e.titleItalic)
        .put("bodySize", e.bodySize).put("bodyColor", e.bodyColor)
        .put("bodyBold", e.bodyBold).put("bodyItalic", e.bodyItalic)

    private fun supplierJson(s: Supplier) = JSONObject().put("id", s.id).put("name", s.name)
        .put("phone", s.phone).put("address", s.address).put("gstin", s.gstin).put("isDefault", s.isDefault)

    private fun purchaseJson(p: Purchase) = JSONObject().put("id", p.id).put("purchaseNo", p.purchaseNo)
        .put("dateMillis", p.dateMillis).put("supplierId", p.supplierId).put("supplierName", p.supplierName)
        .put("paymentMethod", p.paymentMethod).put("subTotal", p.subTotal).put("taxTotal", p.taxTotal)
        .put("additionalCharge", p.additionalCharge).put("discount", p.discount).put("grandTotal", p.grandTotal)
        .put("paidAmount", p.paidAmount).put("supplierGstin", p.supplierGstin).put("source", p.source)

    private fun pLineJson(l: PurchaseItem) = JSONObject().put("id", l.id).put("purchaseId", l.purchaseId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)

    private fun groupJson(g: AccountGroup) = JSONObject().put("id", g.id).put("name", g.name)
        .put("nature", g.nature.name).put("isSystem", g.isSystem)

    private fun headJson(h: AccountHead) = JSONObject().put("id", h.id).put("name", h.name)
        .put("groupId", h.groupId).put("openingBalance", h.openingBalance)
        .put("openingIsDebit", h.openingIsDebit).put("isSystem", h.isSystem)

    private fun jEntryJson(e: JournalEntry) = JSONObject().put("id", e.id).put("voucherNo", e.voucherNo)
        .put("dateMillis", e.dateMillis).put("narration", e.narration)
        .put("cashMode", e.cashMode).put("cashIsIn", e.cashIsIn).put("cashAmount", e.cashAmount)
        .put("source", e.source)

    private fun jLineJson(l: JournalLine) = JSONObject().put("id", l.id).put("entryId", l.entryId)
        .put("headId", l.headId).put("headName", l.headName).put("amount", l.amount).put("isDebit", l.isDebit)

    private fun batchJson(b: ItemBatch) = JSONObject().put("id", b.id).put("itemId", b.itemId)
        .put("batchNo", b.batchNo).put("expiryMillis", b.expiryMillis).put("quantity", b.quantity)

    private fun readBatch(o: JSONObject) = ItemBatch(
        id = o.optLong("id"), itemId = o.optLong("itemId"), batchNo = o.optString("batchNo"),
        expiryMillis = o.optLong("expiryMillis"), quantity = o.optDouble("quantity", 0.0)
    )

    private fun itemAttJson(a: ItemAttachment) = JSONObject().put("id", a.id).put("itemId", a.itemId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime).put("kind", a.kind)

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
        address = o.optString("address"), gstin = o.optString("gstin"), isDefault = o.optBoolean("isDefault", false)
    )

    private fun readItem(o: JSONObject) = Item(
        id = o.optLong("id"), name = o.optString("name"),
        price = o.optDouble("price", 0.0), taxPercent = o.optDouble("taxPercent", 0.0),
        barcode = o.optString("barcode"), hsn = o.optString("hsn"),
        category = o.optString("category"), openingStock = o.optDouble("openingStock", 0.0),
        unit = o.optString("unit", "PCS"), storeLocation = o.optString("storeLocation")
    )

    private fun readBill(o: JSONObject) = Bill(
        id = o.optLong("id"), billNo = o.optString("billNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        paymentMethod = o.optString("paymentMethod"), subTotal = o.optDouble("subTotal", 0.0),
        taxTotal = o.optDouble("taxTotal", 0.0), additionalCharge = o.optDouble("additionalCharge", 0.0),
        discount = o.optDouble("discount", 0.0), grandTotal = o.optDouble("grandTotal", 0.0),
        paidAmount = o.optDouble("paidAmount", 0.0), customerGstin = o.optString("customerGstin"),
        source = o.optString("source"), remarks = o.optString("remarks")
    )

    private fun readLine(o: JSONObject) = BillItem(
        id = o.optLong("id"), billId = o.optLong("billId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        batchNo = o.optString("batchNo")
    )

    private fun readReceipt(o: JSONObject) = Receipt(
        id = o.optLong("id"), receiptNo = o.optString("receiptNo"), billId = o.optLong("billId"),
        billNo = o.optString("billNo"), customerName = o.optString("customerName"),
        dateMillis = o.optLong("dateMillis"), amount = o.optDouble("amount", 0.0),
        paymentMode = o.optString("paymentMode"), payFrom = o.optString("payFrom"), source = o.optString("source")
    )

    private fun readExpense(o: JSONObject) = Expense(
        id = o.optLong("id"), voucherNo = o.optString("voucherNo"), dateMillis = o.optLong("dateMillis"),
        description = o.optString("description"), amount = o.optDouble("amount", 0.0),
        paymentMode = o.optString("paymentMode"), purchaseId = o.optLong("purchaseId"),
        purchaseNo = o.optString("purchaseNo"), payTo = o.optString("payTo"), source = o.optString("source")
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

    private fun readEntry(o: JSONObject) = DiaryEntry(
        id = o.optLong("id"), title = o.optString("title"), remarks = o.optString("remarks"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
        reminderEnabled = o.optBoolean("reminderEnabled", false), reminderAt = o.optLong("reminderAt"),
        reminderDaily = o.optBoolean("reminderDaily", false),
        titleSize = o.optInt("titleSize", 20), titleColor = o.optInt("titleColor", 0),
        titleBold = o.optBoolean("titleBold", true), titleItalic = o.optBoolean("titleItalic", false),
        bodySize = o.optInt("bodySize", 15), bodyColor = o.optInt("bodyColor", 0),
        bodyBold = o.optBoolean("bodyBold", false), bodyItalic = o.optBoolean("bodyItalic", false)
    )

    private fun readSupplier(o: JSONObject) = Supplier(
        id = o.optLong("id"), name = o.optString("name"), phone = o.optString("phone"),
        address = o.optString("address"), gstin = o.optString("gstin"), isDefault = o.optBoolean("isDefault", false)
    )

    private fun readPurchase(o: JSONObject) = Purchase(
        id = o.optLong("id"), purchaseNo = o.optString("purchaseNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        paymentMethod = o.optString("paymentMethod"), subTotal = o.optDouble("subTotal", 0.0),
        taxTotal = o.optDouble("taxTotal", 0.0), additionalCharge = o.optDouble("additionalCharge", 0.0),
        discount = o.optDouble("discount", 0.0), grandTotal = o.optDouble("grandTotal", 0.0),
        paidAmount = o.optDouble("paidAmount", 0.0), supplierGstin = o.optString("supplierGstin"),
        source = o.optString("source")
    )

    private fun readPLine(o: JSONObject) = PurchaseItem(
        id = o.optLong("id"), purchaseId = o.optLong("purchaseId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        batchNo = o.optString("batchNo")
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
        source = o.optString("source")
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
            type = type, text = o.optString("text"), path = path,
            name = o.optString("name"), mime = o.optString("mime"), durationMs = o.optLong("durationMs")
        )
    }
}
