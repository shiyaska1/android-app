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
import com.billing.pos.data.BillItem
import com.billing.pos.data.BillWithItems
import com.billing.pos.data.Customer
import com.billing.pos.data.Item
import com.billing.pos.data.PaymentMethod
import com.billing.pos.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One editable line in the current (unsaved) bill. */
data class CartLine(
    val itemId: Long,
    val name: String,
    val price: Double,
    val taxPercent: Double,
    val qty: Double
) {
    val base: Double get() = price * qty
    val tax: Double get() = base * taxPercent / 100.0
    val total: Double get() = base + tax
}

class BillingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)

    /** Shown on invoices/receipts — change here to your shop's name. */
    val shopName: String = "My Shop"

    val customers: StateFlow<List<Customer>> =
        repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> =
        repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- editable bill state ----
    var selectedCustomer by mutableStateOf<Customer?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var payment by mutableStateOf(PaymentMethod.CASH); private set
    var additionalChargeText by mutableStateOf(""); private set
    var discountText by mutableStateOf(""); private set
    var billNo by mutableStateOf("INV-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis()); private set

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
    val grandTotal: Double get() = subTotal + taxTotal + additionalCharge - discount

    // ---- mutations ----
    fun selectCustomer(c: Customer) { selectedCustomer = c; dirty = true }
    fun setPayment(m: PaymentMethod) { payment = m; dirty = true }
    fun setAdditionalCharge(v: String) { additionalChargeText = v; dirty = true }
    fun setDiscount(v: String) { discountText = v; dirty = true }

    fun addItemToCart(item: Item) {
        val idx = cart.indexOfFirst { it.itemId == item.id }
        if (idx >= 0) {
            cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        } else {
            cart.add(CartLine(item.id, item.name, item.price, item.taxPercent, 1.0))
        }
        dirty = true
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

    fun addItem(name: String, price: Double, taxPercent: Double, addToCart: Boolean, onCreated: () -> Unit) {
        if (name.isBlank()) { _message.value = "Enter item name"; return }
        viewModelScope.launch {
            val id = repo.addItem(name, price, taxPercent)
            if (addToCart) addItemToCart(Item(id, name.trim(), price, taxPercent))
            _message.value = "Item added"
            onCreated()
        }
    }

    /** Validates and persists the current bill. Returns the saved bill (with real id) or null. */
    suspend fun saveCurrent(): BillWithItems? {
        val customer = selectedCustomer
        if (customer == null) { _message.value = "Select a customer"; return null }
        if (cart.isEmpty()) { _message.value = "Add at least one item"; return null }

        if (!dirty && lastSaved != null) return lastSaved

        val bill = Bill(
            billNo = billNo,
            dateMillis = dateMillis,
            customerId = customer.id,
            customerName = customer.name,
            paymentMethod = payment.label,
            subTotal = subTotal,
            taxTotal = taxTotal,
            additionalCharge = additionalCharge,
            discount = discount,
            grandTotal = grandTotal
        )
        val lines = cart.map {
            BillItem(
                billId = 0,
                name = it.name,
                qty = it.qty,
                price = it.price,
                taxPercent = it.taxPercent,
                lineTotal = it.total
            )
        }
        val id = repo.saveBill(bill, lines)
        val saved = BillWithItems(bill.copy(id = id), lines)
        lastSaved = saved
        dirty = false
        _message.value = "Bill $billNo saved"
        return saved
    }

    /** Clears the form for a brand-new bill and refreshes the auto bill number. */
    fun newBill() {
        cart.clear()
        additionalChargeText = ""
        discountText = ""
        payment = PaymentMethod.CASH
        dateMillis = System.currentTimeMillis()
        selectedCustomer = customers.value.firstOrNull { it.isDefault } ?: customers.value.firstOrNull()
        lastSaved = null
        dirty = true
        viewModelScope.launch { billNo = repo.nextBillNo() }
    }
}
