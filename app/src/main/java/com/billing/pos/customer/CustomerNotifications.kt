package com.billing.pos.customer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.billing.pos.MainActivity
import com.billing.pos.data.CustomerNotification
import com.billing.pos.data.OnlineOrderStatus

/** Marker extra: tapping this notification should open straight into the notification list
 *  (see [MainActivity]'s captureIncoming), same convention as [com.billing.pos.diary.EXTRA_OPEN_DIARY_ID]. */
const val EXTRA_OPEN_CUSTOMER_NOTIFICATIONS = "open_customer_notifications"

/** System notifications for a customer install: order status changes / messages from the shop —
 *  same channel/builder pattern as the diary and quick-note reminders elsewhere in this app. */
object CustomerNotifications {
    const val CHANNEL_ID = "customer_order_updates"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Order updates", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "Updates from the shop about your orders"
                        enableVibration(true)
                        setShowBadge(true)
                    }
            )
        }
    }

    fun show(context: Context, notification: CustomerNotification) {
        ensureChannel(context)
        val statusLabel = OnlineOrderStatus.entries.find { it.name == notification.status }?.label
        val text = when {
            statusLabel != null && notification.message.isNotBlank() -> "$statusLabel — ${notification.message}"
            statusLabel != null -> statusLabel
            notification.message.isNotBlank() -> notification.message
            else -> "Update on your order"
        }
        val open = PendingIntent.getActivity(
            context, notification.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_CUSTOMER_NOTIFICATIONS, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = notification.shopName.ifBlank { "Order update" }
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            // Explicit priority/vibrate on top of the channel's IMPORTANCE_HIGH — some OEM
            // notification stacks (MIUI, ColorOS, One UI) only pop up a heads-up banner when
            // the notification itself also asks for attention, not just the channel.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()

        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            runCatching { NotificationManagerCompat.from(context).notify("customer_order_update", notification.id.toInt(), built) }
        }
    }
}
