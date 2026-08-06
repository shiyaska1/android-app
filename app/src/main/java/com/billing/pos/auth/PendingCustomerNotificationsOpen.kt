package com.billing.pos.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Set when a customer taps an order-update notification — consumed by
 *  [com.billing.pos.ui.customer.CustomerCatalogScreen] itself (there's no separate route for the
 *  notification list, it's a dialog on that one screen) to open the list straight away. Same
 *  pattern as [PendingDiaryOpen], just a flag instead of an id. */
object PendingCustomerNotificationsOpen {
    var pending by mutableStateOf(false)

    fun consume(): Boolean {
        val v = pending
        pending = false
        return v
    }
}
