package com.focusvault.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityWindowInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.focusvault.app.manager.ProtectionEngine
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.ui.BlockOverlayActivity
import com.focusvault.app.util.PrefsManager

/**
 * Authoritative foreground package watcher and enforcement engine for Focus Vault.
 * 
 * CORE BLOCKING RULES:
 * 1. The current foreground package is the SINGLE SOURCE OF TRUTH for blocking decisions.
 * 2. Narrow window filtering ignores only true non-activity components (IME, Toast, Dialog, Popups),
 *    preserving frame and layout events that fire when resuming an activity from Recents.
 * 3. Dismissal cooldown (700ms) prevents overlay loops during transitions back to Home, but is
 *    bypassed if the app is verified as genuinely foreground.
 * 4. Cooldown expiration is strictly time-based.
 * 5. performGlobalAction(GLOBAL_ACTION_HOME) is dispatched immediately, followed by posting
 *    the overlay ~120ms later on the main looper to prevent it from being hidden behind the launcher.
 * 6. Distraction count increments exactly once per genuine episode.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    private var currentForegroundPackage: String? = null
    var lastBlockedPackage: String? = null
    private var lastOverlayLaunchTime: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingOverlayRunnable: Runnable? = null

    companion object {
        @Volatile
        private var serviceInstance: AppBlockAccessibilityService? = null

        fun resetLastBlockedPackage() {
            serviceInstance?.lastBlockedPackage = null
            serviceInstance?.cancelPendingOverlayLaunch()
        }

        // Essential system packages that must never be blocked even by accident (telephony / calls)
        private val ESSENTIAL_CALL_PACKAGES = setOf(
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.server.telecom"
        )

        // System settings and package management apps protected during Strict Mode to prevent force stop or bypass
        private val SENSITIVE_SYSTEM_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )
        // We removed whole vendor device care apps and com.android.vending from here to prevent blocking innocent parts of those apps.
        // Instead we could check the Activity class name in onAccessibilityEvent if we needed more granular blocks.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        ProtectionEngine.isAccessibilityBound.set(true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (serviceInstance === this) serviceInstance = null
        cancelPendingOverlayLaunch()
        ProtectionEngine.isAccessibilityBound.set(false)
        ProtectionEngine.notifyFailureIfSessionActive(this, "App blocking service disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (serviceInstance === this) serviceInstance = null
        cancelPendingOverlayLaunch()
        ProtectionEngine.isAccessibilityBound.set(false)
        ProtectionEngine.notifyFailureIfSessionActive(this, "App blocking service was stopped")
        super.onDestroy()
    }

    private fun cancelPendingOverlayLaunch() {
        pendingOverlayRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingOverlayRunnable = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Narrow filter: ignore only transient overlays that are never full Activity transitions
        if (isNonActivityWindow(className)) {
            return
        }

        // 1. Ignore essential telephony / call packages
        if (packageName in ESSENTIAL_CALL_PACKAGES) {
            currentForegroundPackage = packageName
            lastBlockedPackage = null
            return
        }

        // 2. Ignore our own application package (BlockOverlayActivity, MainActivity, etc.)
        if (packageName == applicationContext.packageName) {
            return
        }

        // 3. Post-dismiss cooldown check:
        // If cooldown is active, only suppress if the app is NOT genuinely active in foreground
        if (ProtectionEngine.isDismissCooldownActive(packageName)) {
            if (!isAppGenuinelyForeground(packageName)) {
                return
            }
        }

        currentForegroundPackage = packageName

        // 4. Check if session is active or emergency pause active
        if (!PrefsManager.isSessionCurrentlyActive(this) || PrefsManager.isEmergencyPauseActive(this)) {
            cancelPendingOverlayLaunch()
            lastBlockedPackage = null
            return
        }

        // 5. Determine whether the current foreground package should be blocked
        val isStrict = PrefsManager.isStrictModeActive(this)
        val blockedPackages = PrefsManager.getBlockedPackages(this)

        val shouldBlock = (packageName in blockedPackages) || (isStrict && packageName in SENSITIVE_SYSTEM_PACKAGES)

        if (shouldBlock) {
            val now = com.focusvault.app.util.TimeUtils.getSecureCurrentTimeMillis(this)
            // Suppress rapid activity re-launches within 250ms across same blocked package
            if (lastBlockedPackage == packageName && (now - lastOverlayLaunchTime) < 250L) {
                return
            }

            val isNewEpisode = lastBlockedPackage != packageName
            lastBlockedPackage = packageName
            lastOverlayLaunchTime = now

            if (isNewEpisode) {
                SessionStateManager.recordDistractionAttempt(this)
            }

            // Cleanly background the blocked app at the OS accessibility level first
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Post showBlockOverlay() ~120ms later on main looper so Home action executes first
            // and the overlay is brought to front rather than pushed behind launcher
            cancelPendingOverlayLaunch()
            val launchRunnable = Runnable {
                if (PrefsManager.isSessionCurrentlyActive(this) && !PrefsManager.isEmergencyPauseActive(this)) {
                    showBlockOverlay(packageName)
                }
            }
            pendingOverlayRunnable = launchRunnable
            mainHandler.postDelayed(launchRunnable, 120L)
        } else {
            // Foreground package is allowed (Home launcher, System UI, allowed app) -> clear state!
            // Note: Cooldown ends by time only; do not call clearDismissal().
            lastBlockedPackage = null
        }
    }

    /**
     * Narrow check: only ignores IME, Toast, PopupWindow, and AlertDialog wrappers.
     * Does NOT ignore android.widget.* or android.view.* containers (e.g. FrameLayout).
     */
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
     * Verifies if the blocked app is genuinely the active foreground window
     * via interactive accessibility windows or UsageStatsManager.
     */
    private fun isAppGenuinelyForeground(packageName: String): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val activeWindow = windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                if (activeWindow?.root?.packageName?.toString() == packageName) {
                    return true
                }
            }
            if (rootInActiveWindow?.packageName?.toString() == packageName) {
                return true
            }
        } catch (e: Exception) { }

        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val now = com.focusvault.app.util.TimeUtils.getSecureCurrentTimeMillis(this)
                val events = usm.queryEvents(now - 1000L, now)
                val event = UsageEvents.Event()
                var lastEventPkg: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                    ) {
                        lastEventPkg = event.packageName
                    }
                }
                if (lastEventPkg == packageName) {
                    return true
                }
            }
        } catch (e: Exception) { }

        return false
    }

    private fun showBlockOverlay(blockedPackage: String) {
        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        cancelPendingOverlayLaunch()
    }
}
