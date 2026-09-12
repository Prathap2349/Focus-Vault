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

    private var breathingAnimator: ValueAnimator? = null
    private var breathingScale: Float = 1f
    private val breathingGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private var orbitalAngle: Float = 0f
    var isOrbitalActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var ringStartColor: Int = Color.parseColor("#8B5CF6") // Electric Violet
    private var ringEndColor: Int = Color.parseColor("#7C3AED")
    private var trackColor: Int = Color.argb(40, 51, 69, 111) // #33456F subtle track

    private val strokeWidthPx = context.resources.displayMetrics.density * 9f

    private val trackSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var sheenAngle = 0f
    private var sheenAnimator: ValueAnimator? = null
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
        com.focusvault.app.util.HapticHelper.mediumClick(this)
        lockInAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 650L
            interpolator = android.view.animation.OvershootInterpolator(1.5f)
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

    
    private fun startBreathingAnimation() {
        if (breathingAnimator?.isRunning == true) return
        breathingAnimator = ValueAnimator.ofFloat(1f, 1.15f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                breathingScale = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        breathingScale = 1f
        invalidate()
    }


    private fun startSheenAnimation() {
        if (sheenAnimator?.isRunning == true) return
        sheenAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { va ->
                sheenAngle = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    private fun stopSheenAnimation() {
        sheenAnimator?.cancel()
        sheenAnimator = null
        invalidate()
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
                Color.parseColor("#34D399"), // Success Green
                Color.parseColor("#10B981"),
                Color.argb(30, 52, 211, 153)
            )
            isPaused -> setColors(
                Color.parseColor("#FBBF24"), // Warning / Paused Amber
                Color.parseColor("#F59E0B"),
                Color.argb(30, 251, 191, 36)
            )
            isStrict -> setColors(
                Color.parseColor("#FB7185"), // Strict Rose Red
                Color.parseColor("#F43F5E"),
                Color.argb(30, 251, 113, 133)
            )
            isActive -> {
                setColors(
                    Color.parseColor("#8B5CF6"), // Electric Violet
                    Color.parseColor("#7C3AED"),
                    Color.argb(35, 139, 92, 246)
                )
            }
            else -> setColors(
                Color.parseColor("#8B5CF6"),
                Color.parseColor("#7C3AED"),
                Color.argb(40, 51, 69, 111) // #33456F subtle border track
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
        trackSheenPaint.strokeWidth = strokeWidthPx
        trackSheenPaint.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Color.TRANSPARENT, Color.argb(60, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f)
        )
        breathingGlowPaint.strokeWidth = strokeWidthPx * 1.5f
        breathingGlowPaint.maskFilter = android.graphics.BlurMaskFilter(strokeWidthPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
        particleGlowPaint.color = Color.argb(120, Color.red(ringStartColor), Color.green(ringStartColor), Color.blue(ringStartColor))
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

            // Draw calm, refined focus bead at the tip of the progress arc
            if (progress > 0.01f) {
                val tipAngleRad = Math.toRadians((-90.0 + 360.0 * progress))
                val px = (cx + radius * cos(tipAngleRad)).toFloat()
                val py = (cy + radius * sin(tipAngleRad)).toFloat()

                // Outer soft glow bead
                canvas.drawCircle(px, py, strokeWidthPx * 0.65f, particleGlowPaint)
                // Inner crisp core
                canvas.drawCircle(px, py, strokeWidthPx * 0.32f, particleCorePaint)
            }
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

