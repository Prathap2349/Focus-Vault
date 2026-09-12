package com.focusvault.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import com.focusvault.app.data.SessionMode
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.service.FocusVpnService
import com.focusvault.app.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SessionStarter {

    const val REQUEST_VPN_CONSENT = 4242

    /**
     * Starts a focus session through the authoritative SessionStateManager.
     * Checks VPN consent if websites are configured for blocking.
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
                val started = SessionStateManager.startSession(activity.applicationContext, durationMillis, mode, title)
                if (!started) {
                    android.widget.Toast.makeText(activity, "Session is already active or invalid duration", android.widget.Toast.LENGTH_SHORT).show()
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
