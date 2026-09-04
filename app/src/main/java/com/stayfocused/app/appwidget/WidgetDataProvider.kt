package com.stayfocused.app.appwidget

import android.content.Context
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.util.FocusStatsManager
import com.stayfocused.app.util.PrefsManager

/** Everything the widget needs to redraw itself, gathered once per refresh - so a single
 * AppWidgetManager update cycle only reads PrefsManager/Room once no matter how many
 * instances of the widget are on the home screen. */
data class WidgetSnapshot(
    val isActive: Boolean,
    val modeLabel: String,
    val remainingLabel: String,
    val canOpenEmergencyMode: Boolean,
    val todayMinutes: Int,
    val goalMinutes: Int,
    val goalProgressPercent: Int,
    val weekTotalMinutes: Int,
    val weekGoalMinutes: Int,
    val weekProgressPercent: Int,
    val streak: Int,
    val avgSessionMinutes: Int,
    val longestSessionMinutes: Int
)

object WidgetDataProvider {

    suspend fun buildSnapshot(context: Context): WidgetSnapshot {
        val isActive = PrefsManager.isSessionCurrentlyActive(context)
        val mode = PrefsManager.getSessionMode(context)
        val modeLabel = when (mode) {
            SessionMode.STRICT -> "Strict Mode"
            SessionMode.LOCK -> "Lock Mode"
            SessionMode.NORMAL -> "Focus Mode"
        }
        val remainingLabel = if (isActive) {
            val remainingMillis = PrefsManager.getSessionEndTime(context) - System.currentTimeMillis()
            if (remainingMillis > 0) formatRemaining(remainingMillis) else "Ending…"
        } else {
            "Not focusing"
        }
        val canOpenEmergencyMode = isActive && mode != SessionMode.STRICT

        val stats = FocusStatsManager.getDashboardStats(context)
        val goalProgressPercent = if (stats.goalMinutes > 0)
            ((stats.todayMinutes * 100) / stats.goalMinutes).coerceIn(0, 100) else 0
        val weekGoalMinutes = stats.weeklyGoalMinutes.takeIf { it > 0 } ?: (stats.goalMinutes * 7)
        val weekProgressPercent = if (weekGoalMinutes > 0)
            ((stats.weekTotalMinutes * 100) / weekGoalMinutes).coerceIn(0, 100) else 0

        return WidgetSnapshot(
            isActive = isActive,
            modeLabel = modeLabel,
            remainingLabel = remainingLabel,
            canOpenEmergencyMode = canOpenEmergencyMode,
            todayMinutes = stats.todayMinutes,
            goalMinutes = stats.goalMinutes,
            goalProgressPercent = goalProgressPercent,
            weekTotalMinutes = stats.weekTotalMinutes,
            weekGoalMinutes = weekGoalMinutes,
            weekProgressPercent = weekProgressPercent,
            streak = stats.streak,
            avgSessionMinutes = stats.avgSessionMinutes,
            longestSessionMinutes = stats.longestSessionMinutes
        )
    }

    private fun formatRemaining(millis: Long): String {
        val totalMinutes = (millis / 60000L).toInt().coerceAtLeast(0)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0 -> "${m}m left"
            m == 0 -> "${h}h left"
            else -> "${h}h ${m}m left"
        }
    }
}
