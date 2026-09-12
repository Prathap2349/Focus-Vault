package com.focusvault.app.appwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Dispatches targeted and global widget update broadcasts to [FocusWidgetProvider].
 * Tracks update telemetry and metrics for integration with Diagnostics.
 */
object WidgetUpdater {

    val lastUpdateTimestamp = AtomicLong(0L)
    val lastExecutionDurationMillis = AtomicLong(0L)
    val totalUpdateCount = AtomicInteger(0)
    val failedUpdateCount = AtomicInteger(0)

    fun requestUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, FocusWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            val intent = Intent(context, FocusWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
            totalUpdateCount.incrementAndGet()
            lastUpdateTimestamp.set(System.currentTimeMillis())
        }
    }

    fun requestUpdateForWidget(context: Context, appWidgetId: Int) {
        val intent = Intent(context, FocusWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        }
        context.sendBroadcast(intent)
        totalUpdateCount.incrementAndGet()
        lastUpdateTimestamp.set(System.currentTimeMillis())
    }

    fun getActiveWidgetCount(context: Context): Int {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, FocusWidgetProvider::class.java)).size
    }
}
