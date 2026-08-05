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

/** System notification for the shop owner: a customer placed a new online order — the mirror
 *  of [CustomerNotifications] on the other side of this feature. */
object ShopNotifications {
    const val CHANNEL_ID = "shop_new_orders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New online orders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "A customer placed a new online order" }
            )
        }
    }

    fun showNewOrders(context: Context, count: Int) {
        ensureChannel(context)
        val text = if (count == 1) "1 new online order" else "$count new online orders"
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New order")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            runCatching { NotificationManagerCompat.from(context).notify("shop_new_order", System.currentTimeMillis().toInt(), built) }
        }
    }
}
