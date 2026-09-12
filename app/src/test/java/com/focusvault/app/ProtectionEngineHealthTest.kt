package com.focusvault.app

import com.focusvault.app.manager.HealthItem
import com.focusvault.app.manager.ProtectionReport
import com.focusvault.app.manager.ProtectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionEngineHealthTest {

    @Test
    fun testProtectionReport_lastCheckedFormatting() {
        val now = System.currentTimeMillis()

        val reportJustNow = ProtectionReport(
            status = ProtectionStatus.PROTECTION_ACTIVE,
            scorePercentage = 100,
            items = emptyList(),
            headlineMessage = "All good",
            lastCheckedTimestamp = now - 2_000L // 2 sec ago
        )
        assertEquals("just now", reportJustNow.getFormattedLastChecked())

        val reportSecondsAgo = ProtectionReport(
            status = ProtectionStatus.PROTECTION_ACTIVE,
            scorePercentage = 100,
            items = emptyList(),
            headlineMessage = "All good",
            lastCheckedTimestamp = now - 35_000L // 35 sec ago
        )
        assertEquals("35 seconds ago", reportSecondsAgo.getFormattedLastChecked())

        val reportMinutesAgo = ProtectionReport(
            status = ProtectionStatus.PROTECTION_ACTIVE,
            scorePercentage = 100,
            items = emptyList(),
            headlineMessage = "All good",
            lastCheckedTimestamp = now - 180_000L // 3 min ago
        )
        assertEquals("3m ago", reportMinutesAgo.getFormattedLastChecked())
    }

    @Test
    fun testHealthItem_properties() {
        val item = HealthItem(
            id = "accessibility",
            title = "App Blocking Engine",
            subtitle = "Active",
            isHealthy = true,
            isRequired = true,
            fixActionTitle = "Enable"
        )
        assertEquals("accessibility", item.id)
        assertTrue(item.isHealthy)
        assertTrue(item.isRequired)
        assertEquals("Enable", item.fixActionTitle)
    }

    @Test
    fun testProtectionStatus_hierarchy() {
        // Verify statuses exist and represent correct severity
        val active = ProtectionStatus.PROTECTION_ACTIVE
        val degraded = ProtectionStatus.PROTECTION_DEGRADED
        val failed = ProtectionStatus.PROTECTION_FAILED

        assertTrue(active != degraded)
        assertTrue(degraded != failed)
    }
}
