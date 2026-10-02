package com.focusvault.app.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.focusvault.app.data.SessionMode
import com.focusvault.app.databinding.ActivityLockConfirmBinding
import com.focusvault.app.service.StayFocusedDeviceAdminReceiver
import com.focusvault.app.util.PrefsManager

/**
 * Confirmation + PIN setup screen for Lock Mode, mirroring StrictModeConfirmActivity's
 * "no accidental lock-ins" pattern. Verifies Device Admin protection is active before
 * allowing the session to start.
 */
class LockModeConfirmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockConfirmBinding
    private var pendingDurationMillis: Long = 0L

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (StayFocusedDeviceAdminReceiver.isDeviceAdminActive(this)) {
            startLockSession(pendingDurationMillis)
        } else {
            Toast.makeText(
                this,
                "Lock Mode requires Device Admin to prevent uninstalling Focus Vault during an active focus session.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingDurationMillis = intent.getLongExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, 0L)
        val pinAlreadySet = PrefsManager.hasLockPin(this)

        if (pinAlreadySet) {
            // PIN already exists - no need to set a new one, just reuse it.
            binding.etLockPin.visibility = android.view.View.GONE
            binding.etLockPinConfirm.visibility = android.view.View.GONE
            binding.tvBody.text = "Your existing PIN will be used to unlock this Lock Mode session early if needed."
        }

        binding.btnConfirmLock.setOnClickListener {
            if (!pinAlreadySet) {
                val pin = binding.etLockPin.text.toString()
                val confirmPin = binding.etLockPinConfirm.text.toString()

                if (pin.length < 4) {
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pin != confirmPin) {
                    Toast.makeText(this, "PINs don't match", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                PrefsManager.setLockPin(this, pin)

                LockPinDialog.promptSecurityQuestionSetupIfNeeded(this) {
                    checkAdminAndStart(pendingDurationMillis)
                }
                return@setOnClickListener
            }

            checkAdminAndStart(pendingDurationMillis)
        }

        binding.btnCancelLock.setOnClickListener {
            finish()
        }
    }

    private fun checkAdminAndStart(durationMillis: Long) {
        if (!StayFocusedDeviceAdminReceiver.isDeviceAdminActive(this)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    StayFocusedDeviceAdminReceiver.getComponentName(this@LockModeConfirmActivity)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lock Mode requires Device Admin to prevent uninstalling Focus Vault during an active focus session."
                )
            }
            deviceAdminLauncher.launch(intent)
        } else {
            startLockSession(durationMillis)
        }
    }

    private fun startLockSession(durationMillis: Long) {
        SessionStarter.startSession(this, durationMillis, SessionMode.LOCK) { success ->
            if (success) {
                finish()
            }
        }
    }
}
