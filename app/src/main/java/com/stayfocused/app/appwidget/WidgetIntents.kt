package com.stayfocused.app.appwidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.ui.MainActivity
import com.stayfocused.app.ui.WidgetQuickStartActivity

/**
 * Every widget button opens [MainActivity] or [WidgetQuickStartActivity] with an extra telling it what to do next, rather
 * than performing the action directly from the widget process. That's deliberate: starting a
 * session or stopping one early has to pass through the exact same PIN/Strict-Mode/emergency-
 * limit checks MainActivity already enforces (see MainActivity.handleWidgetIntentExtras) - a
 * widget PendingIntent has no way to show a PIN dialog or a confirmation sheet itself, and
 * duplicating that gating logic in a second place is how it eventually drifts out of sync.
 */
object WidgetIntents {

    private const val ACTION_OPEN = 10
    private const val ACTION_QUICK_START = 20
    private const val ACTION_EMERGENCY = 30
    private const val ACTION_MODE_SHEET = 40

    fun openApp(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_OPEN), intent, flags()
        )
    }

    /** Used by the Small/Medium widget's generic "Start" button - opens the same Focus Mode
     * Selection sheet that tapping Start Focus in-app opens, rather than silently assuming a
     * mode. This uses [WidgetQuickStartActivity] which pops up as a dialog flow rather than
     * opening the full app dashboard. */
    fun openModeSheet(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetQuickStartActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_MODE_SHEET), intent, flags()
        )
    }

    /** Used by the Large widget's preset buttons (Focus/Lock/Strict) - opens the timer popup
     * directly for that mode using [WidgetQuickStartActivity]. */
    fun quickStart(context: Context, appWidgetId: Int, mode: SessionMode): PendingIntent {
        val intent = Intent(context, WidgetQuickStartActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(WidgetQuickStartActivity.EXTRA_MODE, mode.name)
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_QUICK_START + mode.ordinal), intent, flags()
        )
    }

    fun emergency(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_AUTO_EMERGENCY, true)
        }
        return PendingIntent.getActivity(
            context, requestCode(appWidgetId, ACTION_EMERGENCY), intent, flags()
        )
    }

    // Unique per (widget instance x action) so multiple widgets on the home screen, and
    // multiple buttons on the same widget, never collide and overwrite each other's intent.
    private fun requestCode(appWidgetId: Int, actionCode: Int) = appWidgetId * 1000 + actionCode

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
