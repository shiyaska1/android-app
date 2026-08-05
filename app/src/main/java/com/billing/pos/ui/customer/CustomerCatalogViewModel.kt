package com.billing.pos.ui.customer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.customer.LocationHelper
import com.billing.pos.customer.OrderSubmit
import com.billing.pos.customer.ShopCatalogSync
import com.billing.pos.customer.ShopSwitch
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CustomerOrderHistory
import com.billing.pos.data.ShopCatalogItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CustomerCatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    private val dao = AppDatabase.get(app).shopCatalogDao()
    private val historyDao = AppDatabase.get(app).customerOrderHistoryDao()

    val items: StateFlow<List<ShopCatalogItem>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<CustomerOrderHistory>> =
        historyDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val lastFetchedAt: Long get() = prefs.catalogLastFetchedAt

    // Selected quantities, keyed by ShopCatalogItem.id.
    private val _qty = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val qty: StateFlow<Map<Long, Int>> = _qty

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    val savedCustomerName: String get() = prefs.customerName
    val savedCustomerPhone: String get() = prefs.customerPhone
    val savedCustomerAddress: String get() = prefs.customerAddress
    val isPremiumShop: Boolean get() = prefs.customerPremiumShop

    init {
        // First open after install: the cache is empty, so fetch immediately without
        // waiting for the user to find the refresh button.
        if (prefs.catalogLastFetchedAt <= 0L) refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            when (val result = ShopCatalogSync.refresh(getApplication())) {
                is ShopCatalogSync.Result.Ok -> _message.value = "Updated — ${result.count} item(s)"
                is ShopCatalogSync.Result.Failed -> _message.value = "Could not update: ${result.message}"
            }
            _refreshing.value = false
        }
    }

    fun messageShown() { _message.value = null }

    fun recentShops(): List<ShopSwitch.Shop> = ShopSwitch.recent(getApplication())

    /** Points this install at a different shop (scanned QR or a recent one) — no reinstall. */
    fun switchShop(shop: ShopSwitch.Shop, onDone: () -> Unit) {
        viewModelScope.launch {
            ShopSwitch.switchTo(getApplication(), shop)
            clearSelection()
            onDone()
            refresh()
        }
    }

    fun setQty(itemId: Long, qty: Int) {
        _qty.value = _qty.value.toMutableMap().apply {
            if (qty <= 0) remove(itemId) else put(itemId, qty)
        }
    }

    fun clearSelection() { _qty.value = emptyMap() }

    /** POSTs the current selection to the shop's server. Remembers name/phone/address for next
     *  time, captures the device's current location (best-effort, needs location permission),
     *  and — on success — files a local [CustomerOrderHistory] record for Order History/Re-order. */
    fun saveOrder(name: String, phone: String, address: String = "", note: String = "", attachmentImage: String? = null) {
        val selection = _qty.value.mapNotNull { (id, count) ->
            items.value.find { it.id == id }?.let { it to count }
        }
        if (selection.isEmpty()) { _message.value = "Nothing selected"; return }
        if (_saving.value) return
        prefs.customerName = name
        prefs.customerPhone = phone
        prefs.customerAddress = address
        viewModelScope.launch {
            _saving.value = true
            val app: Application = getApplication()
            val locationLink = runCatching { LocationHelper.currentLocationLink(app) }.getOrNull()
            when (val result = OrderSubmit.submit(app, selection, name, phone, locationLink, address, note, attachmentImage)) {
                is OrderSubmit.Result.Ok -> {
                    _message.value = "Order saved — the shop will contact you"
                    val total = selection.sumOf { (item, qty) -> item.price * qty }
                    val lines = selection.map { (item, qty) ->
                        CustomerOrderHistory.Line(item.serverId, item.name, qty, item.price)
                    }
                    historyDao.insert(
                        CustomerOrderHistory(
                            itemsJson = CustomerOrderHistory.packItems(lines),
                            total = total,
                            placedAt = System.currentTimeMillis(),
                            location = locationLink.orEmpty()
                        )
                    )
                    clearSelection()
                }
                is OrderSubmit.Result.Failed -> _message.value = "Could not save order: ${result.message}"
            }
            _saving.value = false
        }
    }

    /** Restores a past order's quantities into the current selection — only items still in the
     *  live catalog (matched by serverId) can be re-added. */
    fun reorder(order: CustomerOrderHistory) {
        val byServerId = items.value.associateBy { it.serverId }
        val restored = order.items.mapNotNull { line -> byServerId[line.serverId]?.let { it.id to line.qty } }
        if (restored.isEmpty()) { _message.value = "Those items are no longer available"; return }
        _qty.value = restored.toMap()
    }

    /** WhatsApp text for the current selection: item lines + total. Blank if nothing's selected. */
    fun orderMessage(): String {
        val selected = _qty.value
        if (selected.isEmpty()) return ""
        val byId = items.value.associateBy { it.id }
        var total = 0.0
        val lines = selected.entries.mapNotNull { (id, count) ->
            val item = byId[id] ?: return@mapNotNull null
            val lineTotal = item.price * count
            total += lineTotal
            "- ${item.name} x$count = ₹${com.billing.pos.util.Format.money(lineTotal)}"
        }
        return buildString {
            append("Order from POS Billing app:\n")
            append(lines.joinToString("\n"))
            append("\nTotal: ₹${com.billing.pos.util.Format.money(total)}")
        }
    }
}
