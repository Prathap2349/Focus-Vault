package com.stayfocused.app.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.stayfocused.app.R
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How much room the widget currently has on the home screen. Recomputed any time the user
 * resizes it (via [onAppWidgetOptionsChanged]) or a fresh update cycle runs (via [onUpdate]) -
 * there's only ever one provider/one widget entry the user places, and it swaps between these
 * three layouts instead of the user having to place two separate widgets. */
private enum class WidgetSizeTier { TINY, COMPACT, FULL }

/** Single home-screen widget: swaps between [WidgetSizeTier] layouts as the user drags it
 * bigger or smaller, instead of shipping a separate small/medium widget the user has to pick
 * between. [AppWidgetProvider] callbacks run on the main thread with a strict time budget, but
 * building [WidgetSnapshot] touches Room - so this uses goAsync() to move that work off the
 * main thread while still finishing promptly in the normal case. */
class FocusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        providerScope.launch {
            try {
                val snapshot = WidgetDataProvider.buildSnapshot(context.applicationContext)
                appWidgetIds.forEach { id ->
                    val tier = resolveSizeTier(appWidgetManager.getAppWidgetOptions(id))
                    val views = buildRemoteViews(context, snapshot, id, tier)
                    appWidgetManager.updateAppWidget(id, views)
                }
            } catch (e: Exception) {
                // A widget refresh should never crash the launcher - if this cycle failed for
                // any reason (e.g. the DB briefly unavailable), the next scheduled or
                // explicitly requested update will simply try again.
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // While the user is actively dragging a resize handle, the launcher fires this callback
        // repeatedly - once per pixel/step, not just once at the end. Previously each call
        // immediately kicked off its own Room read, so a single drag gesture could queue up
        // dozens of overlapping DB queries and visibly stall the phone. Cancelling any
        // in-flight rebuild for this widget and waiting a short debounce window means only the
        // size the user actually lets go on triggers a real rebuild.
        val pendingResult = goAsync()
        val previousJob = resizeJobs[appWidgetId]
        val job = providerScope.launch {
            try {
                previousJob?.cancelAndJoin()
                delay(DEBOUNCE_MILLIS)
                val snapshot = WidgetDataProvider.buildSnapshot(context.applicationContext)
                val tier = resolveSizeTier(newOptions)
                val views = buildRemoteViews(context, snapshot, appWidgetId, tier)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: CancellationException) {
                // Superseded by a newer resize event - expected, not an error.
            } catch (e: Exception) {
                // Same reasoning as onUpdate - never crash the launcher over a failed rebuild.
            } finally {
                pendingResult.finish()
            }
        }
        resizeJobs[appWidgetId] = job
        job.invokeOnCompletion { resizeJobs.remove(appWidgetId, job) }
    }

    private fun buildRemoteViews(
        context: Context,
        snapshot: WidgetSnapshot,
        appWidgetId: Int,
        tier: WidgetSizeTier
    ): RemoteViews = when (tier) {
        WidgetSizeTier.TINY -> buildTiny(context, snapshot, appWidgetId)
        WidgetSizeTier.COMPACT -> buildCompact(context, snapshot, appWidgetId)
        WidgetSizeTier.FULL -> buildFull(context, snapshot, appWidgetId)
    }

    private fun buildTiny(context: Context, snapshot: WidgetSnapshot, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tiny)

        views.setTextViewText(R.id.tvWidgetTinyIcon, if (snapshot.isActive) "🎯" else "🔥")
        views.setTextViewText(
            R.id.tvWidgetTinyLabel,
            if (snapshot.isActive) snapshot.remainingLabel else streakLabel(snapshot.streak)
        )

        val action = if (snapshot.isActive) {
            WidgetIntents.openApp(context, appWidgetId)
        } else {
            WidgetIntents.openModeSheet(context, appWidgetId)
        }
        views.setOnClickPendingIntent(R.id.widgetTinyRoot, action)

