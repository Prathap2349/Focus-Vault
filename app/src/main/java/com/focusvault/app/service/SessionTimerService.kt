package com.focusvault.app.service

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
import com.focusvault.app.appwidget.WidgetUpdater
import com.focusvault.app.data.SessionMode
import com.focusvault.app.data.SessionState
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.ui.MainActivity
import com.focusvault.app.util.PrefsManager
import com.focusvault.app.util.StreakManager
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
    private var endingSoonAlertSent = false

    companion object {
        const val CHANNEL_ID = "focus_session_channel"
        const val NOTIF_ID = 1001
        const val COMPLETION_NOTIF_ID = 1003
        const val COMPLETION_CHANNEL_ID = "focus_session_complete_channel"

        const val ACTION_START = "com.focusvault.app.action.START_SESSION"
        const val ACTION_STOP_EARLY = "com.focusvault.app.action.STOP_EARLY"
        const val ACTION_PAUSE = "com.focusvault.app.action.PAUSE_SESSION"
        const val ACTION_RESUME = "com.focusvault.app.action.RESUME_SESSION"

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

        fun pause(context: Context) {
            val intent = Intent(context, SessionTimerService::class.java).apply {
                action = ACTION_PAUSE
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
        com.focusvault.app.manager.ProtectionEngine.isTimerServiceRunning.set(true)
        com.focusvault.app.manager.ProtectionEngine.lastTimerHeartbeat.set(System.currentTimeMillis())

        // Always satisfy Android OS foreground notification requirements on every start/restart
        ensureForegroundNotification()

        when (intent?.action) {
            ACTION_START -> {
                val duration = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L)
                val mode = runCatching {
                    SessionMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: SessionMode.NORMAL.name)
                }.getOrDefault(SessionMode.NORMAL)
                beginSession(duration, mode)
            }
            ACTION_PAUSE -> {
                if (PrefsManager.getSessionMode(this) != SessionMode.STRICT) {
                    scope.launch {
                        SessionStateManager.pauseSession(applicationContext, 5 * 60 * 1000L, "Pause")
                    }
                }
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

    private fun ensureForegroundNotification() {
        val remaining = (PrefsManager.getSessionEndTime(this) - System.currentTimeMillis()).coerceAtLeast(0L)
        val mode = PrefsManager.getSessionMode(this)
        val isPaused = PrefsManager.isEmergencyPauseActive(this)
        var startedForegroundSuccessfully = false
        try {
            val notification = buildNotification(remaining, mode, isPaused)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    startedForegroundSuccessfully = true
                } catch (e: Exception) {
                    android.util.Log.w("SessionTimerService", "Failed to start with SPECIAL_USE type, falling back to default", e)
                    startForeground(NOTIF_ID, notification)
                    startedForegroundSuccessfully = true
                }
            } else {
                startForeground(NOTIF_ID, notification)
                startedForegroundSuccessfully = true
            }
        } catch (e: Exception) {
            android.util.Log.e("SessionTimerService", "Failed to post foreground notification", e)
        }

        if (!startedForegroundSuccessfully) {
            android.util.Log.e("SessionTimerService", "Service could not start foreground notification; stopping self to prevent OS process crash")
            stopSelf()
        }
    }

    private fun beginSession(durationMillis: Long, mode: SessionMode) {
        endingSoonAlertSent = false
        val endTime = PrefsManager.getSessionEndTime(this)
        val targetEnd = if (endTime > System.currentTimeMillis()) endTime else System.currentTimeMillis() + durationMillis

        ensureForegroundNotification()
        WidgetUpdater.requestUpdate(applicationContext)

        startTicker(targetEnd, mode)
    }

    private fun startTicker(endTime: Long, mode: SessionMode) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var lastWidgetPushSecond = -1L

            while (isActive) {
                com.focusvault.app.manager.ProtectionEngine.isTimerServiceRunning.set(true)
                com.focusvault.app.manager.ProtectionEngine.lastTimerHeartbeat.set(System.currentTimeMillis())

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

                if (remaining in 1..120_000L && !endingSoonAlertSent && !isPaused) {
                    endingSoonAlertSent = true
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

        val title = "Focus Vault"
        val totalDuration = (PrefsManager.getSessionEndTime(this) - PrefsManager.getSessionStartTime(this)).coerceAtLeast(1000L)
        val initialMinutes = ((totalDuration + 30000L) / 60000L).coerceAtLeast(1)

        val content = when {
            isPaused -> "⏸ Focus session paused\n${formatRemaining(millisRemaining)}"
            millisRemaining in 1..120_000L -> "⏳ Focus session ending soon\n2 minutes remaining"
            millisRemaining >= totalDuration - 2000L -> "🎯 Focus session started\n$initialMinutes minutes of distraction-free time."
            else -> "🎯 Focus session active\n${formatRemaining(millisRemaining)}"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
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
            val endIntent = PendingIntent.getService(
                this, 2,
                Intent(this, SessionTimerService::class.java).apply { action = ACTION_STOP_EARLY },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_play, "RESUME", resumeIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "END", endIntent)
        } else if (mode != SessionMode.STRICT) {
            val pauseIntent = PendingIntent.getService(
                this, 3,
                Intent(this, SessionTimerService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_pause, "PAUSE", pauseIntent)
        }

        return builder.build()
    }

    private fun updateNotification(millisRemaining: Long, mode: SessionMode, isPaused: Boolean) {
        safeNotify(NOTIF_ID, buildNotification(millisRemaining, mode, isPaused))
    }

    private fun showCompletionNotification(mode: SessionMode) {
        createChannelIfNeeded()
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        scope.launch(Dispatchers.IO) {
            val todayMinutes = StreakManager.getTodayFocusMinutes(applicationContext)
            val sessionMinutes = ((PrefsManager.getSessionEndTime(applicationContext) - PrefsManager.getSessionStartTime(applicationContext)) / 60000L).toInt().coerceAtLeast(1)
            val displayMinutes = if (todayMinutes > 0) todayMinutes else sessionMinutes
            val content = "✓ Focus session complete\n$displayMinutes minutes focused today."

            val notification = NotificationCompat.Builder(applicationContext, COMPLETION_CHANNEL_ID)
                .setContentTitle("Focus Vault")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            safeNotify(COMPLETION_NOTIF_ID, notification)
        }
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
            // 1. Focus Sessions
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Focus Sessions", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing timer and controls during focus sessions"
                    setShowBadge(false)
                }
            )
            // 2. Session Completion
            nm.createNotificationChannel(
                NotificationChannel(COMPLETION_CHANNEL_ID, "Session Completion", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts when a focus session finishes successfully"
                    enableVibration(true)
                }
            )
            // 3. Website Protection
            nm.createNotificationChannel(
                NotificationChannel(FocusVpnService.CHANNEL_ID, "Website Protection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Status of background distraction blocking"
                    setShowBadge(false)
                }
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
        com.focusvault.app.manager.ProtectionEngine.isTimerServiceRunning.set(false)
        tickerJob?.cancel()
        serviceJob.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (e: Exception) { }
        WidgetUpdater.requestUpdate(applicationContext)
        super.onDestroy()
    }
}
