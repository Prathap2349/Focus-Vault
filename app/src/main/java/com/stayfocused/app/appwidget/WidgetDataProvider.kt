package com.stayfocused.app.appwidget

import android.content.Context
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.ScheduledSession
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.data.SessionState
import com.stayfocused.app.manager.ProtectionEngine
import com.stayfocused.app.manager.ProtectionStatus
import com.stayfocused.app.manager.SessionStateManager
import com.stayfocused.app.util.FocusStatsManager
import com.stayfocused.app.util.PrefsManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Authoritative, immutable data snapshot representing everything the widget needs to render.
 * Gathered once per update cycle to prevent duplicate Room/Prefs queries across multiple widget instances.
 */
data class WidgetSnapshot(
    val sessionState: SessionState,
    val isActive: Boolean,
    val isPaused: Boolean,
    val isCompleted: Boolean,
    val mode: SessionMode,
    val modeLabel: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val remainingMillis: Long,
    val remainingFormatted: String,      // "42:18" or "01:25:30"
    val remainingLabelCompact: String,   // "42:18" or "Ready"
    val remainingLabelFull: String,      // "42:18 remaining"
    val sessionStartFormatted: String,   // "2:15 PM"
    val sessionEndFormatted: String,     // "2:57 PM"
    val sessionElapsedPercent: Int,      // 0 - 100%
    val canOpenEmergencyMode: Boolean,
    val todayMinutes: Int,
    val goalMinutes: Int,
    val goalProgressPercent: Int,        // 0 - 100%
    val isGoalComplete: Boolean,
    val weekTotalMinutes: Int,
    val weekGoalMinutes: Int,
    val weekProgressPercent: Int,        // 0 - 100%
    val streak: Int,
    val avgSessionMinutes: Int,
    val longestSessionMinutes: Int,
    val nextScheduleTitle: String?,
    val nextScheduleFormatted: String?,  // "Today · 7:00 PM (45m)"
    val isProtectionHealthy: Boolean,
    val protectionHeadline: String,
    val distractionsBlocked: Int,
    val snapshotTimestamp: Long = System.currentTimeMillis()
)

object WidgetDataProvider {

    private val lastKnownSnapshot = AtomicReference<WidgetSnapshot?>(null)
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    suspend fun buildSnapshot(context: Context): WidgetSnapshot {
        return try {
            val snapshot = gatherAuthoritativeSnapshot(context)
            lastKnownSnapshot.set(snapshot)
            snapshot
        } catch (e: Exception) {
            // Fall back safely to Last Known Good State with live adjusted time
            lastKnownSnapshot.get()?.let { cached ->
                adjustCachedSnapshot(cached)
            } ?: createDefaultFallbackSnapshot(context)
        }
    }

    private suspend fun gatherAuthoritativeSnapshot(context: Context): WidgetSnapshot {
        val session = SessionStateManager.getSessionSnapshot(context)
        val sessionMode = session.mode
        val sessionState = session.state

        val now = System.currentTimeMillis()
        val endTime = session.endTimeMillis
        val isTimeRemaining = endTime > 0L && now < endTime

        // A session is ONLY active if state is live AND current time is strictly before end time
        val isPaused = (sessionState == SessionState.PAUSED || PrefsManager.isEmergencyPauseActive(context)) && isTimeRemaining
        val isActive = sessionState.isLive && sessionState != SessionState.PAUSED && isTimeRemaining
        val isCompleted = sessionState == SessionState.COMPLETED || (sessionState.isLive && now >= endTime)

        val startTime = session.startTimeMillis.takeIf { it > 0 } ?: (if (endTime > 0) endTime - 25 * 60 * 1000L else 0L)
        val remainingMillis = if (isActive || isPaused) (endTime - now).coerceAtLeast(0L) else 0L

        val modeLabel = when (sessionMode) {
            SessionMode.STRICT -> "Strict Mode"
            SessionMode.LOCK -> "Lock Mode"
            SessionMode.NORMAL -> "Focus Mode"
        }

        val remainingFormatted = formatRemainingMMSS(remainingMillis)
        val remainingLabelCompact = if (isActive) remainingFormatted else if (isPaused) "Paused" else "Focus"
        val remainingLabelFull = if (isActive) "$remainingFormatted remaining" else if (isPaused) "Session Paused" else "Not focusing"

        val sessionStartFormatted = if (isActive && startTime > 0) timeFormat.format(startTime) else "--:--"
        val sessionEndFormatted = if (isActive && endTime > 0) timeFormat.format(endTime) else "--:--"

        val totalSessionDuration = (endTime - startTime).coerceAtLeast(1L)
        val elapsed = (now - startTime).coerceAtLeast(0L)
        val sessionElapsedPercent = if (isActive) {
            ((elapsed * 100) / totalSessionDuration).toInt().coerceIn(0, 100)
        } else if (isCompleted) {
            100
        } else {
            0
        }

        val canOpenEmergencyMode = isActive && sessionMode != SessionMode.STRICT && !PrefsManager.isEmergencyPauseActive(context)

        // Read Stats safely from Room
        val stats = FocusStatsManager.getDashboardStats(context)
        val goalMinutes = stats.goalMinutes.coerceAtLeast(1)
        val todayMinutes = stats.todayMinutes.coerceAtLeast(0)
        val goalProgressPercent = ((todayMinutes * 100) / goalMinutes).coerceIn(0, 100)
        val isGoalComplete = todayMinutes >= goalMinutes

        val weekGoalMinutes = stats.weeklyGoalMinutes.takeIf { it > 0 } ?: (goalMinutes * 7)
        val weekTotalMinutes = stats.weekTotalMinutes.coerceAtLeast(0)
        val weekProgressPercent = if (weekGoalMinutes > 0) {
            ((weekTotalMinutes * 100) / weekGoalMinutes).coerceIn(0, 100)
        } else {
            0
        }

        // Protection health check
        val protectionReport = ProtectionEngine.evaluate(context)
        val isProtectionHealthy = protectionReport.status == ProtectionStatus.PROTECTION_ACTIVE
        val protectionHeadline = protectionReport.headlineMessage

        // Next scheduled session check
        val nextSchedule = findNextScheduledSession(context)
        val (nextTitle, nextFormatted) = formatNextSchedule(nextSchedule)

        return WidgetSnapshot(
            sessionState = if (isActive) SessionState.ACTIVE else if (isPaused) SessionState.PAUSED else if (isCompleted) SessionState.COMPLETED else SessionState.IDLE,
            isActive = isActive,
            isPaused = isPaused,
            isCompleted = isCompleted,
            mode = sessionMode,
            modeLabel = modeLabel,
            startTimeMillis = startTime,
            endTimeMillis = endTime,
            remainingMillis = remainingMillis,
            remainingFormatted = remainingFormatted,
            remainingLabelCompact = remainingLabelCompact,
            remainingLabelFull = remainingLabelFull,
            sessionStartFormatted = sessionStartFormatted,
            sessionEndFormatted = sessionEndFormatted,
            sessionElapsedPercent = sessionElapsedPercent,
            canOpenEmergencyMode = canOpenEmergencyMode,
            todayMinutes = todayMinutes,
            goalMinutes = goalMinutes,
            goalProgressPercent = goalProgressPercent,
            isGoalComplete = isGoalComplete,
            weekTotalMinutes = weekTotalMinutes,
            weekGoalMinutes = weekGoalMinutes,
            weekProgressPercent = weekProgressPercent,
            streak = stats.streak,
            avgSessionMinutes = stats.avgSessionMinutes,
            longestSessionMinutes = stats.longestSessionMinutes,
            nextScheduleTitle = nextTitle,
            nextScheduleFormatted = nextFormatted,
            isProtectionHealthy = isProtectionHealthy,
            protectionHeadline = protectionHeadline,
            distractionsBlocked = session.distractionsBlocked,
            snapshotTimestamp = now
        )
    }

