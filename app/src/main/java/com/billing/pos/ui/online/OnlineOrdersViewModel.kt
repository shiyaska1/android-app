package com.billing.pos.ui.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.customer.OrderStatusPush
import com.billing.pos.customer.OrdersFetch
import com.billing.pos.customer.PushTokenRegistration
import com.billing.pos.customer.ShopOrderPoll
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.CustOrder
import com.billing.pos.data.CustOrderItem
import com.billing.pos.data.License
import com.billing.pos.data.OnlineOrder
import com.billing.pos.data.OnlineOrderStatus
import com.billing.pos.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnlineOrdersViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).onlineOrderDao()
    private val repo = Repository(app)
    private val prefs = com.billing.pos.data.AppPrefs(app)
    val deviceId: String get() = License.deviceId(getApplication())

    private val _showProLimitDialog = MutableStateFlow(false)
    val showProLimitDialog: StateFlow<Boolean> = _showProLimitDialog
    fun dismissProLimitDialog() { _showProLimitDialog.value = false }

    /** Validates [key] against this device's id and, if it matches, unlocks unlimited online
     *  items AND unlimited daily notifications for good (same key gates both). */
    fun activatePro(key: String): Boolean {
        val valid = License.isOnlineCatalogProValid(deviceId, key)
        if (valid) prefs.onlineCatalogPro = true
        return valid
    }

    init {
        // Best-effort background poll for new orders, as a fallback for whenever a push doesn't
        // arrive — arms itself every time this screen opens, same pattern as CustomerNotificationPoll.
        ShopOrderPoll.schedule(app)
        viewModelScope.launch { PushTokenRegistration.registerIfNeeded(app) }
    }

    val orders: StateFlow<List<OnlineOrder>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _fetching = MutableStateFlow(false)
    val fetching: StateFlow<Boolean> = _fetching

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun messageShown() { _message.value = null }

    fun fetch() {
        if (_fetching.value) return
        viewModelScope.launch {
            _fetching.value = true
            val app: Application = getApplication()
            when (val result = OrdersFetch.fetch(app)) {
                is OrdersFetch.Result.Ok -> {
                    _message.value = if (result.count > 0) "Fetched ${result.count} new order(s)" else "No new orders"
                    // Pop an actual system notification here too, not just on the background
                    // poll/push path (ShopOrderPollReceiver) — otherwise opening this screen
                    // yourself never alerts you the way a background fetch would.
                    if (result.count > 0) com.billing.pos.customer.ShopNotifications.showNewOrders(app, result.count)
                }
                is OrdersFetch.Result.Failed -> _message.value = "Could not fetch orders: ${result.message}"
            }
            _fetching.value = false
        }
    }

    fun setStatus(order: OnlineOrder, status: String) {
        viewModelScope.launch {
            // Best-effort — the local status change stands even if the customer isn't notified,
            // whether that's a network failure or the free-plan daily notification cap.
            if (License.reserveNotificationSend(getApplication())) {
                OrderStatusPush.push(getApplication(), order.customerPhone, order.serverId, status = status)
            } else {
                _showProLimitDialog.value = true
            }
            if (status == OnlineOrderStatus.ACCEPTED.name) {
                convertToOrderAndRemove(order)
            } else {
                dao.updateStatus(order.id, status)
            }
        }
    }

    /** A free-text message to the customer about this order, not tied to a status change. */
    fun sendMessage(order: OnlineOrder, message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            if (License.reserveNotificationSend(getApplication())) {
                OrderStatusPush.push(getApplication(), order.customerPhone, order.serverId, message = message)
            } else {
                _showProLimitDialog.value = true
            }
        }
    }

    /** Accepting an order hands it off to the app's regular Orders (Masters > Orders), where
     *  "select orders > Convert to Sale" already exists — this only needs to create the order
     *  and remove it from this list, not rebuild that whole workflow here. */
    private suspend fun convertToOrderAndRemove(order: OnlineOrder) {
        val (lat, lng) = parseLatLng(order.location)
        val orderNo = repo.nextOrderNo()
        val custOrder = CustOrder(
            orderNo = orderNo,
            dateMillis = System.currentTimeMillis(),
            customerId = order.customerId,
            customerName = order.customerName,
            remark = order.note,
            latitude = lat,
            longitude = lng,
            grandTotal = order.total,
            deviceId = License.deviceId(getApplication())
        )
        val lines = order.items.map { line ->
            CustOrderItem(
                orderId = 0, itemId = 0, name = line.name,
                qty = line.qty.toDouble(), price = line.price, lineTotal = line.price * line.qty
            )
        }
        repo.saveOrder(custOrder, lines)
        dao.delete(order)
        _message.value = "Accepted — moved to Orders as $orderNo"
    }

    private fun parseLatLng(location: String): Pair<Double, Double> {
        val q = runCatching { android.net.Uri.parse(location).getQueryParameter("q") }.getOrNull() ?: return 0.0 to 0.0
        val parts = q.split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return lat to lng
    }
}
