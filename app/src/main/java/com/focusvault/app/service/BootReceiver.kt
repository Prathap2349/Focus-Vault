package com.focusvault.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles device boot and app package update events:
 * - Recovers any active or expired session safely via SessionStateManager.
 * - Restores foreground notifications, widgets, and website blocking.
 * - Reschedules any active scheduled focus alarms.
 * - Restarts FocusVpnService if 24/7 permanent blocked domains exist and VPN
 *   permission is already granted (no user interaction needed after boot).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Recover or cleanly finalize session state
                SessionStateManager.recoverSessionIfNeeded(context.applicationContext)

                // Reschedule any enabled schedules
                com.focusvault.app.receiver.ScheduleAlarmReceiver.rescheduleAll(context.applicationContext)

                // If permanent-blocked domains exist and the user hasn't manually stopped the VPN,
                // restart it. VPN permission is retained by Android across reboots — prepare()==null
                // means "already granted". If non-null, the user must re-grant in the UI; we skip
                // (WebsiteBlockActivity handles that case with an explicit permission prompt).
                val permanentDomains = PrefsManager.getPermanentBlockedDomains(context.applicationContext)
                val manuallyStopped = PrefsManager.isVpnManuallyStopped(context.applicationContext)
                if (permanentDomains.isNotEmpty() && !manuallyStopped) {
                    try {
                        if (VpnService.prepare(context.applicationContext) == null) {
                            val vpnIntent = Intent(context.applicationContext, FocusVpnService::class.java)
                            context.applicationContext.startForegroundService(vpnIntent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("BootReceiver", "Could not restart 24/7 VPN after boot", e)
                    }
                }
            } catch (e: Exception) {
                // Fail-safe to avoid crash in background receiver
            } finally {
                pendingResult.finish()
            }
        }
    }
}
