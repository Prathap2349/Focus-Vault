package com.focusvault.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.focusvault.app.R
import com.focusvault.app.databinding.ActivityBlockOverlayBinding
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.util.EdgeToEdge
import com.focusvault.app.util.PrefsManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen premium "you're blocked" screen shown whenever a blocked app comes to foreground.
 * Respects Strict Mode anti-bypass and provides rate-limited emergency unlock for other modes.
 */
class BlockOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockOverlayBinding
    private var ticker: CountDownTimer? = null
    private var sessionTotalMillis: Long = 1L
    private var currentBlockedPackage: String? = null
    
    private val quoteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var quoteIndex = 0
    private val quoteRunnable = object : Runnable {
        override fun run() {
            binding.tvQuote.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction {
                    quoteIndex = (quoteIndex + 1) % CALM_QUOTES.size
                    binding.tvQuote.text = CALM_QUOTES[quoteIndex]
                    binding.tvQuote.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .start()
                }.start()
            quoteHandler.postDelayed(this, 5000)
        }
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "blocked_package"

        private val CALM_QUOTES = listOf(
            "💡 Stay with your goal. Small moments of focus build extraordinary results.",
            "🎯 You chose to focus for a reason. Don't trade your future for a quick impulse.",
            "🌱 Discipline is choosing between what you want now and what you want most.",
            "⚡ Deep work produces deep outcomes. Return to your task.",
            "🛡️ Your attention is your most valuable asset. Guard it."
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root, useDarkIcons = false)

        // Clock breathing aura animation
        com.focusvault.app.util.AnimationHelper.startBreathingAura(binding.frameCountdownRing)

        // Spring touch physics for action buttons
        com.focusvault.app.util.AnimationHelper.attachSpringPressFeedback(binding.btnGoHome)
        com.focusvault.app.util.AnimationHelper.attachSpringPressFeedback(binding.btnEmergencyUnlockOverlay)

        binding.btnGoHome.setOnClickListener {
            com.focusvault.app.util.HapticHelper.mediumClick(it)
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
        }

        binding.btnEmergencyUnlockOverlay.setOnClickListener {
            com.focusvault.app.util.HapticHelper.heavyClick(it)
            val isLock = PrefsManager.isLockModeActive(this)
            val proceed = {
                if (PrefsManager.canUseEmergencyUnlockToday(this)) {
                    DialogHelper.showCustomDialog(
                        context = this,
                        title = "Use Emergency Unlock? 🚨",
                        message = "This ends your current session early. You get 1 emergency unlock per day.",
                        positiveText = "Use Unlock",
                        positiveAction = {
                            PrefsManager.consumeEmergencyUnlock(this)
                            lifecycleScope.launch {
                                SessionStateManager.stopSessionEarly(applicationContext, "Emergency unlock from overlay")
                                finish()
                            }
                        },
                        negativeText = "Cancel"
                    )
                } else {
                    Toast.makeText(this, "No emergency unlocks left today", Toast.LENGTH_SHORT).show()
                }
            }
            if (isLock) {
                LockPinDialog.promptAndVerify(this) { proceed() }
            } else {
                proceed()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Route directly to home screen instead of letting underlying blocked app through
                binding.btnGoHome.performClick()
            }
        })

        refreshUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshUi()
    }

    private fun refreshUi() {
        if (!PrefsManager.isSessionCurrentlyActive(this)) {
            finish()
            return
        }

        val isStrict = PrefsManager.isStrictModeActive(this)
        val isLock = PrefsManager.isLockModeActive(this)
        val endTime = PrefsManager.getSessionEndTime(this)

        // Resolve friendly application name
        val rawPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        currentBlockedPackage = rawPackage
        val appLabel = if (!rawPackage.isNullOrEmpty()) {
            try {
                val appInfo = packageManager.getApplicationInfo(rawPackage, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                "This app"
            }
        } else {
            "This app"
        }

        binding.tvAppName.text = "$appLabel is blocked"

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val endsAtStr = timeFormat.format(Date(endTime))
        val modeTitle = when {
            isStrict -> "Strict Mode"
            isLock -> "Lock Mode"
            else -> "Focus Mode"
        }
        binding.tvModeSubtitle.text = "You're currently in $modeTitle · Ends at $endsAtStr"

        if (isStrict) {
            binding.cardStrictModeNotice.visibility = android.view.View.VISIBLE
            binding.tvStrictEndsAt.text = "This app is unavailable until your session ends at $endsAtStr."
            binding.btnEmergencyUnlockOverlay.visibility = android.view.View.GONE
        } else {
            binding.cardStrictModeNotice.visibility = android.view.View.GONE
            binding.btnEmergencyUnlockOverlay.visibility = android.view.View.VISIBLE
        }

        // Rotating calm quote
        quoteIndex = (System.currentTimeMillis() % CALM_QUOTES.size).toInt()
        binding.tvQuote.text = CALM_QUOTES[quoteIndex]
        quoteHandler.removeCallbacks(quoteRunnable)
        quoteHandler.postDelayed(quoteRunnable, 5000)

        val (heroBgRes, ringStart, ringEnd) = when {
            isStrict -> Triple(R.drawable.bg_gradient_strict, R.color.gradient_strict_start, R.color.gradient_strict_end)
            isLock -> Triple(R.drawable.bg_gradient_lock, R.color.gradient_lock_start, R.color.gradient_lock_end)
            else -> Triple(R.drawable.bg_gradient_normal, R.color.gradient_normal_start, R.color.gradient_normal_end)
        }
        binding.rootOverlay.setBackgroundResource(heroBgRes)
        binding.ringCountdown.setColors(
            ContextCompat.getColor(this, ringStart),
            ContextCompat.getColor(this, ringEnd),
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
        if (remaining <= 0) { finish(); return }

        val isStrict = PrefsManager.isStrictModeActive(this)
        val initialRatio = (remaining.toFloat() / totalSessionDuration.toFloat()).coerceIn(0f, 1f)
        binding.ringCountdown.progress = initialRatio
        binding.ringCountdown.applyFocusStateColors(isActive = true, isStrict = isStrict, remainingRatio = initialRatio)

        ticker = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val ratio = (millisUntilFinished.toFloat() / totalSessionDuration.toFloat()).coerceIn(0f, 1f)
                binding.tvCountdown.text = formatTime(millisUntilFinished)
                binding.ringCountdown.progress = ratio
                binding.ringCountdown.applyFocusStateColors(isActive = true, isStrict = isStrict, remainingRatio = ratio)
            }
            override fun onFinish() {
                com.focusvault.app.util.AnimationHelper.stopBreathingAura(binding.frameCountdownRing)
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
        com.focusvault.app.util.AnimationHelper.stopBreathingAura(binding.frameCountdownRing)
        ticker?.cancel()
        quoteHandler.removeCallbacks(quoteRunnable)
        super.onDestroy()
    }
}
