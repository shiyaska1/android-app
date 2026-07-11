package com.billing.pos.ui.billing

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.data.Bill
import com.billing.pos.data.BillAttachment
import com.billing.pos.data.BillItem
import com.billing.pos.data.BillWithItems
import com.billing.pos.data.Customer
import com.billing.pos.data.Item
import com.billing.pos.data.PaymentMethod
import com.billing.pos.data.Repository
import com.billing.pos.data.primaryChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One editable line in the current (unsaved) bill. uid is a stable client id. */
data class CartLine(
    val itemId: Long,
    val name: String,
    val price: Double,
    val taxPercent: Double,
    val qty: Double,
    val batchNo: String = "",
    /** In-memory only: expiry for a newly-received purchase batch. */
    val batchExpiry: Long = 0,
    /** Unit this line is billed in. Blank = the item's primary unit. */
    val unit: String = "",
    /** Primary units in one [unit]; 1.0 for the primary unit, 1/factor for the secondary. */
    val primaryPerUnit: Double = 1.0,
    val uid: Long = nextUid()
) {
    /** What the customer pays for this line — the selling price is tax-inclusive. */
    val total: Double get() = price * qty
    /** Tax portion extracted out of the inclusive [total]. */
    val tax: Double get() = if (taxPercent > 0.0) total - total / (1.0 + taxPercent / 100.0) else 0.0
    /** Taxable value (inclusive total minus the extracted tax). */
    val base: Double get() = total - tax

    /** [qty] expressed in the item's primary unit, for stock math. */
    val primaryQty: Double get() = qty * primaryPerUnit

    companion object {
        private var counter = 0L
        fun nextUid(): Long = ++counter
    }
}

class BillingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)

    /** Shown on invoices/receipts — change here to your shop's name. */
    val shopName: String = "My Shop"

    val customers: StateFlow<List<Customer>> =
        repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> =
        repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** All item batches (batch no + expiry + qty), for the sale batch picker. */
    val allBatches: StateFlow<List<com.billing.pos.data.ItemBatch>> =
        repo.itemBatches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** All item sizes/variants, for the sale size picker. */
    val allSizes: StateFlow<List<com.billing.pos.data.ItemSize>> =
        repo.itemSizes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** itemId -> current stock (opening + purchased - sold), for the item picker. */
    val stockByItem: StateFlow<Map<Long, Double>> =
        kotlinx.coroutines.flow.combine(repo.items, repo.purchaseLines, repo.soldQty) { list, pLines, sold ->
            val purchasedByName = pLines.groupBy { it.name.lowercase() }
            val soldByName = sold.associate { it.name.lowercase() to it.qty }
            list.associate { item ->
                val key = item.name.lowercase()
                item.id to (item.openingStock + (purchasedByName[key]?.sumOf { it.qty } ?: 0.0) - (soldByName[key] ?: 0.0))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    /** itemId -> first product photo path, for the quick-bill grid. */
    val itemPhotos: StateFlow<Map<Long, String>> =
        repo.itemAttachments.map { atts ->
            atts.filter { it.mime.startsWith("image/") }
                .groupBy { it.itemId }
                .mapValues { (_, l) -> (l.firstOrNull { it.kind == "PHOTO" } ?: l.first()).path }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ---- editable bill state ----
    var selectedCustomer by mutableStateOf<Customer?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    /** Documents attached to the current invoice (staged; persisted on save). */
    val editAttachments: SnapshotStateList<BillAttachment> = mutableStateListOf()
    var payment by mutableStateOf(PaymentMethod.CASH); private set
    var additionalChargeText by mutableStateOf(""); private set
    var discountText by mutableStateOf(""); private set
    var remarks by mutableStateOf(""); private set
    /** When it parses to a number, overrides the computed grand total (photo / manual bills). */
    var manualTotalText by mutableStateOf(""); private set
    var billNo by mutableStateOf("INV-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis()); private set

    /** Non-null when editing an existing bill. */
    var editingBillId by mutableStateOf<Long?>(null); private set
    private var editingSource: String = ""
    private var editingPaidAmount: Double = 0.0
    private var editingWasCredit: Boolean = false

    private var dirty = true
    private var lastSaved: BillWithItems? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            billNo = repo.nextBillNo()
        }
        // Auto-select the default (Cash) customer once customers load.
        viewModelScope.launch {
            customers.collect { list ->
                if (selectedCustomer == null && list.isNotEmpty()) {
                    selectedCustomer = list.firstOrNull { it.isDefault } ?: list.first()
                }
            }
        }
    }

    // ---- derived totals ----
    val subTotal: Double get() = cart.sumOf { it.base }
    val taxTotal: Double get() = cart.sumOf { it.tax }
    val additionalCharge: Double get() = additionalChargeText.toDoubleOrNull() ?: 0.0
    val discount: Double get() = discountText.toDoubleOrNull() ?: 0.0
    val grandTotal: Double get() =
        manualTotalText.toDoubleOrNull() ?: (subTotal + taxTotal + additionalCharge - discount)
    val isManualTotal: Boolean get() = manualTotalText.toDoubleOrNull() != null

    // ---- mutations ----
    fun selectCustomer(c: Customer) { selectedCustomer = c; dirty = true }
    fun selectPayment(m: PaymentMethod) { payment = m; dirty = true }
    fun setAdditionalCharge(v: String) { additionalChargeText = v; dirty = true }
    fun setDiscount(v: String) { discountText = v; dirty = true }
    fun updateRemarks(v: String) { remarks = v; dirty = true }
    fun setManualTotal(v: String) { manualTotalText = v.filter { it.isDigit() || it == '.' }; dirty = true }
    fun clearManualTotal() { manualTotalText = ""; dirty = true }
    fun updateDate(millis: Long) { dateMillis = millis; dirty = true }

    fun addAttachmentUris(context: android.content.Context, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { com.billing.pos.bills.BillAttachmentStore.copyIn(context, it) } }
            editAttachments.addAll(added); dirty = true
        }
    }
    fun removeBillAttachment(att: BillAttachment) {
        editAttachments.remove(att); dirty = true
        viewModelScope.launch {
            if (att.id > 0) repo.deleteBillAttachment(att)
            else withContext(Dispatchers.IO) { com.billing.pos.bills.BillAttachmentStore.delete(att) }
        }
    }

    /** Adds an item in its primary unit (the no-prompt path for single-unit items). */
    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryChoice())

    /** Adds an item billed in [choice]'s unit, at that unit's rate. */
    fun addItemWithUnit(item: Item, choice: com.billing.pos.data.UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.unit == choice.unit && it.batchNo.isBlank() }
        if (idx >= 0) {
            cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        } else {
            cart.add(
                CartLine(
                    item.id, item.name, choice.price, item.taxPercent, 1.0,
                    unit = choice.unit, primaryPerUnit = choice.primaryPerUnit
                )
            )
        }
        dirty = true
    }

    /** Adds an item at the chosen size's price; the size is shown/printed in the line name. */
    fun addItemWithSize(item: Item, size: com.billing.pos.data.ItemSize) {
        val name = "${item.name} (${size.name})"
        val idx = cart.indexOfFirst { it.itemId == item.id && it.name == name }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, name, size.price, item.taxPercent, 1.0, unit = item.unit))
        dirty = true
    }

    /** Adds an item to the cart tied to a specific batch (batch tracking on), in [choice]'s unit. */
    fun addItemWithBatch(
        item: Item,
        batch: com.billing.pos.data.ItemBatch,
        choice: com.billing.pos.data.UnitChoice = item.primaryChoice()
    ) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.batchNo == batch.batchNo && it.unit == choice.unit }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(
            CartLine(
                item.id, item.name, choice.price, item.taxPercent, 1.0, batchNo = batch.batchNo,
                unit = choice.unit, primaryPerUnit = choice.primaryPerUnit
            )
        )
        dirty = true
    }

    /**
     * Adds an ad-hoc line not from the item master. Description is optional.
     * If [saveToMaster] is set and a description is given, the item is also created
     * in the item master (deduplicated by name) using [sellingPrice] (or the line price).
     */
    fun addCustomLine(
        description: String, price: Double, taxPercent: Double,
        saveToMaster: Boolean = false, sellingPrice: Double = 0.0
    ) {
        val name = description.trim().ifBlank { "Item" }
        cart.add(CartLine(itemId = 0, name = name, price = price, taxPercent = taxPercent, qty = 1.0))
        dirty = true
        if (saveToMaster && description.isNotBlank()) {
            val masterPrice = sellingPrice.takeIf { it > 0.0 } ?: price
            viewModelScope.launch {
                if (repo.itemByName(name) == null) {
                    repo.addItem(name, masterPrice, taxPercent)
                    _message.value = "Saved \"$name\" to items"
                }
            }
        }
    }

    /**
     * Adds a line captured from handwriting. Looks the name up in the item master
     * (case-insensitive); if missing, creates the master item with [price], then adds
     * the line to the cart. [onResult] reports success + a short status message.
     */
    fun addHandwrittenLine(rawName: String, price: Double, onResult: (Boolean, String) -> Unit) {
        val name = rawName.trim()
        if (name.isBlank()) { onResult(false, "Couldn't read the item name — write it again"); return }
        viewModelScope.launch {
            val match = items.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: repo.itemByName(name)
            if (match != null) {
                val rate = if (price > 0.0) price else match.price
                cart.add(CartLine(match.id, match.name, rate, match.taxPercent, 1.0))
                onResult(true, "Added ${match.name}")
            } else {
                val id = repo.addItem(name, price, 0.0)
                cart.add(CartLine(id, name, price, 0.0, 1.0))
                onResult(true, "New item \"$name\" saved & added")
            }
            dirty = true
        }
    }

    /**
     * Adds each OCR-scanned line as a cart line. New names are created in the item master;
     * the parsed price (if any) is used, else the master price. Grand total stays automatic.
     */
    fun addOcrItemsToCart(scanned: List<com.billing.pos.ocr.ScannedItem>) {
        if (scanned.isEmpty()) { _message.value = "No items found in the photo"; return }
        viewModelScope.launch {
            var added = 0
            scanned.forEach { s ->
                val name = s.name.trim()
                if (name.isBlank()) return@forEach
                val match = items.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: repo.itemByName(name)
                if (match != null) {
                    val rate = if (s.price > 0.0) s.price else match.price
                    cart.add(CartLine(match.id, match.name, rate, match.taxPercent, 1.0))
                } else {
                    val id = repo.addItem(name, s.price, 0.0)
                    cart.add(CartLine(id, name, s.price, 0.0, 1.0))
                }
                added++
            }
            manualTotalText = ""   // OCR bills use the auto-computed total
            dirty = true
            _message.value = "Added $added item(s) from photo"
        }
    }

    fun changeQty(index: Int, delta: Double) {
        val line = cart.getOrNull(index) ?: return
        val q = (line.qty + delta)
        if (q <= 0) cart.removeAt(index) else cart[index] = line.copy(qty = q)
        dirty = true
    }

    fun setQty(index: Int, qty: Double) {
        val line = cart.getOrNull(index) ?: return
        if (qty <= 0) cart.removeAt(index) else cart[index] = line.copy(qty = qty)
        dirty = true
    }

    fun removeLine(index: Int) { cart.removeAt(index); dirty = true }

    fun setLinePrice(index: Int, price: Double) {
        val line = cart.getOrNull(index) ?: return
        cart[index] = line.copy(price = price)
        dirty = true
    }

    fun addCustomer(name: String, phone: String, address: String, onCreated: () -> Unit) {
        if (name.isBlank()) { _message.value = "Enter customer name"; return }
        viewModelScope.launch {
            val id = repo.addCustomer(name, phone, address)
            selectedCustomer = Customer(id, name.trim(), phone.trim(), address.trim())
            dirty = true
            _message.value = "Customer added"
            onCreated()
        }
    }

    fun addItem(form: com.billing.pos.ui.billing.NewItemForm, addToCart: Boolean, onCreated: () -> Unit) {
        if (form.name.isBlank()) { _message.value = "Enter item name"; return }
        viewModelScope.launch {
            val id = repo.addItem(
                name = form.name, price = form.price, taxPercent = form.taxPercent,
                barcode = form.barcode, hsn = form.hsn, category = form.category,
                openingStock = form.openingStock, unit = form.unit, storeLocation = form.storeLocation,
                secondaryUnit = form.secondaryUnit, conversionFactor = form.conversionFactor
            )
            if (addToCart) addItemToCart(
                Item(
                    id = id, name = form.name.trim(), price = form.price, taxPercent = form.taxPercent,
                    barcode = form.barcode.trim(), hsn = form.hsn.trim(), category = form.category.trim(),
                    openingStock = form.openingStock, unit = form.unit,
                    secondaryUnit = form.secondaryUnit, conversionFactor = form.conversionFactor,
                    storeLocation = form.storeLocation.trim()
                )
            )
            _message.value = "Item added"
            onCreated()
        }
    }

    /**
     * Renames a cart line. If the new name matches an existing master item the line is
     * re-linked to it; otherwise itemId is cleared and the item is created in the master
     * on save. Optionally adopt the matched item's price/tax.
     */
    fun setLineName(i: Int, newName: String) {
        val l = cart.getOrNull(i) ?: return
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val match = items.value.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        // A fresh uid forces the row's price/qty text boxes to re-init from the new values.
        cart[i] = if (match != null)
            l.copy(
                name = match.name, itemId = match.id, price = match.price, taxPercent = match.taxPercent,
                unit = match.unit, primaryPerUnit = 1.0, uid = CartLine.nextUid()
            )
        else
            // A new (unknown) item: clear the price to zero, ready for entry; saved to master on save.
            l.copy(name = trimmed, itemId = 0L, price = 0.0, uid = CartLine.nextUid())
        dirty = true
    }

    /** Adds an item to the cart by its scanned barcode. */
    fun onBarcodeScanned(barcode: String) {
        viewModelScope.launch {
            val item = repo.itemByBarcode(barcode)
            if (item != null) { addItemToCart(item); _message.value = "Added ${item.name}" }
            else _message.value = "No item for barcode $barcode"
        }
    }

    /** Validates and persists the current bill. Returns the saved bill (with real id) or null. */
    suspend fun saveCurrent(): BillWithItems? {
        val customer = selectedCustomer
        if (customer == null) { _message.value = "Select a customer"; return null }
        val hasPhoto = editAttachments.any { it.mime.startsWith("image/") }
        if (cart.isEmpty() && !isManualTotal && !hasPhoto) { _message.value = "Add at least one item"; return null }

        if (!dirty && lastSaved != null) return lastSaved

        // Any cart line whose name isn't in the master yet is created there now, so
        // typed-in items become real items (with their price/tax/unit).
        for (idx in cart.indices) {
            val l = cart[idx]
            if (l.itemId == 0L && l.name.isNotBlank()) {
                val existing = items.value.firstOrNull { it.name.equals(l.name, ignoreCase = true) }
                val id = existing?.id ?: repo.addItem(
                    name = l.name, price = l.price, taxPercent = l.taxPercent, unit = l.unit.ifBlank { "PCS" }
                )
                cart[idx] = l.copy(itemId = id)
            }
        }

        val editId = editingBillId
        val paid = when {
            // Keep collected receipts only if it was already credit; a cash bill switched
            // to credit becomes fully outstanding.
            payment == PaymentMethod.CREDIT && editId != null && editingWasCredit -> editingPaidAmount
            payment == PaymentMethod.CREDIT -> 0.0
            else -> grandTotal   // cash/upi/card = fully paid
        }
        val bill = Bill(
            id = editId ?: 0,
            billNo = billNo,
            dateMillis = dateMillis,
            customerId = customer.id,
            customerName = customer.name,
            paymentMethod = payment.label,
            subTotal = subTotal,
            taxTotal = taxTotal,
            additionalCharge = additionalCharge,
            discount = discount,
            grandTotal = grandTotal,
            paidAmount = paid,
            customerGstin = customer.gstin,
            source = editingSource,
            remarks = remarks.trim()
        )
        val lines = cart.map {
            BillItem(
                billId = editId ?: 0,
                name = it.name,
                qty = it.qty,
                price = it.price,
                taxPercent = it.taxPercent,
                lineTotal = it.total,
                batchNo = it.batchNo,
                unit = it.unit,
                primaryQty = it.primaryQty
            )
        }
        val saved: BillWithItems
        if (editId != null) {
            repo.updateBill(bill, lines)
            saved = BillWithItems(bill, lines)
            _message.value = "Bill $billNo updated"
        } else {
            val id = repo.saveBill(bill, lines)
            saved = BillWithItems(bill.copy(id = id), lines)
            // Deduct sold quantities from their chosen batches (new bills only).
            cart.filter { it.batchNo.isNotBlank() && it.itemId > 0 }
                .forEach { repo.deductBatch(it.itemId, it.batchNo, it.primaryQty) }
            // Keep editing this same bill so re-saving (e.g. after Print) updates it
            // instead of creating a duplicate with the same bill number.
            editingBillId = id
            editingWasCredit = payment == PaymentMethod.CREDIT
            editingPaidAmount = paid
            _message.value = "Bill $billNo saved"
        }
        // Persist any newly-attached documents, then reload so they carry real ids.
        val savedBillId = saved.bill.id
        val newAtts = editAttachments.filter { it.id == 0L }
        if (newAtts.isNotEmpty()) {
            newAtts.forEach { repo.addBillAttachment(it.copy(billId = savedBillId)) }
            editAttachments.clear()
            editAttachments.addAll(repo.billAttachmentsFor(savedBillId))
        }
        lastSaved = saved
        dirty = false
        return saved
    }

    /** True when we must ask for a WhatsApp number (selected customer has none). */
    fun needsWhatsAppInfo(): Boolean = selectedCustomer?.phone.isNullOrBlank()

    /**
     * Adds/updates the customer master with the given name+number, then saves the bill.
     * Returns the saved bill, or null if invalid.
     */
    suspend fun prepareWhatsApp(nameOverride: String, numberOverride: String): BillWithItems? {
        val number = numberOverride.trim().ifBlank { selectedCustomer?.phone ?: "" }
        val name = nameOverride.trim()
        if (number.isNotBlank()) {
            val cur = selectedCustomer
            if (cur == null || cur.isDefault) {
                selectedCustomer = repo.addCustomerReturning(name.ifBlank { "Customer" }, number)
            } else if (cur.phone.isBlank() || name.isNotEmpty()) {
                val updated = cur.copy(name = name.ifBlank { cur.name }, phone = number)
                repo.updateCustomer(updated)
                selectedCustomer = updated
            }
            dirty = true
        }
        return saveCurrent()
    }

    /** Loads an existing bill for editing. Called once from the UI. */
    fun startEditing(billId: Long) {
        if (editingBillId == billId) return
        viewModelScope.launch {
            val bill = repo.billById(billId) ?: return@launch
            val lines = repo.linesFor(billId)
            editingBillId = bill.id
            editingSource = bill.source
            editingPaidAmount = bill.paidAmount
            editingWasCredit = bill.paymentMethod == PaymentMethod.CREDIT.label
            billNo = bill.billNo
            dateMillis = bill.dateMillis
            payment = PaymentMethod.values().firstOrNull { it.label == bill.paymentMethod } ?: PaymentMethod.CASH
            additionalChargeText = if (bill.additionalCharge != 0.0) bill.additionalCharge.toString() else ""
            discountText = if (bill.discount != 0.0) bill.discount.toString() else ""
            remarks = bill.remarks
            selectedCustomer = customers.value.firstOrNull { it.id == bill.customerId }
                ?: Customer(id = bill.customerId, name = bill.customerName)
            editAttachments.clear()
            editAttachments.addAll(repo.billAttachmentsFor(bill.id))
            cart.clear()
            lines.forEach {
                val perUnit = if (it.primaryQty > 0 && it.qty > 0) it.primaryQty / it.qty else 1.0
                cart.add(
                    CartLine(
                        0, it.name, it.price, it.taxPercent, it.qty, batchNo = it.batchNo,
                        unit = it.unit, primaryPerUnit = perUnit
                    )
                )
            }
            // A saved photo/manual bill has no lines but a total — keep it editable.
            manualTotalText = if (lines.isEmpty() && bill.grandTotal > 0.0) {
                val g = bill.grandTotal
                if (g == g.toLong().toDouble()) g.toLong().toString() else g.toString()
            } else ""
            dirty = false
            lastSaved = BillWithItems(bill, lines)
        }
    }

    /** Clears the form for a brand-new bill and refreshes the auto bill number. */
    fun newBill() {
        cart.clear()
        val unsaved = editAttachments.filter { it.id == 0L }
        editAttachments.clear()
        if (unsaved.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { unsaved.forEach { com.billing.pos.bills.BillAttachmentStore.delete(it) } }
        additionalChargeText = ""
        discountText = ""
        remarks = ""
        manualTotalText = ""
        payment = PaymentMethod.CASH
        dateMillis = System.currentTimeMillis()
        selectedCustomer = customers.value.firstOrNull { it.isDefault } ?: customers.value.firstOrNull()
        editingBillId = null
        editingSource = ""
        lastSaved = null
        dirty = true
        viewModelScope.launch { billNo = repo.nextBillNo() }
    }
}
