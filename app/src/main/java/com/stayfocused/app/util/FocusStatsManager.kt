package com.stayfocused.app.util

import android.content.Context
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.SessionHistoryEntry
import com.stayfocused.app.data.SessionMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DayMinutes(val label: String, val minutes: Int, val isToday: Boolean)

data class SmartInsights(
    val bestDay: String,
    val avgSessionMinutes: Int,
    val streakMilestone: String,
    val completionRatePercent: Int,
    val summaryTip: String,
    val peakFocusPeriod: String = "Morning",
    val recommendedSessionMinutes: Int = 25
)

data class DashboardStats(
    val todayMinutes: Int,
    val goalMinutes: Int,
    val streak: Int,
    val weekByDay: List<DayMinutes>,
    val weekTotalMinutes: Int,
    val avgSessionMinutes: Int,
    val longestSessionMinutes: Int,
    val productivityScore: Int,
    val recentSessions: List<SessionHistoryEntry>,
    val insights: SmartInsights,
    val weeklyGoalMinutes: Int = 0,
    val monthlyGoalMinutes: Int = 0
)

object FocusStatsManager {

    private val dateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayLabelFormat get() = SimpleDateFormat("EEE", Locale.US)

    suspend fun recordSessionEnd(
        context: Context,
        mode: SessionMode,
        startTimeMillis: Long,
        endTimeMillis: Long,
        completedNaturally: Boolean,
        title: String = "Focus Session",
        distractionsBlocked: Int = 0,
        stopReason: String = ""
    ) {
        val minutes = ((endTimeMillis - startTimeMillis) / 60000L).toInt().coerceAtLeast(0)
        val db = AppDatabase.getInstance(context)
        db.sessionHistoryDao().insert(
            SessionHistoryEntry(
                mode = mode,
                startTimeMillis = startTimeMillis,
                endTimeMillis = endTimeMillis,
                durationMinutes = minutes,
                completedNaturally = completedNaturally,
                title = title,
                distractionsBlocked = distractionsBlocked,
                stopReason = stopReason
            )
        )
        StreakManager.addFocusMinutes(context, minutes)
    }

