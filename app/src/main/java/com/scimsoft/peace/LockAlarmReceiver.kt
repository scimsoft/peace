package com.scimsoft.peace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles lock-expiry alarms, emergency-unlock-due alarms, and BOOT_COMPLETED
 * so a reboot cannot silently leave the device unlocked before the timer ends
 * (or stuck locked after it should have ended).
 */
class LockAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "onReceive: $action")
        val prefs = LockPrefs(context)
        val now = System.currentTimeMillis()

        when (action) {
            ACTION_LOCK_EXPIRED,
            ACTION_EMERGENCY_UNLOCK_DUE -> {
                LockController.unlock(context, reason = action)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                if (!prefs.isLocked) return
                val emergencyDue = prefs.emergencyUnlockAtMs > 0 && now >= prefs.emergencyUnlockAtMs
                val timerDue = prefs.lockEndAtMs > 0 && now >= prefs.lockEndAtMs
                if (emergencyDue || timerDue) {
                    LockController.unlock(context, reason = "boot_expired")
                } else {
                    // Resume VPN + foreground timer + re-arm alarms.
                    LockController.rearmAfterBoot(context)
                }
            }
        }
    }

    companion object {
        private const val TAG = "LockAlarmReceiver"
        const val ACTION_LOCK_EXPIRED = "com.scimsoft.peace.ACTION_LOCK_EXPIRED"
        const val ACTION_EMERGENCY_UNLOCK_DUE = "com.scimsoft.peace.ACTION_EMERGENCY_UNLOCK_DUE"
    }
}
