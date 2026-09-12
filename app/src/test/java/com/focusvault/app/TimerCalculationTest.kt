package com.focusvault.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerCalculationTest {

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    @Test
    fun testRemainingTimeCalculation() {
        val startTime = 100_000L
        val duration = 25 * 60 * 1000L // 25 min = 1,500,000 ms
        val targetEndTime = startTime + duration

        val midway = startTime + 10 * 60 * 1000L
        val remaining = (targetEndTime - midway).coerceAtLeast(0L)
        assertEquals(15 * 60 * 1000L, remaining)
        assertEquals("00:15:00", formatTime(remaining))
    }

    @Test
    fun testPauseTimeExtension() {
        val originalEndTime = 1_000_000L
        val pauseDuration = 5 * 60 * 1000L // 5 min pause

        // When paused and resumed, end time shifts by the paused duration
        val adjustedEndTime = originalEndTime + pauseDuration
        assertEquals(1_300_000L, adjustedEndTime)
    }

    @Test
    fun testLapsedTimeClamping() {
        val targetEndTime = 500_000L
        val currentTimeAfterEnd = 600_000L
        val remaining = (targetEndTime - currentTimeAfterEnd).coerceAtLeast(0L)
        assertEquals(0L, remaining)
        assertEquals("00:00:00", formatTime(remaining))
    }

    @Test
    fun testFormatTimeMultiHours() {
        val millis = (2 * 3600 + 45 * 60 + 30) * 1000L
        assertEquals("02:45:30", formatTime(millis))
    }
}
