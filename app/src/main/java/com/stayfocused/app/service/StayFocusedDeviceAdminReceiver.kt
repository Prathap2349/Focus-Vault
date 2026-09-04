package com.stayfocused.app.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.stayfocused.app.util.PrefsManager

/**
 * Registering as a Device Admin means Android requires "deactivate device admin" as an
 * explicit extra step before Stay Focused can be uninstalled from Settings - this is what
 * the Lock/Strict Mode screenshots mean by "block ... app uninstallations". It is friction,
 * not a hard lock: Android does not let a regular (non profile/device-owner) admin app
 * forbid its own deactivation outright. onDisableRequested below is the one hook Android
 * gives us - a warning message shown on the OS's own confirmation dialog before deactivation
 * completes, which we use to remind the person a focus session is running.
 */
class StayFocusedDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return if (PrefsManager.isSessionCurrentlyActive(context)) {
            "A Stay Focused session is currently active. Deactivating admin now removes " +
                "uninstall protection early - are you sure?"
        } else {
            "Deactivating this removes Stay Focused's uninstall protection."
        }
    }
}
