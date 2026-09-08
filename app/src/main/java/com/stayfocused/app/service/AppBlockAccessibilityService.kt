package com.stayfocused.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.stayfocused.app.manager.ProtectionEngine
import com.stayfocused.app.manager.SessionStateManager
import com.stayfocused.app.ui.BlockOverlayActivity
import com.stayfocused.app.util.PrefsManager

/**
 * Authoritative foreground package watcher and enforcement engine for Focus Vault.
 * 
 * CORE BLOCKING RULES:
 * 1. The current foreground package is the SINGLE SOURCE OF TRUTH for blocking decisions.
 * 2. `lastBlockedPackageShown` is used ONLY to prevent duplicate overlay launches during the
 *    SAME active foreground transition.
 * 3. Moving to any non-blocked app, launcher, Home, Recent Apps, or system screen clears
 *    `lastBlockedPackageShown = null`.
 * 4. Opening a blocked app (e.g. Chrome -> Home -> Chrome) ALWAYS triggers the block overlay again.
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

        currentForegroundPackage = packageName

        // 3. Check if session is active or emergency pause active
        if (!PrefsManager.isSessionCurrentlyActive(this) || PrefsManager.isEmergencyPauseActive(this)) {
            lastBlockedPackage = null
            return
        }

        // 4. Determine whether the current foreground package should be blocked
        val isStrict = PrefsManager.isStrictModeActive(this)
        val blockedPackages = PrefsManager.getBlockedPackages(this)

        val shouldBlock = (packageName in blockedPackages) || (isStrict && packageName in SENSITIVE_SYSTEM_PACKAGES)

        if (shouldBlock) {
            val now = System.currentTimeMillis()
            // Suppress rapid activity re-launches within 250ms across any blocked packages to prevent flashing
            if ((now - lastOverlayLaunchTime) < 250L) {
                lastBlockedPackage = packageName
                return
            }

            lastBlockedPackage = packageName
            lastOverlayLaunchTime = now
            SessionStateManager.recordDistractionAttempt(this)
            showBlockOverlay(packageName)
        } else {
            // Foreground package is allowed (Home launcher, System UI, allowed app) -> clear state!
            lastBlockedPackage = null
        }
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

