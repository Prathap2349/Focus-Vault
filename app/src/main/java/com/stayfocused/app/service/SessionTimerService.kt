package com.stayfocused.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.stayfocused.app.appwidget.WidgetUpdater
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.data.SessionState
import com.stayfocused.app.manager.SessionStateManager
import com.stayfocused.app.ui.MainActivity
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SessionTimerService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var tickerJob: Job? = null

    companion object {
        const val CHANNEL_ID = "focus_session_channel"
        const val NOTIF_ID = 1001
        const val COMPLETION_NOTIF_ID = 1003
        const val COMPLETION_CHANNEL_ID = "focus_session_complete_channel"

        const val ACTION_START = "com.stayfocused.app.action.START_SESSION"
        const val ACTION_STOP_EARLY = "com.stayfocused.app.action.STOP_EARLY"
        const val ACTION_PAUSE = "com.stayfocused.app.action.PAUSE_SESSION"
        const val ACTION_RESUME = "com.stayfocused.app.action.RESUME_SESSION"

        const val EXTRA_DURATION_MILLIS = "duration_millis"
        const val EXTRA_MODE = "mode"

        fun start(context: Context, durationMillis: Long, mode: SessionMode) {
            val intent = Intent(context, SessionTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_MILLIS, durationMillis)
                putExtra(EXTRA_MODE, mode.name)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // If background execution restrictions apply, handled safely
            }
        }

        fun stopEarly(context: Context) {
            val intent = Intent(context, SessionTimerService::class.java).apply {
                action = ACTION_STOP_EARLY
            }
            try {
                context.startService(intent)
            } catch (e: Exception) { }
        }

        fun resume(context: Context) {
            val intent = Intent(context, SessionTimerService::class.java).apply {
                action = ACTION_RESUME
            }
            try {
                context.startService(intent)
            } catch (e: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val duration = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L)
                val mode = runCatching {
                    SessionMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: SessionMode.NORMAL.name)
                }.getOrDefault(SessionMode.NORMAL)
                beginSession(duration, mode)
            }
            ACTION_STOP_EARLY -> {
                if (PrefsManager.getSessionMode(this) != SessionMode.STRICT) {
                    scope.launch {
                        SessionStateManager.stopSessionEarly(applicationContext)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_RESUME -> {
                scope.launch {
                    SessionStateManager.resumeSession(applicationContext)
                }
            }
        }
        return START_STICKY
    }

    private fun beginSession(durationMillis: Long, mode: SessionMode) {
        val endTime = PrefsManager.getSessionEndTime(this)
        val targetEnd = if (endTime > System.currentTimeMillis()) endTime else System.currentTimeMillis() + durationMillis
        val remaining = (targetEnd - System.currentTimeMillis()).coerceAtLeast(0L)

        // Show immediate foreground notification to satisfy Android requirements
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    buildNotification(remaining, mode, isPaused = false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification(remaining, mode, isPaused = false))
            }
        } catch (e: Exception) {
            // Guard against background start restrictions
        }
        WidgetUpdater.requestUpdate(applicationContext)

        startTicker(targetEnd, mode)
    }

    private fun startTicker(endTime: Long, mode: SessionMode) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var lastWidgetPushSecond = -1L

            while (isActive) {
                val isPaused = PrefsManager.isEmergencyPauseActive(applicationContext)
                val currentEnd = PrefsManager.getSessionEndTime(applicationContext).let {
                    if (it > 0) it else endTime
                }
                val now = System.currentTimeMillis()
                val remaining = (currentEnd - now).coerceAtLeast(0L)

                if (remaining <= 0) {
                    // Natural completion: countdown elapsed
                    SessionStateManager.completeSession(applicationContext)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    showCompletionNotification(mode)
                    stopSelf()
                    break
                }

                updateNotification(remaining, mode, isPaused)

                // Push periodic widget update once per minute
                val currentSec = remaining / 1000L
                if (currentSec % 60L == 0L && currentSec != lastWidgetPushSecond) {
                    lastWidgetPushSecond = currentSec
                    WidgetUpdater.requestUpdate(applicationContext)
                }

                delay(1000L)
            }
        }
    }

    private fun buildNotification(millisRemaining: Long, mode: SessionMode, isPaused: Boolean): Notification {
        createChannelIfNeeded()
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = when (mode) {
            SessionMode.STRICT -> "Strict Mode"
            SessionMode.LOCK -> "Lock Mode"
            SessionMode.NORMAL -> "Focus Mode"
        }

        val title = if (isPaused) "⏸️ $modeLabel Paused" else "🎯 $modeLabel Active"
        val content = if (isPaused) {
            "Blocking temporarily paused (${PrefsManager.getEmergencyPauseLabel(this)})"
        } else {
            formatRemaining(millisRemaining)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        // Action buttons
        if (isPaused) {
            val resumeIntent = PendingIntent.getService(
                this, 1,
                Intent(this, SessionTimerService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_play, "Resume Now", resumeIntent)
        } else if (mode == SessionMode.NORMAL) {
            val stopIntent = PendingIntent.getService(
                this, 2,
                Intent(this, SessionTimerService::class.java).apply { action = ACTION_STOP_EARLY },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Early", stopIntent)
        }

        return builder.build()
    }

    private fun updateNotification(millisRemaining: Long, mode: SessionMode, isPaused: Boolean) {
        safeNotify(NOTIF_ID, buildNotification(millisRemaining, mode, isPaused))
    }

    private fun showCompletionNotification(mode: SessionMode) {
        createChannelIfNeeded()
        val modeLabel = when (mode) {
            SessionMode.STRICT -> "Strict Mode"
            SessionMode.LOCK -> "Lock Mode"
            SessionMode.NORMAL -> "Focus Mode"
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
            .setContentTitle("🎉 $modeLabel session complete")
            .setContentText("Great job staying focused! Your apps and websites are unlocked.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        safeNotify(COMPLETION_NOTIF_ID, notification)
    }

    private fun safeNotify(id: Int, notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (e: SecurityException) {
            // Missing notification permission
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Focus Session", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(COMPLETION_CHANNEL_ID, "Focus Session Complete", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun formatRemaining(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%02d:%02d:%02d remaining", h, m, s)
        else String.format("%02d:%02d remaining", m, s)
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceJob.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (e: Exception) { }
        super.onDestroy()
    }
}
