package com.billing.pos.data

import android.content.Context
import com.billing.pos.auth.PasswordHasher
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

data class ImportResult(
    val billsAdded: Int,
    val billsSkipped: Int,
    val receiptsAdded: Int,
    val expensesAdded: Int,
    val source: String
)

/** Single access point for all data operations. */
class Repository(context: Context) {

    private val db = AppDatabase.get(context)
    private val customerDao = db.customerDao()
    private val itemDao = db.itemDao()
    private val billDao = db.billDao()
    private val userDao = db.userDao()
    private val receiptDao = db.receiptDao()
    private val expenseDao = db.expenseDao()

    val customers: Flow<List<Customer>> = customerDao.observeAll()
    val items: Flow<List<Item>> = itemDao.observeAll()
    val users: Flow<List<User>> = userDao.observeAll()
    val allBills: Flow<List<Bill>> = billDao.observeAll()
    val allReceipts: Flow<List<Receipt>> = receiptDao.observeAll()
    val allExpenses: Flow<List<Expense>> = expenseDao.observeAll()

    /** Seeds the default Cash customer and the initial super user. */
    suspend fun ensureDefaults() {
        if (customerDao.count() == 0) {
            customerDao.insert(Customer(name = "Cash Customer", isDefault = true))
        }
        if (userDao.count() == 0) {
            userDao.insert(
                User(
                    username = "superadmin",
                    passwordHash = PasswordHasher.hash("admin123"),
                    role = Role.SUPER_USER,
                    canCreateInvoice = true, canEditInvoice = true, canDeleteInvoice = true, canViewInvoice = true,
                    canCreateReceipt = true, canEditReceipt = true, canDeleteReceipt = true, canViewReceipt = true,
                    canCreatePayment = true, canEditPayment = true, canDeletePayment = true, canViewPayment = true,
                    canViewCashbook = true,
                    canExport = true, canImport = true, canManageUsers = true
                )
            )
        }
    }

    // ---- auth / users ----
    suspend fun login(username: String, password: String): User? {
        val user = userDao.byUsername(username.trim()) ?: return null
        return if (PasswordHasher.verify(password, user.passwordHash)) user else null
    }

    suspend fun userById(id: Long): User? = userDao.byId(id)

    suspend fun createUser(user: User, password: String): Result<Unit> = runCatching {
        userDao.insert(user.copy(passwordHash = PasswordHasher.hash(password)))
        Unit
    }

    /** Updates a user. If [newPassword] is non-blank the password is reset. */
    suspend fun updateUser(user: User, newPassword: String?): Result<Unit> = runCatching {
        val hash = if (newPassword.isNullOrBlank()) user.passwordHash
        else PasswordHasher.hash(newPassword)
        userDao.update(user.copy(passwordHash = hash))
        Unit
    }

    suspend fun deleteUser(user: User) = userDao.delete(user)

    // ---- customers / items ----
    suspend fun addCustomer(name: String, phone: String, address: String): Long =
        customerDao.insert(Customer(name = name.trim(), phone = phone.trim(), address = address.trim()))

    suspend fun addCustomerReturning(name: String, phone: String): Customer {
        val id = customerDao.insert(Customer(name = name.trim(), phone = phone.trim()))
        return Customer(id = id, name = name.trim(), phone = phone.trim())
    }

    suspend fun updateCustomer(customer: Customer) = customerDao.update(customer)

    suspend fun customerHasTransactions(customer: Customer): Boolean =
        billDao.countForCustomer(customer.id, customer.name) > 0

    /** Deletes a customer only if it has no invoices and is not the default. */
    suspend fun deleteCustomer(customer: Customer): Result<Unit> {
        if (customer.isDefault) return Result.failure(IllegalStateException("Cannot delete the default customer"))
        if (customerHasTransactions(customer)) return Result.failure(IllegalStateException("Customer has invoices"))
        customerDao.delete(customer)
        return Result.success(Unit)
    }

    val allCustomers: Flow<List<Customer>> get() = customers

    suspend fun addItem(name: String, price: Double, taxPercent: Double): Long =
        itemDao.insert(Item(name = name.trim(), price = price, taxPercent = taxPercent))

    /** Bill number for the next locally-created bill, e.g. INV-0001. */
    suspend fun nextBillNo(): String {
        val n = billDao.localCount() + 1
        return "INV-" + n.toString().padStart(4, '0')
    }

    // ---- bills ----
    suspend fun saveBill(bill: Bill, lines: List<BillItem>): Long = billDao.saveBill(bill, lines)
    suspend fun updateBill(bill: Bill, lines: List<BillItem>) = billDao.updateBill(bill, lines)
    suspend fun deleteBill(bill: Bill) = billDao.deleteBill(bill)
    suspend fun linesFor(billId: Long): List<BillItem> = billDao.linesFor(billId)
    suspend fun billById(id: Long): Bill? = billDao.byId(id)
    fun billsBetween(from: Long, to: Long): Flow<List<Bill>> = billDao.observeBetween(from, to)

