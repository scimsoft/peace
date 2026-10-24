package com.scimsoft.peace

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device-admin / device-owner entry point.
 * Provision with:
 *   adb shell dpm set-device-owner com.scimsoft.peace/.LockDeviceAdminReceiver
 */
class LockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    companion object {
        private const val TAG = "LockDeviceAdmin"
    }
}
