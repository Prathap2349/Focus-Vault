package com.focusvault.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
 * 2. Non-activity window events (IME, toast, dialog popups) are ignored to prevent false-positive re-triggers.
 * 3. Moving to any non-blocked app, launcher, Home, Recent Apps, or system screen clears `lastBlockedPackage = null`.
 * 4. Dismissal cooldown (ProtectionEngine) suppresses rapid overlay loop when returning to Home.
 * 5. performGlobalAction(GLOBAL_ACTION_HOME) is dispatched immediately when a blocked app is detected to cleanly background it.
 * 6. Distraction count increments exactly once per genuine episode.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    private var currentForegroundPackage: String? = null
    private var lastBlockedPackage: String? = null
    private var lastOverlayLaunchTime: Long = 0L

    companion object {
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ProtectionEngine.isAccessibilityBound.set(true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ProtectionEngine.isAccessibilityBound.set(false)
        ProtectionEngine.notifyFailureIfSessionActive(this, "App blocking service disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ProtectionEngine.isAccessibilityBound.set(false)
        ProtectionEngine.notifyFailureIfSessionActive(this, "App blocking service was stopped")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Filter out non-activity window events (IME keyboard popups, toasts, standalone dialog wrappers)
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
        // Do NOT update currentForegroundPackage or reset lastBlockedPackage when our own app is foregrounded.
        if (packageName == applicationContext.packageName) {
            return
        }

        // 3. Post-dismiss cooldown check: If the user just dismissed the overlay to go Home,
        // ignore transitional events from the dismissed package during the cooldown window.
        if (ProtectionEngine.isDismissCooldownActive(packageName)) {
            return
        }

        currentForegroundPackage = packageName

        // 4. Check if session is active or emergency pause active
        if (!PrefsManager.isSessionCurrentlyActive(this) || PrefsManager.isEmergencyPauseActive(this)) {
            lastBlockedPackage = null
            return
        }

        // 5. Determine whether the current foreground package should be blocked
        val isStrict = PrefsManager.isStrictModeActive(this)
        val blockedPackages = PrefsManager.getBlockedPackages(this)

        val shouldBlock = (packageName in blockedPackages) || (isStrict && packageName in SENSITIVE_SYSTEM_PACKAGES)

        if (shouldBlock) {
            val now = System.currentTimeMillis()
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

            // Then present the block overlay
            showBlockOverlay(packageName)
        } else {
            // Foreground package is allowed (Home launcher, System UI, allowed app) -> clear state!
            lastBlockedPackage = null
            ProtectionEngine.clearDismissal()
        }
    }

    private fun isNonActivityWindow(className: String): Boolean {
        if (className.isEmpty()) return false
        if (className.startsWith("android.widget.") && !className.contains("Activity")) return true
        if (className.startsWith("android.view.")) return true
        if (className.startsWith("android.inputmethodservice.") || className.contains("InputMethod")) return true
        if (className == "android.app.Dialog" || className == "android.widget.Toast" || className == "android.widget.PopupWindow") return true
        return false
    }

    private fun showBlockOverlay(blockedPackage: String) {
        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
        }
        startActivity(intent)
    }

    override fun onInterrupt() { /* no-op */ }
}
