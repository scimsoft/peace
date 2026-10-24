package com.scimsoft.peace

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Orchestrates starting / stopping a lock session:
 * prefs → DevicePolicy hardening → always-on VPN → VpnService → timer FGS → AlarmManager.
 */
object LockController {

    private const val TAG = "LockController"
    private const val REQ_LOCK_ALARM = 2001

    /**
     * @param durationMs focus duration
     * @param allowlistPackage optional package that may bypass the VPN (or empty)
     */
    fun lock(context: Context, durationMs: Long, allowlistPackage: String) {
        require(durationMs > 0L) { "duration must be > 0" }
        val app = context.applicationContext
        val prefs = LockPrefs(app)
        val end = System.currentTimeMillis() + durationMs

        prefs.allowlistPackage = allowlistPackage
        prefs.lockEndAtMs = end
        prefs.isLocked = true
        prefs.emergencyUnlockAtMs = 0L

        val policy = DevicePolicyHelper(app)
        policy.setUninstallBlocked(true)
        policy.applyLockRestrictions()
        policy.enableAlwaysOnVpnLockdown()

        LockVpnService.start(app)
        LockTimerService.start(app)
        scheduleExpiryAlarm(app, end)

        Log.i(TAG, "Lock started until $end allowlist='$allowlistPackage'")
    }

    fun unlock(context: Context, reason: String) {
        val app = context.applicationContext
        Log.i(TAG, "Unlock reason=$reason")
        val prefs = LockPrefs(app)
        val policy = DevicePolicyHelper(app)

        cancelExpiryAlarm(app)
        EmergencyUnlock.cancel(app)

        LockVpnService.stop(app)
        LockTimerService.stop(app)

        policy.clearAlwaysOnVpn()
        policy.setUninstallBlocked(false)
        policy.relaxLockRestrictions()

        prefs.clearLock()
    }

    fun rearmAfterBoot(context: Context) {
        val app = context.applicationContext
        val prefs = LockPrefs(app)
        if (!prefs.isLocked) return

        val policy = DevicePolicyHelper(app)
        policy.setUninstallBlocked(true)
        policy.applyLockRestrictions()
        policy.enableAlwaysOnVpnLockdown()

        LockVpnService.start(app)
        LockTimerService.start(app)
        scheduleExpiryAlarm(app, prefs.lockEndAtMs)
        EmergencyUnlock.rearmIfPending(app)
        Log.i(TAG, "Rearmed after boot; end=${prefs.lockEndAtMs}")
    }

    fun scheduleExpiryAlarm(context: Context, endAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = expiryPendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMs, pi)
        } else {
            @Suppress("DEPRECATION")
            am.setExact(AlarmManager.RTC_WAKEUP, endAtMs, pi)
        }
    }

    private fun cancelExpiryAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(expiryPendingIntent(context))
    }

    private fun expiryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LockAlarmReceiver::class.java)
            .setAction(LockAlarmReceiver.ACTION_LOCK_EXPIRED)
        return PendingIntent.getBroadcast(
            context,
            REQ_LOCK_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
