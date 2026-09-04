package com.stayfocused.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.stayfocused.app.manager.ProtectionEngine
import com.stayfocused.app.manager.SessionStateManager
import com.stayfocused.app.ui.BlockOverlayActivity
import com.stayfocused.app.util.PrefsManager

/**
 * Watches for TYPE_WINDOW_STATE_CHANGED events and enforces app blocking.
 * - Utilizes high-performance in-memory cache to prevent disk reads on the hot path.
 * - Tracks service lifecycle in ProtectionEngine.
 * - Enforces anti-bypass protection during Strict Mode.
 * - Records distraction attempts for local analytics.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    private var lastEventTime = 0L
    private var lastBlockedPackageShown: String? = null
    private var overlayCurrentlyShown = false

    companion object {
        // Essential system packages that must never be blocked even by accident
        private val ESSENTIAL_WHITELIST = setOf(
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.server.telecom",
            "com.android.systemui"
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
        if (packageName in ESSENTIAL_WHITELIST) return

        val now = System.currentTimeMillis()
        if (now - lastEventTime < 80L && packageName == lastBlockedPackageShown) return
        lastEventTime = now

        if (packageName == applicationContext.packageName) {
            overlayCurrentlyShown = event.className == BlockOverlayActivity::class.java.name
            if (!overlayCurrentlyShown) lastBlockedPackageShown = null
            return
        }

        if (overlayCurrentlyShown) return

        if (!PrefsManager.isSessionCurrentlyActive(this)) {
            lastBlockedPackageShown = null
            return
        }

        if (PrefsManager.isEmergencyPauseActive(this)) {
            lastBlockedPackageShown = null
            return
        }

        val isStrict = PrefsManager.isStrictModeActive(this)
        val blockedPackages = PrefsManager.getBlockedPackages(this)

        val shouldBlock = (packageName in blockedPackages) || (isStrict && packageName in SENSITIVE_SYSTEM_PACKAGES)

        if (shouldBlock) {
            if (lastBlockedPackageShown != packageName) {
                lastBlockedPackageShown = packageName
                overlayCurrentlyShown = true
                SessionStateManager.recordDistractionAttempt(this)
                showBlockOverlay(packageName)
            }
        } else {
            lastBlockedPackageShown = null
        }
    }

    private fun showBlockOverlay(blockedPackage: String) {
        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
        }
        startActivity(intent)
    }

    override fun onInterrupt() { /* no-op */ }
}
