package com.stayfocused.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.databinding.ActivityLockConfirmBinding
import com.stayfocused.app.util.PrefsManager

/**
 * Confirmation + PIN setup screen for Lock Mode, mirroring StrictModeConfirmActivity's
 * "no accidental lock-ins" pattern. Unlike Strict Mode, Lock Mode keeps an exit door open -
 * that door is the PIN set here. If a PIN already exists from a previous Lock Mode session,
 * it's reused and the person just confirms they're starting a new session.
 */
class LockModeConfirmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockConfirmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val durationMillis = intent.getLongExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, 0L)
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

                // This may be the very first PIN ever created on this device (if the person
                // never visited Settings' "App Lock PIN" first) - make sure recovery is set
                // up here too, not just from that other entry point.
                LockPinDialog.promptSecurityQuestionSetupIfNeeded(this) {
                    SessionStarter.startSession(this, durationMillis, SessionMode.LOCK)
                    finish()
                }
                return@setOnClickListener
            }

            SessionStarter.startSession(this, durationMillis, SessionMode.LOCK)
            finish()
        }

        binding.btnCancelLock.setOnClickListener {
            finish()
        }
    }

    // Deliberately no override of onBackPressed to bypass confirmation - back button
    // just cancels like btnCancelLock, since the session hasn't started yet here.
}
