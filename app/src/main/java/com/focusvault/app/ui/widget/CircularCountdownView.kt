package com.focusvault.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.focusvault.app.util.AnimationHelper
import kotlin.math.cos
import kotlin.math.sin

/**
 * A minimal, premium circular focus ring.
 * Features rounded stroke caps, smooth progress animation, state-based dynamic coloring,
 * orbital light particle, vault lock-in dial animations, and lifecycle-safe rendering.
 */
class CircularCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var progressAnimator: ValueAnimator? = null
    private var orbitalAnimator: ValueAnimator? = null
    private var lockInAnimator: ValueAnimator? = null

    private var orbitalAngle: Float = 0f
    var isOrbitalActive: Boolean = false
        set(value) {
            field = value
            if (value && !AnimationHelper.isReduceMotion(context)) {
                startOrbitalAnimation()
            } else {
                stopOrbitalAnimation()
            }
        }

    private var ringStartColor: Int = Color.parseColor("#6366F1") // Indigo
    private var ringEndColor: Int = Color.parseColor("#4F46E5")
    private var trackColor: Int = Color.argb(25, 99, 102, 241)

    private val strokeWidthPx = context.resources.displayMetrics.density * 9f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val particleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val particleCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val arcRect = RectF()
    private var rotationOffset: Float = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    fun setProgressSmooth(targetProgress: Float, durationMs: Long = 300) {
        progressAnimator?.cancel()
        val clamped = targetProgress.coerceIn(0f, 1f)
        if (AnimationHelper.isReduceMotion(context)) {
            progress = clamped
            return
        }
        progressAnimator = ValueAnimator.ofFloat(progress, clamped).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                progress = va.animatedValue as Float
            }
            start()
        }
    }

    fun playLockInAnimation(onComplete: (() -> Unit)? = null) {
        if (AnimationHelper.isReduceMotion(context)) {
            onComplete?.invoke()
            return
        }
        lockInAnimator?.cancel()
        lockInAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 550L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                rotationOffset = va.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    rotationOffset = 0f
                    invalidate()
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    private fun startOrbitalAnimation() {
        if (orbitalAnimator?.isRunning == true) return
        orbitalAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                orbitalAngle = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopOrbitalAnimation() {
        orbitalAnimator?.cancel()
        orbitalAnimator = null
        invalidate()
    }

    fun setColors(start: Int, end: Int, track: Int = trackColor) {
        ringStartColor = start
        ringEndColor = end
        trackColor = track
        particleGlowPaint.color = Color.argb(120, Color.red(start), Color.green(start), Color.blue(start))
        rebuild()
    }

    fun applyFocusStateColors(
        isActive: Boolean = false,
        isPaused: Boolean = false,
        isCompleted: Boolean = false,
        isStrict: Boolean = false,
        remainingRatio: Float = 1f
    ) {
        isOrbitalActive = isActive && !isPaused && !isCompleted

        when {
            isCompleted -> setColors(
                Color.parseColor("#22C55E"), // Success Green
                Color.parseColor("#14B8A6"), // Teal
                Color.argb(30, 34, 197, 94)
            )
            isPaused -> setColors(
                Color.parseColor("#F59E0B"), // Warning Amber
                Color.parseColor("#D97706"),
                Color.argb(30, 245, 158, 11)
            )
            isStrict -> setColors(
                Color.parseColor("#EF4444"), // Strict Red
                Color.parseColor("#DC2626"),
                Color.argb(30, 239, 68, 68)
            )
            isActive -> {
                // Dynamic Color Shifting: As session completes (final 15%), blend into emerald/teal
                if (remainingRatio < 0.15f) {
                    setColors(
                        Color.parseColor("#10B981"), // Emerald
                        Color.parseColor("#06B6D4"), // Cyan
                        Color.argb(30, 16, 185, 129)
                    )
                } else {
                    setColors(
                        Color.parseColor("#6366F1"), // Indigo
                        Color.parseColor("#4F46E5"),
                        Color.argb(30, 99, 102, 241)
                    )
                }
            }
            else -> setColors(
                Color.parseColor("#6366F1"),
                Color.parseColor("#818CF8"),
                Color.argb(20, 148, 163, 184)
            )
        }
    }

    private fun rebuild() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val inset = strokeWidthPx / 2f
        arcRect.set(inset, inset, w - inset, h - inset)

        trackPaint.strokeWidth = strokeWidthPx
        trackPaint.color = trackColor

        progressPaint.strokeWidth = strokeWidthPx
        progressPaint.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(ringStartColor, ringEndColor, ringStartColor),
            floatArrayOf(0f, 0.5f, 1f)
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arcRect.isEmpty) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = (arcRect.width()) / 2f

        canvas.save()
        if (rotationOffset != 0f) {
            canvas.rotate(rotationOffset, cx, cy)
        }

        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)
        }

        // Draw Ambient Orbital Light Particle
        if (isOrbitalActive && progress > 0.02f) {
            val angleRad = Math.toRadians((orbitalAngle - 90.0).toDouble())
            val px = (cx + radius * cos(angleRad)).toFloat()
            val py = (cy + radius * sin(angleRad)).toFloat()

            // Outer soft glow bead
            canvas.drawCircle(px, py, strokeWidthPx * 0.75f, particleGlowPaint)
            // Inner crisp core
            canvas.drawCircle(px, py, strokeWidthPx * 0.35f, particleCorePaint)
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        progressAnimator?.cancel()
        orbitalAnimator?.cancel()
        lockInAnimator?.cancel()
    }
}

