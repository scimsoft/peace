package com.scimsoft.peace

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.DateFormat
import java.util.Date

/**
 * Delayed safety valve: user requests emergency unlock inside the app; after a
 * configurable cooldown (default 24h) the lock lifts even if the focus timer
 * has not finished — so the user can never permanently lock themselves out.
 *
 * During the cooldown the lock (VPN + restrictions) stays fully active.
 */
object EmergencyUnlock {

    private const val TAG = "EmergencyUnlock"
    private const val REQ_CODE = 2002

    fun isPending(prefs: LockPrefs): Boolean = prefs.emergencyUnlockAtMs > 0L

    fun pendingAtFormatted(prefs: LockPrefs): String {
        val at = prefs.emergencyUnlockAtMs
        if (at <= 0L) return ""
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(at))
    }

    fun request(context: Context, cooldownHours: Int = LockPrefs(context).emergencyCooldownHours) {
        val prefs = LockPrefs(context)
        if (!prefs.isLocked) return
        val hours = cooldownHours.coerceAtLeast(1)
        prefs.emergencyCooldownHours = hours
        val at = System.currentTimeMillis() + hours * 60L * 60L * 1000L
        // Never schedule emergency later than the normal lock end if that is sooner —
        // actually: emergency is a fallback when the timer is LONG; allow it to fire
        // even after lockEnd so a stuck session can still clear. Keep independent.
        prefs.emergencyUnlockAtMs = at
        scheduleAlarm(context, at)
        Log.i(TAG, "Emergency unlock scheduled for $at ($hours h)")
    }

    fun cancel(context: Context) {
        val prefs = LockPrefs(context)
        prefs.emergencyUnlockAtMs = 0L
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
        Log.i(TAG, "Emergency unlock cancelled")
    }

    fun scheduleAlarm(context: Context, atMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            @Suppress("DEPRECATION")
            am.setExact(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    fun rearmIfPending(context: Context) {
        val prefs = LockPrefs(context)
        val at = prefs.emergencyUnlockAtMs
        if (at > System.currentTimeMillis()) {
            scheduleAlarm(context, at)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LockAlarmReceiver::class.java)
            .setAction(LockAlarmReceiver.ACTION_EMERGENCY_UNLOCK_DUE)
        return PendingIntent.getBroadcast(
            context,
            REQ_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
