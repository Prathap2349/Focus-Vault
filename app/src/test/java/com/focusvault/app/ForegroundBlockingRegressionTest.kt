package com.focusvault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Simulates the Accessibility Service foreground package blocking engine to verify that:
 * 1. `overlayCurrentlyShown` is NOT a stale lock.
 * 2. Moving to Home launcher, Recent Apps, or allowed app clears `lastBlockedPackage = null`.
 * 3. Opening Chrome -> Home -> Chrome ALWAYS triggers the overlay again.
 * 4. Switching Chrome -> YouTube -> Instagram -> Chrome triggers the overlay for each app transition.
 * 5. Re-triggering Chrome after >200ms when overlay was dismissed re-launches overlay.
 * 6. Essential telephony/call packages update foreground state and clear blocked state.
 * 7. Sensitive system packages (Settings) are blocked in Strict Mode but allowed in normal mode.
 */
class ForegroundBlockingRegressionTest {

    private var isSessionActive = true
    private var isEmergencyPause = false
    private var isStrict = false

    private val blockedPackages = setOf("com.android.chrome", "com.google.android.youtube", "com.instagram.android")
    private val essentialCallPackages = setOf("com.android.phone", "com.google.android.dialer", "com.android.server.telecom")
    private val sensitiveSystemPackages = setOf("com.android.settings", "com.google.android.packageinstaller")

    private val ownPackage = "com.focusvault.app"
    private val launcherPackage = "com.sec.android.app.launcher"
    private val systemUiPackage = "com.android.systemui"

    private var currentForegroundPackage: String? = null
    private var lastBlockedPackage: String? = null
    private var lastOverlayLaunchTime = 0L
    private var overlayLaunchCount = 0
    private var lastLaunchedPackage: String? = null

    @Before
    fun setUp() {
        isSessionActive = true
        isEmergencyPause = false
        isStrict = false
        currentForegroundPackage = null
        lastBlockedPackage = null
        lastOverlayLaunchTime = 0L
        overlayLaunchCount = 0
        lastLaunchedPackage = null
    }

    /**
     * Simulates AppBlockAccessibilityService.onAccessibilityEvent(packageName, timestamp)
     */
    private fun simulateAccessibilityEvent(packageName: String, timestamp: Long): Boolean {
        if (packageName in essentialCallPackages) {
            currentForegroundPackage = packageName
            lastBlockedPackage = null
            return false
        }

        if (packageName == ownPackage) {
            // Our overlay or main app in foreground - state is preserved
            return false
        }

        currentForegroundPackage = packageName

        if (!isSessionActive || isEmergencyPause) {
            lastBlockedPackage = null
            return false
        }

        val shouldBlock = (packageName in blockedPackages) || (isStrict && packageName in sensitiveSystemPackages)

        if (shouldBlock) {
            // Suppress duplicate events for SAME blocked package within 200ms
            if (lastBlockedPackage == packageName && (timestamp - lastOverlayLaunchTime) < 200L) {
                return false
            }

            lastBlockedPackage = packageName
            lastOverlayLaunchTime = timestamp
            overlayLaunchCount++
            lastLaunchedPackage = packageName
            return true // Overlay launched
        } else {
            // Allowed app, launcher, home, systemui
            lastBlockedPackage = null
            return false
        }
    }

    @Test
    fun testHomeToChromeRegression() {
        var time = 1000L

        // 1. User opens Chrome
        val blocked1 = simulateAccessibilityEvent("com.android.chrome", time)
        assertTrue("Chrome should be blocked initially", blocked1)
        assertEquals("com.android.chrome", lastBlockedPackage)
        assertEquals(1, overlayLaunchCount)

        // 2. Overlay opens (our app)
        time += 50
        val blockedOverlay = simulateAccessibilityEvent(ownPackage, time)
        assertFalse(blockedOverlay)
        assertEquals("com.android.chrome", lastBlockedPackage)

        // 3. User presses Home button (Launcher opens)
        time += 500
        val blockedHome = simulateAccessibilityEvent(launcherPackage, time)
        assertFalse("Home screen is allowed", blockedHome)
        assertNull("Moving Home MUST clear lastBlockedPackage to null", lastBlockedPackage)

        // 4. User opens Chrome AGAIN
        time += 1000
        val blocked2 = simulateAccessibilityEvent("com.android.chrome", time)
        assertTrue("Chrome MUST be blocked again after Home transition!", blocked2)
        assertEquals("com.android.chrome", lastBlockedPackage)
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
        assertNull("System UI / Recents MUST clear lastBlockedPackage", lastBlockedPackage)

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
    fun testDuplicateEventSuppressionWithin200ms() {
        val time = 1000L

        // Event 1 for Chrome
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))

        // Event 2 for Chrome 50ms later (rapid window state change)
        assertFalse("Duplicate event within 200ms must be suppressed", simulateAccessibilityEvent("com.android.chrome", time + 50))

        assertEquals(1, overlayLaunchCount)
    }

    @Test
    fun testRepeatedEventAfter200msReLaunchesOverlay() {
        val time = 1000L

        // Event 1 for Chrome
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))

        // Event 2 for Chrome 300ms later (e.g. user attempts to stay in Chrome)
        assertTrue("Event after >200ms must re-trigger overlay", simulateAccessibilityEvent("com.android.chrome", time + 300))

        assertEquals(2, overlayLaunchCount)
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
        assertNull(lastBlockedPackage)
    }

    @Test
    fun testEssentialPhoneCallsNeverBlocked() {
        var time = 1000L

        // User receives phone call
        val blockedPhone = simulateAccessibilityEvent("com.android.phone", time)
        assertFalse("Phone calls must never be blocked", blockedPhone)
        assertEquals("com.android.phone", currentForegroundPackage)
        assertNull(lastBlockedPackage)

        // User then opens Chrome -> blocked
        time += 500
        assertTrue(simulateAccessibilityEvent("com.android.chrome", time))
    }

    @Test
    fun testStrictModeSettingsBlocking() {
        var time = 1000L

        // Normal mode: Settings allowed
        isStrict = false
        assertFalse("Settings allowed in normal mode", simulateAccessibilityEvent("com.android.settings", time))

        // Strict mode: Settings blocked
        time += 500
        isStrict = true
        assertTrue("Settings blocked in Strict Mode", simulateAccessibilityEvent("com.android.settings", time))
    }
}
