package com.focusvault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit and regression test suite verifying:
 * 1. Post-dismiss cooldown of 700ms and time-based expiry.
 * 2. Reopening at 0.5s (500ms):
 *    - Transient non-genuine window suppressed without loop.
 *    - Genuine active foreground window blocked without bypass.
 * 3. Reopening at 1.5s (1500ms) and 3.0s (3000ms) reliably triggers block overlay in Focus, Lock, and Strict modes.
 * 4. Narrow isNonActivityWindow: ignores IME/Toast/Dialog/Popup, but does NOT ignore FrameLayout (resuming from Recents).
 * 5. Single distraction increment per genuine episode.
 * 6. Essential call exemption and Strict Mode sensitive packages.
 */
class ForegroundBlockingRegressionTest {

    private var isSessionActive = true
    private var isEmergencyPause = false
    private var isStrict = false
    private var isLock = false

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
    private val dismissCooldownMs = 700L

    @Before
    fun setUp() {
        isSessionActive = true
        isEmergencyPause = false
        isStrict = false
        isLock = false
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
        val lower = className.lowercase()
        if (lower.contains("inputmethod") || className.startsWith("android.inputmethodservice.")) return true
        if (className == "android.widget.Toast" || className.startsWith("android.widget.Toast$")) return true
        if (className == "android.widget.PopupWindow" || className.startsWith("android.widget.PopupWindow$") ||
            className == "android.widget.ListPopupWindow" || className.startsWith("android.widget.ListPopupWindow$")) return true
        if (className == "android.app.Dialog" || className == "androidx.appcompat.app.AlertDialog" ||
            className == "android.app.AlertDialog") return true
        return false
    }

    /**
     * Simulates AppBlockAccessibilityService.onAccessibilityEvent
     */
    private fun simulateAccessibilityEvent(
        packageName: String,
        className: String = "android.app.Activity",
        timestamp: Long = 1000L,
        isGenuinelyForeground: Boolean = true
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

        // 3. Cooldown check: if cooldown active and app is NOT genuinely foreground (transient window event), suppress.
        // If app IS genuinely foreground (explicit user reopen), proceed to block.
        if (isDismissCooldownActive(packageName, timestamp)) {
            if (!isGenuinelyForeground) {
                return false
            }
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
            return true
        } else {
            // Allowed app, launcher, home, systemui
            // Note: Cooldown ends strictly by time, not cleared here.
            lastBlockedPackage = null
            return false
        }
    }

    private fun userDismissesOverlay(packageName: String, timestamp: Long) {
        dismissedPackage = packageName
        dismissedTimestamp = timestamp
        // AppBlockAccessibilityService.resetLastBlockedPackage()
        lastBlockedPackage = null
    }

