package com.stayfocused.app.appwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Call this any time something a widget displays has changed (a session started/stopped,
 * today's focus minutes changed, the streak changed, the daily goal was edited...) instead of
 * waiting for Android's own widget update cycle, which is throttled to roughly once every 30
 * minutes system-wide and would make the widgets feel stale. This just re-triggers each
 * provider's own onUpdate() (via the same broadcast the OS itself uses), so there's only one
 * place ([FocusWidgetProvider]) that actually knows how to build widget content. */
object WidgetUpdater {

    fun requestUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, FocusWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            val intent = Intent(context, FocusWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
