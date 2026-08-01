package com.billing.pos.quicknote

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
import com.billing.pos.data.QuickNote
import com.billing.pos.data.QuickNoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val EXTRA_OPEN_QUICKNOTE_ID = "open_quicknote_id"

class QuickNoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(QuickNoteReminderScheduler.EXTRA_ID, 0L)
        if (id <= 0L) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val note = runCatching { QuickNoteRepository(app).byId(id) }.getOrNull()
                // Note gone or reminder switched off → stop nagging for good.
                if (note == null || !note.reminderEnabled) {
                    QuickNoteReminderScheduler.cancel(app, id)
                    return@launch
                }
                showNotification(app, note)
                // Nag again in 5 minutes until the user turns it off or deletes the note.
                QuickNoteReminderScheduler.scheduleRepeat(app, note)
            } catch (e: Exception) {
                // never crash on a reminder
            } finally {
                pending.finish()
            }
        }
    }

    private fun showNotification(context: Context, note: QuickNote) {
        QuickNoteReminderScheduler.ensureChannel(context)
        val text = note.text.ifBlank { "Quick note" }

        // Tapping opens the app straight into this note in edit mode.
        val open = PendingIntent.getActivity(
            context, note.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_QUICKNOTE_ID, note.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, QuickNoteReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Quick note reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            // Tagged, so a numerically-equal diary entry id never overwrites this note's notification.
            runCatching { NotificationManagerCompat.from(context).notify("quicknote", note.id.toInt(), notification) }
        }
    }
}
