package com.stayfocused.app.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object AnimationHelper {

    fun isReduceMotion(context: Context): Boolean {
        if (PrefsManager.isReduceMotion(context)) return true
        return try {
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
     * Subtle staggered card entrance animation (slide up 12dp + fade in)
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
            .setDuration(220)
            .setStartDelay(delayMs)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
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

    private val activeAnimators = java.util.WeakHashMap<View, android.animation.ValueAnimator>()

    /**
     * Signature Focus Aura: Extremely subtle, slow breathing scale (1.0 -> 1.018 -> 1.0 over 2.6s)
     * Lifecycle-safe and bypassable via Reduced Motion.
     */
    fun startBreathingAura(view: View) {
        if (isReduceMotion(view.context)) return
        stopBreathingAura(view)

        val animator = android.animation.ValueAnimator.ofFloat(1.0f, 1.018f, 1.0f).apply {
            duration = 2600L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val scale = va.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
            }
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
