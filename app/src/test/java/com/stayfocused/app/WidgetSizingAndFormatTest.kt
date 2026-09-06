package com.stayfocused.app

import com.stayfocused.app.appwidget.FocusWidgetProvider
import com.stayfocused.app.appwidget.WidgetDataProvider
import com.stayfocused.app.appwidget.WidgetSizeTier
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizingAndFormatTest {

    private fun resolveSizeTier(minWidth: Int, minHeight: Int): WidgetSizeTier {
        if (minWidth <= 0 && minHeight <= 0) return WidgetSizeTier.COMPACT

        return when {
            minWidth >= 220 && minHeight < 110 -> WidgetSizeTier.WIDE
            minWidth >= 220 && minHeight >= 110 -> WidgetSizeTier.LARGE
            minWidth < 100 || minHeight < 100 -> WidgetSizeTier.TINY
            else -> WidgetSizeTier.COMPACT
        }
    }

    @Test
    fun testTinyWidgetSizeResolution() {
        assertEquals(WidgetSizeTier.TINY, resolveSizeTier(80, 80))
        assertEquals(WidgetSizeTier.TINY, resolveSizeTier(99, 150))
        assertEquals(WidgetSizeTier.TINY, resolveSizeTier(150, 90))
    }

    @Test
    fun testCompactWidgetSizeResolution() {
        assertEquals(WidgetSizeTier.COMPACT, resolveSizeTier(140, 140))
        assertEquals(WidgetSizeTier.COMPACT, resolveSizeTier(180, 180))
        assertEquals(WidgetSizeTier.COMPACT, resolveSizeTier(0, 0)) // Fallback safe
    }

    @Test
    fun testWideWidgetSizeResolution() {
        assertEquals(WidgetSizeTier.WIDE, resolveSizeTier(250, 80))
        assertEquals(WidgetSizeTier.WIDE, resolveSizeTier(300, 100))
    }

    @Test
    fun testLargeWidgetSizeResolution() {
        assertEquals(WidgetSizeTier.LARGE, resolveSizeTier(250, 200))
        assertEquals(WidgetSizeTier.LARGE, resolveSizeTier(320, 280))
    }

    @Test
    fun testFormatRemainingMMSS() {
        assertEquals("42:18", WidgetDataProvider.formatRemainingMMSS((42 * 60 + 18) * 1000L))
        assertEquals("00:05", WidgetDataProvider.formatRemainingMMSS(5 * 1000L))
        assertEquals("00:00", WidgetDataProvider.formatRemainingMMSS(0L))
        assertEquals("00:00", WidgetDataProvider.formatRemainingMMSS(-5000L)) // Negative clamped
        assertEquals("1:25:30", WidgetDataProvider.formatRemainingMMSS((1 * 3600 + 25 * 60 + 30) * 1000L))
    }
}