        return views
    }

    private fun buildCompact(context: Context, snapshot: WidgetSnapshot, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact)

        views.setTextViewText(
            R.id.tvWidgetCompactMode,
            if (snapshot.isActive) "🎯 ${snapshot.modeLabel}" else "🎯 Not focusing"
        )
        views.setTextViewText(R.id.tvWidgetCompactStreak, streakLabel(snapshot.streak))

        views.setProgressBar(R.id.progressWidgetCompactGoal, 100, snapshot.goalProgressPercent, false)

        if (snapshot.isActive) {
            views.setTextViewText(R.id.btnWidgetCompactAction, "Open App")
            views.setOnClickPendingIntent(R.id.btnWidgetCompactAction, WidgetIntents.openApp(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.btnWidgetCompactAction, "Start Focus")
            views.setOnClickPendingIntent(
                R.id.btnWidgetCompactAction, WidgetIntents.openModeSheet(context, appWidgetId)
            )
        }

        views.setOnClickPendingIntent(R.id.widgetCompactRoot, WidgetIntents.openApp(context, appWidgetId))

        return views
    }

    private fun buildFull(context: Context, snapshot: WidgetSnapshot, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_full)

        views.setTextViewText(
            R.id.tvWidgetFullMode,
            if (snapshot.isActive) "🎯 ${snapshot.modeLabel}" else "🎯 Not focusing"
        )
        views.setTextViewText(R.id.tvWidgetFullStreak, streakLabel(snapshot.streak))

        views.setProgressBar(R.id.progressWidgetFullGoal, 100, snapshot.goalProgressPercent, false)
        views.setTextViewText(
            R.id.tvWidgetFullGoalText,
            "${snapshot.todayMinutes} / ${snapshot.goalMinutes} min today" +
                if (snapshot.isActive) " · ${snapshot.remainingLabel}" else ""
        )

        if (snapshot.isActive) {
            views.setTextViewText(R.id.btnWidgetFullAction, "Open App")
            views.setOnClickPendingIntent(R.id.btnWidgetFullAction, WidgetIntents.openApp(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.btnWidgetFullAction, "Start Focus")
            views.setOnClickPendingIntent(
                R.id.btnWidgetFullAction, WidgetIntents.openModeSheet(context, appWidgetId)
            )
        }

        if (snapshot.canOpenEmergencyMode) {
            views.setViewVisibility(R.id.btnWidgetFullEmergency, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.btnWidgetFullEmergency, WidgetIntents.emergency(context, appWidgetId))
        } else {
            views.setViewVisibility(R.id.btnWidgetFullEmergency, View.GONE)
        }

        views.setOnClickPendingIntent(R.id.widgetFullRoot, WidgetIntents.openApp(context, appWidgetId))

        return views
    }

    private fun streakLabel(streak: Int) = when (streak) {
        0 -> "🔥 Start today"
        1 -> "🔥 1 day"
        else -> "🔥 $streak days"
    }

    companion object {
        private const val TINY_MAX_DP = 100
        private const val FULL_MIN_WIDTH_DP = 250

        // How long to wait after the last resize step before actually rebuilding the widget.
        // Well above a single drag "step" gap, short enough that letting go still feels instant.
        private const val DEBOUNCE_MILLIS = 250L

        // Shared across all onUpdate/onAppWidgetOptionsChanged calls for this provider instead
        // of spinning up a new CoroutineScope per call, so work doesn't pile up unbounded.
        private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Tracks the in-flight rebuild job per widget id, so a new resize step can cancel the
        // one still running for the same widget instead of letting both finish.
        private val resizeJobs = ConcurrentHashMap<Int, Job>()

        /** Reads the width/height the launcher currently reports for this widget instance and
         * maps it to a layout tier. Some OEM launchers (Vivo/Funtouch was seen doing this)
         * report 0 for these options right after placement instead of the real size - default
         * to [WidgetSizeTier.COMPACT] in that case rather than collapsing to the tiny layout. */
        private fun resolveSizeTier(options: Bundle?): WidgetSizeTier {
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0

            if (minWidth <= 0 && minHeight <= 0) return WidgetSizeTier.COMPACT

            return when {
                minWidth < TINY_MAX_DP || minHeight < TINY_MAX_DP -> WidgetSizeTier.TINY
                minWidth >= FULL_MIN_WIDTH_DP -> WidgetSizeTier.FULL
                else -> WidgetSizeTier.COMPACT
            }
        }
    }
}