    suspend fun getDashboardStats(context: Context): DashboardStats {
        val db = AppDatabase.getInstance(context)
        val streakDao = db.streakDao()
        val historyDao = db.sessionHistoryDao()

        val goalMinutes = PrefsManager.getDailyGoalMinutes(context)
        val weeklyGoalMinutes = PrefsManager.getWeeklyGoalMinutes(context).let { if (it > 0) it else goalMinutes * 7 }
        val monthlyGoalMinutes = PrefsManager.getMonthlyGoalMinutes(context).let { if (it > 0) it else goalMinutes * 30 }
        val streak = StreakManager.getCurrentStreak(context)

        val calendar = Calendar.getInstance()
        val todayLabel = dateFormat.format(calendar.time)

        // Find the start of the current week (Sunday)
        val weekStartCalendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val weekStart = dateFormat.format(weekStartCalendar.time)

        val recentDays = streakDao.getDaysSince(weekStart).associateBy { it.date }

        val weekByDay = mutableListOf<DayMinutes>()
        val cursor = weekStartCalendar.clone() as Calendar
        var maxDayName = "None"
        var maxDayMins = 0

        repeat(7) {
            val dateKey = dateFormat.format(cursor.time)
            val dayName = dayLabelFormat.format(cursor.time)
            val mins = recentDays[dateKey]?.totalFocusMinutes ?: 0
            if (mins > maxDayMins) {
                maxDayMins = mins
                maxDayName = dayName
            }
            weekByDay += DayMinutes(
                label = dayName.take(1),
                minutes = mins,
                isToday = dateKey == todayLabel
            )
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }

        val todayMinutes = recentDays[todayLabel]?.totalFocusMinutes ?: 0
        val weekTotalMinutes = weekByDay.sumOf { it.minutes }

        val sinceMillis = weekStartCalendar.timeInMillis
        val weekSessions = historyDao.getSince(sinceMillis)
        val avgSessionMinutes = if (weekSessions.isEmpty()) 0 else weekSessions.sumOf { it.durationMinutes } / weekSessions.size
        val longestSessionMinutes = weekSessions.maxOfOrNull { it.durationMinutes } ?: 0

        // Comprehensive Focus Score (0-100) based on Goal achievement (40%), Completion rate (35%), and Streak consistency (25%)
        val weeklyGoalTotal = if (weeklyGoalMinutes > 0) weeklyGoalMinutes else (goalMinutes * 7)
        val goalFactor = if (weeklyGoalTotal > 0) ((weekTotalMinutes.toFloat() / weeklyGoalTotal.toFloat()) * 40f).coerceIn(0f, 40f) else 0f
        val completedSessionsCount = weekSessions.count { it.completedNaturally }
        val completionRate = if (weekSessions.isNotEmpty()) (completedSessionsCount.toFloat() / weekSessions.size.toFloat()) else 1f
        val completionFactor = completionRate * 35f
        val streakFactor = (streak * 5f).coerceIn(0f, 25f)
        val productivityScore = (goalFactor + completionFactor + streakFactor).toInt().coerceIn(0, 100)

        // Peak focus hour analysis
        val peakPeriod = if (weekSessions.isNotEmpty()) {
            val hourMins = mutableMapOf<Int, Int>()
            val cal = Calendar.getInstance()
            for (s in weekSessions) {
                cal.timeInMillis = s.startTimeMillis
                val h = cal.get(Calendar.HOUR_OF_DAY)
                hourMins[h] = (hourMins[h] ?: 0) + s.durationMinutes
            }
            val bestHour = hourMins.maxByOrNull { it.value }?.key ?: 9
            when (bestHour) {
                in 5..11 -> "Morning ($bestHour:00)"
                in 12..16 -> "Afternoon ($bestHour:00)"
                in 17..21 -> "Evening ($bestHour:00)"
                else -> "Night ($bestHour:00)"
            }
        } else {
            "Morning (9:00)"
        }

        // Recommended duration
        val recommendedMinutes = when {
            avgSessionMinutes >= 60 -> 60
            avgSessionMinutes >= 45 -> 45
            avgSessionMinutes >= 30 -> 30
            else -> 25
        }

        // Streak milestone calculation
        val nextMilestone = when {
            streak < 3 -> "3 days"
            streak < 7 -> "7 days"
            streak < 14 -> "14 days"
            streak < 30 -> "30 days"
            streak < 60 -> "60 days"
            else -> "100 days"
        }

        val tip = when {
            streak >= 7 -> "🔥 Outstanding consistency! You've maintained a 7+ day streak."
            avgSessionMinutes >= 45 -> "🎯 Great focus endurance! Peak period: $peakPeriod."
            todayMinutes >= goalMinutes -> "🎉 Today's goal achieved! Keep the momentum going."
            else -> "💡 Tip: A $recommendedMinutes-min session in the $peakPeriod will help meet your goal."
        }

        val insights = SmartInsights(
            bestDay = if (maxDayMins > 0) "$maxDayName ($maxDayMins min)" else "No data yet",
            avgSessionMinutes = avgSessionMinutes,
            streakMilestone = nextMilestone,
            completionRatePercent = (completionRate * 100).toInt(),
            summaryTip = tip,
            peakFocusPeriod = peakPeriod,
            recommendedSessionMinutes = recommendedMinutes
        )

        val recentSessions = historyDao.getRecent(10)

        return DashboardStats(
            todayMinutes = todayMinutes,
            goalMinutes = goalMinutes,
            streak = streak,
            weekByDay = weekByDay,
            weekTotalMinutes = weekTotalMinutes,
            avgSessionMinutes = avgSessionMinutes,
            longestSessionMinutes = longestSessionMinutes,
            productivityScore = productivityScore,
            recentSessions = recentSessions,
            insights = insights,
            weeklyGoalMinutes = weeklyGoalMinutes,
            monthlyGoalMinutes = monthlyGoalMinutes
        )
    }
}
