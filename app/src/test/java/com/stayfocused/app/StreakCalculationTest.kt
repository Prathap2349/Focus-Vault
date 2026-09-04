package com.stayfocused.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreakCalculationTest {

    data class DayRecord(val date: String, val completedSessions: Int)

    private fun calculateCurrentStreak(history: List<DayRecord>, today: LocalDate): Int {
        // history sorted descending by date
        val activeDates = history.filter { it.completedSessions > 0 }.map { LocalDate.parse(it.date) }.toSet()
        if (activeDates.isEmpty()) return 0

        var streak = 0
        var checkDate = today

        // If today has no completed sessions, we check if yesterday has completed sessions
        if (!activeDates.contains(checkDate)) {
            checkDate = today.minusDays(1)
        }

        while (activeDates.contains(checkDate)) {
            streak++
            checkDate = checkDate.minusDays(1)
        }

        return streak
    }

    @Test
    fun testZeroSessionDayDoesNotIncrementStreak() {
        val today = LocalDate.of(2026, 9, 4)
        val history = listOf(
            DayRecord("2026-09-04", 0), // today has 0 completed sessions
            DayRecord("2026-09-03", 0)  // yesterday has 0 completed sessions
        )
        val streak = calculateCurrentStreak(history, today)
        assertEquals(0, streak)
    }

    @Test
    fun testConsecutiveDaysIncrementStreak() {
        val today = LocalDate.of(2026, 9, 4)
        val history = listOf(
            DayRecord("2026-09-04", 2), // today: 2 sessions
            DayRecord("2026-09-03", 1), // yesterday: 1 session
            DayRecord("2026-09-02", 3)  // 2 days ago: 3 sessions
        )
        val streak = calculateCurrentStreak(history, today)
        assertEquals(3, streak)
    }

    @Test
    fun testMissedDayBreaksStreak() {
        val today = LocalDate.of(2026, 9, 4)
        val history = listOf(
            DayRecord("2026-09-04", 1), // today: 1 session
            DayRecord("2026-09-02", 2)  // missed 2026-09-03!
        )
        val streak = calculateCurrentStreak(history, today)
        assertEquals(1, streak)
    }

    @Test
    fun testYesterdayActiveKeepsStreakAliveIfTodayNotDoneYet() {
        val today = LocalDate.of(2026, 9, 4)
        val history = listOf(
            DayRecord("2026-09-03", 2), // yesterday: 2 sessions
            DayRecord("2026-09-02", 1)  // 2 days ago: 1 session
            // today not yet completed
        )
        val streak = calculateCurrentStreak(history, today)
        assertEquals(2, streak)
    }
}
