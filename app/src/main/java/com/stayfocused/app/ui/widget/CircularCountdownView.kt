package com.stayfocused.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/**
 * A minimal ring-style progress indicator for session countdowns - no custom attrs, no
 * external chart library. Center content (the actual digits/labels) is a separate view
 * layered on top of this one in XML; this view only ever draws the ring itself.
 */
class CircularCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 1f = session just started (full ring), 0f = finished (empty ring). */
    var progress: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var ringStartColor: Int = Color.CYAN
    private var ringEndColor: Int = Color.MAGENTA
    private var trackColor: Int = Color.argb(35, 255, 255, 255)

    private val strokeWidthPx = context.resources.displayMetrics.density * 10f

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

    /** Sets the ring's gradient + track colors and redraws. Safe to call before or after
     * layout - the shader is (re)built as soon as a real size is known. */
    fun setColors(start: Int, end: Int, track: Int = trackColor) {
        ringStartColor = start
        ringEndColor = end
        trackColor = track
        rebuild()
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
}
