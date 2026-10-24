package com.scimsoft.peace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns the countdown notification and periodically
 * checks whether the lock (or emergency unlock) is due — backup to AlarmManager.
 */
class LockTimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val prefs = LockPrefs(this@LockTimerService)
            if (!prefs.isLocked) {
                stopSelf()
                return
            }
            val now = System.currentTimeMillis()
            if (prefs.emergencyUnlockAtMs > 0L && now >= prefs.emergencyUnlockAtMs) {
                LockController.unlock(this@LockTimerService, "emergency_tick")
                return
            }
            if (now >= prefs.lockEndAtMs) {
                LockController.unlock(this@LockTimerService, "timer_tick")
                return
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(prefs.remainingMs(now)))
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            handler.removeCallbacksAndMessages(null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = LockPrefs(this)
        startForeground(NOTIF_ID, buildNotification(prefs.remainingMs()))
        handler.removeCallbacksAndMessages(null)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildNotification(remainingMs: Long): Notification {
        ensureChannel()
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, formatDuration(remainingMs)))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "peace_timer"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.scimsoft.peace.TIMER_STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LockTimerService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LockTimerService::class.java).setAction(ACTION_STOP)
            )
        }

        fun formatDuration(ms: Long): String {
            var rem = ms.coerceAtLeast(0L)
            val hours = TimeUnit.MILLISECONDS.toHours(rem)
            rem -= TimeUnit.HOURS.toMillis(hours)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(rem)
            rem -= TimeUnit.MINUTES.toMillis(minutes)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(rem)
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