    // ---- receipts (money received against credit invoices) ----
    suspend fun nextReceiptNo(): String =
        "RV-" + (receiptDao.localCount() + 1).toString().padStart(4, '0')

    /** Records a receipt against [bill] and increases the invoice's paid amount. */
    suspend fun addReceipt(bill: Bill, amount: Double, mode: PayMode): Receipt {
        val receipt = Receipt(
            receiptNo = nextReceiptNo(),
            billId = bill.id,
            billNo = bill.billNo,
            customerName = bill.customerName,
            dateMillis = System.currentTimeMillis(),
            amount = amount,
            paymentMode = mode.label,
            payFrom = bill.customerName
        )
        receiptDao.insert(receipt)
        val newPaid = (bill.paidAmount + amount).coerceAtMost(bill.grandTotal)
        billDao.updateBillHeader(bill.copy(paidAmount = newPaid))
        return receipt
    }

    /** Records a receipt from any source (no invoice reference). */
    suspend fun addStandaloneReceipt(payFrom: String, amount: Double, mode: PayMode): Receipt {
        val receipt = Receipt(
            receiptNo = nextReceiptNo(),
            billId = 0,
            billNo = "",
            customerName = payFrom.trim(),
            dateMillis = System.currentTimeMillis(),
            amount = amount,
            paymentMode = mode.label,
            payFrom = payFrom.trim()
        )
        receiptDao.insert(receipt)
        return receipt
    }

    /** Distinct "pay from" names previously used, for the dropdown. */
    suspend fun payFromNames(): List<String> = receiptDao.payFromNames()

    // ---- expenses / payment vouchers (money paid out) ----
    suspend fun nextVoucherNo(): String =
        "PV-" + (expenseDao.localCount() + 1).toString().padStart(4, '0')

    suspend fun addExpense(description: String, amount: Double, mode: PayMode): Expense {
        val expense = Expense(
            voucherNo = nextVoucherNo(),
            dateMillis = System.currentTimeMillis(),
            description = description.trim(),
            amount = amount,
            paymentMode = mode.label
        )
        expenseDao.insert(expense)
        return expense
    }

    /** Edits a receipt and re-applies the difference to the linked invoice's paid amount. */
    suspend fun updateReceipt(old: Receipt, newAmount: Double, mode: PayMode) {
        if (old.billId > 0) {
            billDao.byId(old.billId)?.let { bill ->
                val adjusted = (bill.paidAmount - old.amount + newAmount)
                    .coerceIn(0.0, bill.grandTotal)
                billDao.updateBillHeader(bill.copy(paidAmount = adjusted))
            }
        }
        receiptDao.update(old.copy(amount = newAmount, paymentMode = mode.label))
    }

    /** Deletes a receipt and reduces the linked invoice's paid amount. */
    suspend fun deleteReceipt(receipt: Receipt) {
        if (receipt.billId > 0) {
            billDao.byId(receipt.billId)?.let { bill ->
                val adjusted = (bill.paidAmount - receipt.amount).coerceAtLeast(0.0)
                billDao.updateBillHeader(bill.copy(paidAmount = adjusted))
            }
        }
        receiptDao.delete(receipt)
    }

    suspend fun updateExpense(expense: Expense, description: String, amount: Double, mode: PayMode) {
        expenseDao.update(expense.copy(description = description.trim(), amount = amount, paymentMode = mode.label))
    }

    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    // ---- data export / import (invoices, customers, items — NOT users) ----

    /** Serialises this device's own data to a JSON string, stamped with [sourceLabel]. */
    suspend fun exportJson(sourceLabel: String): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("source", sourceLabel)
        root.put("exportedAt", System.currentTimeMillis())

        val custArr = JSONArray()
        customerDao.all().filter { !it.isDefault }.forEach { c ->
            custArr.put(JSONObject().put("name", c.name).put("phone", c.phone).put("address", c.address))
        }
        root.put("customers", custArr)

        val itemArr = JSONArray()
        itemDao.all().forEach { i ->
            itemArr.put(JSONObject().put("name", i.name).put("price", i.price).put("taxPercent", i.taxPercent))
        }
        root.put("items", itemArr)

