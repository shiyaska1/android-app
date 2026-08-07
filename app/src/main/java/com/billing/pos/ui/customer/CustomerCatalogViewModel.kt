package com.billing.pos.ui.customer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.customer.CustomerNotificationPoll
import com.billing.pos.customer.LocationHelper
import com.billing.pos.customer.NotificationsFetch
import com.billing.pos.customer.OrderSubmit
import com.billing.pos.customer.PushTokenRegistration
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

    val savedCustomerName: String get() = prefs.customerName
    val savedCustomerPhone: String get() = prefs.customerPhone
    val savedCustomerAddress: String get() = prefs.customerAddress
    val isPremiumShop: Boolean get() = prefs.customerPremiumShop

    init {
        // First open after install: the cache is empty, so fetch immediately without
        // waiting for the user to find the refresh button.
        if (prefs.catalogLastFetchedAt <= 0L) refresh()
        // Best-effort background poll for order-status updates, as a fallback for whenever a
        // push doesn't arrive — arms itself on every open, since there's no separate "leaving
        // customer mode" hook to arm it from once and forget.
        CustomerNotificationPoll.schedule(app)
        viewModelScope.launch {
            // Pop an actual system notification for anything new here too — not just the
            // background poll/push path (CustomerNotificationReceiver) — otherwise a promotion
            // that arrives while the customer already has the app open only ever shows up as an
            // in-app badge, never as a heads-up alert.
            when (val result = runCatching { NotificationsFetch.fetchAllKnownShops(app) }.getOrNull()) {
                is NotificationsFetch.Result.Ok -> result.fresh.forEach { com.billing.pos.customer.CustomerNotifications.show(app, it) }
                else -> {}
            }
        }
        viewModelScope.launch { PushTokenRegistration.registerIfNeeded(app) }
    }

    /** Marks every notification read — called when the customer opens the notification list. */
    fun notificationsOpened() {
        viewModelScope.launch { notificationDao.markAllRead() }
    }

    fun deleteNotification(notification: CustomerNotification) {
        viewModelScope.launch { notificationDao.delete(notification) }
    }

    fun clearAllNotifications() {
        viewModelScope.launch { notificationDao.deleteAll() }
    }

    fun isShopMuted(shop: String): Boolean = com.billing.pos.customer.ShopSwitch.isMuted(getApplication(), shop)

    /** Stops future notifications from [shop] (e.g. one sending too many promotions) without
     *  affecting its catalog/ordering, or its already-received notifications. */
    fun setShopMuted(shop: String, muted: Boolean) {
        com.billing.pos.customer.ShopSwitch.setMuted(getApplication(), shop, muted)
        _message.value = if (muted) "Muted — you won't get notifications from this shop anymore" else "Unmuted"
    }

    private val _replying = MutableStateFlow(false)
    val replying: StateFlow<Boolean> = _replying

    /** Sends a reply to the shop about [notification] — the customer side of a live chat with
     *  the shop, picked up on the shop owner's next Messages poll/refresh. */
    fun replyToNotification(notification: CustomerNotification, text: String) {
        if (text.isBlank() || _replying.value) return
        viewModelScope.launch {
            _replying.value = true
            val app: Application = getApplication()
            // Route to whichever shop actually sent this notification, not whichever shop
            // happens to be active right now — they can differ once a customer is connected to
            // more than one shop (see ShopSwitch). Falls back to the current shop for
            // pre-migration rows that never recorded which shop they came from.
            val target = com.billing.pos.customer.ShopSwitch.known(app).find { it.shop == notification.shop }
            val ok = com.billing.pos.customer.CustomerMessageSend.send(
                app, text, notification.orderId,
                targetUrl = target?.url.orEmpty(), targetShop = target?.shop ?: notification.shop
            )
            _message.value = if (ok) "Reply sent" else "Could not send reply — check your connection"
            _replying.value = false
        }
    }

    /** The correct UPI payee for a bill from [shop] — critical to get right for a customer
     *  connected to more than one shop (see ShopSwitch): a notification can arrive from a shop
     *  that is NOT the currently active one, and that shop's own VPA (not whichever shop happens
     *  to be active right now) must be what the QR code pays. Returns null when that shop
     *  has no UPI ID set, which must hide the pay button entirely — never fall back to some other
     *  shop's UPI ID. */
    fun upiFor(shop: String): Pair<String, String>? {
        if (shop.isBlank()) return null
        if (shop == prefs.shopCode) {
            return prefs.shopUpiId.takeIf { it.isNotBlank() }?.let { it to prefs.shopUpiName }
        }
        val known = com.billing.pos.customer.ShopSwitch.known(getApplication<Application>()).find { it.shop == shop }
        return known?.upi?.takeIf { it.isNotBlank() }?.let { it to known.upiName }
    }

    /** [proofAttachment] — a payment-success screenshot, required before "I've paid via QR" can
     *  even be tapped (the QR flow has no automatic success callback the app can detect on its
     *  own) — travels to the shop as proof, same data-URI convention as every other attachment
     *  in this app. */
    fun markNotificationPaid(notification: CustomerNotification, proofAttachment: String? = null) {
        viewModelScope.launch {
            val app: Application = getApplication()
            if (notification.orderId.isNotBlank()) historyDao.markPaid(notification.orderId)
            val target = com.billing.pos.customer.ShopSwitch.known(app).find { it.shop == notification.shop }
            com.billing.pos.customer.CustomerMessageSend.send(
                app,
                message = "Paid " + com.billing.pos.util.Format.rupee(notification.amount) + " via UPI" +
                    (if (notification.orderId.isNotBlank()) " for order #${notification.orderId}" else ""),
                orderId = notification.orderId,
                targetUrl = target?.url.orEmpty(), targetShop = target?.shop ?: notification.shop,
                paymentStatus = "UPI",
                attachments = proofAttachment?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            )
            _message.value = "Payment recorded — the shop has been notified"
        }
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
     *  Picking a known/recent shop from a list shows its cached items/details immediately (see
     *  [ShopSwitch.switchTo]) and only fetches if there's genuinely nothing cached yet. A fresh
     *  QR scan ([forceFetch]) always fetches — the whole point of scanning (rather than picking
     *  from the list) is to add or confirm a shop right now, e.g. a friend forwarded its QR code
     *  and the customer wants its current items even if they'd visited that shop before. Either
     *  way the customer can always pull the manual refresh button for the latest items/prices. */
    fun switchShop(shop: ShopSwitch.Shop, forceFetch: Boolean = false, onDone: () -> Unit) {
        viewModelScope.launch {
            ShopSwitch.switchTo(getApplication(), shop)
            _shopCode.value = shop.shop
            clearSelection()
            onDone()
            if (forceFetch || prefs.catalogLastFetchedAt <= 0L) refresh()
        }
    }

    fun setQty(itemId: Long, qty: Int) {
        _qty.value = _qty.value.toMutableMap().apply {
            if (qty <= 0) remove(itemId) else put(itemId, qty)
        }
    }

    fun clearSelection() { _qty.value = emptyMap() }

    /** POSTs the current selection to the shop's server. Remembers name/phone/address for next
     *  time, and — on success — files a local [CustomerOrderHistory] record for Order
     *  History/Re-order. Location is compulsory: the shop needs to know where to deliver.
     *  [manualLocationLink], when set (the customer said they're not at the delivery point right
     *  now), is used as-is instead of reading the phone's own GPS — the caller is responsible for
     *  having already obtained location permission before calling this without one. */
    fun saveOrder(
        name: String,
        phone: String,
        address: String = "",
        note: String = "",
        attachments: List<String> = emptyList(),
        manualLocationLink: String? = null,
        paymentStatus: String = ""
    ) {
        val selection = _qty.value.mapNotNull { (id, count) ->
            items.value.find { it.id == id }?.let { it to count }
        }
        // A customer who doesn't want to pick from the catalog can just write what they want, or
        // send a photo alone (e.g. a prescription to a shop with no browsable items at all, like
        // a medical store) — only block if there's genuinely nothing to send at all.
        if (selection.isEmpty() && note.isBlank() && attachments.isEmpty()) {
            _message.value = "Add items, write your order, or attach a photo"
            return
        }
        if (_saving.value) return
        prefs.customerName = name
        prefs.customerPhone = phone
        prefs.customerAddress = address
        viewModelScope.launch { PushTokenRegistration.registerIfNeeded(getApplication()) }
        viewModelScope.launch {
            _saving.value = true
            val app: Application = getApplication()
            val locationLink = if (!manualLocationLink.isNullOrBlank()) manualLocationLink
                else runCatching { LocationHelper.currentLocationLink(app) }.getOrNull()
            if (locationLink.isNullOrBlank()) {
                _message.value = "Could not get your location — turn on Location and try again"
                _saving.value = false
                return@launch
            }
            when (val result = OrderSubmit.submit(app, selection, name, phone, locationLink, address, note, attachments, paymentStatus)) {
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
                            note = note,
                            paymentStatus = paymentStatus,
                            attachments = CustomerOrderHistory.packAttachments(attachments)
                        )
                    )
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

    /** Frees up phone space by removing one past order's local record — including whatever
     *  photos/voice notes it (or the shop's reply to it) is holding onto. The server never had
     *  this copy for more than a moment either way, so there's nothing else to clean up. */
    fun deleteHistoryOrder(order: CustomerOrderHistory) {
        viewModelScope.launch { historyDao.delete(order) }
    }
}
