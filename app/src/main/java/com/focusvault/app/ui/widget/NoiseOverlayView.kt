package com.focusvault.app.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Random

class NoiseOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var noiseBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (255 * 0.05f).toInt() // 5% opacity
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            generateNoiseBitmap(w, h)
        }
    }

    private fun generateNoiseBitmap(w: Int, h: Int) {
        val scaleDown = 4
        val bw = (w / scaleDown).coerceAtLeast(1)
        val bh = (h / scaleDown).coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bw * bh)
        val random = Random()
        
        for (i in pixels.indices) {
            val brightness = random.nextInt(256)
            pixels[i] = Color.argb(255, brightness, brightness, brightness)
        }
        
        bitmap.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        noiseBitmap = bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        noiseBitmap?.let {
            val destRect = android.graphics.Rect(0, 0, width, height)
            canvas.drawBitmap(it, null, destRect, paint)
        }
    }
}
