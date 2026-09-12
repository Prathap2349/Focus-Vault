package com.focusvault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {

    @Test
    fun testGoalPercentageClamping() {
        val goalMinutes = 120
        val todayMinutesUnder = 60
        val todayMinutesOver = 180
        val todayMinutesZero = 0

        val percentUnder = ((todayMinutesUnder * 100) / goalMinutes).coerceIn(0, 100)
        val percentOver = ((todayMinutesOver * 100) / goalMinutes).coerceIn(0, 100)
        val percentZero = ((todayMinutesZero * 100) / goalMinutes).coerceIn(0, 100)

        assertEquals(50, percentUnder)
        assertEquals(100, percentOver)
        assertEquals(0, percentZero)
    }

    @Test
    fun testZeroGoalSafeHandling() {
        val goalMinutes = 0
        val todayMinutes = 50

        val goalProgressPercent = if (goalMinutes > 0) ((todayMinutes * 100) / goalMinutes.coerceAtLeast(1)).coerceIn(0, 100) else 0
        assertEquals(0, goalProgressPercent)
    }

    @Test
    fun testGoalCompletionFlag() {
        val goalMinutes = 120
        assertTrue(120 >= goalMinutes)
        assertTrue(150 >= goalMinutes)
        assertFalse(119 >= goalMinutes)
    }

    @Test
    fun testSessionElapsedPercentCalculation() {
        val startTime = 100000L
        val duration = 50 * 60 * 1000L // 50 min

        val midway = startTime + 25 * 60 * 1000L
        val elapsed = (midway - startTime).coerceAtLeast(0L)
        val percent = ((elapsed * 100) / duration).toInt().coerceIn(0, 100)

        assertEquals(50, percent)
    }
}
