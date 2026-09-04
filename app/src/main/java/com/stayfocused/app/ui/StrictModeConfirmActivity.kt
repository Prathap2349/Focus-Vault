package com.stayfocused.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.databinding.ActivityStrictConfirmBinding

class StrictModeConfirmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStrictConfirmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStrictConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val durationMillis = intent.getLongExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, 0L)

        binding.btnConfirmStrict.setOnClickListener {
            SessionStarter.startSession(this, durationMillis, SessionMode.STRICT)
            finish()
        }
        binding.btnCancelStrict.setOnClickListener {
            finish()
        }
    }

    // Deliberately no override of onBackPressed to bypass confirmation - back button
    // just cancels like btnCancelStrict, since the session hasn't started yet here.
}
