package com.focusvault.app.receiver

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.focusvault.app.data.AppDatabase
import com.focusvault.app.data.ScheduledSession
import com.focusvault.app.data.SessionMode
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_SCHEDULE = "com.focusvault.app.action.TRIGGER_SCHEDULE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DURATION = "duration_minutes"
        const val EXTRA_TITLE = "title"

        const val SCHEDULE_CHANNEL_ID = "scheduled_focus_channel"
        const val SCHEDULE_NOTIF_ID = 2001

        fun rescheduleAll(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getInstance(context)
                val schedules = db.scheduledSessionDao().getEnabledSchedules()
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@launch

                for (schedule in schedules) {
                    scheduleAlarm(context, am, schedule)
                }
            }
        }

        fun scheduleAlarm(context: Context, am: AlarmManager, schedule: ScheduledSession) {
            if (!schedule.isEnabled) return
            
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, schedule.startHour)
                set(Calendar.MINUTE, schedule.startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (schedule.daysOfWeek == "ONCE") {
                if (calendar.before(Calendar.getInstance())) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
            } else {
                val allowedDays = schedule.daysOfWeek.split(",").mapNotNull { it.toIntOrNull() }
                if (allowedDays.isNotEmpty()) {
                    var attempts = 0
                    while (!allowedDays.contains(calendar.get(Calendar.DAY_OF_WEEK)) || calendar.before(Calendar.getInstance())) {
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                        attempts++
                        if (attempts > 7) break
                    }
                }
            }

            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_SCHEDULE
                putExtra(EXTRA_SCHEDULE_ID, schedule.id)
                putExtra(EXTRA_MODE, schedule.mode.name)
                putExtra(EXTRA_DURATION, schedule.durationMinutes)
                putExtra(EXTRA_TITLE, schedule.title)
            }

            val pi = PendingIntent.getBroadcast(
                context,
                schedule.id.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canScheduleExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
                } else if (canScheduleExact) {
                    am.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
                }
            } catch (e: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_SCHEDULE) return

        val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1)
        if (scheduleId == -1L) return

        val durationMinutes = intent.getIntExtra(EXTRA_DURATION, 25)
        val modeStr = intent.getStringExtra(EXTRA_MODE) ?: SessionMode.NORMAL.name
        val mode = runCatching { SessionMode.valueOf(modeStr) }.getOrDefault(SessionMode.NORMAL)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Scheduled Focus"

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val schedule = db.scheduledSessionDao().getScheduleById(scheduleId) ?: return@launch
                
                val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                val isOnce = schedule.daysOfWeek == "ONCE"
                val allowedDays = if (isOnce) emptyList() else schedule.daysOfWeek.split(",").mapNotNull { it.toIntOrNull() }
                
                if (!isOnce && allowedDays.isNotEmpty() && !allowedDays.contains(currentDay)) {
                    // Skip non-matching day but reschedule for the next one
                    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    if (am != null) scheduleAlarm(context, am, schedule)
                    return@launch
                }

                // Check if session is already running
                val activeSession = SessionStateManager.getSessionSnapshot(context)
                if (!activeSession.state.isLive) {
                    notifyScheduledStart(context, title, durationMinutes, mode)
                }

                // Disable "ONCE" after firing, or reschedule
                if (isOnce) {
                    db.scheduledSessionDao().upsert(schedule.copy(isEnabled = false))
                } else {
                    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    if (am != null) scheduleAlarm(context, am, schedule)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun notifyScheduledStart(context: Context, title: String, durationMinutes: Int, mode: SessionMode) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SCHEDULE_CHANNEL_ID,
                "Scheduled Focus Reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val adminActive = com.focusvault.app.service.StayFocusedDeviceAdminReceiver.isDeviceAdminActive(context)
        val adminNotice = if ((mode == SessionMode.STRICT || mode == SessionMode.LOCK) && !adminActive) {
            " (Device Admin setup required)"
        } else ""

        val notification = NotificationCompat.Builder(context, SCHEDULE_CHANNEL_ID)
            .setContentTitle("⏰ Time for: $title")
            .setContentText("$durationMinutes min ${mode.name.lowercase()} focus session scheduled now$adminNotice. Tap to begin.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(SCHEDULE_NOTIF_ID, notification)
        } catch (e: SecurityException) { }
    }
}
