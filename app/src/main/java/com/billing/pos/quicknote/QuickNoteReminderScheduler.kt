package com.billing.pos.quicknote

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.billing.pos.MainActivity
import com.billing.pos.data.QuickNote
import java.util.Calendar

/**
 * Schedules quick-note reminders via AlarmManager.setAlarmClock (exact, no special permission).
 *
 * Kept separate from the diary's ReminderScheduler rather than shared: both note tables restart
 * their primary key at 1, and PendingIntent/notification identity would collide if the two
 * features reused the same request-code space.
 */
object QuickNoteReminderScheduler {

    const val CHANNEL_ID = "quick_note_reminders"
    const val EXTRA_ID = "id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Quick note reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Reminders for quick notes" }
            )
        }
    }

    /** Returns true if the alarm was scheduled. Never throws (scheduling must not crash a save). */
    fun schedule(context: Context, note: QuickNote): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            if (!note.reminderEnabled || note.reminderAt <= 0L) {
                am.cancel(pendingFor(context, note.id))
                return false
            }
            val triggerAt = if (note.reminderDaily) nextDaily(note.reminderAt) else note.reminderAt
            // Skip a one-time reminder whose time has already passed.
            if (!note.reminderDaily && triggerAt <= System.currentTimeMillis()) return false

            ensureChannel(context)
            val pi = pendingFor(context, note.id)
            try {
                val show = PendingIntent.getActivity(
                    context, note.id.toInt(),
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
            } catch (e: Exception) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } catch (e2: Exception) {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Re-arms the reminder to nag again after [REPEAT_INTERVAL_MS]. Never throws. */
    fun scheduleRepeat(context: Context, note: QuickNote) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + REPEAT_INTERVAL_MS
        val pi = pendingFor(context, note.id)
        try {
            val show = PendingIntent.getActivity(
                context, note.id.toInt(),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
        } catch (e: Exception) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                runCatching { am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
            }
        }
    }

    fun cancel(context: Context, noteId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingFor(context, noteId))
    }

    private fun pendingFor(context: Context, noteId: Long): PendingIntent {
        val intent = Intent(context, QuickNoteReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, noteId)
        }
        return PendingIntent.getBroadcast(
            context, noteId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next occurrence of the time-of-day encoded in [base]. */
    fun nextDaily(base: Long): Long {
        val src = Calendar.getInstance().apply { timeInMillis = base }
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, src.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, src.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis
    }

    /** How often a set reminder re-notifies until it is turned off or the note is deleted. */
    const val REPEAT_INTERVAL_MS = 5 * 60 * 1000L
}
