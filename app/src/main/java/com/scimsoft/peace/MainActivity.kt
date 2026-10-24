package com.scimsoft.peace

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scimsoft.peace.databinding.ActivityMainBinding

/**
 * Simple lock UI: pick duration, optional allowlist, start lock.
 * While locked, stop is not offered (timer / emergency unlock only).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: LockPrefs
    private lateinit var policy: DevicePolicyHelper
    private val handler = Handler(Looper.getMainLooper())

    private var pendingDurationMs: Long = 0L
    private var pendingAllowlist: String = ""

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                beginLock(pendingDurationMs, pendingAllowlist)
            } else {
                Toast.makeText(this, R.string.status_vpn_consent, Toast.LENGTH_LONG).show()
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val uiTick = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = LockPrefs(this)
        policy = DevicePolicyHelper(this)

        requestNotifPermissionIfNeeded()

        binding.startButton.setOnClickListener { onStartClicked() }
        binding.emergencyButton.setOnClickListener { onEmergencyClicked() }
        binding.cancelEmergencyButton.setOnClickListener {
            EmergencyUnlock.cancel(this)
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
    }

    override fun onPause() {
        handler.removeCallbacks(uiTick)
        super.onPause()
    }

    private fun onStartClicked() {
        if (prefs.isLocked) return
        val hours = binding.hoursInput.text?.toString()?.toIntOrNull() ?: 0
        val minutes = binding.minutesInput.text?.toString()?.toIntOrNull() ?: 0
        val durationMs = (hours * 60L + minutes) * 60L * 1000L
        if (durationMs <= 0L) {
            Toast.makeText(this, R.string.error_duration, Toast.LENGTH_SHORT).show()
            return
        }
        val allow = binding.allowlistInput.text?.toString()?.trim().orEmpty()
        val emergencyHours = binding.emergencyHoursInput.text?.toString()?.toIntOrNull() ?: 24
        prefs.emergencyCooldownHours = emergencyHours.coerceAtLeast(1)

        pendingDurationMs = durationMs
        pendingAllowlist = allow

        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            beginLock(durationMs, allow)
        }
    }

    private fun beginLock(durationMs: Long, allowlist: String) {
        LockController.lock(this, durationMs, allowlist)
        refreshUi()
    }

    private fun onEmergencyClicked() {
        if (!prefs.isLocked) return
        if (EmergencyUnlock.isPending(prefs)) return
        val hours = binding.emergencyHoursInput.text?.toString()?.toIntOrNull()
            ?: prefs.emergencyCooldownHours
        EmergencyUnlock.request(this, hours)
        refreshUi()
        Toast.makeText(
            this,
            getString(R.string.emergency_pending, EmergencyUnlock.pendingAtFormatted(prefs)),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshUi() {
        val locked = prefs.isLocked
        binding.ownerStatusText.text = if (policy.isDeviceOwner()) {
            getString(R.string.status_owner)
        } else {
            getString(R.string.status_not_owner)
        }

        if (locked) {
            binding.titleText.text = getString(R.string.title_locked)
            binding.titleText.setTextColor(ContextCompat.getColor(this, R.color.peace_locked))
            binding.remainingText.text = LockTimerService.formatDuration(prefs.remainingMs())
            binding.setupCard.visibility = View.GONE
            binding.startButton.isEnabled = false
            binding.startButton.text = getString(R.string.btn_stop_disabled)
            binding.emergencyButton.visibility = View.VISIBLE

            val pending = EmergencyUnlock.isPending(prefs)
            binding.cancelEmergencyButton.visibility = if (pending) View.VISIBLE else View.GONE
            binding.emergencyButton.isEnabled = !pending
            if (pending) {
                binding.emergencyStatusText.visibility = View.VISIBLE
                binding.emergencyStatusText.text = getString(
                    R.string.emergency_pending,
                    EmergencyUnlock.pendingAtFormatted(prefs)
                )
            } else {
                binding.emergencyStatusText.visibility = View.GONE
            }
        } else {
            binding.titleText.text = getString(R.string.title_idle)
            binding.titleText.setTextColor(ContextCompat.getColor(this, R.color.peace_primary))
            binding.remainingText.text = "—"
            binding.setupCard.visibility = View.VISIBLE
            binding.startButton.isEnabled = true
            binding.startButton.text = getString(R.string.btn_start)
            binding.emergencyButton.visibility = View.GONE
            binding.cancelEmergencyButton.visibility = View.GONE
            binding.emergencyStatusText.visibility = View.GONE
            if (binding.emergencyHoursInput.text.isNullOrBlank()) {
                binding.emergencyHoursInput.setText(prefs.emergencyCooldownHours.toString())
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
