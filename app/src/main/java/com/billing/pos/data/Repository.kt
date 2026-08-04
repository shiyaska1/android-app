package com.billing.pos.data

import android.content.Context
import com.billing.pos.auth.PasswordHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class ImportResult(
    val billsAdded: Int,
    val billsSkipped: Int,
    val receiptsAdded: Int,
    val expensesAdded: Int,
    val source: String
)

data class DepreciationResult(val assetsProcessed: Int, val totalAmount: Double)

/** Single access point for all data operations. */
class Repository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val customerDao = db.customerDao()
    private val itemDao = db.itemDao()
    private val itemAttachmentDao = db.itemAttachmentDao()
    private val billAttachmentDao = db.billAttachmentDao()
    private val billDao = db.billDao()
    private val userDao = db.userDao()
    private val receiptDao = db.receiptDao()
    private val expenseDao = db.expenseDao()
    private val supplierDao = db.supplierDao()
    private val purchaseDao = db.purchaseDao()
    private val accountDao = db.accountDao()
    private val journalDao = db.journalDao()
    private val chequeDao = db.chequeDao()
    private val costCenterDao = db.costCenterDao()
    private val bankReconciliationDao = db.bankReconciliationDao()
    private val recurringJournalDao = db.recurringJournalDao()
    private val fixedAssetDao = db.fixedAssetDao()
    private val appPrefs = AppPrefs(context)

    /** Device letter/name prefix for document numbers (e.g. "A-"), blank if not set. Keeps two synced phones from clashing. */
    private fun tagPrefix(): String {
        val t = appPrefs.deviceTag
        return if (t.isBlank()) "" else "$t-"
    }

    val customers: Flow<List<Customer>> = customerDao.observeAll()
    val items: Flow<List<Item>> = itemDao.observeAll()
    val itemAttachments: Flow<List<ItemAttachment>> = itemAttachmentDao.observeAll()
    /** Item name (lowercased) -> its first PHOTO attachment path. Used to optionally print item
     * images on Quotation/Purchase Order/Purchase Quotation PDFs (matched by name since those
     * documents' saved lines don't carry an itemId). */
    val itemPhotoByName: Flow<Map<String, String>> = combine(items, itemAttachments) { its, atts ->
        val photosByItemId = atts.filter { it.kind == "PHOTO" }.groupBy { it.itemId }
        its.mapNotNull { item -> photosByItemId[item.id]?.firstOrNull()?.path?.let { path -> item.name.lowercase() to path } }.toMap()
    }
    val billAttachments: Flow<List<BillAttachment>> = billAttachmentDao.observeAll()
    val users: Flow<List<User>> = userDao.observeAll()
    val allBills: Flow<List<Bill>> = billDao.observeAll()
    val billLines: Flow<List<BillItem>> = billDao.observeAllLines()
    val allReceipts: Flow<List<Receipt>> = receiptDao.observeAll()
    val allExpenses: Flow<List<Expense>> = expenseDao.observeAll()
    val suppliers: Flow<List<Supplier>> = supplierDao.observeAll()
    val allPurchases: Flow<List<Purchase>> = purchaseDao.observeAll()
    val accountGroups: Flow<List<AccountGroup>> = accountDao.observeGroups()
    val accountHeads: Flow<List<AccountHead>> = accountDao.observeHeads()
    val journalEntries: Flow<List<JournalEntry>> = journalDao.observeAll()
    val journalLines: Flow<List<JournalLine>> = journalDao.observeAllLines()
    val cheques: Flow<List<ChequeEntry>> = chequeDao.observeAll()
    val costCenters: Flow<List<CostCenter>> = costCenterDao.observeAll()
    val fixedAssets: Flow<List<FixedAsset>> = fixedAssetDao.observeAll()
    val purchaseLines: Flow<List<PurchaseLineInfo>> = purchaseDao.observePurchaseLines()
    val purchaseLineParties: Flow<List<PurchaseLineParty>> = purchaseDao.observePurchaseLineParties()
    val soldQty: Flow<List<NameQty>> = billDao.observeSoldQty()

    /** Seeds the default Cash customer and the initial super user. */
    companion object {
        const val DEFAULT_USERNAME = "superadmin"
        const val DEFAULT_PASSWORD = "admin123"
    }

    suspend fun ensureDefaults() {
        if (customerDao.count() == 0) {
            customerDao.insert(Customer(name = "Cash Customer", isDefault = true))
        }
        if (supplierDao.count() == 0) {
            supplierDao.insert(Supplier(name = "Cash Supplier", isDefault = true))
        }
        if (accountDao.groupCount() == 0) seedChartOfAccounts()
        runCatching { syncPartyHeads() }   // ensure every customer/supplier has a ledger head
        if (userDao.count() == 0) {
            userDao.insert(
                User(
                    username = DEFAULT_USERNAME,
                    passwordHash = PasswordHasher.hash(DEFAULT_PASSWORD),
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

    private suspend fun seedChartOfAccounts() {
        suspend fun g(name: String, nature: AccountNature) =
            accountDao.insertGroup(AccountGroup(name = name, nature = nature, isSystem = true))
        val cash = g("Cash-in-hand", AccountNature.ASSET)
        val bank = g("Bank Accounts", AccountNature.ASSET)
        val debtors = g("Sundry Debtors", AccountNature.ASSET)
        g("Current Assets", AccountNature.ASSET)
        g("Fixed Assets", AccountNature.ASSET)
        val creditors = g("Sundry Creditors", AccountNature.LIABILITY)
        val taxes = g("Duties & Taxes", AccountNature.LIABILITY)
        g("Current Liabilities", AccountNature.LIABILITY)
        val capital = g("Capital Account", AccountNature.LIABILITY)
        val salesGrp = g("Sales Account", AccountNature.INCOME)
        g("Direct Income", AccountNature.INCOME)
        val indirectInc = g("Indirect Income", AccountNature.INCOME)
        val purchaseGrp = g("Purchase Account", AccountNature.EXPENSE)
        g("Direct Expenses", AccountNature.EXPENSE)
        val indirectExp = g("Indirect Expenses", AccountNature.EXPENSE)

        suspend fun h(name: String, groupId: Long) =
            accountDao.insertHead(AccountHead(name = name, groupId = groupId, isSystem = true))
        h("Cash", cash)
        h("Bank", bank)
        h("Sundry Debtors", debtors)
        h("Sundry Creditors", creditors)
        h("Sales", salesGrp)
        h("Purchase", purchaseGrp)
        h("Output Tax (GST/VAT)", taxes)
        h("Input Tax (GST/VAT)", taxes)
        h("Discount Allowed", indirectExp)
        h("Discount Received", indirectInc)
        h("Opening Capital", capital)
    }

    suspend fun addAccountGroup(name: String, nature: AccountNature, parentGroupId: Long? = null): Long =
        accountDao.insertGroup(AccountGroup(name = name.trim(), nature = nature, parentGroupId = parentGroupId))

    suspend fun updateAccountGroup(group: AccountGroup) = accountDao.updateGroup(group)

    suspend fun deleteAccountGroup(group: AccountGroup): Result<Unit> {
        if (group.isSystem) return Result.failure(IllegalStateException("System group cannot be deleted"))
        if (accountDao.headCountInGroup(group.id) > 0) return Result.failure(IllegalStateException("Group has account heads"))
        if (accountDao.allGroups().any { it.parentGroupId == group.id }) return Result.failure(IllegalStateException("Group has sub-groups"))
        accountDao.deleteGroup(group)
        return Result.success(Unit)
    }

    suspend fun addAccountHead(name: String, groupId: Long, openingBalance: Double, openingIsDebit: Boolean): Long =
        accountDao.insertHead(AccountHead(name = name.trim(), groupId = groupId, openingBalance = openingBalance, openingIsDebit = openingIsDebit))

    suspend fun updateAccountHead(head: AccountHead) = accountDao.updateHead(head)

    // ---- party & cash/bank ledger helpers (used by receipts/payments) ----
    private suspend fun groupIdByName(name: String): Long =
        accountDao.allGroups().firstOrNull { it.name.equals(name, true) }?.id ?: 0

    /** Ensure a ledger head [name] exists under [groupName]; returns its id (0 if group missing/blank). */
    private suspend fun ensureHeadIn(name: String, groupName: String): Long {
        val n = name.trim()
        if (n.isBlank()) return 0
        val gid = groupIdByName(groupName)
        if (gid == 0L) return 0
        return accountDao.headByNameGroup(n, gid)?.id ?: accountDao.insertHead(AccountHead(name = n, groupId = gid))
    }
    suspend fun ensureCustomerHead(name: String): Long = ensureHeadIn(name, "Sundry Debtors")
    suspend fun ensureSupplierHead(name: String): Long = ensureHeadIn(name, "Sundry Creditors")

    /** Cash/Bank head for a payment mode (e.g. UPI, Card, Cheque), created under Cash-in-hand if missing. */
    suspend fun ensureModeAccount(modeLabel: String): Long {
        val gid = groupIdByName("Cash-in-hand")
        if (gid == 0L) return 0
        return accountDao.headByNameGroup(modeLabel, gid)?.id
            ?: accountDao.headByName(modeLabel)?.id
            ?: accountDao.insertHead(AccountHead(name = modeLabel, groupId = gid))
    }

    /** Heads under Cash-in-hand + Bank Accounts groups — the "received to"/"from account" options. */
    val cashBankHeads: Flow<List<AccountHead>> =
        kotlinx.coroutines.flow.combine(accountDao.observeGroups(), accountDao.observeHeads()) { groups, heads ->
            val ids = groups.filter { it.name == "Cash-in-hand" || it.name == "Bank Accounts" }.map { it.id }.toSet()
            heads.filter { it.groupId in ids }
        }

    /** Heads under Sundry Debtors + Sundry Creditors groups — extra party options beyond customers/suppliers. */
    val sundryHeads: Flow<List<AccountHead>> =
        kotlinx.coroutines.flow.combine(accountDao.observeGroups(), accountDao.observeHeads()) { groups, heads ->
            val ids = groups.filter { it.name == "Sundry Debtors" || it.name == "Sundry Creditors" }.map { it.id }.toSet()
            heads.filter { it.groupId in ids }
        }

    suspend fun deleteAccountHead(head: AccountHead): Result<Unit> {
        if (head.isSystem) return Result.failure(IllegalStateException("System head cannot be deleted"))
        accountDao.deleteHead(head)
        return Result.success(Unit)
    }

    // ---- cheque / instrument register ----
    suspend fun saveCheque(cheque: ChequeEntry): Long = chequeDao.insert(cheque)
    suspend fun updateCheque(cheque: ChequeEntry) = chequeDao.update(cheque)
    suspend fun deleteCheque(cheque: ChequeEntry) = chequeDao.delete(cheque)

    // ---- bank reconciliation ----
    fun reconciledFor(headId: Long): Flow<List<BankReconciliation>> = bankReconciliationDao.observeForHead(headId)
    suspend fun markReconciled(headId: Long, vch: String, dateMillis: Long, amount: Double) =
        bankReconciliationDao.insert(BankReconciliation(headId = headId, vch = vch, dateMillis = dateMillis, amount = amount))
    suspend fun unmarkReconciled(headId: Long, vch: String, dateMillis: Long, amount: Double) =
        bankReconciliationDao.unmark(headId, vch, dateMillis, amount)

    // ---- recurring journals ----
    val recurringJournals: Flow<List<RecurringJournal>> = recurringJournalDao.observeAll()
    suspend fun recurringJournalLinesFor(id: Long): List<RecurringJournalLine> = recurringJournalDao.linesFor(id)
    suspend fun saveRecurringJournal(t: RecurringJournal, lines: List<RecurringJournalLine>): Long =
        recurringJournalDao.saveTemplate(t, lines)
    suspend fun updateRecurringJournal(t: RecurringJournal, lines: List<RecurringJournalLine>) =
        recurringJournalDao.updateTemplateWithLines(t, lines)
    suspend fun deleteRecurringJournal(t: RecurringJournal) = recurringJournalDao.deleteTemplateWithLines(t)

    private fun advanceRecurring(millis: Long, frequency: String): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = millis
        when (frequency) {
            RecurringFrequency.DAILY -> add(java.util.Calendar.DAY_OF_MONTH, 1)
            RecurringFrequency.WEEKLY -> add(java.util.Calendar.WEEK_OF_YEAR, 1)
            RecurringFrequency.YEARLY -> add(java.util.Calendar.YEAR, 1)
            else -> add(java.util.Calendar.MONTH, 1)
        }
    }.timeInMillis

    /** Posts a real [JournalEntry] for every due recurring template, advancing past each occurrence
     * (catches up on however many were missed since the app was last opened) until nextDueDate is
     * in the future or the template's end date has passed. Returns how many vouchers were posted. */
    suspend fun generateDueRecurringJournals(): Int {
        var count = 0
        val now = System.currentTimeMillis()
        recurringJournalDao.due(now).forEach { t ->
            var next = t.nextDueDate
            var generated = t.occurrencesGenerated
            val lines = recurringJournalDao.linesFor(t.id)
            if (lines.isNotEmpty()) {
                while (next <= now && (t.endDate == 0L || next <= t.endDate)) {
                    val entry = JournalEntry(
                        voucherNo = "RJ-${t.id}-${generated + 1}", dateMillis = next,
                        narration = t.narration.ifBlank { t.name }
                    )
                    val jLines = lines.map { JournalLine(0, 0, it.headId, it.headName, it.amount, it.isDebit) }
                    saveJournal(entry, jLines)
                    generated++
                    count++
                    next = advanceRecurring(next, t.frequency)
                }
            }
            recurringJournalDao.updateTemplate(t.copy(nextDueDate = next, lastGeneratedAt = now, occurrencesGenerated = generated))
        }
        return count
    }

    // ---- cost centers ----
    suspend fun addCostCenter(name: String): Long = costCenterDao.insert(CostCenter(name = name.trim()))
    suspend fun updateCostCenter(costCenter: CostCenter) = costCenterDao.update(costCenter)
    suspend fun deleteCostCenter(costCenter: CostCenter): Result<Unit> {
        if (costCenter.isSystem) return Result.failure(IllegalStateException("System cost center cannot be deleted"))
        costCenterDao.delete(costCenter)
        return Result.success(Unit)
    }

    // ---- fixed assets ----
    suspend fun saveFixedAsset(asset: FixedAsset): Long = fixedAssetDao.insert(asset)
    suspend fun updateFixedAsset(asset: FixedAsset) = fixedAssetDao.update(asset)
    suspend fun deleteFixedAsset(asset: FixedAsset) = fixedAssetDao.delete(asset)

    /** Posts one Depreciation Expense Dr / Accumulated Depreciation Cr journal voucher covering
     * every asset's elapsed period since it was last depreciated (or since purchase, if never).
     * Straight Line depreciates a fixed % of the original cost per year; WDV depreciates a fixed
     * % of the current book value per year. Both are pro-rated by days elapsed and capped so an
     * asset's book value never goes below zero. Assets with no rate set are skipped. Safe to
     * re-run any time — an asset already caught up through [asOf] simply contributes nothing. */
    suspend fun runDepreciation(asOf: Long): DepreciationResult {
        val expenseHeadId = ensureHeadIn("Depreciation", "Indirect Expenses")
        val accumHeadId = ensureHeadIn("Accumulated Depreciation", "Fixed Assets")
        if (expenseHeadId == 0L || accumHeadId == 0L) return DepreciationResult(0, 0.0)

        val toUpdate = mutableListOf<FixedAsset>()
        val names = mutableListOf<String>()
        var total = 0.0
        fixedAssetDao.all().forEach { a ->
            if (a.ratePercent <= 0.0) return@forEach
            val from = if (a.lastDepreciatedMillis > 0) a.lastDepreciatedMillis else a.purchaseDate
            val days = (asOf - from) / 86_400_000.0
            if (days <= 0.0) return@forEach
            val base = if (a.depreciationMethod == DepreciationMethod.WDV) a.bookValue else a.purchaseCost
            val amount = (base * a.ratePercent / 100.0 * (days / 365.0)).coerceAtMost(a.bookValue)
            if (amount <= 0.0) return@forEach
            total += amount
            names += a.name
            toUpdate += a.copy(accumulatedDepreciation = a.accumulatedDepreciation + amount, lastDepreciatedMillis = asOf)
        }
        if (toUpdate.isEmpty()) return DepreciationResult(0, 0.0)

        val entry = JournalEntry(
            voucherNo = "DEP-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis())),
            dateMillis = asOf,
            narration = "Depreciation for ${toUpdate.size} asset(s): ${names.joinToString(", ")}".take(500)
        )
        val lines = listOf(
            JournalLine(0, 0, expenseHeadId, "Depreciation", total, true),
            JournalLine(0, 0, accumHeadId, "Accumulated Depreciation", total, false)
        )
        saveJournal(entry, lines)
        toUpdate.forEach { fixedAssetDao.update(it) }
        return DepreciationResult(toUpdate.size, total)
    }

    // ---- journal ----
    suspend fun nextJournalNo(): String =
        "JV-" + (journalDao.localCountByType(JournalVoucherType.JOURNAL) + 1).toString().padStart(4, '0')

    suspend fun saveJournal(entry: JournalEntry, lines: List<JournalLine>): Long =
        journalDao.saveJournal(entry.copy(updatedAt = System.currentTimeMillis()), lines)
    suspend fun updateJournal(entry: JournalEntry, lines: List<JournalLine>) =
        journalDao.updateJournal(entry.copy(updatedAt = System.currentTimeMillis()), lines)
    suspend fun deleteJournal(entry: JournalEntry) = journalDao.deleteJournal(entry)
    suspend fun journalById(id: Long): JournalEntry? = journalDao.byId(id)
    suspend fun journalLinesFor(id: Long): List<JournalLine> = journalDao.linesFor(id)

    // ---- contra entry (Cash/Bank transfer, stored as a JournalEntry tagged CONTRA) ----
    val contraEntries: Flow<List<JournalEntry>> = journalDao.observeByType(JournalVoucherType.CONTRA)
    suspend fun nextContraNo(): String =
        "CN-" + (journalDao.localCountByType(JournalVoucherType.CONTRA) + 1).toString().padStart(4, '0')

    // ---- auth / users ----
    suspend fun login(username: String, password: String): User? {
        val user = userDao.byUsername(username.trim()) ?: return null
        return if (PasswordHasher.verify(password, user.passwordHash)) user else null
    }

    suspend fun userById(id: Long): User? = userDao.byId(id)

    /** The built-in super-admin, for automatic login. */
    suspend fun superAdmin(): User? = userDao.byUsername(DEFAULT_USERNAME)

    /**
     * True while the factory account still has its printed password. Drives both the
     * credential hint on the login screen and the forced password change after login —
     * derived from the database, so it can never drift out of sync with reality.
     */
    suspend fun usesDefaultPassword(): Boolean {
        val user = userDao.byUsername(DEFAULT_USERNAME) ?: return false
        return PasswordHasher.verify(DEFAULT_PASSWORD, user.passwordHash)
    }

    fun isDefaultAccount(user: User): Boolean =
        user.username.equals(DEFAULT_USERNAME, true) &&
            PasswordHasher.verify(DEFAULT_PASSWORD, user.passwordHash)

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
    suspend fun addCustomer(name: String, phone: String, address: String, gstin: String = "", customerType: String = "General", state: String = ""): Long {
        val id = customerDao.insert(Customer(name = name.trim(), phone = phone.trim(), address = address.trim(), gstin = gstin.trim(), customerType = customerType.trim().ifBlank { "General" }, state = state.trim()))
        ensureCustomerHead(name)   // create the customer's ledger head immediately
        return id
    }

    suspend fun addCustomerReturning(name: String, phone: String, customerType: String = "General"): Customer {
        val id = customerDao.insert(Customer(name = name.trim(), phone = phone.trim(), customerType = customerType.trim().ifBlank { "General" }))
        ensureCustomerHead(name)
        return Customer(id = id, name = name.trim(), phone = phone.trim(), customerType = customerType.trim().ifBlank { "General" })
    }

    suspend fun customerByPhone(phone: String): Customer? =
        phone.trim().takeIf { it.isNotBlank() }?.let { customerDao.byPhone(it) }

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

    // ---- customer opening balance (lives on the customer's ledger head, like any other account) ----
    suspend fun customerOpeningBalance(customerName: String): Pair<Double, Boolean> {
        val gid = accountDao.allGroups().firstOrNull { it.name == "Sundry Debtors" }?.id ?: return 0.0 to true
        val head = accountDao.headByNameGroup(customerName.trim(), gid) ?: return 0.0 to true
        return head.openingBalance to head.openingIsDebit
    }

    suspend fun setCustomerOpeningBalance(customerName: String, amount: Double, isDebit: Boolean) {
        val headId = ensureCustomerHead(customerName)
        val head = accountDao.headById(headId) ?: return
        accountDao.updateHead(head.copy(openingBalance = amount, openingIsDebit = isDebit))
    }

    // ---- quick invoices: a customer-dialog shortcut for a legacy due (amount+note+date, no items) ----
    suspend fun addQuickInvoice(customer: Customer, amount: Double, note: String, dateMillis: Long): Long {
        ensureCustomerHead(customer.name)
        val bill = Bill(
            billNo = nextBillNo(),
            dateMillis = dateMillis,
            customerId = customer.id,
            customerName = customer.name,
            paymentMethod = PaymentMethod.CREDIT.label,
            subTotal = amount,
            taxTotal = 0.0,
            additionalCharge = 0.0,
            discount = 0.0,
            grandTotal = amount,
            paidAmount = 0.0,
            customerGstin = customer.gstin,
            customerState = customer.state,
            remarks = note.trim(),
            isLegacy = true,
            updatedAt = System.currentTimeMillis()
        )
        return billDao.saveBill(bill, emptyList())
    }

    fun quickInvoicesForCustomer(customerId: Long): Flow<List<Bill>> = billDao.quickInvoicesForCustomer(customerId)

    val allCustomers: Flow<List<Customer>> get() = customers

    suspend fun addItem(
        name: String, price: Double, taxPercent: Double, barcode: String = "", hsn: String = "",
        category: String = "", openingStock: Double = 0.0, unit: String = "PCS", storeLocation: String = "",
        chemicalContent: String = "", secondaryUnit: String = "PCS", conversionFactor: Double = 1.0,
        purchasePrice: Double = 0.0, mrp: Double = 0.0, cessPercent: Double = 0.0
    ): Long =
        itemDao.insert(Item(
            name = name.trim(), price = price, purchasePrice = purchasePrice, mrp = mrp, taxPercent = taxPercent,
            cessPercent = cessPercent,
            barcode = barcode.trim(), hsn = hsn.trim(),
            category = category.trim(), openingStock = openingStock, unit = unit.trim().ifBlank { "PCS" },
            secondaryUnit = secondaryUnit.trim().ifBlank { "PCS" },
            conversionFactor = if (conversionFactor > 0) conversionFactor else 1.0,
            storeLocation = storeLocation.trim(), chemicalContent = chemicalContent.trim()
        ))

    suspend fun updateItem(item: Item) = itemDao.update(item)
    suspend fun deleteItem(item: Item) {
        itemAttachmentDao.forItem(item.id).forEach { com.billing.pos.items.ItemAttachmentStore.delete(it) }
        itemAttachmentDao.deleteForItem(item.id)
        db.itemBatchDao().deleteForItem(item.id)
        db.itemSizeDao().deleteForItem(item.id)
        itemDao.delete(item)
    }

    /** Removes every master item, its batches, sizes and attachment files. Bills keep their lines. */
    suspend fun clearAllItems() {
        itemAttachmentDao.all().forEach { com.billing.pos.items.ItemAttachmentStore.delete(it) }
        itemAttachmentDao.deleteAll()
        db.itemBatchDao().deleteAll()
        db.itemSizeDao().deleteAll()
        itemDao.deleteAll()
    }
    suspend fun itemByBarcode(barcode: String): Item? = itemDao.byBarcode(barcode.trim())
    suspend fun itemByName(name: String): Item? = itemDao.byName(name.trim())

    /** Seeds starter demo items for a business type — only when there are no items at all yet. */
    suspend fun seedSampleItems(businessType: String): Int {
        if (itemDao.count() > 0) return 0
        val samples = SampleData.itemsFor(businessType)
        samples.forEach { s -> addItem(s.name, s.price, 0.0, "", "", s.category, 0.0, s.unit, "", s.chemical) }
        return samples.size
    }

    // ---- item batches (batch no + expiry + qty) ----
    private val itemBatchDao = db.itemBatchDao()
    val itemBatches: Flow<List<ItemBatch>> = itemBatchDao.observeAll()

    /** itemId -> total quantity across all its batches, for stock display once batch stock has been recorded. */
    val batchStockByItem: Flow<Map<Long, Double>> = itemBatches.map { list ->
        list.groupBy { it.itemId }.mapValues { (_, l) -> l.sumOf { it.quantity } }
    }

    /** Batch-wise purchase rate + source voucher, for expiry costing and drill-down. */
    val batchCosts: Flow<List<BatchCostRow>> = purchaseDao.observeBatchCosts()
    suspend fun batchesForItem(itemId: Long): List<ItemBatch> = itemBatchDao.forItem(itemId)
    suspend fun addBatch(batch: ItemBatch): Long = itemBatchDao.insert(batch)
    suspend fun replaceBatches(itemId: Long, batches: List<ItemBatch>) {
        itemBatchDao.deleteForItem(itemId)
        batches.forEach { itemBatchDao.insert(it.copy(id = 0, itemId = itemId)) }
    }
    suspend fun batchByItemAndNo(itemId: Long, batchNo: String): ItemBatch? = itemBatchDao.byItemAndNo(itemId, batchNo)
    /** Deducts a sold quantity from a batch's stock. */
    suspend fun deductBatch(itemId: Long, batchNo: String, qty: Double) {
        if (batchNo.isNotBlank()) itemBatchDao.deductQty(itemId, batchNo, qty)
    }
    /** Receives stock into a batch: adds to an existing batch, or creates a new one. */
    suspend fun receiveBatch(itemId: Long, batchNo: String, expiryMillis: Long, qty: Double) {
        if (batchNo.isBlank() || itemId <= 0) return
        val existing = itemBatchDao.byItemAndNo(itemId, batchNo)
        if (existing != null) itemBatchDao.addQty(itemId, batchNo, qty)
        else itemBatchDao.insert(ItemBatch(itemId = itemId, batchNo = batchNo, expiryMillis = expiryMillis, quantity = qty))
    }

    // ---- quotations ----
    private val quotationDao = db.quotationDao()
    val quotations: Flow<List<Quotation>> = quotationDao.observeAll()
    suspend fun nextQuotationNo(): String = "QT-" + (quotationDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveQuotation(q: Quotation, lines: List<QuotationItem>): Long =
        quotationDao.save(q.copy(updatedAt = System.currentTimeMillis()), lines)
    suspend fun updateQuotation(q: Quotation, lines: List<QuotationItem>) =
        quotationDao.update(q.copy(updatedAt = System.currentTimeMillis()), lines)
    suspend fun deleteQuotation(q: Quotation) = quotationDao.delete(q)
    suspend fun quotationById(id: Long): Quotation? = quotationDao.byId(id)
    suspend fun quotationLines(id: Long): List<QuotationItem> = quotationDao.linesFor(id)

    /** Past item notes typed on any quotation line, most-used first — the note editor's suggestions. */
    suspend fun quotationItemNoteSuggestions(): List<String> =
        quotationDao.allLines().map { it.note.trim() }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .map { it.key }

    /** Marks these quotations as converted into [billNo], so they show as used and aren't offered again. */
    suspend fun markQuotationsConverted(ids: List<Long>, billNo: String) {
        ids.forEach { id -> quotationDao.byId(id)?.let { quotationDao.updateHeader(it.copy(convertedBillNo = billNo, updatedAt = System.currentTimeMillis())) } }
    }

    // ---- payment attachments ----
    private val custOrderDao = db.custOrderDao()

    val custOrders: Flow<List<CustOrder>> = custOrderDao.observeAll()
    val custOrderLinesFlow: Flow<List<CustOrderItem>> = custOrderDao.observeAllLines()
    suspend fun nextOrderNo(): String = "ORD-" + (custOrderDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveOrder(o: CustOrder, lines: List<CustOrderItem>): Long = custOrderDao.save(o, lines)
    suspend fun updateOrder(o: CustOrder, lines: List<CustOrderItem>) = custOrderDao.update(o, lines)
    suspend fun deleteOrder(o: CustOrder) = custOrderDao.delete(o)
    suspend fun orderById(id: Long): CustOrder? = custOrderDao.byId(id)
    suspend fun orderLines(id: Long): List<CustOrderItem> = custOrderDao.linesFor(id)
    suspend fun orderAttachmentsFor(id: Long): List<CustOrderAttachment> = custOrderDao.attachmentsFor(id)
    suspend fun replaceOrderAttachments(orderId: Long, list: List<CustOrderAttachment>) {
        val existing = custOrderDao.attachmentsFor(orderId)
        val keep = list.map { it.path }.toSet()
        existing.filter { it.path !in keep }.forEach { runCatching { java.io.File(it.path).delete() } }
        custOrderDao.deleteAttachments(orderId)
        list.forEach { custOrderDao.insertAttachment(it.copy(id = 0, orderId = orderId)) }
    }
    suspend fun updateOrderItemStatus(id: Long, status: OrderStatus) = custOrderDao.updateItemStatus(id, status.name)
    suspend fun updateOrderItemStatusBulk(ids: List<Long>, status: OrderStatus) = custOrderDao.updateItemStatusBulk(ids, status.name)

    // ---- salesman (deviceId -> display name) mapping ----
    private val salesmanMapDao = db.salesmanMapDao()
    val salesmen: Flow<List<SalesmanMap>> = salesmanMapDao.observeAll()
    suspend fun upsertSalesman(deviceId: String, name: String) = salesmanMapDao.upsert(SalesmanMap(deviceId, name))
    suspend fun deleteSalesman(deviceId: String) = salesmanMapDao.delete(deviceId)

    private val customerAttachmentDao = db.customerAttachmentDao()

    suspend fun customerAttachmentsFor(customerId: Long): List<CustomerAttachment> =
        customerAttachmentDao.forCustomer(customerId)

    /** Replaces the whole set, deleting the files of any row that was removed. */
    suspend fun replaceCustomerAttachments(customerId: Long, list: List<CustomerAttachment>) {
        val existing = customerAttachmentDao.forCustomer(customerId)
        val keep = list.map { it.path }.toSet()
        existing.filter { it.path !in keep }.forEach { runCatching { java.io.File(it.path).delete() } }
        customerAttachmentDao.deleteForCustomer(customerId)
        list.forEach { customerAttachmentDao.insert(it.copy(id = 0, customerId = customerId)) }
    }

    /** Adds attachments to a customer without touching the ones already there. */
    suspend fun appendCustomerAttachments(customerId: Long, list: List<CustomerAttachment>) {
        list.forEach { customerAttachmentDao.insert(it.copy(id = 0, customerId = customerId)) }
    }

    private val savedCalcDao = db.savedCalcDao()

    /** Saved calculator tapes, newest first. */
    val savedCalcs: kotlinx.coroutines.flow.Flow<List<SavedCalc>> = savedCalcDao.observeAll()

    suspend fun savedCalcsAll(): List<SavedCalc> = savedCalcDao.all()

    suspend fun saveCalc(c: SavedCalc): Long =
        if (c.id == 0L) savedCalcDao.insert(c) else { savedCalcDao.update(c); c.id }

    suspend fun deleteCalc(id: Long) = savedCalcDao.delete(id)

    private val expenseAttachmentDao = db.expenseAttachmentDao()
    suspend fun expenseAttachmentsFor(expenseId: Long): List<ExpenseAttachment> =
        expenseAttachmentDao.forExpense(expenseId)

    /** Replaces the attachment rows for a payment, deleting files that were removed. */
    suspend fun replaceExpenseAttachments(expenseId: Long, list: List<ExpenseAttachment>) {
        val existing = expenseAttachmentDao.forExpense(expenseId)
        val keep = list.map { it.path }.toSet()
        existing.filter { it.path !in keep }.forEach { com.billing.pos.expenses.ExpenseAttachmentStore.delete(it) }
        expenseAttachmentDao.deleteForExpense(expenseId)
        list.forEach { expenseAttachmentDao.insert(it.copy(id = 0, expenseId = expenseId)) }
    }

    // ---- receipt attachments ----
    private val receiptAttachmentDao = db.receiptAttachmentDao()
    suspend fun receiptAttachmentsFor(receiptId: Long): List<ReceiptAttachment> =
        receiptAttachmentDao.forReceipt(receiptId)

    /** Replaces the attachment rows for a receipt, deleting files that were removed. */
    suspend fun replaceReceiptAttachments(receiptId: Long, list: List<ReceiptAttachment>) {
        val existing = receiptAttachmentDao.forReceipt(receiptId)
        val keep = list.map { it.path }.toSet()
        existing.filter { it.path !in keep }.forEach { ReceiptAttachmentStore.delete(it) }
        receiptAttachmentDao.deleteForReceipt(receiptId)
        receiptAttachmentDao.insertAll(list.map { it.copy(id = 0, receiptId = receiptId) })
    }

    // ---- estimates ----
    // Deliberately absent from stockByName: an estimate never moves stock.
    private val estimateDao = db.estimateDao()
    val estimates: Flow<List<Estimate>> = estimateDao.observeAll()
    suspend fun nextEstimateNo(): String = "EST-" + (estimateDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveEstimate(e: Estimate, lines: List<EstimateItem>): Long =
        estimateDao.save(e.copy(updatedAt = System.currentTimeMillis()), lines)
    suspend fun updateEstimate(e: Estimate, lines: List<EstimateItem>) =
        estimateDao.update(e.copy(updatedAt = System.currentTimeMillis()), lines)
    suspend fun deleteEstimate(e: Estimate) = estimateDao.delete(e)
    suspend fun estimateById(id: Long): Estimate? = estimateDao.byId(id)
    suspend fun estimateLines(id: Long): List<EstimateItem> = estimateDao.linesFor(id)

    // ---- sales returns ----
    private val salesReturnDao = db.salesReturnDao()
    val salesReturns: Flow<List<SalesReturn>> = salesReturnDao.observeAll()
    suspend fun nextSalesReturnNo(): String = "SR-" + (salesReturnDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveSalesReturn(r: SalesReturn, lines: List<SalesReturnItem>): Long = salesReturnDao.save(r, lines)
    suspend fun updateSalesReturn(r: SalesReturn, lines: List<SalesReturnItem>) = salesReturnDao.update(r, lines)
    suspend fun deleteSalesReturn(r: SalesReturn) = salesReturnDao.delete(r)
    suspend fun salesReturnById(id: Long): SalesReturn? = salesReturnDao.byId(id)
    suspend fun salesReturnLines(id: Long): List<SalesReturnItem> = salesReturnDao.linesFor(id)

    /** Each customer's net receivable (opening balance + credit bills − receipts − returns), by name. */
    val customerBalances: Flow<Map<String, Double>> = kotlinx.coroutines.flow.combine(
        accountHeads, accountGroups, allBills, allReceipts, salesReturns
    ) { heads, groups, bills, receipts, returns ->
        com.billing.pos.report.AccountingEngine.build(
            heads, groups, bills, emptyList(), receipts, emptyList(), emptyList(), emptyList(), returns, emptyList()
        ).filter { it.group == "Sundry Debtors" }
            .groupBy { it.head }
            .mapValues { (_, postings) -> postings.sumOf { it.debit - it.credit } }
    }

    // ---- purchase returns ----
    private val purchaseReturnDao = db.purchaseReturnDao()
    val purchaseReturns: Flow<List<PurchaseReturn>> = purchaseReturnDao.observeAll()

    /** Each supplier's net payable (opening balance + credit purchases − payments − returns), by name. */
    val supplierBalances: Flow<Map<String, Double>> = kotlinx.coroutines.flow.combine(
        accountHeads, accountGroups, allPurchases, allExpenses, purchaseReturns
    ) { heads, groups, purchases, expenses, returns ->
        com.billing.pos.report.AccountingEngine.build(
            heads, groups, emptyList(), purchases, emptyList(), expenses, emptyList(), emptyList(), emptyList(), returns
        ).filter { it.group == "Sundry Creditors" }
            .groupBy { it.head }
            .mapValues { (_, postings) -> postings.sumOf { it.credit - it.debit } }
    }
    suspend fun nextPurchaseReturnNo(): String = "PR-" + (purchaseReturnDao.count() + 1).toString().padStart(4, '0')
    suspend fun savePurchaseReturn(r: PurchaseReturn, lines: List<PurchaseReturnItem>): Long = purchaseReturnDao.save(r, lines)
    suspend fun updatePurchaseReturn(r: PurchaseReturn, lines: List<PurchaseReturnItem>) = purchaseReturnDao.update(r, lines)
    suspend fun deletePurchaseReturn(r: PurchaseReturn) = purchaseReturnDao.delete(r)
    suspend fun purchaseReturnById(id: Long): PurchaseReturn? = purchaseReturnDao.byId(id)
    suspend fun purchaseReturnLines(id: Long): List<PurchaseReturnItem> = purchaseReturnDao.linesFor(id)

    // ---- purchase quotations (LPO) ----
    private val purchaseQuotationDao = db.purchaseQuotationDao()
    val purchaseQuotations: Flow<List<PurchaseQuotation>> = purchaseQuotationDao.observeAll()
    private val purchaseQuoteDao = db.purchaseQuoteDao()

    val purchaseQuotes: Flow<List<PurchaseQuote>> = purchaseQuoteDao.observeAll()
    suspend fun nextPurchaseQuoteNo(): String = "PQ-" + (purchaseQuoteDao.count() + 1).toString().padStart(4, '0')
    suspend fun savePurchaseQuote(r: PurchaseQuote, lines: List<PurchaseQuoteItem>): Long = purchaseQuoteDao.save(r, lines)
    suspend fun updatePurchaseQuote(r: PurchaseQuote, lines: List<PurchaseQuoteItem>) = purchaseQuoteDao.update(r, lines)
    suspend fun deletePurchaseQuote(r: PurchaseQuote) = purchaseQuoteDao.delete(r)
    suspend fun purchaseQuoteById(id: Long): PurchaseQuote? = purchaseQuoteDao.byId(id)
    suspend fun purchaseQuoteLines(id: Long): List<PurchaseQuoteItem> = purchaseQuoteDao.linesFor(id)

    suspend fun nextLpoNo(): String = "LPO-" + (purchaseQuotationDao.count() + 1).toString().padStart(4, '0')
    suspend fun savePurchaseQuotation(r: PurchaseQuotation, lines: List<PurchaseQuotationItem>): Long = purchaseQuotationDao.save(r, lines)
    suspend fun updatePurchaseQuotation(r: PurchaseQuotation, lines: List<PurchaseQuotationItem>) = purchaseQuotationDao.update(r, lines)
    suspend fun deletePurchaseQuotation(r: PurchaseQuotation) = purchaseQuotationDao.delete(r)
    suspend fun purchaseQuotationById(id: Long): PurchaseQuotation? = purchaseQuotationDao.byId(id)
    suspend fun purchaseQuotationLines(id: Long): List<PurchaseQuotationItem> = purchaseQuotationDao.linesFor(id)

    // ---- hire (rental) invoices ----
    private val hireInvoiceDao = db.hireInvoiceDao()
    val hireInvoices: Flow<List<HireInvoice>> = hireInvoiceDao.observeAll()
    val hireOutByItem: Flow<List<HireNameQty>> = hireInvoiceDao.observeOutByItem()
    val hireLinesFlow: Flow<List<HireInvoiceItem>> = hireInvoiceDao.observeAllLines()
    suspend fun nextHireNo(): String = "HR-" + (hireInvoiceDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveHireInvoice(h: HireInvoice, lines: List<HireInvoiceItem>): Long = hireInvoiceDao.save(h, lines)
    suspend fun updateHireInvoice(h: HireInvoice, lines: List<HireInvoiceItem>) = hireInvoiceDao.update(h, lines)
    suspend fun deleteHireInvoice(h: HireInvoice) = hireInvoiceDao.delete(h)
    suspend fun hireInvoiceById(id: Long): HireInvoice? = hireInvoiceDao.byId(id)
    suspend fun hireInvoiceLines(id: Long): List<HireInvoiceItem> = hireInvoiceDao.linesFor(id)
    suspend fun allHireLines(): List<HireInvoiceItem> = hireInvoiceDao.allLines()

    // ---- hire returns ----
    private val hireReturnDao = db.hireReturnDao()
    val hireReturns: Flow<List<HireReturn>> = hireReturnDao.observeAll()
    val hireReturnedByItem: Flow<List<HireNameQty>> = hireReturnDao.observeReturnedByItem()
    val hireReturnedByHire: Flow<List<HireIdQty>> = hireReturnDao.observeReturnedByHire()
    suspend fun nextHireReturnNo(): String = "HRR-" + (hireReturnDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveHireReturn(r: HireReturn, lines: List<HireReturnItem>): Long = hireReturnDao.save(r, lines)
    suspend fun updateHireReturn(r: HireReturn, lines: List<HireReturnItem>) = hireReturnDao.update(r, lines)
    suspend fun deleteHireReturn(r: HireReturn) = hireReturnDao.delete(r)
    suspend fun hireReturnById(id: Long): HireReturn? = hireReturnDao.byId(id)
    suspend fun hireReturnLines(id: Long): List<HireReturnItem> = hireReturnDao.linesFor(id)
    suspend fun returnedQtyForHire(hireId: Long): Double = hireReturnDao.returnedQtyForHire(hireId) ?: 0.0

    // ---- medical lab: tests + evaluations ----
    private val labTestDao = db.labTestDao()
    val labTests: Flow<List<LabTest>> = labTestDao.observeTests()
    suspend fun labTestCount(): Int = labTestDao.count()
    suspend fun saveLabTest(t: LabTest, evals: List<LabEvaluation>): Long = labTestDao.saveTest(t, evals)
    suspend fun deleteLabTest(t: LabTest) = labTestDao.delete(t)
    suspend fun labTestById(id: Long): LabTest? = labTestDao.testById(id)
    suspend fun labEvaluationsFor(testId: Long): List<LabEvaluation> = labTestDao.evaluationsFor(testId)
    suspend fun allLabTests(): List<LabTest> = labTestDao.allTests()
    /** Seeds the built-in sample tests the first time (only when none exist). Also fills the masters. */
    suspend fun seedSampleLabTests(): Int {
        if (labTestDao.count() > 0) return 0
        SampleLabData.tests.forEach { s ->
            labTestDao.saveTest(
                LabTest(name = s.name, price = s.price, sampleType = s.sampleType),
                s.evaluations.map { LabEvaluation(testId = 0, name = it.name, unit = it.unit, normalValue = it.normal, groupName = it.group) }
            )
            s.evaluations.forEach { addEvalToMaster(it.name, it.unit, it.normal, it.group) }
        }
        return SampleLabData.tests.size
    }

    // ---- lab masters: groups + evaluations ----
    private val labMasterDao = db.labMasterDao()
    val labGroups: Flow<List<LabGroup>> = labMasterDao.observeGroups()
    val labEvalMasters: Flow<List<LabEvalMaster>> = labMasterDao.observeEvals()
    suspend fun saveLabGroup(g: LabGroup): Long = labMasterDao.insertGroup(g)
    suspend fun deleteLabGroup(g: LabGroup) = labMasterDao.deleteGroup(g)
    suspend fun ensureLabGroup(name: String) { if (name.isNotBlank() && labMasterDao.groupByName(name) == null) labMasterDao.insertGroup(LabGroup(name = name.trim())) }
    suspend fun saveLabEvalMaster(e: LabEvalMaster): Long = if (e.id == 0L) labMasterDao.insertEval(e) else { labMasterDao.updateEval(e); e.id }
    suspend fun deleteLabEvalMaster(e: LabEvalMaster) = labMasterDao.deleteEval(e)
    suspend fun labEvalsInGroup(group: String): List<LabEvalMaster> = labMasterDao.evalsInGroup(group)
    suspend fun allLabGroups(): List<LabGroup> = labMasterDao.allGroups()
    suspend fun allLabEvalMasters(): List<LabEvalMaster> = labMasterDao.allEvals()
    /** Adds an evaluation to the master if a same-named one doesn't already exist (and its group). */
    suspend fun addEvalToMaster(name: String, unit: String, normal: String, group: String) {
        if (name.isBlank()) return
        ensureLabGroup(group)
        if (labMasterDao.evalByName(name) == null)
            labMasterDao.insertEval(LabEvalMaster(name = name.trim(), unit = unit.trim(), normalValue = normal.trim(), groupName = group.trim()))
    }
    val labHeadings: Flow<List<LabHeading>> = labMasterDao.observeHeadings()
    suspend fun deleteLabHeading(h: LabHeading) = labMasterDao.deleteHeading(h)
    suspend fun addHeadingToMaster(name: String) { if (name.isNotBlank() && labMasterDao.headingByName(name) == null) labMasterDao.insertHeading(LabHeading(name = name.trim())) }
    suspend fun allLabHeadings(): List<LabHeading> = labMasterDao.allHeadings()
    val labDoctors: Flow<List<LabDoctor>> = labMasterDao.observeDoctors()
    suspend fun deleteLabDoctor(d: LabDoctor) = labMasterDao.deleteDoctor(d)
    suspend fun addDoctorToMaster(name: String) { if (name.isNotBlank() && labMasterDao.doctorByName(name) == null) labMasterDao.insertDoctor(LabDoctor(name = name.trim())) }
    suspend fun allLabDoctors(): List<LabDoctor> = labMasterDao.allDoctors()

    // ---- lab patients ----
    private val patientDao = db.patientDao()
    val patients: Flow<List<Patient>> = patientDao.observeAll()
    suspend fun savePatient(p: Patient): Long = if (p.id == 0L) patientDao.insert(p) else { patientDao.update(p); p.id }
    suspend fun deletePatient(p: Patient) = patientDao.delete(p)
    suspend fun patientById(id: Long): Patient? = patientDao.byId(id)

    // ---- lab bills + results ----
    private val labBillDao = db.labBillDao()
    val labBills: Flow<List<LabBill>> = labBillDao.observeBills()
    suspend fun nextLabBillNo(): String = "LAB-" + (labBillDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveLabBill(b: LabBill, tests: List<LabBillTest>): Long = labBillDao.saveBill(b, tests)
    suspend fun deleteLabBill(b: LabBill) = labBillDao.delete(b)
    suspend fun labBillById(id: Long): LabBill? = labBillDao.billById(id)
    suspend fun labBillTests(id: Long): List<LabBillTest> = labBillDao.testsFor(id)
    suspend fun labResultsFor(id: Long): List<LabResultValue> = labBillDao.resultsFor(id)
    suspend fun saveLabResults(bill: LabBill, results: List<LabResultValue>) = labBillDao.saveResults(bill, results)

    // ---- lab balance receipts ----
    private val labReceiptDao = db.labReceiptDao()
    val labReceipts: Flow<List<LabReceipt>> = labReceiptDao.observeAll()
    val labReceiptSumByBill: Flow<List<BillIdSum>> = labReceiptDao.observeSumByBill()
    suspend fun addLabReceipt(r: LabReceipt): Long = labReceiptDao.insert(r)
    suspend fun deleteLabReceipt(r: LabReceipt) = labReceiptDao.delete(r)
    /** Outstanding for a lab bill = grand total − advance paid − later receipts. */
    suspend fun labBillOutstanding(bill: LabBill): Double =
        (bill.grandTotal - bill.paidAmount - labReceiptDao.sumForBill(bill.id)).coerceAtLeast(0.0)

    // ---- material out (consumption / issue) ----
    private val materialOutDao = db.materialOutDao()
    val materialOuts: Flow<List<MaterialOut>> = materialOutDao.observeAll()
    val materialOutByItem: Flow<List<NameQty>> = materialOutDao.observeOutByItem()
    suspend fun nextMaterialOutNo(): String = "MAT-" + (materialOutDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveMaterialOut(m: MaterialOut, lines: List<MaterialOutItem>): Long = materialOutDao.save(m, lines)
    suspend fun updateMaterialOut(m: MaterialOut, lines: List<MaterialOutItem>) = materialOutDao.update(m, lines)
    suspend fun deleteMaterialOut(m: MaterialOut) = materialOutDao.delete(m)
    suspend fun materialOutById(id: Long): MaterialOut? = materialOutDao.byId(id)
    suspend fun materialOutLines(id: Long): List<MaterialOutItem> = materialOutDao.linesFor(id)

    // ---- material receipts (goods received against an LPO) ----
    private val materialReceiptDao = db.materialReceiptDao()
    val materialReceipts: Flow<List<MaterialReceipt>> = materialReceiptDao.observeAll()
    val materialReceivedByItem: Flow<List<NameQty>> = materialReceiptDao.observeReceivedByItem()
    val receivedByLpo: Flow<List<LpoReceivedRow>> = materialReceiptDao.observeReceivedByLpo()

    // ---- delivery notes (goods delivered to a customer — decreases stock) ----
    private val deliveryNoteDao = db.deliveryNoteDao()
    val deliveryNotes: Flow<List<DeliveryNote>> = deliveryNoteDao.observeAll()
    val deliveredByItem: Flow<List<NameQty>> = deliveryNoteDao.observeDeliveredByItem()
    suspend fun nextDeliveryNo(): String = tagPrefix() + "DN-" + (deliveryNoteDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveDeliveryNote(d: DeliveryNote, lines: List<DeliveryNoteItem>): Long = deliveryNoteDao.save(d, lines)
    suspend fun updateDeliveryNote(d: DeliveryNote, lines: List<DeliveryNoteItem>) = deliveryNoteDao.update(d, lines)
    suspend fun deleteDeliveryNote(d: DeliveryNote) = deliveryNoteDao.delete(d)
    suspend fun deliveryNoteById(id: Long): DeliveryNote? = deliveryNoteDao.byId(id)
    suspend fun deliveryNoteLines(id: Long): List<DeliveryNoteItem> = deliveryNoteDao.linesFor(id)

    /** Marks these delivery notes as converted into [billNo], so they show as used and aren't offered again. */
    suspend fun markDeliveryNotesConverted(ids: List<Long>, billNo: String) {
        ids.forEach { id -> deliveryNoteDao.byId(id)?.let { deliveryNoteDao.updateHeader(it.copy(convertedBillNo = billNo)) } }
    }
    val purchaseQuotationLinesFlow: Flow<List<PurchaseQuotationItem>> = purchaseQuotationDao.observeAllLines()
    suspend fun nextMrnNo(): String = "MRN-" + (materialReceiptDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveMaterialReceipt(m: MaterialReceipt, lines: List<MaterialReceiptItem>): Long = materialReceiptDao.save(m, lines)
    suspend fun updateMaterialReceipt(m: MaterialReceipt, lines: List<MaterialReceiptItem>) = materialReceiptDao.update(m, lines)
    suspend fun deleteMaterialReceipt(m: MaterialReceipt) = materialReceiptDao.delete(m)
    suspend fun materialReceiptById(id: Long): MaterialReceipt? = materialReceiptDao.byId(id)
    suspend fun materialReceiptLines(id: Long): List<MaterialReceiptItem> = materialReceiptDao.linesFor(id)

    /** Marks these material receipts as converted into [purchaseNo], so they show as used and aren't offered again. */
    suspend fun markMaterialReceiptsConverted(ids: List<Long>, purchaseNo: String) {
        ids.forEach { id -> materialReceiptDao.byId(id)?.let { materialReceiptDao.updateHeader(it.copy(convertedPurchaseNo = purchaseNo)) } }
    }
    suspend fun returnedQtyForSupplier(supplierId: Long): List<NameQty> = purchaseDao.returnedQtyForSupplier(supplierId)

    /**
     * name (lowercased) -> net live-stock delta = stock-purchases + material receipts
     * - sales - material out - delivery notes. Openings are added per item on top of this.
     */
    val stockByName: Flow<Map<String, Double>> =
        kotlinx.coroutines.flow.combine(
            purchaseDao.observePurchaseStockQty(), materialReceivedByItem, soldQty, materialOutByItem, deliveredByItem
        ) { pur, recv, sold, out, delivered ->
            val m = HashMap<String, Double>()
            fun apply(list: List<NameQty>, sign: Double) = list.forEach { nq ->
                val k = nq.name.lowercase(); m[k] = (m[k] ?: 0.0) + sign * nq.qty
            }
            apply(pur, 1.0); apply(recv, 1.0); apply(sold, -1.0); apply(out, -1.0); apply(delivered, -1.0)
            m
        }

    /** All material-out lines belonging to vouchers that reference [billNo]. */
    suspend fun usedMaterialsForBill(billNo: String): List<MaterialOutItem> {
        if (billNo.isBlank()) return emptyList()
        return materialOutDao.all().filter { it.resultRef.contains(billNo, ignoreCase = true) }
            .flatMap { materialOutDao.linesFor(it.id) }
    }
    /** Latest purchase rate per item name (primary unit) — cost basis. */
    suspend fun lastPurchaseRates(): Map<String, Double> =
        purchaseDao.purchaseLinesOnce().groupBy { it.name.lowercase() }
            .mapValues { (_, l) -> l.maxByOrNull { it.dateMillis }?.price ?: 0.0 }

    // ---- item movement report sources ----
    val saleMovements: Flow<List<MoveRow>> = billDao.observeSaleMovements()
    val purchaseMovements: Flow<List<MoveRow>> = purchaseDao.observePurchaseMovements()
    val materialMovements: Flow<List<MoveRow>> = materialOutDao.observeMovements()

    // ---- production (procedures/recipes, and the runs that use them) ----
    private val productionDao = db.productionDao()
    val procedures: Flow<List<ProductionProcedure>> = productionDao.observeProcedures()
    suspend fun procedureMaterials(id: Long): List<ProductionProcedureMaterial> = productionDao.materialsFor(id)
    suspend fun saveProcedure(p: ProductionProcedure, materials: List<ProductionProcedureMaterial>): Long = productionDao.saveProcedure(p, materials)
    suspend fun updateProcedure(p: ProductionProcedure, materials: List<ProductionProcedureMaterial>) = productionDao.updateProcedure(p, materials)
    suspend fun deleteProcedure(p: ProductionProcedure) = productionDao.deleteProcedure(p)
    suspend fun procedureById(id: Long): ProductionProcedure? = productionDao.procedureById(id)

    val productionRuns: Flow<List<ProductionRun>> = productionDao.observeRuns()
    suspend fun nextProductionRunNo(): String = "PROD-" + (productionDao.countRuns() + 1).toString().padStart(4, '0')

    /**
     * Runs a procedure to actually produce [qtyToProduce] of its item: scales the recipe's raw
     * materials and labour cost by qtyToProduce/procedure.producedQty, records the consumption
     * as a MaterialOut and the output as a MaterialReceipt (so stock and every existing stock
     * report need no Production-specific logic), and syncs the produced item's cost to what this
     * run actually cost per unit.
     */
    suspend fun produceRun(procedureId: Long, qtyToProduce: Double, remarks: String): Result<ProductionRun> = runCatching {
        require(qtyToProduce > 0) { "Quantity must be greater than zero" }
        val procedure = productionDao.procedureById(procedureId) ?: error("Procedure not found")
        require(procedure.producedQty > 0) { "Procedure's batch quantity must be greater than zero" }
        val materials = productionDao.materialsFor(procedureId)
        val multiplier = qtyToProduce / procedure.producedQty

        val outLines = materials.map { m ->
            val item = m.itemId.takeIf { it > 0 }?.let { itemDao.byId(it) }
            val rate = item?.costRate ?: 0.0
            val consumedQty = m.qty * multiplier
            Triple(m, consumedQty, consumedQty * rate)
        }
        val materialCost = outLines.sumOf { it.third }
        val labourCost = procedure.labourCost * multiplier
        val totalCost = materialCost + labourCost
        val unitCost = totalCost / qtyToProduce

        val runNo = nextProductionRunNo()
        val dateMillis = System.currentTimeMillis()

        val outId = materialOutDao.save(
            MaterialOut(voucherNo = runNo, dateMillis = dateMillis, remarks = "Production: " + procedure.name),
            outLines.map { (m, consumedQty, _) -> MaterialOutItem(0, 0, m.itemId, m.name, consumedQty, m.unit) }
        )
        val receiptId = materialReceiptDao.save(
            MaterialReceipt(receiptNo = runNo, dateMillis = dateMillis, supplierId = 0, supplierName = "Production", remarks = "Production: " + procedure.name),
            listOf(MaterialReceiptItem(0, 0, procedure.producedItemId, procedure.producedItemName, qtyToProduce, unitCost, unit = procedure.unit))
        )

        val run = ProductionRun(
            runNo = runNo, dateMillis = dateMillis, procedureId = procedure.id, procedureName = procedure.name,
            producedItemId = procedure.producedItemId, producedItemName = procedure.producedItemName,
            qtyProduced = qtyToProduce, unit = procedure.unit, materialCost = materialCost, labourCost = labourCost,
            totalCost = totalCost, unitCost = unitCost, remarks = remarks, materialOutId = outId, materialReceiptId = receiptId
        )
        val runId = productionDao.insertRun(run)
        // Tag both generated vouchers back to the run they belong to, and keep the produced
        // item's cost current so later reports/receipts reflect what it actually cost to make.
        materialOutDao.byId(outId)?.let { materialOutDao.updateHeader(it.copy(productionRunId = runId)) }
        materialReceiptDao.byId(receiptId)?.let { materialReceiptDao.updateHeader(it.copy(productionRunId = runId)) }
        itemDao.byId(procedure.producedItemId)?.let { itemDao.update(it.copy(purchasePrice = unitCost)) }

        run.copy(id = runId)
    }

    /**
     * One-time fix-up: tags every pre-existing (deviceId-less) bill/purchase/receipt/expense/
     * quotation/estimate/order on this device with this device's own id, so cloud-merge dedup
     * (by deviceId + document number) can recognize them too, not just records created after
     * the dedup fix shipped. Safe to call more than once — already-tagged rows are skipped.
     */
    suspend fun backfillDeviceIds() {
        val myId = License.deviceId(context)
        billDao.all().filter { it.deviceId.isBlank() }.forEach { billDao.updateBillHeader(it.copy(deviceId = myId)) }
        purchaseDao.all().filter { it.deviceId.isBlank() }.forEach { purchaseDao.updateHeader(it.copy(deviceId = myId)) }
        receiptDao.all().filter { it.deviceId.isBlank() }.forEach { receiptDao.update(it.copy(deviceId = myId)) }
        expenseDao.all().filter { it.deviceId.isBlank() }.forEach { expenseDao.update(it.copy(deviceId = myId)) }
        quotationDao.all().filter { it.deviceId.isBlank() }.forEach { quotationDao.updateHeader(it.copy(deviceId = myId)) }
        estimateDao.all().filter { it.deviceId.isBlank() }.forEach { estimateDao.updateHeader(it.copy(deviceId = myId)) }
        custOrderDao.all().filter { it.deviceId.isBlank() }.forEach { custOrderDao.updateHeader(it.copy(deviceId = myId)) }
    }

    /** Deletes a run and its linked MaterialOut/MaterialReceipt, reversing its stock effect. */
    suspend fun deleteProductionRun(run: ProductionRun) {
        if (run.materialOutId > 0) materialOutDao.byId(run.materialOutId)?.let { materialOutDao.delete(it) }
        if (run.materialReceiptId > 0) materialReceiptDao.byId(run.materialReceiptId)?.let { materialReceiptDao.delete(it) }
        productionDao.deleteRun(run)
    }

    // ---- item bundles (combos sold as one line, expanded into real item lines when sold) ----
    private val itemBundleDao = db.itemBundleDao()
    val bundles: Flow<List<ItemBundle>> = itemBundleDao.observeAll()
    suspend fun bundleComponents(id: Long): List<ItemBundleComponent> = itemBundleDao.componentsFor(id)
    suspend fun saveBundle(b: ItemBundle, components: List<ItemBundleComponent>): Long = itemBundleDao.save(b, components)
    suspend fun updateBundle(b: ItemBundle, components: List<ItemBundleComponent>) = itemBundleDao.update(b, components)
    suspend fun deleteBundle(b: ItemBundle) = itemBundleDao.delete(b)
    suspend fun bundleById(id: Long): ItemBundle? = itemBundleDao.byId(id)

    // ---- item sizes (variants with their own price) ----
    private val itemSizeDao = db.itemSizeDao()
    val itemSizes: Flow<List<ItemSize>> = itemSizeDao.observeAll()
    suspend fun sizesForItem(itemId: Long): List<ItemSize> = itemSizeDao.forItem(itemId)
    suspend fun replaceSizes(itemId: Long, sizes: List<ItemSize>) {
        itemSizeDao.deleteForItem(itemId)
        sizes.forEach { itemSizeDao.insert(it.copy(id = 0, itemId = itemId)) }
    }

    // ---- item attachments (photos / location photo / PDF catalogue) ----
    suspend fun itemAttachmentsFor(itemId: Long): List<ItemAttachment> = itemAttachmentDao.forItem(itemId)
    suspend fun addItemAttachment(attachment: ItemAttachment): Long = itemAttachmentDao.insert(attachment)
    suspend fun deleteItemAttachment(attachment: ItemAttachment) {
        itemAttachmentDao.delete(attachment)
        com.billing.pos.items.ItemAttachmentStore.delete(attachment)
    }

    // ---- bill attachments (documents attached to an invoice) ----
    suspend fun billAttachmentsFor(billId: Long): List<BillAttachment> = billAttachmentDao.forBill(billId)
    suspend fun addBillAttachment(attachment: BillAttachment): Long = billAttachmentDao.insert(attachment)
    suspend fun deleteBillAttachment(attachment: BillAttachment) {
        billAttachmentDao.delete(attachment)
        com.billing.pos.bills.BillAttachmentStore.delete(attachment)
    }

    /**
     * Bill number for the next locally-created bill, e.g. A-INV-0001 (the A- prefix is this
     * device's tag). In GST mode the series resets each Indian financial year (1 Apr – 31 Mar),
     * e.g. A-INV-25-26-0001, matching how GST invoice series are conventionally numbered.
     */
    suspend fun nextBillNo(): String {
        if (AppPrefs(context).gstEnabled) {
            val fyStart = financialYearStartMillis(System.currentTimeMillis())
            val n = billDao.localCountSince(fyStart) + 1
            return tagPrefix() + "INV-" + financialYearLabel(fyStart) + "-" + n.toString().padStart(4, '0')
        }
        val n = billDao.localCount() + 1
        return tagPrefix() + "INV-" + n.toString().padStart(4, '0')
    }

    /** Bill number for the next No Tax Invoice — its own series, separate from [nextBillNo],
     *  using the prefix set in Settings (e.g. NT-0001, NT-0002, ...). */
    suspend fun nextNoTaxBillNo(): String {
        val prefix = AppPrefs(context).noTaxInvoicePrefix.ifBlank { "NT-" }
        val n = billDao.localCountNoTax() + 1
        return tagPrefix() + prefix + n.toString().padStart(4, '0')
    }

    /** Start of the Indian financial year (1 April, 00:00) containing [millis]. */
    private fun financialYearStartMillis(millis: Long): Long {
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = millis
            if (get(java.util.Calendar.MONTH) < java.util.Calendar.APRIL) add(java.util.Calendar.YEAR, -1)
            set(java.util.Calendar.MONTH, java.util.Calendar.APRIL)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    /** "25-26" for the FY starting at [fyStartMillis]. */
    private fun financialYearLabel(fyStartMillis: Long): String {
        val startYear = java.util.Calendar.getInstance().apply { timeInMillis = fyStartMillis }.get(java.util.Calendar.YEAR)
        return (startYear % 100).toString().padStart(2, '0') + "-" + ((startYear + 1) % 100).toString().padStart(2, '0')
    }

    // ---- bills ----
    suspend fun saveBill(bill: Bill, lines: List<BillItem>): Long =
        billDao.saveBill(bill.copy(updatedAt = System.currentTimeMillis()), lines)
    suspend fun updateBill(bill: Bill, lines: List<BillItem>) =
        billDao.updateBill(bill.copy(updatedAt = System.currentTimeMillis()), lines)
    suspend fun deleteBill(bill: Bill) {
        billAttachmentDao.forBill(bill.id).forEach { com.billing.pos.bills.BillAttachmentStore.delete(it) }
        billAttachmentDao.deleteForBill(bill.id)
        billDao.deleteBill(bill)
    }
    suspend fun linesFor(billId: Long): List<BillItem> = billDao.linesFor(billId)
    suspend fun billById(id: Long): Bill? = billDao.byId(id)
    fun billsBetween(from: Long, to: Long): Flow<List<Bill>> = billDao.observeBetween(from, to)

    // ---- receipts (money received against credit invoices) ----
    suspend fun nextReceiptNo(): String =
        tagPrefix() + "RV-" + (receiptDao.localCount() + 1).toString().padStart(4, '0')

    /** Records a receipt against [bill] and increases the invoice's paid amount. */
    suspend fun addReceipt(bill: Bill, amount: Double, mode: PayMode, dateMillis: Long = System.currentTimeMillis(), toAccountId: Long = 0): Receipt {
        ensureCustomerHead(bill.customerName)
        val receipt = Receipt(
            receiptNo = nextReceiptNo(),
            billId = bill.id,
            billNo = bill.billNo,
            customerName = bill.customerName,
            dateMillis = dateMillis,
            amount = amount,
            paymentMode = mode.label,
            payFrom = bill.customerName,
            toAccountId = if (toAccountId != 0L) toAccountId else ensureModeAccount(mode.label),
            deviceId = License.deviceId(context),
            updatedAt = System.currentTimeMillis()
        )
        val newId = receiptDao.insert(receipt)
        val newPaid = (bill.paidAmount + amount).coerceAtMost(bill.grandTotal)
        billDao.updateBillHeader(bill.copy(paidAmount = newPaid, updatedAt = System.currentTimeMillis()))
        return receipt.copy(id = newId)
    }

    private val receiptAllocationDao = db.receiptAllocationDao()

    /**
     * One receipt settling several invoices for the same customer at once: each bill's own
     * paid amount is adjusted by its own share, but only ONE receipt (one ledger entry) is
     * posted, for the sum of all shares.
     */
    suspend fun addReceiptForBills(shares: List<Pair<Bill, Double>>, mode: PayMode, dateMillis: Long = System.currentTimeMillis(), toAccountId: Long = 0): Receipt {
        require(shares.isNotEmpty()) { "Pick at least one invoice" }
        val party = shares.first().first.customerName
        ensureCustomerHead(party)
        val total = shares.sumOf { it.second }
        val single = shares.singleOrNull()
        val receipt = Receipt(
            receiptNo = nextReceiptNo(),
            billId = single?.first?.id ?: 0,
            billNo = single?.first?.billNo ?: "${shares.size} invoices",
            customerName = party,
            dateMillis = dateMillis,
            amount = total,
            paymentMode = mode.label,
            payFrom = party,
            toAccountId = if (toAccountId != 0L) toAccountId else ensureModeAccount(mode.label),
            deviceId = License.deviceId(context),
            updatedAt = System.currentTimeMillis()
        )
        val newId = receiptDao.insert(receipt)
        shares.forEach { (bill, amount) ->
            val newPaid = (bill.paidAmount + amount).coerceAtMost(bill.grandTotal)
            billDao.updateBillHeader(bill.copy(paidAmount = newPaid, updatedAt = System.currentTimeMillis()))
        }
        if (shares.size > 1) {
            receiptAllocationDao.insertAll(shares.map { (bill, amount) ->
                ReceiptAllocation(receiptId = newId, billId = bill.id, billNo = bill.billNo, amount = amount)
            })
        }
        return receipt.copy(id = newId)
    }

    /** Records a receipt from any source (no invoice link; [refNo] labels it, e.g. a job card no). */
    suspend fun addStandaloneReceipt(payFrom: String, amount: Double, mode: PayMode, dateMillis: Long = System.currentTimeMillis(), toAccountId: Long = 0, partyIsSupplier: Boolean = false, refNo: String = ""): Receipt {
        if (payFrom.isNotBlank()) { if (partyIsSupplier) ensureSupplierHead(payFrom) else ensureCustomerHead(payFrom) }
        val receipt = Receipt(
            receiptNo = nextReceiptNo(),
            billId = 0,
            billNo = refNo,
            customerName = payFrom.trim(),
            dateMillis = dateMillis,
            amount = amount,
            paymentMode = mode.label,
            payFrom = payFrom.trim(),
            toAccountId = if (toAccountId != 0L) toAccountId else ensureModeAccount(mode.label),
            updatedAt = System.currentTimeMillis()
        )
        val newId = receiptDao.insert(receipt)
        return receipt.copy(id = newId)
    }

    /** Distinct "pay from" names previously used, for the dropdown. */
    suspend fun payFromNames(): List<String> = receiptDao.payFromNames()

    // ---- expenses / payment vouchers (money paid out) ----
    suspend fun nextVoucherNo(): String =
        tagPrefix() + "PV-" + (expenseDao.localCount() + 1).toString().padStart(4, '0')

    suspend fun addExpense(description: String, amount: Double, mode: PayMode, dateMillis: Long = System.currentTimeMillis(), fromAccountId: Long = 0): Expense {
        val expense = Expense(
            voucherNo = nextVoucherNo(),
            dateMillis = dateMillis,
            description = description.trim(),
            amount = amount,
            paymentMode = mode.label,
            fromAccountId = if (fromAccountId != 0L) fromAccountId else ensureModeAccount(mode.label),
            deviceId = License.deviceId(context),
            updatedAt = System.currentTimeMillis()
        )
        // Return the row with its generated id — callers attach files to it, and an id of 0
        // silently orphans them.
        val id = expenseDao.insert(expense)
        return expense.copy(id = id)
    }

    /** Like [addExpense] but also records the party paid to. */
    suspend fun addExpenseFull(description: String, amount: Double, mode: PayMode, dateMillis: Long, payTo: String, fromAccountId: Long = 0, partyIsCustomer: Boolean = false): Expense {
        if (payTo.isNotBlank()) { if (partyIsCustomer) ensureCustomerHead(payTo) else ensureSupplierHead(payTo) }
        val expense = Expense(
            voucherNo = nextVoucherNo(),
            dateMillis = dateMillis,
            description = description.trim(),
            amount = amount,
            paymentMode = mode.label,
            payTo = payTo.trim(),
            fromAccountId = if (fromAccountId != 0L) fromAccountId else ensureModeAccount(mode.label),
            deviceId = License.deviceId(context),
            updatedAt = System.currentTimeMillis()
        )
        // Return the row with its generated id — callers attach files to it, and an id of 0
        // silently orphans them.
        val id = expenseDao.insert(expense)
        return expense.copy(id = id)
    }

    /** Edits a receipt and re-applies the difference to the linked invoice's paid amount. */
    suspend fun updateReceipt(old: Receipt, newAmount: Double, mode: PayMode) {
        if (old.billId > 0) {
            billDao.byId(old.billId)?.let { bill ->
                val adjusted = (bill.paidAmount - old.amount + newAmount)
                    .coerceIn(0.0, bill.grandTotal)
                billDao.updateBillHeader(bill.copy(paidAmount = adjusted, updatedAt = System.currentTimeMillis()))
            }
        }
        receiptDao.update(old.copy(amount = newAmount, paymentMode = mode.label, updatedAt = System.currentTimeMillis()))
    }

    /** Invoices covered by a (possibly multi-invoice) receipt; empty for a single/standalone one. */
    suspend fun receiptAllocationsFor(receiptId: Long): List<ReceiptAllocation> = receiptAllocationDao.forReceipt(receiptId)

    /** Deletes a receipt and reduces every invoice it was allocated against, single or multiple. */
    suspend fun deleteReceipt(receipt: Receipt) {
        val allocations = receiptAllocationDao.forReceipt(receipt.id)
        if (allocations.isNotEmpty()) {
            allocations.forEach { a ->
                billDao.byId(a.billId)?.let { bill ->
                    val adjusted = (bill.paidAmount - a.amount).coerceAtLeast(0.0)
                    billDao.updateBillHeader(bill.copy(paidAmount = adjusted, updatedAt = System.currentTimeMillis()))
                }
            }
            receiptAllocationDao.deleteForReceipt(receipt.id)
        } else if (receipt.billId > 0) {
            billDao.byId(receipt.billId)?.let { bill ->
                val adjusted = (bill.paidAmount - receipt.amount).coerceAtLeast(0.0)
                billDao.updateBillHeader(bill.copy(paidAmount = adjusted, updatedAt = System.currentTimeMillis()))
            }
        }
        receiptAttachmentDao.forReceipt(receipt.id).forEach { ReceiptAttachmentStore.delete(it) }
        receiptAttachmentDao.deleteForReceipt(receipt.id)
        receiptDao.delete(receipt)
    }

    /** Records a payment against a (credit) purchase and increases its paid amount. */
    suspend fun addPaymentForPurchase(purchase: Purchase, amount: Double, mode: PayMode, fromAccountId: Long = 0): Expense {
        ensureSupplierHead(purchase.supplierName)
        val expense = Expense(
            voucherNo = nextVoucherNo(),
            dateMillis = System.currentTimeMillis(),
            description = "Payment to ${purchase.supplierName}",
            amount = amount,
            paymentMode = mode.label,
            purchaseId = purchase.id,
            purchaseNo = purchase.purchaseNo,
            payTo = purchase.supplierName,
            fromAccountId = if (fromAccountId != 0L) fromAccountId else ensureModeAccount(mode.label),
            deviceId = License.deviceId(context),
            updatedAt = System.currentTimeMillis()
        )
        val id = expenseDao.insert(expense)
        val newPaid = (purchase.paidAmount + amount).coerceAtMost(purchase.grandTotal)
        purchaseDao.updateHeader(purchase.copy(paidAmount = newPaid, updatedAt = System.currentTimeMillis()))
        return expense.copy(id = id)
    }

    private val expenseAllocationDao = db.expenseAllocationDao()

    /**
     * One payment settling several purchases for the same supplier at once: each purchase's own
     * paid amount is adjusted by its own share, but only ONE expense (one ledger entry) is
     * posted, for the sum of all shares.
     */
    suspend fun addExpenseForPurchases(shares: List<Pair<Purchase, Double>>, mode: PayMode, fromAccountId: Long = 0): Expense {
        require(shares.isNotEmpty()) { "Pick at least one purchase" }
        val supplier = shares.first().first.supplierName
        ensureSupplierHead(supplier)
        val total = shares.sumOf { it.second }
        val single = shares.singleOrNull()
        val expense = Expense(
            voucherNo = nextVoucherNo(),
            dateMillis = System.currentTimeMillis(),
            description = "Payment to $supplier",
            amount = total,
            paymentMode = mode.label,
            purchaseId = single?.first?.id ?: 0,
            purchaseNo = single?.first?.purchaseNo ?: "${shares.size} purchases",
            payTo = supplier,
            fromAccountId = if (fromAccountId != 0L) fromAccountId else ensureModeAccount(mode.label),
            deviceId = License.deviceId(context),
            updatedAt = System.currentTimeMillis()
        )
        val id = expenseDao.insert(expense)
        shares.forEach { (purchase, amount) ->
            val newPaid = (purchase.paidAmount + amount).coerceAtMost(purchase.grandTotal)
            purchaseDao.updateHeader(purchase.copy(paidAmount = newPaid, updatedAt = System.currentTimeMillis()))
        }
        if (shares.size > 1) {
            expenseAllocationDao.insertAll(shares.map { (purchase, amount) ->
                ExpenseAllocation(expenseId = id, purchaseId = purchase.id, purchaseNo = purchase.purchaseNo, amount = amount)
            })
        }
        return expense.copy(id = id)
    }

    suspend fun updateExpense(expense: Expense, description: String, amount: Double, mode: PayMode) {
        if (expense.purchaseId > 0) {
            purchaseDao.byId(expense.purchaseId)?.let { pur ->
                val adjusted = (pur.paidAmount - expense.amount + amount).coerceIn(0.0, pur.grandTotal)
                purchaseDao.updateHeader(pur.copy(paidAmount = adjusted, updatedAt = System.currentTimeMillis()))
            }
        }
        expenseDao.update(expense.copy(description = description.trim(), amount = amount, paymentMode = mode.label, updatedAt = System.currentTimeMillis()))
    }

    /** Purchases covered by a (possibly multi-purchase) payment; empty for a single/general one. */
    suspend fun expenseAllocationsFor(expenseId: Long): List<ExpenseAllocation> = expenseAllocationDao.forExpense(expenseId)

    /** Deletes a payment and reduces every purchase it was allocated against, single or multiple. */
    suspend fun deleteExpense(expense: Expense) {
        val allocations = expenseAllocationDao.forExpense(expense.id)
        if (allocations.isNotEmpty()) {
            allocations.forEach { a ->
                purchaseDao.byId(a.purchaseId)?.let { pur ->
                    val adjusted = (pur.paidAmount - a.amount).coerceAtLeast(0.0)
                    purchaseDao.updateHeader(pur.copy(paidAmount = adjusted, updatedAt = System.currentTimeMillis()))
                }
            }
            expenseAllocationDao.deleteForExpense(expense.id)
        } else if (expense.purchaseId > 0) {
            purchaseDao.byId(expense.purchaseId)?.let { pur ->
                val adjusted = (pur.paidAmount - expense.amount).coerceAtLeast(0.0)
                purchaseDao.updateHeader(pur.copy(paidAmount = adjusted, updatedAt = System.currentTimeMillis()))
            }
        }
        expenseDao.delete(expense)
    }

    // ---- suppliers ----
    suspend fun addSupplier(name: String, phone: String, address: String, gstin: String = "", state: String = ""): Supplier {
        val s = Supplier(name = name.trim(), phone = phone.trim(), address = address.trim(), gstin = gstin.trim(), state = state.trim())
        val id = supplierDao.insert(s)
        ensureSupplierHead(name)   // create the supplier's ledger head immediately
        return s.copy(id = id)
    }

    /** One-time: make sure every existing customer/supplier has a ledger head — including the
     *  default Cash Customer/Cash Supplier, which need one just as much as a named party (sales
     *  and purchases against them still post to Sundry Debtors/Creditors). */
    suspend fun syncPartyHeads() {
        customerDao.all().forEach { ensureCustomerHead(it.name) }
        supplierDao.all().forEach { ensureSupplierHead(it.name) }
    }

    // ---- VAT / tax report data ----
    suspend fun billsAll(): List<Bill> = billDao.all()
    suspend fun purchasesAll(): List<Purchase> = purchaseDao.all()
    suspend fun saleTaxLines(): List<TaxLineInfo> = billDao.taxLines()
    suspend fun purchaseTaxLines(): List<TaxLineInfo> = purchaseDao.taxLines()
    suspend fun itemsAll(): List<Item> = itemDao.all()
    suspend fun salesReturnsAll(): List<SalesReturn> = salesReturnDao.all()
    suspend fun customersAll(): List<Customer> = customerDao.all()
    suspend fun suppliersAll(): List<Supplier> = supplierDao.all()
    suspend fun purchaseReturnsAll(): List<PurchaseReturn> = purchaseReturnDao.all()

    suspend fun updateSupplier(supplier: Supplier) = supplierDao.update(supplier)

    suspend fun deleteSupplier(supplier: Supplier): Result<Unit> {
        if (supplier.isDefault) return Result.failure(IllegalStateException("Cannot delete the default supplier"))
        if (purchaseDao.countForSupplier(supplier.id, supplier.name) > 0)
            return Result.failure(IllegalStateException("Supplier has purchases"))
        supplierDao.delete(supplier)
        return Result.success(Unit)
    }

    // ---- supplier opening balance (lives on the supplier's ledger head, like any other account) ----
    suspend fun supplierOpeningBalance(supplierName: String): Pair<Double, Boolean> {
        val gid = accountDao.allGroups().firstOrNull { it.name == "Sundry Creditors" }?.id ?: return 0.0 to false
        val head = accountDao.headByNameGroup(supplierName.trim(), gid) ?: return 0.0 to false
        return head.openingBalance to head.openingIsDebit
    }

    suspend fun setSupplierOpeningBalance(supplierName: String, amount: Double, isDebit: Boolean) {
        val headId = ensureSupplierHead(supplierName)
        val head = accountDao.headById(headId) ?: return
        accountDao.updateHead(head.copy(openingBalance = amount, openingIsDebit = isDebit))
    }

    // ---- quick purchases: a supplier-dialog shortcut for a legacy due (amount+note+date, no items) ----
    suspend fun addQuickPurchase(supplier: Supplier, amount: Double, note: String, dateMillis: Long): Long {
        ensureSupplierHead(supplier.name)
        val purchase = Purchase(
            purchaseNo = nextPurchaseNo(),
            dateMillis = dateMillis,
            supplierId = supplier.id,
            supplierName = supplier.name,
            paymentMethod = PaymentMethod.CREDIT.label,
            subTotal = amount,
            taxTotal = 0.0,
            additionalCharge = 0.0,
            discount = 0.0,
            grandTotal = amount,
            paidAmount = 0.0,
            supplierGstin = supplier.gstin,
            supplierState = supplier.state,
            remarks = note.trim(),
            isLegacy = true,
            updatedAt = System.currentTimeMillis()
        )
        return purchaseDao.savePurchase(purchase, emptyList())
    }

    fun quickPurchasesForSupplier(supplierId: Long): Flow<List<Purchase>> = purchaseDao.quickPurchasesForSupplier(supplierId)

    // ---- purchases ----
    suspend fun nextPurchaseNo(): String =
        "PUR-" + (purchaseDao.localCount() + 1).toString().padStart(4, '0')

    suspend fun savePurchase(purchase: Purchase, lines: List<PurchaseItem>): Long =
        purchaseDao.savePurchase(purchase.copy(updatedAt = System.currentTimeMillis()), lines)

    suspend fun updatePurchase(purchase: Purchase, lines: List<PurchaseItem>) =
        purchaseDao.updatePurchase(purchase.copy(updatedAt = System.currentTimeMillis()), lines)

    suspend fun deletePurchase(purchase: Purchase) = purchaseDao.deletePurchase(purchase)

    suspend fun purchaseById(id: Long): Purchase? = purchaseDao.byId(id)

    suspend fun purchaseLinesFor(id: Long): List<PurchaseItem> = purchaseDao.linesFor(id)

    // ---- purchase document attachments (e.g. a scanned supplier bill) ----
    private val purchaseAttachmentDao = db.purchaseAttachmentDao()
    suspend fun purchaseAttachmentsFor(purchaseId: Long): List<PurchaseAttachment> = purchaseAttachmentDao.forPurchase(purchaseId)
    suspend fun addPurchaseAttachment(att: PurchaseAttachment): Long = purchaseAttachmentDao.insert(att)
    suspend fun deletePurchaseAttachment(att: PurchaseAttachment) {
        com.billing.pos.purchase.PurchaseAttachmentStore.delete(att)
        purchaseAttachmentDao.delete(att)
    }

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
                    source = source,
                    updatedAt = System.currentTimeMillis()
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
