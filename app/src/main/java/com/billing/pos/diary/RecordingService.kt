package com.billing.pos.diary

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps a voice recording alive while the screen is locked or the app is in the background.
 *
 * From Android 9 an app cannot use the microphone from the background without a foreground
 * service, and that service MUST show a notification the whole time it runs — Android
 * enforces this, and it is deliberately kept: the recording is never hidden. The service
 * only holds the notification and the microphone foreground-service type; the actual
 * MediaRecorder stays in the diary view-model, which starts and stops this alongside it.
 */
class RecordingService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Voice recording", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording voice note")
            .setContentText("Tap the app to stop")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun startForeground(id: Int, notification: Notification) {
        // Declaring the microphone type is what lets the mic keep running in the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            super.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            super.startForeground(id, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "voice_recording"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.billing.pos.STOP_RECORDING"

        /** Starts the foreground service so the mic survives the lock screen. */
        fun start(context: Context) {
            val i = Intent(context, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }
}
