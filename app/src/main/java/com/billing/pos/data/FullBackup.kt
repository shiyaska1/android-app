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
        }
        return zip
    }

    suspend fun restore(context: Context, uri: Uri): Result<String> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot read the file")

        val filesDir = AttachmentStore.dir(context)
        var json: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    when {
                        e.name.endsWith(".json") -> json = zis.readBytes().toString(Charsets.UTF_8)
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
        root.optJSONArray("diaryAttachments")?.let {
            for (i in 0 until it.length()) db.diaryDao().insertAttachment(readAtt(context, it.getJSONObject(i)))
        }

        "Restore complete"
    }

    // ---- serialisers ----
    private fun custJson(c: Customer) = JSONObject().put("id", c.id).put("name", c.name)
        .put("phone", c.phone).put("address", c.address).put("isDefault", c.isDefault)

    private fun itemJson(i: Item) = JSONObject().put("id", i.id).put("name", i.name)
        .put("price", i.price).put("taxPercent", i.taxPercent).put("barcode", i.barcode)

    private fun billJson(b: Bill) = JSONObject().put("id", b.id).put("billNo", b.billNo)
        .put("dateMillis", b.dateMillis).put("customerId", b.customerId).put("customerName", b.customerName)
        .put("paymentMethod", b.paymentMethod).put("subTotal", b.subTotal).put("taxTotal", b.taxTotal)
        .put("additionalCharge", b.additionalCharge).put("discount", b.discount)
        .put("grandTotal", b.grandTotal).put("paidAmount", b.paidAmount).put("source", b.source)

    private fun lineJson(l: BillItem) = JSONObject().put("id", l.id).put("billId", l.billId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal)

    private fun receiptJson(r: Receipt) = JSONObject().put("id", r.id).put("receiptNo", r.receiptNo)
        .put("billId", r.billId).put("billNo", r.billNo).put("customerName", r.customerName)
        .put("dateMillis", r.dateMillis).put("amount", r.amount).put("paymentMode", r.paymentMode)
        .put("payFrom", r.payFrom).put("source", r.source)

    private fun expenseJson(e: Expense) = JSONObject().put("id", e.id).put("voucherNo", e.voucherNo)
        .put("dateMillis", e.dateMillis).put("description", e.description).put("amount", e.amount)
        .put("paymentMode", e.paymentMode).put("source", e.source)

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

    private fun attJson(a: DiaryAttachment) = JSONObject().put("id", a.id).put("entryId", a.entryId)
        .put("file", if (a.type == AttachmentType.LOCATION) "" else File(a.path).name)
        .put("locUrl", if (a.type == AttachmentType.LOCATION) a.path else "")
        .put("name", a.name).put("mime", a.mime).put("type", a.type.name)

    // ---- deserialisers ----
    private fun readCust(o: JSONObject) = Customer(
        id = o.optLong("id"), name = o.optString("name"), phone = o.optString("phone"),
        address = o.optString("address"), isDefault = o.optBoolean("isDefault", false)
    )

    private fun readItem(o: JSONObject) = Item(
        id = o.optLong("id"), name = o.optString("name"),
        price = o.optDouble("price", 0.0), taxPercent = o.optDouble("taxPercent", 0.0),
        barcode = o.optString("barcode")
    )

    private fun readBill(o: JSONObject) = Bill(
        id = o.optLong("id"), billNo = o.optString("billNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        paymentMethod = o.optString("paymentMethod"), subTotal = o.optDouble("subTotal", 0.0),
        taxTotal = o.optDouble("taxTotal", 0.0), additionalCharge = o.optDouble("additionalCharge", 0.0),
        discount = o.optDouble("discount", 0.0), grandTotal = o.optDouble("grandTotal", 0.0),
        paidAmount = o.optDouble("paidAmount", 0.0), source = o.optString("source")
    )

    private fun readLine(o: JSONObject) = BillItem(
        id = o.optLong("id"), billId = o.optLong("billId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0)
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
        paymentMode = o.optString("paymentMode"), source = o.optString("source")
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
        reminderDaily = o.optBoolean("reminderDaily", false)
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
}
