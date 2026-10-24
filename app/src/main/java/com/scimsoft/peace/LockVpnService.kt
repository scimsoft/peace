package com.scimsoft.peace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blackhole / null VPN: captures all IPv4/IPv6 traffic into a TUN and discards it.
 *
 * Combined with device-owner always-on + lockdown ([DevicePolicyHelper.enableAlwaysOnVpnLockdown]),
 * apps (including most system networking) cannot reach the internet while this is up.
 *
 * Calls and SMS are NOT routed through this VPN — they use the cellular telephony stack —
 * so the phone remains usable for emergency calling.
 *
 * Optional allowlist: one app package can be excluded via Builder.addDisallowedApplication
 * so that package's sockets bypass the TUN (and thus may still reach the network).
 */
class LockVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLockVpn()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                startLockVpn()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopLockVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        // User (or system) revoked VPN consent. If we are device-owner always-on lockdown,
        // the system should reconnect us; still tear down cleanly.
        Log.w(TAG, "VPN revoked")
        stopLockVpn()
        super.onRevoke()
    }

    private fun startLockVpn() {
        if (running.getAndSet(true)) return

        val prefs = LockPrefs(this)
        val allow = prefs.allowlistPackage

        try {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                // Claim default routes — all traffic enters the TUN.
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addRoute("::", 0)
                // No DNS servers: DNS queries also die in the TUN.
                .setBlocking(true)

            // Never VPN ourselves (avoid loops).
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
                // ignore if package missing in edge cases
            }

            // Optional allowlist: one chosen app can bypass the blackhole.
            // Note: calls/SMS aren't internet — they work regardless of allowlist.
            if (allow.isNotBlank()) {
                try {
                    builder.addDisallowedApplication(allow)
                    Log.i(TAG, "Allowlisted package: $allow")
                } catch (e: Exception) {
                    Log.w(TAG, "Allowlist package not found: $allow", e)
                }
            }

            tunInterface?.close()
            tunInterface = builder.establish()
            if (tunInterface == null) {
                Log.e(TAG, "Builder.establish() returned null — VPN permission missing?")
                running.set(false)
                stopSelf()
                return
            }

            // Drain and discard every byte from the TUN (blackhole).
            val fd = tunInterface!!
            drainThread = Thread({
                val buffer = ByteArray(32767)
                FileInputStream(fd.fileDescriptor).use { input ->
                    while (running.get()) {
                        try {
                            val n = input.read(buffer)
                            if (n < 0) break
                            // Discard — do not forward anywhere.
                        } catch (_: Exception) {
                            if (!running.get()) break
                        }
                    }
                }
            }, "peace-vpn-drain").also { it.isDaemon = true; it.start() }

            Log.i(TAG, "Blackhole VPN established")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            running.set(false)
            stopSelf()
        }
    }

    private fun stopLockVpn() {
        running.set(false)
        try {
            drainThread?.interrupt()
        } catch (_: Exception) {
        }
        drainThread = null
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("VPN blackhole active")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
        private const val TAG = "LockVpnService"
        private const val CHANNEL_ID = "peace_vpn"
        private const val NOTIF_ID = 1002

        const val ACTION_STOP = "com.scimsoft.peace.VPN_STOP"

        fun prepareIntent(context: Context): Intent? = prepare(context)

        fun start(context: Context) {
            val i = Intent(context, LockVpnService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, LockVpnService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
