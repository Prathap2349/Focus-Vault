package com.focusvault.app.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.focusvault.app.data.SessionMode
import com.focusvault.app.manager.ProtectionEngine
import com.focusvault.app.util.PrefsManager

/**
 * Device Admin Receiver for Focus Vault.
 * Provides OS-level uninstall friction by requiring device admin deactivation before removal.
 */
class StayFocusedDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, StayFocusedDeviceAdminReceiver::class.java)
        }

        fun isDeviceAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return dpm.isAdminActive(getComponentName(context))
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val isActive = PrefsManager.isSessionCurrentlyActive(context)
        val mode = PrefsManager.getSessionMode(context)
        return if (isActive) {
            when (mode) {
                SessionMode.STRICT ->
                    "⚠️ STRICT MODE ACTIVE: Deactivating Device Admin degrades uninstall protection. Your focus session and app blocking will continue running."
                SessionMode.LOCK ->
                    "⚠️ LOCK MODE ACTIVE: Deactivating Device Admin removes uninstall protection while your focus session is active."
                SessionMode.NORMAL ->
                    "A focus session is currently active. Deactivating Device Admin removes uninstall protection."
            }
        } else {
            "Deactivating this removes Focus Vault's uninstall protection."
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Keep isolated from SessionStateManager mutex - do not modify session state or finalization
        val isActive = PrefsManager.isSessionCurrentlyActive(context)
        val mode = PrefsManager.getSessionMode(context)
        if (isActive && (mode == SessionMode.STRICT || mode == SessionMode.LOCK)) {
            ProtectionEngine.notifyFailureIfSessionActive(
                context,
                "Device Admin was deactivated. Uninstall protection is degraded."
            )
        }
    }
}
