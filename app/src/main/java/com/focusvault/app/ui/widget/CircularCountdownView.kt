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
            if (value && !AnimationHelper.isReduceMotion(context)) {
                startOrbitalAnimation()
                startSheenAnimation()
                startBreathingAnimation()
            } else {
                stopOrbitalAnimation()
                stopBreathingAnimation()
            }
        }

    private var ringStartColor: Int = Color.parseColor("#8B5CF6") // Electric Violet
    private var ringEndColor: Int = Color.parseColor("#7C3AED")
    private var trackColor: Int = Color.argb(40, 139, 92, 246)

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
        particleGlowPaint.color = Color.argb(120, Color.red(start), Color.green(start), Color.blue(start))
        breathingGlowPaint.color = Color.argb(15, Color.red(start), Color.green(start), Color.blue(start))
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
                        Color.parseColor("#22C55E"), // Success Green
                        Color.parseColor("#22D3EE"), // Cyan
                        Color.argb(35, 34, 197, 94)
                    )
                } else {
                    setColors(
                        Color.parseColor("#8B5CF6"), // Electric Violet
                        Color.parseColor("#7C3AED"),
                        Color.argb(35, 139, 92, 246)
                    )
                }
            }
            else -> setColors(
                Color.parseColor("#8B5CF6"),
                Color.parseColor("#7C3AED"),
                Color.argb(60, 139, 92, 246) // Visible empty track
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

        
        // Draw Breathing Glow
        if (isOrbitalActive) {
            canvas.save()
            canvas.scale(breathingScale, breathingScale, cx, cy)
            canvas.drawArc(arcRect, 0f, 360f, false, breathingGlowPaint)
            canvas.restore()
        }

        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        if (!AnimationHelper.isReduceMotion(context) && isOrbitalActive) {
            canvas.save()
            canvas.rotate(sheenAngle, cx, cy)
            canvas.drawArc(arcRect, 0f, 360f, false, trackSheenPaint)
            canvas.restore()
        }
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

