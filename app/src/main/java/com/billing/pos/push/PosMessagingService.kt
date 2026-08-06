package com.billing.pos.push

import android.content.Intent
import com.billing.pos.customer.CustomerNotificationReceiver
import com.billing.pos.customer.PushTokenRegistration
import com.billing.pos.customer.ShopOrderPollReceiver
import com.billing.pos.data.AppPrefs
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Every push from the server (see server/pos_online_catalog.php's do=order/status/message
 * handlers, which each best-effort-notify the token(s) registered via do=registerToken) is a
 * plain "something's new, go check" data message — it carries no content of its own. On arrival
 * this just re-triggers the same [ShopOrderPollReceiver]/[CustomerNotificationReceiver] the 30-min
 * AlarmManager poll already uses, so the fetch, local-DB save, and notification-building logic
 * stays in exactly one place. If the push never arrives (offline, battery optimisation, old Play
 * Services, server has no service account configured yet), that same poll still catches it within
 * 30 minutes — push is a latency improvement, not a new code path to keep in sync.
 */
class PosMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val app = applicationContext
        AppPrefs(app).fcmToken = token
        CoroutineScope(Dispatchers.IO).launch { PushTokenRegistration.registerIfNeeded(app) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext
        val prefs = AppPrefs(app)
        val receiver = if (prefs.customerMode) CustomerNotificationReceiver::class.java else ShopOrderPollReceiver::class.java
        sendBroadcast(Intent(app, receiver))
    }
}
