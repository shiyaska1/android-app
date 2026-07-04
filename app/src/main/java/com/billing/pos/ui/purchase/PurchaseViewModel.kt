package com.billing.pos.ui.purchase

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.data.Item
import com.billing.pos.data.PaymentMethod
import com.billing.pos.data.Purchase
import com.billing.pos.data.PurchaseItem
import com.billing.pos.data.Repository
import com.billing.pos.data.Supplier
import com.billing.pos.ui.billing.CartLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PurchaseWithItems(val purchase: Purchase, val lines: List<PurchaseItem>)

class PurchaseViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)

    val suppliers: StateFlow<List<Supplier>> =
        repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> =
        repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedSupplier by mutableStateOf<Supplier?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var payment by mutableStateOf(PaymentMethod.CASH); private set
    var additionalChargeText by mutableStateOf(""); private set
    var discountText by mutableStateOf(""); private set
    var purchaseNo by mutableStateOf("PUR-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis()); private set

    var editingId by mutableStateOf<Long?>(null); private set
    private var editingSource: String = ""
    private var editingPaidAmount: Double = 0.0
    private var dirty = true
    private var lastSaved: PurchaseWithItems? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            purchaseNo = repo.nextPurchaseNo()
        }
        viewModelScope.launch {
            suppliers.collect { list ->
                if (selectedSupplier == null && list.isNotEmpty()) {
                    selectedSupplier = list.firstOrNull { it.isDefault } ?: list.first()
                }
            }
        }
    }

    val subTotal: Double get() = cart.sumOf { it.base }
    val taxTotal: Double get() = cart.sumOf { it.tax }
    val additionalCharge: Double get() = additionalChargeText.toDoubleOrNull() ?: 0.0
    val discount: Double get() = discountText.toDoubleOrNull() ?: 0.0
    val grandTotal: Double get() = subTotal + taxTotal + additionalCharge - discount

    fun selectSupplier(s: Supplier) { selectedSupplier = s; dirty = true }
    fun setPayment(m: PaymentMethod) { payment = m; dirty = true }
    fun setAdditionalCharge(v: String) { additionalChargeText = v; dirty = true }
    fun setDiscount(v: String) { discountText = v; dirty = true }

    fun addItemToCart(item: Item) {
        val idx = cart.indexOfFirst { it.itemId == item.id && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, item.price, item.taxPercent, 1.0))
        dirty = true
    }

    fun addCustomLine(description: String, price: Double, taxPercent: Double) {
        val name = description.trim().ifBlank { "Item" }
        cart.add(CartLine(0, name, price, taxPercent, 1.0))
        dirty = true
    }

    fun changeQty(index: Int, delta: Double) {
        val line = cart.getOrNull(index) ?: return
        val q = line.qty + delta
        if (q <= 0) cart.removeAt(index) else cart[index] = line.copy(qty = q)
        dirty = true
    }

    fun removeLine(index: Int) { cart.removeAt(index); dirty = true }

    fun addSupplier(name: String, phone: String, address: String, onCreated: () -> Unit) {
        if (name.isBlank()) { _message.value = "Enter supplier name"; return }
        viewModelScope.launch {
            selectedSupplier = repo.addSupplier(name, phone, address)
            dirty = true
            _message.value = "Supplier added"
            onCreated()
        }
    }

    fun addItem(name: String, price: Double, taxPercent: Double, addToCart: Boolean, onCreated: () -> Unit) {
        if (name.isBlank()) { _message.value = "Enter item name"; return }
        viewModelScope.launch {
            val id = repo.addItem(name, price, taxPercent)
            if (addToCart) addItemToCart(Item(id, name.trim(), price, taxPercent))
            _message.value = "Item added"
            onCreated()
        }
    }

    suspend fun saveCurrent(): PurchaseWithItems? {
        val supplier = selectedSupplier
        if (supplier == null) { _message.value = "Select a supplier"; return null }
        if (cart.isEmpty()) { _message.value = "Add at least one item"; return null }
        if (!dirty && lastSaved != null) return lastSaved

        val editId = editingId
        val paid = when {
            payment == PaymentMethod.CREDIT && editId != null -> editingPaidAmount
            payment == PaymentMethod.CREDIT -> 0.0
            else -> grandTotal
        }
        val purchase = Purchase(
            id = editId ?: 0,
            purchaseNo = purchaseNo,
            dateMillis = dateMillis,
            supplierId = supplier.id,
            supplierName = supplier.name,
            paymentMethod = payment.label,
            subTotal = subTotal,
            taxTotal = taxTotal,
            additionalCharge = additionalCharge,
            discount = discount,
            grandTotal = grandTotal,
            paidAmount = paid,
            source = editingSource
        )
        val lines = cart.map {
            PurchaseItem(0, editId ?: 0, it.name, it.qty, it.price, it.taxPercent, it.total)
        }
        val saved: PurchaseWithItems
        if (editId != null) {
            repo.updatePurchase(purchase, lines)
            saved = PurchaseWithItems(purchase, lines)
            _message.value = "Purchase $purchaseNo updated"
        } else {
            val id = repo.savePurchase(purchase, lines)
            saved = PurchaseWithItems(purchase.copy(id = id), lines)
            _message.value = "Purchase $purchaseNo saved"
        }
        lastSaved = saved
        dirty = false
        return saved
    }

    fun startEditing(id: Long) {
        if (editingId == id) return
        viewModelScope.launch {
            val purchase = repo.purchaseById(id) ?: return@launch
            val lines = repo.purchaseLinesFor(id)
            editingId = purchase.id
            editingSource = purchase.source
            editingPaidAmount = purchase.paidAmount
            purchaseNo = purchase.purchaseNo
            dateMillis = purchase.dateMillis
            payment = PaymentMethod.values().firstOrNull { it.label == purchase.paymentMethod } ?: PaymentMethod.CASH
            additionalChargeText = if (purchase.additionalCharge != 0.0) purchase.additionalCharge.toString() else ""
            discountText = if (purchase.discount != 0.0) purchase.discount.toString() else ""
            selectedSupplier = suppliers.value.firstOrNull { it.id == purchase.supplierId }
                ?: Supplier(id = purchase.supplierId, name = purchase.supplierName)
            cart.clear()
            lines.forEach { cart.add(CartLine(0, it.name, it.price, it.taxPercent, it.qty)) }
            dirty = false
            lastSaved = PurchaseWithItems(purchase, lines)
        }
    }

    fun newPurchase() {
        cart.clear()
        additionalChargeText = ""
        discountText = ""
        payment = PaymentMethod.CASH
        dateMillis = System.currentTimeMillis()
        selectedSupplier = suppliers.value.firstOrNull { it.isDefault } ?: suppliers.value.firstOrNull()
        editingId = null
        editingSource = ""
        lastSaved = null
        dirty = true
        viewModelScope.launch { purchaseNo = repo.nextPurchaseNo() }
    }
}
