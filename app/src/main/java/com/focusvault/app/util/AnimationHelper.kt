package com.focusvault.app.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

object AnimationHelper {

    fun isReduceMotion(context: Context): Boolean {
        if (PrefsManager.isReduceMotion(context)) return true
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isPowerSaveMode == true) return true

            val durationScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            val transitionScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f
            )
            durationScale == 0f || transitionScale == 0f
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Subtle staggered card entrance animation (slide up 18dp + fade in)
     */
    fun animateCardEntrance(view: View, delayMs: Long = 0) {
        if (isReduceMotion(view.context)) {
            view.alpha = 1f
            view.translationY = 0f
            view.visibility = View.VISIBLE
            return
        }

        view.alpha = 0f
        view.translationY = 24f
        view.visibility = View.VISIBLE

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .setStartDelay(delayMs)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /**
     * Staggered cascade entrance for a collection of views (e.g. bottom sheet cards/chips)
     */
    fun animateStaggeredCascade(views: List<View>, baseDelayMs: Long = 0, stepDelayMs: Long = 40) {
        views.forEachIndexed { index, view ->
            animateCardEntrance(view, baseDelayMs + (index * stepDelayMs))
        }
    }

    /**
     * Subtle micro-interaction on button tap (scales down to 0.96 and snaps back)
     */
    fun animateButtonPress(view: View, onEndAction: (() -> Unit)? = null) {
        if (isReduceMotion(view.context)) {
            onEndAction?.invoke()
            return
        }

        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .withEndAction {
                        onEndAction?.invoke()
                    }
                    .start()
            }
            .start()
    }

    /**
     * Spring physics touch feedback attached to interactive chips/cards.
     * Scales to 0.96x on ACTION_DOWN and smoothly restores to 1.0x on ACTION_UP / ACTION_CANCEL over 150-180ms.
     */
    fun attachSpringPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            if (isReduceMotion(v.context)) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .setDuration(70)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(130)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()
                }
            }
            false
        }
    }

    /**
     * Smooth cross-fade transition between states
     */
    fun crossFade(showView: View, hideView: View, durationMs: Long = 180) {
        if (isReduceMotion(showView.context)) {
            showView.visibility = View.VISIBLE
            showView.alpha = 1f
            hideView.visibility = View.GONE
            hideView.alpha = 0f
            return
        }

        showView.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(durationMs)
                .setListener(null)
        }

        hideView.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    hideView.visibility = View.GONE
                }
            })
    }

    /**
     * Vault "Lock-In" Sequence: dial rotation + snap bounce for sealing the vault
     */
    fun animateVaultLockIn(view: View, onEndAction: (() -> Unit)? = null) {
        if (isReduceMotion(view.context)) {
            onEndAction?.invoke()
            return
        }

        view.animate()
            .rotationBy(360f)
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(450)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(180)
                    .setInterpolator(OvershootInterpolator(1.3f))
                    .withEndAction {
                        onEndAction?.invoke()
                    }
                    .start()
            }
            .start()
    }

    /**
     * Celebration Bloom: radiant expansion & pulse when a focus session completes
     */
    fun animateCelebrationBloom(view: View) {
        if (isReduceMotion(view.context)) return

        view.animate()
            .scaleX(1.12f)
            .scaleY(1.12f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(400)
                    .setInterpolator(OvershootInterpolator(1.5f))
                    .start()
            }
            .start()
    }

    /**
     * Odometer digit roll animation for counters and statistics (e.g. 0 -> 25 mins)
     */
    fun animateOdometerRoll(
        textView: TextView,
        startValue: Int,
        endValue: Int,
        formatter: (Int) -> String = { it.toString() }
    ) {
        if (isReduceMotion(textView.context) || startValue == endValue) {
            textView.text = formatter(endValue)
            return
        }

        val animator = ValueAnimator.ofInt(startValue, endValue).apply {
            duration = 650L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val current = va.animatedValue as Int
                textView.text = formatter(current)
            }
        }
        animator.start()
    }

    /**
     * Subtle milestone pulse animation
     */
    fun animatePulse(view: View) {
        if (isReduceMotion(view.context)) return

        view.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setDuration(150)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private val activeAnimators = java.util.WeakHashMap<View, ValueAnimator>()

    /**
     * Calm State Entrance: A single subtle scale transition (1.0 -> 1.018 -> 1.0 over 350ms)
     * Lifecycle-safe, finishes and leaves view stable.
     */
    fun startBreathingAura(view: View) {
        if (isReduceMotion(view.context)) return
        stopBreathingAura(view)

        val animator = ValueAnimator.ofFloat(1.0f, 1.018f, 1.0f).apply {
            duration = 350L
            repeatCount = 0
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val scale = va.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                }
            })
        }
        activeAnimators[view] = animator
        animator.start()
    }

    fun stopBreathingAura(view: View) {
        activeAnimators.remove(view)?.cancel()
        view.scaleX = 1.0f
        view.scaleY = 1.0f
    }
}

