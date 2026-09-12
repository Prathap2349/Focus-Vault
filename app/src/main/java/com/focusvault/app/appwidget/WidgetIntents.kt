package com.focusvault.app.appwidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.focusvault.app.data.SessionMode
import com.focusvault.app.service.SessionTimerService
import com.focusvault.app.ui.DiagnosticsActivity
import com.focusvault.app.ui.MainActivity
import com.focusvault.app.ui.PresetsActivity
import com.focusvault.app.ui.SchedulesActivity
import com.focusvault.app.ui.WidgetQuickStartActivity

/**
 * Creates unique, collision-free [PendingIntent]s for home-screen widgets.
 * Request codes are deterministically keyed by (appWidgetId * 1000 + actionCode)
 * to guarantee that multiple widgets on the home screen do not overwrite each other.
 */
object WidgetIntents {

    private const val ACTION_OPEN = 10
    private const val ACTION_QUICK_START = 20
    private const val ACTION_EMERGENCY = 30
    private const val ACTION_MODE_SHEET = 40
    private const val ACTION_RESUME = 50
    private const val ACTION_FIX_PROTECTION = 60
    private const val ACTION_PRESETS = 70
    private const val ACTION_SCHEDULES = 80

    fun openApp(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_OPEN), intent, flags()
        )
    }

    fun openModeSheet(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetQuickStartActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_MODE_SHEET), intent, flags()
        )
    }

    fun quickStart(context: Context, appWidgetId: Int, mode: SessionMode): PendingIntent {
        val intent = Intent(context, WidgetQuickStartActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(WidgetQuickStartActivity.EXTRA_MODE, mode.name)
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_QUICK_START + mode.ordinal), intent, flags()
        )
    }

    fun resumeSession(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, SessionTimerService::class.java).apply {
            action = SessionTimerService.ACTION_RESUME
        }
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context, requestCode(appWidgetId, ACTION_RESUME), intent, flags()
            )
        } else {
            PendingIntent.getService(
                context, requestCode(appWidgetId, ACTION_RESUME), intent, flags()
            )
        }
    }

    fun emergency(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetQuickStartActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(WidgetQuickStartActivity.EXTRA_SHOW_EMERGENCY, true)
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_EMERGENCY), intent, flags()
        )
    }

    fun fixProtection(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, DiagnosticsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_FIX_PROTECTION), intent, flags()
        )
    }

    fun openPresets(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, PresetsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_PRESETS), intent, flags()
        )
    }

    fun openSchedules(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, SchedulesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_SCHEDULES), intent, flags()
        )
    }

    private fun requestCode(appWidgetId: Int, actionCode: Int): Int =
        (appWidgetId and 0xFFFF) * 1000 + (actionCode and 0x3FF)

    private fun flags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
