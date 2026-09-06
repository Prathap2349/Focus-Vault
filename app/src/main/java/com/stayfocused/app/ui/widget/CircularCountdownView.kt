package com.stayfocused.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A minimal, premium circular focus ring.
 * Features rounded stroke caps, smooth progress animation, state-based dynamic coloring,
 * and lifecycle-safe rendering.
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

    private val arcRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    fun setProgressSmooth(targetProgress: Float, durationMs: Long = 300) {
        progressAnimator?.cancel()
        val clamped = targetProgress.coerceIn(0f, 1f)
        progressAnimator = ValueAnimator.ofFloat(progress, clamped).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                progress = va.animatedValue as Float
            }
            start()
        }
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
        isStrict: Boolean = false
    ) {
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
            isActive -> setColors(
                Color.parseColor("#6366F1"), // Indigo
                Color.parseColor("#4F46E5"),
                Color.argb(30, 99, 102, 241)
            )
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
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        progressAnimator?.cancel()
    }
}
