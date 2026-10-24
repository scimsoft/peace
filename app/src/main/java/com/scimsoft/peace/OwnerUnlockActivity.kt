package com.scimsoft.peace

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scimsoft.peace.databinding.ActivityOwnerUnlockBinding
import java.text.DateFormat
import java.util.Date

/**
 * Owner master-unlock screen: challenge–response and/or master password.
 * On success, calls [LockController.unlock] like timer/emergency expiry.
 */
class OwnerUnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOwnerUnlockBinding
    private lateinit var prefs: LockPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOwnerUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.owner_unlock_title)

        prefs = LockPrefs(this)

        if (!prefs.isLocked) {
            Toast.makeText(this, R.string.owner_not_locked, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.methodGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.methodChallenge -> {
                    prefs.ownerUnlockMethod = OwnerUnlock.METHOD_CHALLENGE
                    showMethod(OwnerUnlock.METHOD_CHALLENGE)
                }
                R.id.methodPassword -> {
                    prefs.ownerUnlockMethod = OwnerUnlock.METHOD_PASSWORD
                    showMethod(OwnerUnlock.METHOD_PASSWORD)
                }
            }
        }

        binding.newChallengeButton.setOnClickListener { issueFreshChallenge() }
        binding.submitResponseButton.setOnClickListener { onSubmitResponse() }
        binding.submitPasswordButton.setOnClickListener { onSubmitPassword() }

        // Restore preferred method (fall back to whichever is configured).
        val preferred = when {
            prefs.ownerUnlockMethod == OwnerUnlock.METHOD_PASSWORD &&
                OwnerUnlockConfig.isMasterPasswordConfigured() -> OwnerUnlock.METHOD_PASSWORD
            OwnerUnlockConfig.isChallengeResponseConfigured() -> OwnerUnlock.METHOD_CHALLENGE
            OwnerUnlockConfig.isMasterPasswordConfigured() -> OwnerUnlock.METHOD_PASSWORD
            else -> OwnerUnlock.METHOD_CHALLENGE
        }
        if (preferred == OwnerUnlock.METHOD_PASSWORD) {
            binding.methodPassword.isChecked = true
        } else {
            binding.methodChallenge.isChecked = true
        }
        showMethod(preferred)
        refreshConfigWarning()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showMethod(method: String) {
        val challenge = method == OwnerUnlock.METHOD_CHALLENGE
        binding.challengePanel.visibility = if (challenge) View.VISIBLE else View.GONE
        binding.passwordPanel.visibility = if (challenge) View.GONE else View.VISIBLE
        if (challenge) {
            ensureChallengeDisplayed()
        }
        refreshConfigWarning()
    }

    private fun ensureChallengeDisplayed() {
        if (!OwnerUnlockConfig.isChallengeResponseConfigured()) {
            binding.challengeText.text = getString(R.string.owner_challenge_unconfigured)
            binding.challengeExpiryText.text = ""
            return
        }
        val existing = OwnerUnlock.currentChallenge(prefs)
        if (existing != null) {
            binding.challengeText.text = existing.displayCode
            binding.challengeExpiryText.text = getString(
                R.string.owner_challenge_expires,
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(existing.expiresAtMs))
            )
        } else {
            issueFreshChallenge()
        }
    }

    private fun issueFreshChallenge() {
        if (!OwnerUnlockConfig.isChallengeResponseConfigured()) {
            Toast.makeText(this, R.string.owner_challenge_unconfigured, Toast.LENGTH_LONG).show()
            return
        }
        val c = OwnerUnlock.issueChallenge(prefs)
        binding.challengeText.text = c.displayCode
        binding.challengeExpiryText.text = getString(
            R.string.owner_challenge_expires,
            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(c.expiresAtMs))
        )
        binding.responseInput.setText("")
    }

    private fun onSubmitResponse() {
        if (!OwnerUnlockConfig.isChallengeResponseConfigured()) {
            Toast.makeText(this, R.string.owner_challenge_unconfigured, Toast.LENGTH_LONG).show()
            return
        }
        val response = binding.responseInput.text?.toString().orEmpty()
        if (response.isBlank()) {
            Toast.makeText(this, R.string.owner_response_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = OwnerUnlock.verifyResponse(prefs, response)
        if (ok) {
            LockController.unlock(this, reason = "owner_challenge")
            Toast.makeText(this, R.string.owner_unlock_success, Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, R.string.owner_unlock_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun onSubmitPassword() {
        if (!OwnerUnlockConfig.isMasterPasswordConfigured()) {
            Toast.makeText(this, R.string.owner_password_unconfigured, Toast.LENGTH_LONG).show()
            return
        }
        val chars = binding.passwordInput.text?.toString()?.toCharArray() ?: CharArray(0)
        if (chars.isEmpty()) {
            Toast.makeText(this, R.string.owner_password_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = OwnerUnlock.verifyPassword(chars)
        chars.fill('\u0000')
        if (ok) {
            OwnerUnlock.clearChallenge(prefs)
            LockController.unlock(this, reason = "owner_password")
            Toast.makeText(this, R.string.owner_unlock_success, Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, R.string.owner_unlock_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshConfigWarning() {
        val msg = when {
            !OwnerUnlockConfig.anyOwnerUnlockConfigured() ->
                getString(R.string.owner_none_configured)
            binding.methodChallenge.isChecked && !OwnerUnlockConfig.isChallengeResponseConfigured() ->
                getString(R.string.owner_challenge_unconfigured)
            binding.methodPassword.isChecked && !OwnerUnlockConfig.isMasterPasswordConfigured() ->
                getString(R.string.owner_password_unconfigured)
            else -> ""
        }
        if (msg.isEmpty()) {
            binding.configWarningText.visibility = View.GONE
        } else {
            binding.configWarningText.visibility = View.VISIBLE
            binding.configWarningText.text = msg
        }

        binding.methodChallenge.isEnabled = OwnerUnlockConfig.isChallengeResponseConfigured() ||
            !OwnerUnlockConfig.isMasterPasswordConfigured()
        binding.methodPassword.isEnabled = OwnerUnlockConfig.isMasterPasswordConfigured() ||
            !OwnerUnlockConfig.isChallengeResponseConfigured()
    }
}
