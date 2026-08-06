package com.billing.pos.ui.customer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.customer.CustomerNotificationPoll
import com.billing.pos.customer.LocationHelper
import com.billing.pos.customer.NotificationsFetch
import com.billing.pos.customer.OrderSubmit
import com.billing.pos.customer.ShopCatalogSync
import com.billing.pos.customer.ShopSwitch
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CustomerNotification
import com.billing.pos.data.CustomerOrderHistory
import com.billing.pos.data.ShopCatalogItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CustomerCatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    private val dao = AppDatabase.get(app).shopCatalogDao()
    private val historyDao = AppDatabase.get(app).customerOrderHistoryDao()
    private val notificationDao = AppDatabase.get(app).customerNotificationDao()

    // The active shop's code, so switching re-points [items] at that shop's own cache instead of
    // requiring a fresh Flow collector — see switchShop().
    private val _shopCode = MutableStateFlow(prefs.shopCode)

    val items: StateFlow<List<ShopCatalogItem>> = _shopCode
        .flatMapLatest { shop -> dao.observeForShop(shop) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<CustomerOrderHistory>> =
        historyDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<CustomerNotification>> =
        notificationDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadNotifications: StateFlow<Int> =
        notificationDao.observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    // A save/fetch failure that's the server's fault (not something the customer can fix, like a
    // missing permission) — shown as a dialog with the shop's contact details instead of a
    // snackbar, since the customer has no other way to know their order didn't go through.
    private val _technicalError = MutableStateFlow<String?>(null)
    val technicalError: StateFlow<String?> = _technicalError

    val lastFetchedAt: Long get() = prefs.catalogLastFetchedAt

    // Selected quantities, keyed by ShopCatalogItem.id.
    private val _qty = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val qty: StateFlow<Map<Long, Int>> = _qty

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    // Set on a successful order that was placed via the Share button — the UI observes this
    // once to launch the WhatsApp intent, then calls shareTextConsumed().
    private val _shareText = MutableStateFlow<String?>(null)
    val shareText: StateFlow<String?> = _shareText

    val savedCustomerName: String get() = prefs.customerName
    val savedCustomerPhone: String get() = prefs.customerPhone
    val savedCustomerAddress: String get() = prefs.customerAddress
    val isPremiumShop: Boolean get() = prefs.customerPremiumShop
    val isRegistered: Boolean get() = prefs.customerName.isNotBlank() && prefs.customerPhone.isNotBlank()

    init {
        // First open after install: the cache is empty, so fetch immediately without
        // waiting for the user to find the refresh button.
        if (prefs.catalogLastFetchedAt <= 0L) refresh()
        // Best-effort background poll for order-status updates (no push server behind this
        // app) — arms itself on every open, since there's no separate "leaving customer mode"
        // hook to arm it from once and forget.
        CustomerNotificationPoll.schedule(app)
        viewModelScope.launch { runCatching { NotificationsFetch.fetch(app) } }
    }

    /** Marks every notification read — called when the customer opens the notification list. */
    fun notificationsOpened() {
        viewModelScope.launch { notificationDao.markAllRead() }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            when (val result = ShopCatalogSync.refresh(getApplication())) {
                is ShopCatalogSync.Result.Ok -> _message.value = "Updated — ${result.count} item(s)"
                is ShopCatalogSync.Result.Failed -> _technicalError.value = result.message
            }
            _refreshing.value = false
        }
    }

    fun messageShown() { _message.value = null }

    fun technicalErrorShown() { _technicalError.value = null }

    fun recentShops(): List<ShopSwitch.Shop> = ShopSwitch.recent(getApplication())

    /** Every shop this device has ever connected to (current one included), for the "Browse
     *  shops" directory — grouped by category there, not just a flat quick-switch list. */
    fun knownShops(): List<ShopSwitch.Shop> = ShopSwitch.known(getApplication())

    /** Points this install at a different shop (scanned QR or a recent one) — no reinstall.
     *  If that shop was visited before, its cached items/details show immediately (see
     *  [ShopSwitch.switchTo]); only a never-visited shop triggers an automatic fetch. Either way
     *  the customer can always pull the manual refresh button for the latest items/prices. */
    fun switchShop(shop: ShopSwitch.Shop, onDone: () -> Unit) {
        viewModelScope.launch {
            ShopSwitch.switchTo(getApplication(), shop)
            _shopCode.value = shop.shop
            clearSelection()
            onDone()
            if (prefs.catalogLastFetchedAt <= 0L) refresh()
        }
    }

    fun setQty(itemId: Long, qty: Int) {
        _qty.value = _qty.value.toMutableMap().apply {
            if (qty <= 0) remove(itemId) else put(itemId, qty)
        }
    }

    fun clearSelection() { _qty.value = emptyMap() }

    fun shareTextConsumed() { _shareText.value = null }

    /** POSTs the current selection to the shop's server. Remembers name/phone/address for next
     *  time, and — on success — files a local [CustomerOrderHistory] record for Order
     *  History/Re-order. Location is compulsory: the shop needs to know where to deliver, so a
     *  missing/failed location fix fails the whole order rather than silently going through
     *  without one. The caller (the Save/Share buttons) is responsible for having already
     *  obtained location permission before calling this. [alsoShare] additionally opens WhatsApp
     *  with the order text once the save succeeds — see [shareText]. */
    fun saveOrder(
        name: String,
        phone: String,
        address: String = "",
        note: String = "",
        attachmentImage: String? = null,
        alsoShare: Boolean = false
    ) {
        val selection = _qty.value.mapNotNull { (id, count) ->
            items.value.find { it.id == id }?.let { it to count }
        }
        // A customer who doesn't want to pick from the catalog can just write what they want —
        // only block if there's genuinely nothing to send either way.
        if (selection.isEmpty() && note.isBlank()) { _message.value = "Add items, or write your order in the note"; return }
        if (_saving.value) return
        prefs.customerName = name
        prefs.customerPhone = phone
        prefs.customerAddress = address
        viewModelScope.launch {
            _saving.value = true
            val app: Application = getApplication()
            val locationLink = runCatching { LocationHelper.currentLocationLink(app) }.getOrNull()
            if (locationLink.isNullOrBlank()) {
                _message.value = "Could not get your location — turn on Location and try again"
                _saving.value = false
                return@launch
            }
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
                            location = locationLink,
                            serverId = result.orderId,
                            note = note
                        )
                    )
                    if (alsoShare) _shareText.value = buildOrderText(selection, total)
                    clearSelection()
                }
                is OrderSubmit.Result.Failed -> _technicalError.value = result.message
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

    private fun buildOrderText(selection: List<Pair<ShopCatalogItem, Int>>, total: Double): String {
        val lines = selection.map { (item, count) ->
            "- ${item.name} x$count = ₹${com.billing.pos.util.Format.money(item.price * count)}"
        }
        return buildString {
            append("Order from POS Billing app:\n")
            append(lines.joinToString("\n"))
            append("\nTotal: ₹${com.billing.pos.util.Format.money(total)}")
        }
    }
}
