package com.scimsoft.peace

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent lock / emergency / owner-challenge state.
 * Survives process death so AlarmManager + BOOT_COMPLETED can restore the session.
 */
class LockPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isLocked: Boolean
        get() = prefs.getBoolean(KEY_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCKED, value).apply()

    /** Wall-clock millis when the focus lock expires (0 if unlocked). */
    var lockEndAtMs: Long
        get() = prefs.getLong(KEY_LOCK_END, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCK_END, value).apply()

    /** Optional single package allowed through the blackhole VPN (empty = none). */
    var allowlistPackage: String
        get() = prefs.getString(KEY_ALLOWLIST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ALLOWLIST, value.trim()).apply()

    /** Wall-clock millis when emergency unlock becomes available (0 = none pending). */
    var emergencyUnlockAtMs: Long
        get() = prefs.getLong(KEY_EMERGENCY_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_EMERGENCY_AT, value).apply()

    /** Default emergency cooldown in hours (UI default 24). */
    var emergencyCooldownHours: Int
        get() = prefs.getInt(KEY_EMERGENCY_HOURS, 24)
        set(value) = prefs.edit().putInt(KEY_EMERGENCY_HOURS, value.coerceAtLeast(1)).apply()

    /**
     * Preferred owner-unlock UI: [OwnerUnlock.METHOD_CHALLENGE] or [OwnerUnlock.METHOD_PASSWORD].
     */
    var ownerUnlockMethod: String
        get() {
            val v = prefs.getString(KEY_OWNER_METHOD, OwnerUnlock.METHOD_CHALLENGE)
                ?: OwnerUnlock.METHOD_CHALLENGE
            return when (v) {
                OwnerUnlock.METHOD_PASSWORD -> OwnerUnlock.METHOD_PASSWORD
                else -> OwnerUnlock.METHOD_CHALLENGE
            }
        }
        set(value) = prefs.edit().putString(KEY_OWNER_METHOD, value).apply()

    /** Hex of the active owner-challenge payload (empty if none). */
    var ownerChallengePayloadHex: String
        get() = prefs.getString(KEY_OWNER_CHALLENGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OWNER_CHALLENGE, value).apply()

    var ownerChallengeExpiresAtMs: Long
        get() = prefs.getLong(KEY_OWNER_CHALLENGE_EXP, 0L)
        set(value) = prefs.edit().putLong(KEY_OWNER_CHALLENGE_EXP, value).apply()

    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        if (!isLocked) return 0L
        return (lockEndAtMs - now).coerceAtLeast(0L)
    }

    fun clearLock() {
        prefs.edit()
            .putBoolean(KEY_LOCKED, false)
            .putLong(KEY_LOCK_END, 0L)
            .putLong(KEY_EMERGENCY_AT, 0L)
            .putString(KEY_OWNER_CHALLENGE, "")
            .putLong(KEY_OWNER_CHALLENGE_EXP, 0L)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "peace_lock"
        private const val KEY_LOCKED = "locked"
        private const val KEY_LOCK_END = "lock_end_ms"
        private const val KEY_ALLOWLIST = "allowlist"
        private const val KEY_EMERGENCY_AT = "emergency_at_ms"
        private const val KEY_EMERGENCY_HOURS = "emergency_hours"
        private const val KEY_OWNER_METHOD = "owner_unlock_method"
        private const val KEY_OWNER_CHALLENGE = "owner_challenge_hex"
        private const val KEY_OWNER_CHALLENGE_EXP = "owner_challenge_exp"
    }
}
