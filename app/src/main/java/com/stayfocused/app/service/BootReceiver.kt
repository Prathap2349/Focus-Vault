package com.stayfocused.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stayfocused.app.manager.SessionStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles device boot and app package update events:
 * - Recovers any active or expired session safely via SessionStateManager.
 * - Restores foreground notifications, widgets, and website blocking.
 * - Reschedules any active scheduled focus alarms.
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
                com.stayfocused.app.receiver.ScheduleAlarmReceiver.rescheduleAll(context.applicationContext)
            } catch (e: Exception) {
                // Fail-safe to avoid crash in background receiver
            } finally {
                pendingResult.finish()
            }
        }
    }
}
