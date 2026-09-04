package com.stayfocused.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per calendar day (yyyy-MM-dd, device-local) that had at least one naturally
 * completed focus session. A day with zero completions simply has no row here at all -
 * that absence is exactly what StreakManager walks backward looking for to find a gap. */
@Entity(tableName = "streak_day")
data class StreakDay(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val completedSessions: Int = 0,
    // Total minutes spent in a focus session on this day, counted for ANY session that ended
    // (naturally finished, stopped early, or emergency-unlocked) - unlike completedSessions,
    // this reflects real time spent focused, not just fully-finished sessions. Powers the
    // dashboard's "Today's Focus Time" / weekly progress chart.
    val totalFocusMinutes: Int = 0
)
