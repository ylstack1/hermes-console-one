package com.hermesagent.hermes_android.localengine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hermesagent.hermes_android.MainActivity
import com.hermesagent.hermes_android.R

/**
 * Foreground service that keeps the PRoot + Hermes Agent gateway alive in the
 * background while the local engine switch is on.
 *
 * Acquires a PARTIAL_WAKE_LOCK to prevent CPU sleep during long install
 * operations (pip, npm) and while Hermes is serving requests.
 *
 * Based on Console's existing foreground service patterns.
 */
class LocalEngineForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "local_hermes_engine"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_STOP_ENGINE = "com.hermesagent.hermes_android.localengine.STOP_ENGINE"

        fun start(context: Context) {
            val intent = Intent(context, LocalEngineForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocalEngineForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ENGINE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocalEngineForegroundService:wake_lock"
            )
            wakeLock?.acquire()
        } catch (e: Exception) {
            // Wake lock not critical for operation
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.local_engine_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.local_engine_notification_channel_desc)
                setShowBadge(false)
                importance = NotificationManager.IMPORTANCE_LOW
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LocalEngineForegroundService::class.java).apply {
            action = ACTION_STOP_ENGINE
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPending = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.local_engine_notification_title))
            .setContentText(getString(R.string.local_engine_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(launchPending)
            .addAction(
                R.drawable.ic_notification_voice_mic, // reuse existing drawable
                getString(R.string.local_engine_stop_action),
                stopPending,
            )
            .setOngoing(true)
            .build()
    }
}