    private fun adjustCachedSnapshot(cached: WidgetSnapshot): WidgetSnapshot {
        val now = System.currentTimeMillis()
        val remaining = if (cached.isActive) (cached.endTimeMillis - now).coerceAtLeast(0L) else 0L
        val isStillActive = cached.isActive && remaining > 0L

        val remainingFormatted = formatRemainingMMSS(remaining)
        return cached.copy(
            isActive = isStillActive,
            remainingMillis = remaining,
            remainingFormatted = if (isStillActive) remainingFormatted else "00:00",
            remainingLabelCompact = if (isStillActive) remainingFormatted else "Focus",
            remainingLabelFull = if (isStillActive) "$remainingFormatted remaining" else "Not focusing",
            snapshotTimestamp = now
        )
    }

    private fun createDefaultFallbackSnapshot(context: Context): WidgetSnapshot {
        val goal = PrefsManager.getDailyGoalMinutes(context)
        return WidgetSnapshot(
            sessionState = SessionState.IDLE,
            isActive = false,
            isPaused = false,
            isCompleted = false,
            mode = SessionMode.NORMAL,
            modeLabel = "Focus Mode",
            startTimeMillis = 0L,
            endTimeMillis = 0L,
            remainingMillis = 0L,
            remainingFormatted = "00:00",
            remainingLabelCompact = "Focus",
            remainingLabelFull = "Not focusing",
            sessionStartFormatted = "--:--",
            sessionEndFormatted = "--:--",
            sessionElapsedPercent = 0,
            canOpenEmergencyMode = false,
            todayMinutes = 0,
            goalMinutes = goal,
            goalProgressPercent = 0,
            isGoalComplete = false,
            weekTotalMinutes = 0,
            weekGoalMinutes = goal * 7,
            weekProgressPercent = 0,
            streak = 0,
            avgSessionMinutes = 0,
            longestSessionMinutes = 0,
            nextScheduleTitle = null,
            nextScheduleFormatted = null,
            isProtectionHealthy = true,
            protectionHeadline = "Protection active",
            distractionsBlocked = 0,
            snapshotTimestamp = System.currentTimeMillis()
        )
    }

    private suspend fun findNextScheduledSession(context: Context): ScheduledSession? {
        return try {
            val db = AppDatabase.getInstance(context)
            val schedules = db.scheduledSessionDao().getEnabledSchedules()
            if (schedules.isEmpty()) return null

            val now = Calendar.getInstance()
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)
            val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK) // 1=Sun, 2=Mon...

            // Find schedule for today that hasn't passed yet
            val todayUpcoming = schedules.filter { s ->
                val appliesToday = s.daysOfWeek == "ONCE" || s.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(currentDayOfWeek)
                val isLater = (s.startHour > currentHour) || (s.startHour == currentHour && s.startMinute > currentMinute)
                appliesToday && isLater
            }.minByOrNull { it.startHour * 60 + it.startMinute }

            todayUpcoming ?: schedules.minByOrNull { it.startHour * 60 + it.startMinute }
        } catch (e: Exception) {
            null
        }
    }

    private fun formatNextSchedule(schedule: ScheduledSession?): Pair<String?, String?> {
        if (schedule == null) return Pair(null, null)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, schedule.startHour)
            set(Calendar.MINUTE, schedule.startMinute)
        }
        val timeStr = timeFormat.format(cal.time)
        val formatted = "Today · $timeStr (${schedule.durationMinutes}m)"
        return Pair(schedule.title, formatted)
    }

    fun formatRemainingMMSS(millis: Long): String {
        val totalSec = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
