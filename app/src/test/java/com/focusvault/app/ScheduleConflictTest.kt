package com.focusvault.app

import com.focusvault.app.ui.SchedulesActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleConflictTest {

    @Test
    fun testDaysOverlap_dailyWithAny_returnsTrue() {
        assertTrue(SchedulesActivity.daysOverlap("Daily", "Weekdays"))
        assertTrue(SchedulesActivity.daysOverlap("Daily", "Weekends"))
        assertTrue(SchedulesActivity.daysOverlap("Weekdays", "Daily"))
        assertTrue(SchedulesActivity.daysOverlap("Daily", "Daily"))
    }

    @Test
    fun testDaysOverlap_weekdaysAndWeekends_returnsFalse() {
        assertFalse(SchedulesActivity.daysOverlap("Weekdays (Mon-Fri)", "Weekends (Sat-Sun)"))
        assertFalse(SchedulesActivity.daysOverlap("Weekends", "Weekdays"))
    }

    @Test
    fun testDaysOverlap_sameDays_returnsTrue() {
        assertTrue(SchedulesActivity.daysOverlap("Weekdays (Mon-Fri)", "Weekdays"))
        assertTrue(SchedulesActivity.daysOverlap("Weekends", "Weekends"))
    }

    @Test
    fun testTimesOverlap_overlappingStandardRanges() {
        // 09:00 (540m) for 120m (ends 660m) vs 10:00 (600m) for 60m (ends 660m)
        assertTrue(SchedulesActivity.timesOverlap(540, 120, 600, 60))

        // 13:00 (780m) for 60m (ends 840m) vs 13:30 (810m) for 60m (ends 870m)
        assertTrue(SchedulesActivity.timesOverlap(780, 60, 810, 60))
    }

    @Test
    fun testTimesOverlap_nonOverlappingStandardRanges() {
        // 09:00 (540m) for 60m (ends 600m) vs 11:00 (660m) for 60m (ends 720m)
        assertFalse(SchedulesActivity.timesOverlap(540, 60, 660, 60))

        // Sequential adjacent schedules: 09:00 (540m) for 60m (ends 600m) vs 10:00 (600m) for 60m
        assertFalse(SchedulesActivity.timesOverlap(540, 60, 600, 60))
    }

    @Test
    fun testTimesOverlap_midnightCrossingSchedules() {
        // 22:00 (1320m) for 240m (ends at 1560m = 02:00 next day)
        // vs 01:00 (60m) for 120m (ends at 180m = 03:00)
        assertTrue(SchedulesActivity.timesOverlap(1320, 240, 60, 120))

        // vs 23:00 (1380m) for 60m (ends at 1440m = 00:00)
        assertTrue(SchedulesActivity.timesOverlap(1320, 240, 1380, 60))

        // vs 03:00 (180m) for 60m (ends at 240m = 04:00) -> ends after 02:00, no overlap
        assertFalse(SchedulesActivity.timesOverlap(1320, 240, 180, 60))
    }
}
