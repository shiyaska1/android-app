package com.billing.pos.ui.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.customer.OrderStatusPush
import com.billing.pos.customer.OrdersFetch
import com.billing.pos.customer.ShopOrderPoll
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.OnlineOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnlineOrdersViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).onlineOrderDao()

    init {
        // Best-effort background poll for new orders (no push server behind this app) —
        // arms itself every time this screen opens, same pattern as CustomerNotificationPoll.
        ShopOrderPoll.schedule(app)
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
            when (val result = OrdersFetch.fetch(getApplication())) {
                is OrdersFetch.Result.Ok ->
                    _message.value = if (result.count > 0) "Fetched ${result.count} new order(s)" else "No new orders"
                is OrdersFetch.Result.Failed -> _message.value = "Could not fetch orders: ${result.message}"
            }
            _fetching.value = false
        }
    }

    fun setStatus(order: OnlineOrder, status: String) {
        viewModelScope.launch {
            dao.updateStatus(order.id, status)
            // Best-effort — the local status change stands even if the customer isn't notified.
            OrderStatusPush.push(getApplication(), order.customerPhone, order.serverId, status = status)
        }
    }

    /** A free-text message to the customer about this order, not tied to a status change. */
    fun sendMessage(order: OnlineOrder, message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            OrderStatusPush.push(getApplication(), order.customerPhone, order.serverId, message = message)
        }
    }
}
