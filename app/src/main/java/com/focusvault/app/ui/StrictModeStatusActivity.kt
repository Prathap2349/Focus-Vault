package com.focusvault.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.focusvault.app.R
import com.focusvault.app.databinding.ActivityStrictModeStatusBinding
import com.focusvault.app.ui.widget.CircularCountdownView
import com.focusvault.app.util.AnimationHelper
import com.focusvault.app.util.EdgeToEdge
import com.focusvault.app.util.HapticHelper
import com.focusvault.app.util.PrefsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated full-screen status view shown in Strict Mode to inform the user
 * of active countdown progress and strict anti-bypass guarantees without any escape hatches.
 */
class StrictModeStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStrictModeStatusBinding
    private var ticker: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStrictModeStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root, useDarkIcons = false)

        AnimationHelper.startBreathingAura(binding.frameCountdownRing)
        AnimationHelper.attachSpringPressFeedback(binding.btnGoHome)

        binding.btnGoHome.setOnClickListener {
            HapticHelper.mediumClick(it)
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                binding.btnGoHome.performClick()
            }
        })

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        if (!PrefsManager.isSessionCurrentlyActive(this) || !PrefsManager.isStrictModeActive(this)) {
            finish()
            return
        }

        val endTime = PrefsManager.getSessionEndTime(this)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val endsAtStr = timeFormat.format(Date(endTime))
        binding.tvEndsAtSubtitle.text = "Strict Mode is active — there is no early exit.\nEnds at $endsAtStr"

        binding.ringCountdown.setColors(
            ContextCompat.getColor(this, R.color.gradient_strict_start),
            ContextCompat.getColor(this, R.color.gradient_strict_end),
            android.graphics.Color.argb(70, 255, 255, 255)
        )
        binding.ringCountdown.isOrbitalActive = true

        startCountdown()
    }

    private fun startCountdown() {
        ticker?.cancel()
        ticker = null

        val totalSessionDuration = (PrefsManager.getSessionEndTime(this) - PrefsManager.getSessionStartTime(this)).coerceAtLeast(1000L)
        val remaining = (PrefsManager.getSessionEndTime(this) - System.currentTimeMillis()).coerceAtLeast(0L)
        if (remaining <= 0) {
            finish()
            return
        }

        val initialRatio = (remaining.toFloat() / totalSessionDuration.toFloat()).coerceIn(0f, 1f)
        binding.ringCountdown.progress = initialRatio
        binding.ringCountdown.applyFocusStateColors(isActive = true, isStrict = true, remainingRatio = initialRatio)

        ticker = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val ratio = (millisUntilFinished.toFloat() / totalSessionDuration.toFloat()).coerceIn(0f, 1f)
                binding.tvCountdown.text = formatTime(millisUntilFinished)
                binding.ringCountdown.progress = ratio
                binding.ringCountdown.applyFocusStateColors(isActive = true, isStrict = true, remainingRatio = ratio)
            }
            override fun onFinish() {
                AnimationHelper.stopBreathingAura(binding.frameCountdownRing)
                finish()
            }
        }.start()
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    override fun onDestroy() {
        AnimationHelper.stopBreathingAura(binding.frameCountdownRing)
        ticker?.cancel()
        super.onDestroy()
    }
}