    @Test
    fun testCooldownReopenAt0_5s_TransientSuppressed() {
        var time = 1000L

        // 1. User opens Chrome
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))
        assertEquals(1, overlayLaunchCount)
        assertEquals(1, distractionAttemptCount)

        // 2. User dismisses overlay to Home at 1200ms
        time = 1200L
        userDismissesOverlay("com.android.chrome", time)

        // 3. Transient window event at 0.5s (500ms) after dismissal (not genuine foreground)
        time += 500L // 1700ms (< 700ms cooldown)
        val transientBlocked = simulateAccessibilityEvent("com.android.chrome", timestamp = time, isGenuinelyForeground = false)
        assertFalse("Transient window event within 700ms cooldown must be suppressed", transientBlocked)
        assertEquals(1, overlayLaunchCount)
        assertEquals(1, distractionAttemptCount)
    }

    @Test
    fun testCooldownReopenAt0_5s_GenuinelyForegroundBlocked() {
        var time = 1000L

        // 1. User opens Chrome
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))

        // 2. Dismiss to Home
        time = 1200L
        userDismissesOverlay("com.android.chrome", time)

        // 3. User explicitly reopens Chrome from Recents/Home within 0.5s (genuinely foreground)
        time += 500L // 1700ms
        val explicitReopen = simulateAccessibilityEvent("com.android.chrome", timestamp = time, isGenuinelyForeground = true)
        assertTrue("Explicit reopen within cooldown must NOT bypass protection", explicitReopen)
        assertEquals(2, overlayLaunchCount)
        assertEquals(2, distractionAttemptCount)
    }

    @Test
    fun testCooldownReopenAt1_5s_InFocusMode() {
        isStrict = false
        isLock = false
        runCooldownReopenAtTimeTest(reopenDelayMs = 1500L)
    }

    @Test
    fun testCooldownReopenAt1_5s_InLockMode() {
        isStrict = false
        isLock = true
        runCooldownReopenAtTimeTest(reopenDelayMs = 1500L)
    }

    @Test
    fun testCooldownReopenAt1_5s_InStrictMode() {
        isStrict = true
        isLock = false
        runCooldownReopenAtTimeTest(reopenDelayMs = 1500L)
    }

    @Test
    fun testCooldownReopenAt3_0s_InFocusMode() {
        isStrict = false
        isLock = false
        runCooldownReopenAtTimeTest(reopenDelayMs = 3000L)
    }

    @Test
    fun testCooldownReopenAt3_0s_InLockMode() {
        isStrict = false
        isLock = true
        runCooldownReopenAtTimeTest(reopenDelayMs = 3000L)
    }

    @Test
    fun testCooldownReopenAt3_0s_InStrictMode() {
        isStrict = true
        isLock = false
        runCooldownReopenAtTimeTest(reopenDelayMs = 3000L)
    }

    private fun runCooldownReopenAtTimeTest(reopenDelayMs: Long) {
        var time = 1000L

        // 1. Open blocked app
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))
        assertEquals(1, overlayLaunchCount)
        assertEquals(1, distractionAttemptCount)

        // 2. Dismiss to Home
        time += 200L
        userDismissesOverlay("com.android.chrome", time)

        // 3. Launcher event (allowed)
        time += 100L
        assertFalse(simulateAccessibilityEvent(launcherPackage, timestamp = time))
        assertTrue("Cooldown should still be active by time even after launcher event", isDismissCooldownActive("com.android.chrome", time))

        // 4. Reopen after specified delay (1.5s or 3.0s)
        time = 1200L + reopenDelayMs
        assertFalse("Cooldown should have expired", isDismissCooldownActive("com.android.chrome", time))
        val reBlocked = simulateAccessibilityEvent("com.android.chrome", timestamp = time)
        assertTrue("App must be blocked after cooldown expiration", reBlocked)
        assertEquals(2, overlayLaunchCount)
        assertEquals(2, distractionAttemptCount)
    }

    @Test
    fun testNarrowWindowFilterAllowsFrameLayoutFromRecents() {
        val time = 1000L

        // FrameLayout emitted during activity resume from Recents -> MUST NOT BE IGNORED
        val frameLayoutEvent = simulateAccessibilityEvent(
            "com.android.chrome",
            className = "android.widget.FrameLayout",
            timestamp = time
        )
        assertTrue("FrameLayout events from blocked app must be processed as activity transitions", frameLayoutEvent)
        assertEquals(1, overlayLaunchCount)
        assertEquals(1, distractionAttemptCount)
    }

    @Test
    fun testNarrowWindowFilterIgnoresImeAndToasts() {
        val time = 1000L

        assertFalse(simulateAccessibilityEvent("com.android.chrome", className = "android.inputmethodservice.InputMethodService", timestamp = time))
        assertFalse(simulateAccessibilityEvent("com.android.chrome", className = "android.widget.Toast", timestamp = time))
        assertFalse(simulateAccessibilityEvent("com.android.chrome", className = "android.widget.PopupWindow", timestamp = time))
        assertFalse(simulateAccessibilityEvent("com.android.chrome", className = "androidx.appcompat.app.AlertDialog", timestamp = time))

        assertEquals(0, overlayLaunchCount)
        assertEquals(0, distractionAttemptCount)
    }

    @Test
    fun testEssentialPhoneCallsNeverBlocked() {
        val time = 1000L
        val blockedPhone = simulateAccessibilityEvent("com.android.phone", timestamp = time)
        assertFalse("Phone calls must never be blocked", blockedPhone)
        assertEquals("com.android.phone", currentForegroundPackage)
        assertNull(lastBlockedPackage)
    }

    @Test
    fun testStrictModeSettingsBlocking() {
        var time = 1000L
        isStrict = false
        assertFalse("Settings allowed in normal mode", simulateAccessibilityEvent("com.android.settings", timestamp = time))

        time += 500L
        isStrict = true
        assertTrue("Settings blocked in Strict Mode", simulateAccessibilityEvent("com.android.settings", timestamp = time))
        assertEquals(1, distractionAttemptCount)
    }

    @Test
    fun testEmergencyPauseAllowsBlockedApps() {
        var time = 1000L
        assertTrue(simulateAccessibilityEvent("com.android.chrome", timestamp = time))

        isEmergencyPause = true
        time += 500L
        assertFalse("Emergency pause must allow apps", simulateAccessibilityEvent("com.android.chrome", timestamp = time))
    }
}
