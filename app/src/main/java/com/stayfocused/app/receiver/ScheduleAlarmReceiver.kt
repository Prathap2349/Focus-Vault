package com.stayfocused.app.receiver

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.ScheduledSession
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.manager.SessionStateManager
import com.stayfocused.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_SCHEDULE = "com.stayfocused.app.action.TRIGGER_SCHEDULE"
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
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, schedule.startHour)
                set(Calendar.MINUTE, schedule.startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
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

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
                }
            } catch (e: SecurityException) {
                // If exact alarm permission is missing on Android 12+, use standard alarm
                am.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_SCHEDULE) return

        val durationMinutes = intent.getIntExtra(EXTRA_DURATION, 25)
        val modeStr = intent.getStringExtra(EXTRA_MODE) ?: SessionMode.NORMAL.name
        val mode = runCatching { SessionMode.valueOf(modeStr) }.getOrDefault(SessionMode.NORMAL)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Scheduled Focus"

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if session is already running
                val activeSession = SessionStateManager.getSessionSnapshot(context)
                if (!activeSession.state.isLive) {
                    // Start session or notify user to tap and begin
                    notifyScheduledStart(context, title, durationMinutes, mode)
                }
                // Reschedule for next occurrence
                rescheduleAll(context)
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

        val notification = NotificationCompat.Builder(context, SCHEDULE_CHANNEL_ID)
            .setContentTitle("⏰ Time for: $title")
            .setContentText("$durationMinutes min ${mode.name.lowercase()} focus session scheduled now. Tap to begin.")
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
