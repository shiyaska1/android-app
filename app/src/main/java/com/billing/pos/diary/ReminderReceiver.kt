package com.billing.pos.diary

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.billing.pos.MainActivity
import com.billing.pos.data.DiaryEntry

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.ensureChannel(context)

        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0L)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE)?.ifBlank { "Diary reminder" }
            ?: "Diary reminder"
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT).orEmpty()
        val daily = intent.getBooleanExtra(ReminderScheduler.EXTRA_DAILY, false)
        val at = intent.getLongExtra(ReminderScheduler.EXTRA_AT, 0L)

        val open = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            runCatching { NotificationManagerCompat.from(context).notify(id.toInt(), notification) }
        }

        // Reschedule tomorrow for daily reminders.
        if (daily) {
            ReminderScheduler.schedule(
                context,
                DiaryEntry(
                    id = id, title = title, remarks = text,
                    createdAt = 0, updatedAt = 0,
                    reminderEnabled = true, reminderAt = at, reminderDaily = true
                )
            )
        }
    }
}
