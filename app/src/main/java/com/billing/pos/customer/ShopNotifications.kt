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

/** Which customer's thread to open (a phone number) — carried by a "new message" notification's
 *  tap intent, same convention as [com.billing.pos.diary.EXTRA_OPEN_DIARY_ID]. */
const val EXTRA_OPEN_MESSAGE_PHONE = "open_message_phone"

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
                    .apply {
                        description = "A customer placed a new online order"
                        enableVibration(true)
                        setShowBadge(true)
                    }
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
            runCatching { NotificationManagerCompat.from(context).notify("shop_new_order", System.currentTimeMillis().toInt(), built) }
        }
    }

    /** A customer sent a message/reply — see [ShopMessagesFetch]. Opens straight into that
     *  customer's thread on the Messages screen rather than just the app's default screen. */
    fun showNewMessage(context: Context, customerPhone: String, customerName: String, text: String) {
        ensureChannel(context)
        val title = customerName.ifBlank { customerPhone }
        val open = PendingIntent.getActivity(
            context, customerPhone.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_MESSAGE_PHONE, customerPhone)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()

        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            runCatching { NotificationManagerCompat.from(context).notify("shop_message_$customerPhone", customerPhone.hashCode(), built) }
        }
    }

    /** A customer registered their name/phone in the customer app for the first time, before
     *  placing any order — see [OnlineCustomersFetch]. Opens straight into Customers so the shop
     *  owner sees the new entry (already added to the Customer master by that point). */
    fun showNewCustomer(context: Context, customerName: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New customer registered")
            .setContentText("$customerName installed your online ordering app")
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()

        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            runCatching { NotificationManagerCompat.from(context).notify("shop_new_customer", System.currentTimeMillis().toInt(), built) }
        }
    }
}
