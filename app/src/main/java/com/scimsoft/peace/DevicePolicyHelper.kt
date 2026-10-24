package com.scimsoft.peace

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * Thin wrapper around [DevicePolicyManager] for device-owner hardening.
 *
 * Must be provisioned as device owner:
 *   adb shell dpm set-device-owner com.scimsoft.peace/.LockDeviceAdminReceiver
 * (device must have no accounts; typically right after factory reset.)
 *
 * Subtle bits:
 * - [DevicePolicyManager.setAlwaysOnVpnPackage] with lockdownEnabled=true prevents apps from
 *   bypassing the VPN. Available since API 24; lockdown is most reliable on API 29+.
 * - User restrictions and uninstall-block are applied only while locked, then relaxed.
 */
class DevicePolicyHelper(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    val adminComponent: ComponentName =
        ComponentName(context, LockDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    /**
     * Enables always-on VPN + lockdown for [LockVpnService].
     *
     * Modern signature (API 24+):
     *   setAlwaysOnVpnPackage(ComponentName admin, String vpnPackage, boolean lockdownEnabled)
     *
     * API 29+ also offers an overload with a lockdown allowlist Set; we use the boolean form.
     */
    fun enableAlwaysOnVpnLockdown(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "enableAlwaysOnVpnLockdown: not device owner")
            return false
        }
        return try {
            // lockdownEnabled=true → networking that does not go through the always-on VPN is blocked.
            dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, /* lockdownEnabled = */ true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setAlwaysOnVpnPackage failed", e)
            false
        }
    }

    fun clearAlwaysOnVpn(): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "clearAlwaysOnVpn failed", e)
            false
        }
    }

    /** Block uninstall of this package while the lock is active. */
    fun setUninstallBlocked(blocked: Boolean) {
        if (!isDeviceOwner()) return
        try {
            dpm.setUninstallBlocked(adminComponent, context.packageName, blocked)
        } catch (e: Exception) {
            Log.e(TAG, "setUninstallBlocked($blocked) failed", e)
        }
    }

    /**
     * Apply resistance restrictions while locked.
     * Relaxed again in [relaxLockRestrictions].
     */
    fun applyLockRestrictions() {
        if (!isDeviceOwner()) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
        } catch (e: Exception) {
            Log.e(TAG, "applyLockRestrictions failed", e)
        }
    }

    fun relaxLockRestrictions() {
        if (!isDeviceOwner()) return
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
        } catch (e: Exception) {
            Log.e(TAG, "relaxLockRestrictions failed", e)
        }
    }

    companion object {
        private const val TAG = "DevicePolicyHelper"
    }
}
