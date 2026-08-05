package com.billing.pos.ui.customer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.customer.OrderSubmit
import com.billing.pos.customer.ShopCatalogSync
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.ShopCatalogItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CustomerCatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    private val dao = AppDatabase.get(app).shopCatalogDao()

    val items: StateFlow<List<ShopCatalogItem>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun setQty(itemId: Long, qty: Int) {
        _qty.value = _qty.value.toMutableMap().apply {
            if (qty <= 0) remove(itemId) else put(itemId, qty)
        }
    }

    fun clearSelection() { _qty.value = emptyMap() }

    /** POSTs the current selection to the shop's server. Remembers name/phone for next time. */
    fun saveOrder(name: String, phone: String) {
        val selection = _qty.value.mapNotNull { (id, count) ->
            items.value.find { it.id == id }?.let { it to count }
        }
        if (selection.isEmpty()) { _message.value = "Nothing selected"; return }
        if (_saving.value) return
        prefs.customerName = name
        prefs.customerPhone = phone
        viewModelScope.launch {
            _saving.value = true
            when (val result = OrderSubmit.submit(getApplication(), selection, name, phone)) {
                is OrderSubmit.Result.Ok -> {
                    _message.value = "Order saved — the shop will contact you"
                    clearSelection()
                }
                is OrderSubmit.Result.Failed -> _message.value = "Could not save order: ${result.message}"
            }
            _saving.value = false
        }
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
