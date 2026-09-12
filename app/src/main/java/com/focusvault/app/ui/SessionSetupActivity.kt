package com.focusvault.app.ui

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.focusvault.app.R
import com.focusvault.app.data.SessionMode
import com.focusvault.app.databinding.ActivitySessionSetupBinding

class SessionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionSetupBinding
    private lateinit var mode: SessionMode

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_DURATION_MILLIS = "duration_millis"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        mode = SessionMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: SessionMode.NORMAL.name)
        binding.tvModeLabel.text = when (mode) {
            SessionMode.STRICT -> "🔒 Strict Mode"
            SessionMode.LOCK -> "🔐 Lock Mode"
            SessionMode.NORMAL -> "🎯 Focus Mode"
        }

        binding.pickerHoursValue.minValue = 0
        binding.pickerHoursValue.maxValue = 12
        binding.pickerHoursValue.value = 0

        binding.pickerMinutesValue.minValue = 0
        binding.pickerMinutesValue.maxValue = 59
        binding.pickerMinutesValue.value = 25 // default: a pomodoro-length block

        // NumberPicker is a long-standing Android widget that doesn't reliably follow the
        // app's theme - on plenty of devices its digits render in a fixed color (often
        // white) no matter what background it's sitting on, so they're invisible in light
        // mode. Forcing the color here, from @color/text_primary, makes it always match the
        // rest of the screen - and since that color already flips between dark (light mode)
        // and light (dark mode) via values-night/colors.xml, this one line handles both.
        val pickerTextColor = ContextCompat.getColor(this, R.color.text_primary)
        binding.pickerHoursValue.setTextColorCompat(pickerTextColor)
        binding.pickerMinutesValue.setTextColorCompat(pickerTextColor)

        binding.btnConfirmStart.setOnClickListener {
            val hours = binding.pickerHoursValue.value
            val minutes = binding.pickerMinutesValue.value
            val totalMillis = (hours * 3600_000L) + (minutes * 60_000L)

            if (totalMillis <= 0) {
                Toast.makeText(this, "Pick a duration longer than 0 minutes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            when (mode) {
                SessionMode.STRICT -> {
                    // Strict mode always routes through an explicit confirmation screen first -
                    // no accidental strict lock-ins.
                    val intent = Intent(this, StrictModeConfirmActivity::class.java)
                    intent.putExtra(EXTRA_DURATION_MILLIS, totalMillis)
                    startActivity(intent)
                    finish()
                }
                SessionMode.LOCK -> {
                    // Lock mode routes through its own confirm screen, which also handles
                    // setting up (or reusing) the PIN.
                    val intent = Intent(this, LockModeConfirmActivity::class.java)
                    intent.putExtra(EXTRA_DURATION_MILLIS, totalMillis)
                    startActivity(intent)
                    finish()
                }
                SessionMode.NORMAL -> {
                    SessionStarter.startSession(this, totalMillis, SessionMode.NORMAL)
                    finish()
                }
            }
        }
    }

    /** Forces NumberPicker's digit color. NumberPicker draws the surrounding (non-selected)
     * values with an internal Paint object that Android doesn't expose any public API for -
     * this reflectively grabs it, which is a well-known, widely-used workaround (the field
     * name has been stable across Android versions). The currently-selected value is a real
     * child EditText, so that's covered normally, without reflection. If reflection ever
     * fails on some future Android version, this fails silently - it's a cosmetic fix only,
     * never something that should crash the screen. */
    private fun NumberPicker.setTextColorCompat(color: Int) {
        try {
            val paintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
            paintField.isAccessible = true
            (paintField.get(this) as Paint).color = color
            invalidate()
        } catch (e: Exception) {
            // Cosmetic-only fallback: OEM/AOSP field name changed on this device. The picker
            // still works, it just may show its default (often invisible) text color.
        }
        for (i in 0 until childCount) {
            (getChildAt(i) as? EditText)?.setTextColor(color)
        }
    }
}
