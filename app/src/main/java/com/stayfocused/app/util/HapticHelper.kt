package com.stayfocused.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Provides subtle, purposeful haptic feedback for key user interactions.
 * Respects user settings and reduced-motion preferences.
 */
object HapticHelper {

    fun performClick(view: View) {
        if (!PrefsManager.isHapticFeedbackEnabled(view.context)) return
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    fun lightClick(view: View) {
        performClick(view)
    }

    fun mediumClick(view: View) {
        if (!PrefsManager.isHapticFeedbackEnabled(view.context)) return
        view.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    fun heavyClick(view: View) {
        if (!PrefsManager.isHapticFeedbackEnabled(view.context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(
                HapticFeedbackConstants.CONFIRM,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } else {
            vibrate(view.context, 40L, 200)
        }
    }

    fun performStartFocus(context: Context, view: View? = null) {
        if (!PrefsManager.isHapticFeedbackEnabled(context)) return
        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM) ?: vibrate(context, 40L, 180)
    }

    fun performPause(context: Context, view: View? = null) {
        if (!PrefsManager.isHapticFeedbackEnabled(context)) return
        view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) ?: vibrate(context, 25L, 120)
    }

    fun performResume(context: Context, view: View? = null) {
        if (!PrefsManager.isHapticFeedbackEnabled(context)) return
        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM) ?: vibrate(context, 35L, 160)
    }

    fun performComplete(context: Context) {
        if (!PrefsManager.isHapticFeedbackEnabled(context)) return
        vibratePattern(context, longArrayOf(0, 40, 80, 60), intArrayOf(0, 160, 0, 220))
    }

    fun successHaptic(view: View) {
        performComplete(view.context)
    }

    private fun vibrate(context: Context, durationMillis: Long, amplitude: Int) {
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, amplitude.coerceIn(1, 255)))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMillis)
        }
    }

    private fun vibratePattern(context: Context, timings: LongArray, amplitudes: IntArray) {
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
