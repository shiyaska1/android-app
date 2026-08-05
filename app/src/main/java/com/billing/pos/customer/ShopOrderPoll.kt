package com.billing.pos.customer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.billing.pos.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shop owner side mirror of [CustomerNotificationPoll]: a best-effort background poll for new
 * orders, since there's no push server (no FCM) behind this app. Only does anything once online
 * ordering is actually configured (onlineCatalogUrl set) — armed the first time the shop owner
 * opens Online Orders, self-rearms from then on.
 */
object ShopOrderPoll {
    private const val REQUEST_CODE = 192837465
    private const val INTERVAL_MS = 30 * 60 * 1000L // 30 min — best-effort, not time-critical

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: Exception) {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, ShopOrderPollReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class ShopOrderPollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPrefs(app)
                if (!prefs.customerMode && prefs.onlineCatalogUrl.isNotBlank()) {
                    when (val result = OrdersFetch.fetch(app)) {
                        is OrdersFetch.Result.Ok -> if (result.count > 0) ShopNotifications.showNewOrders(app, result.count)
                        is OrdersFetch.Result.Failed -> {}
                    }
                    ShopOrderPoll.schedule(app)
                }
            } catch (e: Exception) {
                // best-effort — a background poll must never crash the app
            } finally {
                pending.finish()
            }
        }
    }
}
