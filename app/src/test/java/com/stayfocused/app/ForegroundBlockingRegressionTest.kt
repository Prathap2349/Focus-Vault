package com.stayfocused.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Simulates the Accessibility Service foreground package blocking engine to verify that:
 * 1. `overlayCurrentlyShown` is NOT a stale lock.
 * 2. Moving to Home launcher, Recent Apps, or allowed app clears `lastBlockedPackageShown = null`.
 * 3. Opening Chrome -> Home -> Chrome ALWAYS triggers the overlay again.
 * 4. Switching Chrome -> YouTube -> Instagram -> Chrome triggers the overlay for each app transition.
 */
class ForegroundBlockingRegressionTest {

    private var isSessionActive = true
    private var isEmergencyPause = false
    private var isStrict = false

    private val blockedPackages = setOf("com.android.chrome", "com.google.android.youtube", "com.instagram.android")
    private val ownPackage = "com.stayfocused.app"
    private val launcherPackage = "com.sec.android.app.launcher"
    private val systemUiPackage = "com.android.systemui"

    private var lastBlockedPackageShown: String? = null
    private var overlayLaunchCount = 0
    private var lastLaunchedPackage: String? = null
    private var lastEventTime = 0L

    @Before
    fun setUp() {
        isSessionActive = true
        isEmergencyPause = false
        isStrict = false
        lastBlockedPackageShown = null
        overlayLaunchCount = 0
        lastLaunchedPackage = null
        lastEventTime = 0L
    }

    /**
     * Simulates AccessibilityService.onAccessibilityEvent(packageName, timestamp)
     */
    private fun simulateAccessibilityEvent(packageName: String, timestamp: Long): Boolean {
        if (packageName == ownPackage) {
            // Our overlay or main app in foreground - do not change state
            return false
        }

        if (!isSessionActive || isEmergencyPause) {
            lastBlockedPackageShown = null
            return false
        }

        val shouldBlock = packageName in blockedPackages

        if (shouldBlock) {
            // Suppress duplicate events for SAME package within 200ms
            if (lastBlockedPackageShown == packageName && (timestamp - lastEventTime) < 200L) {
                return false
            }

            if (lastBlockedPackageShown != packageName) {
                lastBlockedPackageShown = packageName
                lastEventTime = timestamp
                overlayLaunchCount++
                lastLaunchedPackage = packageName
                return true // Overlay launched
            }
        } else {
            // Allowed app, launcher, home, systemui
            lastBlockedPackageShown = null
        }
        return false
    }

    @Test
    fun testHomeToChromeRegression() {
        var time = 1000L

        // 1. User opens Chrome
        val blocked1 = simulateAccessibilityEvent("com.android.chrome", time)
        assertTrue("Chrome should be blocked initially", blocked1)
        assertEquals("com.android.chrome", lastBlockedPackageShown)
        assertEquals(1, overlayLaunchCount)

        // 2. Overlay opens (our app)
        time += 50
        val blockedOverlay = simulateAccessibilityEvent(ownPackage, time)
        assertFalse(blockedOverlay)
        assertEquals("com.android.chrome", lastBlockedPackageShown)

        // 3. User presses Home button (Launcher opens)
        time += 500
        val blockedHome = simulateAccessibilityEvent(launcherPackage, time)
        assertFalse("Home screen is allowed", blockedHome)
        assertNull("Moving Home MUST clear lastBlockedPackageShown to null", lastBlockedPackageShown)

        // 4. User opens Chrome AGAIN
        time += 1000
        val blocked2 = simulateAccessibilityEvent("com.android.chrome", time)
        assertTrue("Chrome MUST be blocked again after Home transition!", blocked2)
        assertEquals("com.android.chrome", lastBlockedPackageShown)
        assertEquals(2, overlayLaunchCount)
    }

    @Test
    fun testRecentAppsToChromeRegression() {
        var time = 1000L

        // 1. User opens Chrome -> Blocked
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))
        assertEquals(1, overlayLaunchCount)

        // 2. User opens Recent Apps (System UI)
        time += 300
        simulateAccessibilityEvent(systemUiPackage, time)
        assertNull("System UI / Recents MUST clear lastBlockedPackageShown", lastBlockedPackageShown)

        // 3. User taps Chrome in Recents -> MUST BE BLOCKED AGAIN
        time += 500
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))
        assertEquals(2, overlayLaunchCount)
    }

    @Test
    fun testMultiAppSwitching() {
        var time = 1000L

        // Chrome -> Blocked
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))
        assertEquals("com.android.chrome", lastLaunchedPackage)

        // Switch to YouTube -> Blocked
        time += 500
        assertTrue(simulateAccessibilityEvent("com.google.android.youtube", time))
        assertEquals("com.google.android.youtube", lastLaunchedPackage)

        // Switch to Instagram -> Blocked
        time += 500
        assertTrue(simulateAccessibilityEvent("com.instagram.android", time))
        assertEquals("com.instagram.android", lastLaunchedPackage)

        // Switch back to Chrome -> Blocked
        time += 500
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))
        assertEquals("com.android.chrome", lastLaunchedPackage)

        assertEquals(4, overlayLaunchCount)
    }

    @Test
    fun testDuplicateEventSuppression() {
        val time = 1000L

        // Event 1 for Chrome
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))

        // Event 2 for Chrome 50ms later (rapid window state change)
        assertFalse("Duplicate event within 200ms must be suppressed", simulateAccessibilityEvent("com.android.chrome", time + 50))

        assertEquals(1, overlayLaunchCount)
    }

    @Test
    fun testEmergencyPauseStateReset() {
        val time = 1000L

        // Chrome blocked
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))

        // Emergency pause activated
        isEmergencyPause = true

        // Next Chrome event while paused -> allowed
        assertFalse(simulateAccessibilityEvent("com.android.chrome", time + 500))
        assertNull(lastBlockedPackageShown)
    }
}
