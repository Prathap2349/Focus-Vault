package com.stayfocused.app.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.stayfocused.app.R
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.ScheduledSession
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.databinding.ActivitySchedulesBinding
import com.stayfocused.app.databinding.ItemScheduleBinding
import com.stayfocused.app.receiver.ScheduleAlarmReceiver
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class SchedulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySchedulesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySchedulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnAddSchedule.setOnClickListener {
            showAddScheduleDialog()
        }

        loadSchedules()
    }

    private fun loadSchedules() {
        val db = AppDatabase.getInstance(applicationContext)
        lifecycleScope.launch {
            db.scheduledSessionDao().observeSchedules().collect { schedules ->
                binding.containerSchedules.removeAllViews()
                binding.tvEmptySchedules.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
                val inflater = LayoutInflater.from(this@SchedulesActivity)

                schedules.forEach { schedule ->
                    val row = ItemScheduleBinding.inflate(inflater, binding.containerSchedules, false)
                    row.tvScheduleTitle.text = schedule.title

                    val amPm = if (schedule.startHour < 12) "AM" else "PM"
                    val hour12 = if (schedule.startHour % 12 == 0) 12 else schedule.startHour % 12
                    val timeStr = String.format(Locale.US, "%02d:%02d %s (%d min)", hour12, schedule.startMinute, amPm, schedule.durationMinutes)
                    row.tvScheduleTime.text = timeStr
                    row.tvScheduleDays.text = "${schedule.daysOfWeek} · ${schedule.mode.name.lowercase().replaceFirstChar { it.uppercase() }} Mode"

                    row.switchSchedule.isChecked = schedule.isEnabled
                    row.switchSchedule.setOnCheckedChangeListener { _, isChecked ->
                        lifecycleScope.launch {
                            db.scheduledSessionDao().upsert(schedule.copy(isEnabled = isChecked))
                            ScheduleAlarmReceiver.rescheduleAll(applicationContext)
                        }
                    }

                    row.root.setOnLongClickListener {
                        DialogHelper.showCustomDialog(
                            context = this@SchedulesActivity,
                            title = "Delete Schedule 🗑️",
                            message = "Are you sure you want to delete '${schedule.title}'?",
                            positiveText = "Delete",
                            positiveAction = {
                                lifecycleScope.launch {
                                    db.scheduledSessionDao().delete(schedule)
                                    ScheduleAlarmReceiver.rescheduleAll(applicationContext)
                                }
                            },
                            negativeText = "Cancel"
                        )
                        true
                    }

                    binding.containerSchedules.addView(row.root)
                }
            }
        }
    }

    private fun showAddScheduleDialog() {
        val density = resources.displayMetrics.density
        val titleInput = DialogHelper.createPillEditText(this, "Schedule Title (e.g. Evening Deep Work)")
        val durationInput = DialogHelper.createPillEditText(
            this,
            "Duration in minutes (e.g. 60)",
            "60",
            android.text.InputType.TYPE_CLASS_NUMBER
        )

        var selectedHour = 19
        var selectedMinute = 0

        val timeBtn = Button(this, null, 0, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton).apply {
            text = "⏰ Pick Start Time: 07:00 PM"
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setOnClickListener {
                TimePickerDialog(this@SchedulesActivity, { _, hourOfDay, minute ->
                    selectedHour = hourOfDay
                    selectedMinute = minute
                    val amPm = if (hourOfDay < 12) "AM" else "PM"
                    val h12 = if (hourOfDay % 12 == 0) 12 else hourOfDay % 12
                    text = String.format(Locale.US, "⏰ Start Time: %02d:%02d %s", h12, minute, amPm)
                }, selectedHour, selectedMinute, false).show()
            }
        }

        val daysSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SchedulesActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("📅 Daily (Mon-Sun)", "💼 Weekdays (Mon-Fri)", "🏖️ Weekends (Sat-Sun)")
            )
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }

        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SchedulesActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("🟢 Focus Mode (Lite)", "🔐 Lock Mode (PIN Guarded)", "🔒 Strict Mode (Hardcore)")
            )
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }

        fun createLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@SchedulesActivity, R.color.text_secondary))
            setPadding((4 * density).toInt(), (8 * density).toInt(), 0, (4 * density).toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            addView(createLabel("SCHEDULE TITLE"))
            addView(titleInput)
            addView(createLabel("SESSION DURATION (MINUTES)"))
            addView(durationInput)
            addView(createLabel("START TIME"))
            addView(timeBtn)
            addView(createLabel("RECURRING DAYS"))
            addView(daysSpinner)
            addView(createLabel("DISCIPLINE MODE"))
            addView(modeSpinner)
        }

        DialogHelper.showCustomDialog(
            context = this,
            title = "Add Focus Schedule 📅",
            customView = container,
            positiveText = "Save Schedule",
            positiveAction = {
                val title = titleInput.text.toString().trim().ifEmpty { "Focus Session" }
                val duration = durationInput.text.toString().toIntOrNull() ?: 60
                val rawDays = daysSpinner.selectedItem.toString()
                val days = when {
                    rawDays.contains("Daily") -> "Daily (Mon-Sun)"
                    rawDays.contains("Weekdays") -> "Weekdays (Mon-Fri)"
                    rawDays.contains("Weekends") -> "Weekends (Sat-Sun)"
                    else -> rawDays
                }
                val mode = when (modeSpinner.selectedItemPosition) {
                    1 -> SessionMode.LOCK
                    2 -> SessionMode.STRICT
                    else -> SessionMode.NORMAL
                }

                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(applicationContext)
                    val existingSchedules = db.scheduledSessionDao().getEnabledSchedules()
                    val newStartMin = selectedHour * 60 + selectedMinute
                    val conflict = existingSchedules.firstOrNull { existing ->
                        val existStartMin = existing.startHour * 60 + existing.startMinute
                        daysOverlap(days, existing.daysOfWeek) &&
                            timesOverlap(newStartMin, duration, existStartMin, existing.durationMinutes)
                    }

                    val saveAction = {
                        lifecycleScope.launch {
                            db.scheduledSessionDao().upsert(
                                ScheduledSession(
                                    title = title,
                                    daysOfWeek = days,
                                    startHour = selectedHour,
                                    startMinute = selectedMinute,
                                    durationMinutes = duration,
                                    mode = mode,
                                    isEnabled = true
                                )
                            )
                            ScheduleAlarmReceiver.rescheduleAll(applicationContext)
                            Toast.makeText(this@SchedulesActivity, "Schedule saved & alarm set", Toast.LENGTH_SHORT).show()
                        }
                    }

                    if (conflict != null) {
                        DialogHelper.showCustomDialog(
                            context = this@SchedulesActivity,
                            title = "Schedule Conflict ⚠️",
                            message = "'$title' overlaps with existing schedule '${conflict.title}'.\n\nThese sessions will clash. Would you like to save anyway?",
                            positiveText = "Save Anyway",
                            positiveAction = { saveAction() },
                            negativeText = "Adjust"
                        )
                    } else {
                        saveAction()
                    }
                }
            },
            negativeText = "Cancel"
        )
    }

    companion object {
        fun daysOverlap(days1: String, days2: String): Boolean {
            if (days1.startsWith("Daily") || days2.startsWith("Daily")) return true
            if (days1.startsWith("Weekdays") && days2.startsWith("Weekdays")) return true
            if (days1.startsWith("Weekends") && days2.startsWith("Weekends")) return true
            return false
        }

        fun timesOverlap(start1: Int, dur1: Int, start2: Int, dur2: Int): Boolean {
            val end1 = start1 + dur1
            val end2 = start2 + dur2
            val normEnd1 = if (end1 > 1440) end1 - 1440 else end1
            val normEnd2 = if (end2 > 1440) end2 - 1440 else end2

            if (start1 < end2 && end1 > start2) return true
            if (end1 > 1440 && start2 < normEnd1) return true
            if (end2 > 1440 && start1 < normEnd2) return true
            return false
        }
    }
}
