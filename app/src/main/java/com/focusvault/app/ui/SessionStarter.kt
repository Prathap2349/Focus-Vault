package com.focusvault.app.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.VpnService
import com.focusvault.app.data.SessionMode
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.service.FocusVpnService
import com.focusvault.app.service.StayFocusedDeviceAdminReceiver
import com.focusvault.app.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SessionStarter {

    const val REQUEST_VPN_CONSENT = 4242

    /**
     * Starts a focus session through the authoritative SessionStateManager.
     * Validates Device Admin requirement for protected modes and checks VPN consent if websites are configured for blocking.
     */
    fun startSession(
        activity: Activity,
        durationMillis: Long,
        mode: SessionMode,
        title: String = "Focus Session",
        onStarted: ((Boolean) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // If starting a protected session without active Device Admin, prompt activation
                if ((mode == SessionMode.STRICT || mode == SessionMode.LOCK) &&
                    !StayFocusedDeviceAdminReceiver.isDeviceAdminActive(activity)
                ) {
                    val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            StayFocusedDeviceAdminReceiver.getComponentName(activity)
                        )
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Device Admin is required for ${mode.name.lowercase().replaceFirstChar { it.uppercase() }} Mode to prevent app uninstallation during an active session."
                        )
                    }
                    activity.startActivity(adminIntent)
                    android.widget.Toast.makeText(
                        activity,
                        "Activate Device Admin to enable uninstall protection for ${mode.name.lowercase().replaceFirstChar { it.uppercase() }} Mode",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    onStarted?.invoke(false)
                    return@launch
                }

                val started = SessionStateManager.startSession(activity.applicationContext, durationMillis, mode, title)
                if (!started) {
                    android.widget.Toast.makeText(activity, "Unable to start session (already active, invalid duration, or missing required protection)", android.widget.Toast.LENGTH_SHORT).show()
                    onStarted?.invoke(false)
                    return@launch
                }

                if (PrefsManager.getBlockedDomains(activity).isNotEmpty()) {
                    val consentIntent = VpnService.prepare(activity)
                    if (consentIntent != null) {
                        @Suppress("DEPRECATION")
                        activity.startActivityForResult(consentIntent, REQUEST_VPN_CONSENT)
                    } else {
                        try {
                            activity.startService(Intent(activity, FocusVpnService::class.java))
                        } catch (e: Exception) {
                            android.util.Log.e("SessionStarter", "Failed to start FocusVpnService", e)
                        }
                    }
                }
                onStarted?.invoke(true)
            } catch (e: Exception) {
                android.util.Log.e("SessionStarter", "Error starting focus session", e)
                android.widget.Toast.makeText(
                    activity,
                    "Unable to start session: ${e.localizedMessage ?: "Unknown error"}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                onStarted?.invoke(false)
            }
        }
    }
}