        val billArr = JSONArray()
        billDao.all().filter { it.source.isEmpty() }.forEach { b ->
            val bo = JSONObject()
                .put("billNo", b.billNo)
                .put("dateMillis", b.dateMillis)
                .put("customerName", b.customerName)
                .put("paymentMethod", b.paymentMethod)
                .put("subTotal", b.subTotal)
                .put("taxTotal", b.taxTotal)
                .put("additionalCharge", b.additionalCharge)
                .put("discount", b.discount)
                .put("grandTotal", b.grandTotal)
                .put("paidAmount", b.paidAmount)
            val lineArr = JSONArray()
            billDao.linesFor(b.id).forEach { l ->
                lineArr.put(
                    JSONObject().put("name", l.name).put("qty", l.qty)
                        .put("price", l.price).put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal)
                )
            }
            bo.put("lines", lineArr)
            billArr.put(bo)
        }
        root.put("bills", billArr)

        val receiptArr = JSONArray()
        receiptDao.all().filter { it.source.isEmpty() }.forEach { r ->
            receiptArr.put(
                JSONObject().put("receiptNo", r.receiptNo).put("billNo", r.billNo)
                    .put("customerName", r.customerName).put("dateMillis", r.dateMillis)
                    .put("amount", r.amount).put("paymentMode", r.paymentMode)
            )
        }
        root.put("receipts", receiptArr)

        val expenseArr = JSONArray()
        expenseDao.all().filter { it.source.isEmpty() }.forEach { e ->
            expenseArr.put(
                JSONObject().put("voucherNo", e.voucherNo).put("dateMillis", e.dateMillis)
                    .put("description", e.description).put("amount", e.amount)
                    .put("paymentMode", e.paymentMode)
            )
        }
        root.put("expenses", expenseArr)

        return root.toString()
    }

    /**
     * Merges an exported JSON into this database. Users/roles are never touched.
     * Bills are deduped by (source, billNo) so re-importing the same file is safe.
     */
    suspend fun importJson(json: String): ImportResult {
        val root = JSONObject(json)
        val source = root.optString("source", "imported").ifBlank { "imported" }

        root.optJSONArray("customers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isNotBlank() && customerDao.byName(name) == null) {
                    customerDao.insert(
                        Customer(name = name, phone = o.optString("phone"), address = o.optString("address"))
                    )
                }
            }
        }

        root.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isNotBlank() && itemDao.byName(name) == null) {
                    itemDao.insert(Item(name = name, price = o.optDouble("price", 0.0), taxPercent = o.optDouble("taxPercent", 0.0)))
                }
            }
        }

        var added = 0
        var skipped = 0
        root.optJSONArray("bills")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val billNo = o.optString("billNo")
                if (billNo.isBlank()) { skipped++; continue }
                if (billDao.findBySourceAndNo(source, billNo) != null) { skipped++; continue }

                val bill = Bill(
                    billNo = billNo,
                    dateMillis = o.optLong("dateMillis", System.currentTimeMillis()),
                    customerId = 0,
                    customerName = o.optString("customerName", "Cash Customer"),
                    paymentMethod = o.optString("paymentMethod", "Cash"),
                    subTotal = o.optDouble("subTotal", 0.0),
                    taxTotal = o.optDouble("taxTotal", 0.0),
                    additionalCharge = o.optDouble("additionalCharge", 0.0),
                    discount = o.optDouble("discount", 0.0),
                    grandTotal = o.optDouble("grandTotal", 0.0),
                    paidAmount = o.optDouble(
                        "paidAmount",
                        if (o.optString("paymentMethod") == PaymentMethod.CREDIT.label) 0.0
                        else o.optDouble("grandTotal", 0.0)
                    ),
                    source = source
                )
                val lines = mutableListOf<BillItem>()
                o.optJSONArray("lines")?.let { la ->
                    for (j in 0 until la.length()) {
                        val lo = la.getJSONObject(j)
                        lines.add(
                            BillItem(
                                billId = 0,
                                name = lo.optString("name"),
                                qty = lo.optDouble("qty", 0.0),
                                price = lo.optDouble("price", 0.0),
                                taxPercent = lo.optDouble("taxPercent", 0.0),
                                lineTotal = lo.optDouble("lineTotal", 0.0)
                            )
                        )
                    }
                }
                billDao.saveBill(bill, lines)
                added++
            }
        }

        var receiptsAdded = 0
        root.optJSONArray("receipts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val no = o.optString("receiptNo")
                if (no.isBlank() || receiptDao.findBySourceAndNo(source, no) != null) continue
                receiptDao.insert(
                    Receipt(
                        receiptNo = no,
                        billId = 0,
                        billNo = o.optString("billNo"),
                        customerName = o.optString("customerName"),
                        dateMillis = o.optLong("dateMillis", System.currentTimeMillis()),
                        amount = o.optDouble("amount", 0.0),
                        paymentMode = o.optString("paymentMode", "Cash"),
                        source = source
                    )
                )
                receiptsAdded++
            }
        }

        var expensesAdded = 0
        root.optJSONArray("expenses")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val no = o.optString("voucherNo")
                if (no.isBlank() || expenseDao.findBySourceAndNo(source, no) != null) continue
                expenseDao.insert(
                    Expense(
                        voucherNo = no,
                        dateMillis = o.optLong("dateMillis", System.currentTimeMillis()),
                        description = o.optString("description"),
                        amount = o.optDouble("amount", 0.0),
                        paymentMode = o.optString("paymentMode", "Cash"),
                        source = source
                    )
                )
                expensesAdded++
            }
        }

        return ImportResult(added, skipped, receiptsAdded, expensesAdded, source)
    }
}
