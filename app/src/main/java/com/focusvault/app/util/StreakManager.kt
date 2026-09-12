package com.focusvault.app.util

import android.content.Context
import com.focusvault.app.data.AppDatabase
import com.focusvault.app.data.StreakDay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A "streak" is the number of consecutive calendar days (device-local) with at least one
 * naturally completed focus session (timer ran out on its own - not stopped early, not
 * ended via emergency unlock). Call [recordCompletion] exactly once per natural completion;
 * call [getCurrentStreak] to read the number back for display.
 */
object StreakManager {

    private val dateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Marks today as having at least one completed session. Safe to call more than once on
     * the same day - it increments a counter rather than assuming it's the first call. */
    suspend fun recordCompletion(context: Context) {
        val dao = AppDatabase.getInstance(context).streakDao()
        val today = PrefsManager.currentDateString()
        val existing = dao.getDay(today)
        dao.upsert(
            StreakDay(
                date = today,
                completedSessions = (existing?.completedSessions ?: 0) + 1,
                totalFocusMinutes = existing?.totalFocusMinutes ?: 0
            )
        )
    }

    /** Adds [minutes] of focus time to today's row, for ANY finished session (natural,
     * stopped early, or emergency-unlocked) - unlike [recordCompletion], this is about time
     * actually spent focused, not "did the timer run all the way out". Powers the dashboard's
     * Today's Focus Time / weekly chart. */
    suspend fun addFocusMinutes(context: Context, minutes: Int) {
        if (minutes <= 0) return
        val dao = AppDatabase.getInstance(context).streakDao()
        val today = PrefsManager.currentDateString()
        val existing = dao.getDay(today)
        dao.upsert(
            StreakDay(
                date = today,
                completedSessions = existing?.completedSessions ?: 0,
                totalFocusMinutes = (existing?.totalFocusMinutes ?: 0) + minutes
            )
        )
    }

    /** Returns today's recorded focus minutes. */
    suspend fun getTodayFocusMinutes(context: Context): Int {
        val dao = AppDatabase.getInstance(context).streakDao()
        val today = PrefsManager.currentDateString()
        return dao.getDay(today)?.totalFocusMinutes ?: 0
    }

    /** Counts backward from today, day by day, stopping at the first missing day - except
     * today itself is allowed to be missing (you haven't necessarily finished a session yet
     * today, but that shouldn't zero out yesterday's progress until the day actually ends). */
    suspend fun getCurrentStreak(context: Context): Int {
        val dao = AppDatabase.getInstance(context).streakDao()
        val completedDates = dao.getAllDatesDesc().toHashSet()
        if (completedDates.isEmpty()) return 0

        val calendar = Calendar.getInstance()
        var streak = 0
        var cursor = dateFormat.format(calendar.time)

        // Today is allowed to be the one gap in the chain (see doc comment above).
        if (cursor !in completedDates) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            cursor = dateFormat.format(calendar.time)
        }

        while (cursor in completedDates) {
            streak++
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            cursor = dateFormat.format(calendar.time)
        }
        return streak
    }

    /** Calculates the longest consecutive streak of completed days in history. */
    suspend fun getBestStreak(context: Context): Int {
        val dao = AppDatabase.getInstance(context).streakDao()
        val completedDates = dao.getAllDatesDesc().toSortedSet().toList()
        if (completedDates.isEmpty()) return 0

        var best = 1
        var current = 1
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        for (i in 0 until completedDates.size - 1) {
            val date1 = sdf.parse(completedDates[i]) ?: continue
            val date2 = sdf.parse(completedDates[i + 1]) ?: continue
            val diffDays = ((date2.time - date1.time) / (1000 * 60 * 60 * 24)).toInt()
            if (diffDays == 1) {
                current++
                if (current > best) best = current
            } else if (diffDays > 1) {
                current = 1
            }
        }
        return best
    }
}
