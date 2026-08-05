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
 * Best-effort background poll for order-status notifications — there's no push server (no FCM)
 * behind this app, so a plain self-rearming AlarmManager stands in, same pattern as
 * QuickNoteReminderScheduler. Only useful while the app is in customer mode; each firing
 * re-schedules the next one, so it naturally stops once [AppPrefs.customerMode] goes false
 * (the receiver checks that before doing any work).
 */
object CustomerNotificationPoll {
    private const val REQUEST_CODE = 918273645
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
            context, REQUEST_CODE, Intent(context, CustomerNotificationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class CustomerNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (AppPrefs(app).customerMode) {
                    when (val result = NotificationsFetch.fetch(app)) {
                        is NotificationsFetch.Result.Ok -> result.fresh.forEach { CustomerNotifications.show(app, it) }
                        is NotificationsFetch.Result.Failed -> {}
                    }
                    CustomerNotificationPoll.schedule(app)
                }
            } catch (e: Exception) {
                // best-effort — a background poll must never crash the app
            } finally {
                pending.finish()
            }
        }
    }
}
