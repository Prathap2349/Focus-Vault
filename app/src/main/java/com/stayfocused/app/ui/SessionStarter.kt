package com.stayfocused.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.manager.SessionStateManager
import com.stayfocused.app.service.FocusVpnService
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SessionStarter {

    const val REQUEST_VPN_CONSENT = 4242

    /**
     * Starts a focus session through the authoritative SessionStateManager.
     * Checks VPN consent if websites are configured for blocking.
     */
    fun startSession(activity: Activity, durationMillis: Long, mode: SessionMode, title: String = "Focus Session") {
        CoroutineScope(Dispatchers.Main).launch {
            SessionStateManager.startSession(activity.applicationContext, durationMillis, mode, title)

            if (PrefsManager.getBlockedDomains(activity).isNotEmpty()) {
                val consentIntent = VpnService.prepare(activity)
                if (consentIntent != null) {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(consentIntent, REQUEST_VPN_CONSENT)
                } else {
                    try {
                        activity.startService(Intent(activity, FocusVpnService::class.java))
                    } catch (e: Exception) { }
                }
            }
        }
    }
}
