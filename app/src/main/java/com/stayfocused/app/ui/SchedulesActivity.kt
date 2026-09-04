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
                        AlertDialog.Builder(this@SchedulesActivity)
                            .setTitle("Delete Schedule")
                            .setMessage("Delete '${schedule.title}'?")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch {
                                    db.scheduledSessionDao().delete(schedule)
                                    ScheduleAlarmReceiver.rescheduleAll(applicationContext)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }

                    binding.containerSchedules.addView(row.root)
                }
            }
        }
    }

    private fun showAddScheduleDialog() {
        val titleInput = EditText(this).apply { hint = "Schedule Title (e.g. Evening Study)" }
        val durationInput = EditText(this).apply {
            hint = "Duration in minutes (e.g. 60)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("60")
        }

        var selectedHour = 19
        var selectedMinute = 0

        val timeBtn = android.widget.Button(this).apply {
            text = "Pick Start Time: 07:00 PM"
            setOnClickListener {
                TimePickerDialog(this@SchedulesActivity, { _, hourOfDay, minute ->
                    selectedHour = hourOfDay
                    selectedMinute = minute
                    val amPm = if (hourOfDay < 12) "AM" else "PM"
                    val h12 = if (hourOfDay % 12 == 0) 12 else hourOfDay % 12
                    text = String.format(Locale.US, "Start Time: %02d:%02d %s", h12, minute, amPm)
                }, selectedHour, selectedMinute, false).show()
            }
        }

        val daysSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SchedulesActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Daily (Mon-Sun)", "Weekdays (Mon-Fri)", "Weekends (Sat-Sun)"))
        }

        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SchedulesActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Focus Mode (Normal)", "Lock Mode (PIN)", "Strict Mode"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(titleInput)
            addView(durationInput)
            addView(timeBtn)
            addView(daysSpinner)
            addView(modeSpinner)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Focus Schedule")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim().ifEmpty { "Focus Session" }
                val duration = durationInput.text.toString().toIntOrNull() ?: 60
                val days = daysSpinner.selectedItem.toString()
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
                        AlertDialog.Builder(this@SchedulesActivity)
                            .setTitle("Schedule Conflict")
                            .setMessage("'$title' overlaps with existing schedule '${conflict.title}'.\n\nThese sessions will clash. Would you like to save anyway?")
                            .setPositiveButton("Save Anyway") { _, _ -> saveAction() }
                            .setNegativeButton("Adjust", null)
                            .show()
                    } else {
                        saveAction()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
