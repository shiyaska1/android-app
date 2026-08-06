package com.billing.pos.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A customer's phone number captured from a "new message" notification, to open that
 *  customer's thread on the Messages screen straight away — see [PendingDiaryOpen] for the same
 *  pattern on the diary side. */
object PendingShopMessageThreadOpen {
    var phone by mutableStateOf<String?>(null)

    fun consume(): String? {
        val v = phone
        phone = null
        return v
    }
}
