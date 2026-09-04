package com.stayfocused.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Minimal 7-bar chart for the dashboard's weekly focus-time progress - no external chart
 * library, matching [CircularCountdownView]'s "plain Canvas, no custom attrs" approach.
 * Each bar's height is its value relative to [goalValue]; a bar can exceed the goal line
 * (capped visually at a small headroom above it) so hitting/beating the goal is obvious.
 */
class WeeklyBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Bar(val label: String, val value: Int, val highlighted: Boolean)

    private var bars: List<Bar> = emptyList()
    private var goalValue: Int = 1

    private var barColor: Int = Color.GRAY
    private var barHighlightColor: Int = Color.CYAN
    private var trackColor: Int = Color.argb(30, 128, 128, 128)
    private var goalLineColor: Int = Color.argb(120, 128, 128, 128)
    private var labelColor: Int = Color.GRAY

    private val density = context.resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val goalLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(density * 4f, density * 4f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = density * 11f
    }

    private val barRect = RectF()

    /** Sets the week's data (7 entries, Sunday -> Saturday) and the goal reference value, then
     * redraws. [Bar.highlighted] marks today's bar so it visually stands out from the rest. */
    fun setData(bars: List<Bar>, goalValue: Int, barColor: Int, barHighlightColor: Int, goalLineColor: Int, labelColor: Int) {
        this.bars = bars
        this.goalValue = max(1, goalValue)
        this.barColor = barColor
        this.barHighlightColor = barHighlightColor
        this.goalLineColor = goalLineColor
        this.labelColor = labelColor
        goalLinePaint.color = goalLineColor
        labelPaint.color = labelColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val labelHeight = density * 16f
        val chartTop = density * 4f
        val chartBottom = height - labelHeight
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)

        val n = bars.size
        val slotWidth = width.toFloat() / n
        val barWidth = (slotWidth * 0.42f).coerceAtLeast(density * 6f)
        val cornerRadius = barWidth / 2f

        // Cap visual scale a bit above the goal so a big day doesn't flatten the rest of the
        // week into invisibility, while still showing it clearly exceeded the goal.
        val maxValue = max(goalValue.toFloat() * 1.25f, (bars.maxOfOrNull { it.value } ?: 0).toFloat())

        // Dashed goal reference line
        val goalY = chartBottom - (goalValue / maxValue) * chartHeight
        canvas.drawLine(0f, goalY, width.toFloat(), goalY, goalLinePaint)

        bars.forEachIndexed { i, bar ->
            val centerX = slotWidth * i + slotWidth / 2f

            // Background track (full height) so empty days still show a pill outline
            barRect.set(centerX - barWidth / 2f, chartTop, centerX + barWidth / 2f, chartBottom)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, trackPaint)

            if (bar.value > 0) {
                val barHeight = ((bar.value / maxValue) * chartHeight).coerceAtLeast(barWidth * 0.5f)
                barPaint.color = if (bar.highlighted) barHighlightColor else barColor
                barRect.set(centerX - barWidth / 2f, chartBottom - barHeight, centerX + barWidth / 2f, chartBottom)
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
            }

            labelPaint.color = if (bar.highlighted) barHighlightColor else labelColor
            labelPaint.isFakeBoldText = bar.highlighted
            canvas.drawText(bar.label, centerX, height.toFloat() - density * 2f, labelPaint)
        }
    }
}
