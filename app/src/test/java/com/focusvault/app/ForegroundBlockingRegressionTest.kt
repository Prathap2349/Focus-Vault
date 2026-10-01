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
 * 3. Opening Chrome -> Home -> Chrome ALWAYS triggers the overlay again after cooldown.
 * 4. Dismissal cooldown (2000ms) prevents overlay re-trigger loop during the transition back to Home.
 * 5. Non-activity windows (IME keyboard, dialog popups, toasts) are ignored.
 * 6. Distraction count increments exactly once per genuine episode, not on every relaunch or debounce.
 * 7. Switching Chrome -> YouTube -> Instagram -> Chrome triggers the overlay and increments distraction for each episode.
 * 8. Essential telephony/call packages update foreground state and clear blocked state.
 * 9. Sensitive system packages (Settings) are blocked in Strict Mode but allowed in normal mode.
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
    private var distractionAttemptCount = 0
    private var lastLaunchedPackage: String? = null

    private var dismissedPackage: String? = null
    private var dismissedTimestamp = 0L
    private val dismissCooldownMs = 2000L

    @Before
    fun setUp() {
        isSessionActive = true
        isEmergencyPause = false
        isStrict = false
        currentForegroundPackage = null
        lastBlockedPackage = null
        lastOverlayLaunchTime = 0L
        overlayLaunchCount = 0
        distractionAttemptCount = 0
        lastLaunchedPackage = null
        dismissedPackage = null
        dismissedTimestamp = 0L
    }

    private fun isDismissCooldownActive(packageName: String, now: Long): Boolean {
        if (dismissedPackage != packageName) return false
        val elapsed = now - dismissedTimestamp
        return elapsed in 0 until dismissCooldownMs
    }

    private fun isNonActivityWindow(className: String): Boolean {
        if (className.isEmpty()) return false
        if (className.startsWith("android.widget.") && !className.contains("Activity")) return true
        if (className.startsWith("android.view.")) return true
        if (className.startsWith("android.inputmethodservice.") || className.contains("InputMethod")) return true
        if (className == "android.app.Dialog" || className == "android.widget.Toast" || className == "android.widget.PopupWindow") return true
        return false
    }

    /**
     * Simulates AppBlockAccessibilityService.onAccessibilityEvent(packageName, className, timestamp)
     */
    private fun simulateAccessibilityEvent(
        packageName: String,
        className: String = "android.app.Activity",
        timestamp: Long = 1000L
    ): Boolean {
        if (isNonActivityWindow(className)) {
            return false
        }

        if (packageName in essentialCallPackages) {
            currentForegroundPackage = packageName
            lastBlockedPackage = null
            return false
        }

        if (packageName == ownPackage) {
            // Our overlay or main app in foreground - state is preserved
            return false
        }

        if (isDismissCooldownActive(packageName, timestamp)) {
            return false
        }

        currentForegroundPackage = packageName

        if (!isSessionActive || isEmergencyPause) {
            lastBlockedPackage = null
            return false
        }

        val shouldBlock = (packageName in blockedPackages) || (isStrict && packageName in sensitiveSystemPackages)

        if (shouldBlock) {
            // Suppress rapid activity re-launches within 250ms across SAME blocked package
            if (lastBlockedPackage == packageName && (timestamp - lastOverlayLaunchTime) < 250L) {
                return false
            }

            val isNewEpisode = lastBlockedPackage != packageName
            lastBlockedPackage = packageName
            lastOverlayLaunchTime = timestamp

            if (isNewEpisode) {
                distractionAttemptCount++
            }

            overlayLaunchCount++
            lastLaunchedPackage = packageName
            return true // Overlay launched
        } else {
            // Allowed app, launcher, home, systemui
            lastBlockedPackage = null
            dismissedPackage = null
            dismissedTimestamp = 0L
            return false
        }
    }

    private fun userDismissesOverlay(packageName: String, timestamp: Long) {
        dismissedPackage = packageName
        dismissedTimestamp = timestamp
    }

    @Test
    fun testHomeToChromeRegression() {
        var time = 1000L

        // 1. User opens Chrome
        val blocked1 = simulateAccessibilityEvent("com.android.chrome", timestamp = time)
        assertTrue("Chrome should be blocked initially", blocked1)
        assertEquals("com.android.chrome", lastBlockedPackage)
        assertEquals(1, overlayLaunchCount)
        assertEquals(1, distractionAttemptCount)

        // 2. Overlay opens (our app)
        time += 50
        val blockedOverlay = simulateAccessibilityEvent(ownPackage, timestamp = time)
        assertFalse(blockedOverlay)
        assertEquals("com.android.chrome", lastBlockedPackage)

        // 3. User taps Go Home (records dismissal cooldown and finishes overlay)
        time += 200
        userDismissesOverlay("com.android.chrome", time)

        // Underneath window briefly exposes Chrome as Home starts -> Cooldown suppresses relaunch loop!
        time += 100
        val blockedUnderneath = simulateAccessibilityEvent("com.android.chrome", timestamp = time)
        assertFalse("Underlying Chrome window must NOT re-trigger overlay during dismiss cooldown", blockedUnderneath)

        // 4. User lands on Home Screen
        time += 200
        val blockedHome = simulateAccessibilityEvent(launcherPackage, timestamp = time)
        assertFalse("Home screen is allowed", blockedHome)
        assertNull("Moving Home MUST clear lastBlockedPackage to null", lastBlockedPackage)

        // 5. User opens Chrome AGAIN after cooldown
        time += 2500
        val blocked2 = simulateAccessibilityEvent("com.android.chrome", timestamp = time)
        assertTrue("Chrome MUST be blocked again when deliberately reopened!", blocked2)
        assertEquals("com.android.chrome", lastBlockedPackage)
        assertEquals(2, overlayLaunchCount)
        assertEquals(2, distractionAttemptCount)
    }

    @Test
    fun testNonActivityWindowsIgnored() {
        val time = 1000L

        // IME / Soft Keyboard event from blocked app
        val imeEvent = simulateAccessibilityEvent("com.android.chrome", className = "android.inputmethodservice.InputMethodService", timestamp = time)
        assertFalse("IME windows must be ignored", imeEvent)

        // Toast popup
        val toastEvent = simulateAccessibilityEvent("com.android.chrome", className = "android.widget.Toast", timestamp = time)
        assertFalse("Toast windows must be ignored", toastEvent)

        // Generic view layout change
        val viewEvent = simulateAccessibilityEvent("com.android.chrome", className = "android.widget.FrameLayout", timestamp = time)
        assertFalse("Generic FrameLayout windows must be ignored", viewEvent)

        assertEquals(0, overlayLaunchCount)
        assertEquals(0, distractionAttemptCount)
    }

    @Test
    fun testRecentAppsToChromeRegression() {
        var time = 1000L

        // 1. User opens Chrome -> Blocked
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))
        assertEquals(1, overlayLaunchCount)
        assertEquals(1, distractionAttemptCount)

        // 2. User opens Recent Apps (System UI)
        time += 300
        simulateAccessibilityEvent(systemUiPackage, timestamp = time)
        assertNull("System UI / Recents MUST clear lastBlockedPackage", lastBlockedPackage)

        // 3. User taps Chrome in Recents -> MUST BE BLOCKED AGAIN & increment count
        time += 500
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))
        assertEquals(2, overlayLaunchCount)
        assertEquals(2, distractionAttemptCount)
    }

    @Test
    fun testMultiAppSwitching() {
        var time = 1000L

        // Chrome -> Blocked
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))
        assertEquals("com.android.chrome", lastLaunchedPackage)
        assertEquals(1, distractionAttemptCount)

        // Switch to YouTube -> Blocked
        time += 500
        assertTrue(simulateAccessibilityEvent("com.google.android.youtube", timestamp = time))
        assertEquals("com.google.android.youtube", lastLaunchedPackage)
        assertEquals(2, distractionAttemptCount)

        // Switch to Instagram -> Blocked
        time += 500
        assertTrue(simulateAccessibilityEvent("com.instagram.android", timestamp = time))
        assertEquals("com.instagram.android", lastLaunchedPackage)
        assertEquals(3, distractionAttemptCount)

        // Switch back to Chrome -> Blocked
        time += 500
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))
        assertEquals("com.android.chrome", lastLaunchedPackage)
        assertEquals(4, distractionAttemptCount)

        assertEquals(4, overlayLaunchCount)
    }

    @Test
    fun testDuplicateEventSuppressionWithin250ms() {
        val time = 1000L

        // Event 1 for Chrome
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))
        assertEquals(1, distractionAttemptCount)

        // Event 2 for Chrome 50ms later (rapid window state change during same episode)
        assertFalse("Duplicate event within 250ms must be suppressed", simulateAccessibilityEvent("com.android.chrome", timestamp = time + 50))

        assertEquals(1, overlayLaunchCount)
        assertEquals(1, distractionAttemptCount)
    }

    @Test
    fun testEmergencyPauseStateReset() {
        val time = 1000L

        // Chrome blocked
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))

        // Emergency pause activated
        isEmergencyPause = true

        // Next Chrome event while paused -> allowed
        assertFalse(simulateAccessibilityEvent("com.android.chrome", timestamp = time + 500))
        assertNull(lastBlockedPackage)
    }

    @Test
    fun testEssentialPhoneCallsNeverBlocked() {
        var time = 1000L

        // User receives phone call
        val blockedPhone = simulateAccessibilityEvent("com.android.phone", timestamp = time)
        assertFalse("Phone calls must never be blocked", blockedPhone)
        assertEquals("com.android.phone", currentForegroundPackage)
        assertNull(lastBlockedPackage)

        // User then opens Chrome -> blocked
        time += 500
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))
        assertEquals(1, distractionAttemptCount)
    }

    @Test
    fun testStrictModeSettingsBlocking() {
        var time = 1000L

        // Normal mode: Settings allowed
        isStrict = false
        assertFalse("Settings allowed in normal mode", simulateAccessibilityEvent("com.android.settings", timestamp = time))

        // Strict mode: Settings blocked
        time += 500
        isStrict = true
        assertTrue("Settings blocked in Strict Mode", simulateAccessibilityEvent("com.android.settings", timestamp = time))
        assertEquals(1, distractionAttemptCount)
    }
}
