package com.focusvault.app.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.focusvault.app.data.SessionMode
import com.focusvault.app.databinding.ActivityStrictConfirmBinding
import com.focusvault.app.service.StayFocusedDeviceAdminReceiver

class StrictModeConfirmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStrictConfirmBinding
    private var pendingDurationMillis: Long = 0L

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (StayFocusedDeviceAdminReceiver.isDeviceAdminActive(this)) {
            startStrictSession(pendingDurationMillis)
        } else {
            Toast.makeText(
                this,
                "Strict Mode requires Device Admin to prevent uninstalling Focus Vault during an active focus session.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStrictConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingDurationMillis = intent.getLongExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, 0L)

        binding.btnConfirmStrict.setOnClickListener {
            if (!StayFocusedDeviceAdminReceiver.isDeviceAdminActive(this)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        StayFocusedDeviceAdminReceiver.getComponentName(this@StrictModeConfirmActivity)
                    )
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Strict Mode requires Device Admin to prevent uninstalling Focus Vault during an active focus session."
                    )
                }
                deviceAdminLauncher.launch(intent)
            } else {
                startStrictSession(pendingDurationMillis)
            }
        }

        binding.btnCancelStrict.setOnClickListener {
            finish()
        }
    }

    private fun startStrictSession(durationMillis: Long) {
        SessionStarter.startSession(this, durationMillis, SessionMode.STRICT) { success ->
            if (success) {
                finish()
            }
        }
    }
}
