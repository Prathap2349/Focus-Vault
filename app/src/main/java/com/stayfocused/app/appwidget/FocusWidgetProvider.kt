package com.stayfocused.app.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.stayfocused.app.R
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.util.PrefsManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Responsive home-screen widget size tiers.
 */
enum class WidgetSizeTier { TINY, COMPACT, WIDE, LARGE }

/**
 * Authoritative Home-Screen Widget Provider for Focus Vault.
 * Renders across 4 distinct size tiers (Tiny, Compact, Wide, Large) with real-time countdown,
 * resilient snapshot caching, smart state adaptation, and per-instance configuration.
 */
class FocusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        val startTime = System.currentTimeMillis()

        providerScope.launch {
            try {
                val snapshot = WidgetDataProvider.buildSnapshot(context.applicationContext)
                appWidgetIds.forEach { id ->
                    try {
                        val options = appWidgetManager.getAppWidgetOptions(id)
                        val tier = resolveSizeTier(options)
                        val views = buildRemoteViews(context, snapshot, id, tier)
                        appWidgetManager.updateAppWidget(id, views)
                    } catch (e: Exception) {
                        android.util.Log.e("FocusWidgetProvider", "Failed to update widget $id", e)
                        try {
                            val fallbackViews = buildTiny(context, snapshot, id)
                            appWidgetManager.updateAppWidget(id, fallbackViews)
                        } catch (_: Exception) { }
                    }
                }
                WidgetUpdater.lastExecutionDurationMillis.set(System.currentTimeMillis() - startTime)
                WidgetUpdater.lastUpdateTimestamp.set(System.currentTimeMillis())
            } catch (e: Exception) {
                WidgetUpdater.failedUpdateCount.incrementAndGet()
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
                // Expected when user is actively resizing
            } catch (e: Exception) {
                WidgetUpdater.failedUpdateCount.incrementAndGet()
                try {
                    val fallbackViews = buildTiny(context, WidgetDataProvider.buildSnapshot(context.applicationContext), appWidgetId)
                    appWidgetManager.updateAppWidget(appWidgetId, fallbackViews)
                } catch (_: Exception) { }
            } finally {
                pendingResult.finish()
            }
        }
        resizeJobs[appWidgetId] = job
        job.invokeOnCompletion { resizeJobs.remove(appWidgetId, job) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { id ->
            PrefsManager.removeWidgetConfig(context, id)
        }
    }

    private fun buildRemoteViews(
        context: Context,
        snapshot: WidgetSnapshot,
        appWidgetId: Int,
        tier: WidgetSizeTier
    ): RemoteViews = try {
        when (tier) {
            WidgetSizeTier.TINY -> buildTiny(context, snapshot, appWidgetId)
            WidgetSizeTier.COMPACT -> buildCompact(context, snapshot, appWidgetId)
            WidgetSizeTier.WIDE -> buildWide(context, snapshot, appWidgetId)
            WidgetSizeTier.LARGE -> buildLarge(context, snapshot, appWidgetId)
        }
    } catch (e: Exception) {
        android.util.Log.e("FocusWidgetProvider", "Error in buildRemoteViews for tier $tier", e)
        buildTiny(context, snapshot, appWidgetId)
    }

    // ==========================================
    // 1. TINY WIDGET (1x1)
    // ==========================================
    private fun buildTiny(context: Context, snapshot: WidgetSnapshot, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tiny)

        if (snapshot.isActive) {
            views.setTextViewText(R.id.tvWidgetTinyIcon, "🛡️")
            views.setTextViewText(R.id.tvWidgetTinyValue, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetTinySub, "REMAINING")
            views.setContentDescription(R.id.widgetTinyRoot, "Focus session active. ${snapshot.remainingFormatted} remaining. Tap to open.")
            views.setOnClickPendingIntent(R.id.widgetTinyRoot, WidgetIntents.openApp(context, appWidgetId))
        } else if (snapshot.isPaused) {
            views.setTextViewText(R.id.tvWidgetTinyIcon, "⏸️")
            views.setTextViewText(R.id.tvWidgetTinyValue, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetTinySub, "PAUSED")
            views.setContentDescription(R.id.widgetTinyRoot, "Focus session paused. Tap to resume.")
            views.setOnClickPendingIntent(R.id.widgetTinyRoot, WidgetIntents.resumeSession(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.tvWidgetTinyIcon, if (snapshot.streak > 0) "🔥" else "🛡️")
            views.setTextViewText(R.id.tvWidgetTinyValue, if (snapshot.streak > 0) "${snapshot.streak}d" else "Focus")
            views.setTextViewText(R.id.tvWidgetTinySub, if (snapshot.streak > 0) "STREAK" else "READY")
            views.setContentDescription(R.id.widgetTinyRoot, "Focus Vault. Start a focus session.")
            views.setOnClickPendingIntent(R.id.widgetTinyRoot, WidgetIntents.openModeSheet(context, appWidgetId))
        }

        return views
    }

    // ==========================================
    // 2. COMPACT WIDGET (2x2)
    // ==========================================
    private fun buildCompact(context: Context, snapshot: WidgetSnapshot, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact)

        views.setTextViewText(R.id.tvWidgetCompactBadge, streakLabel(snapshot.streak))

        if (snapshot.isActive) {
            views.setTextViewText(R.id.tvWidgetCompactMode, snapshot.modeLabel.uppercase())
            views.setTextViewText(R.id.tvWidgetCompactTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetCompactSubtitle, "remaining · ${snapshot.sessionElapsedPercent}% elapsed")
            views.setProgressBar(R.id.progressWidgetCompactGoal, 100, snapshot.sessionElapsedPercent, false)
            views.setTextViewText(R.id.btnWidgetCompactAction, "View Session")
            views.setOnClickPendingIntent(R.id.btnWidgetCompactAction, WidgetIntents.openApp(context, appWidgetId))
        } else if (snapshot.isPaused) {
            views.setTextViewText(R.id.tvWidgetCompactMode, "SESSION PAUSED")
            views.setTextViewText(R.id.tvWidgetCompactTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetCompactSubtitle, "Paused · Tap to resume")
            views.setProgressBar(R.id.progressWidgetCompactGoal, 100, snapshot.sessionElapsedPercent, false)
            views.setTextViewText(R.id.btnWidgetCompactAction, "Resume Now")
            views.setOnClickPendingIntent(R.id.btnWidgetCompactAction, WidgetIntents.resumeSession(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.tvWidgetCompactMode, "TODAY'S PROGRESS")
            views.setTextViewText(R.id.tvWidgetCompactTimer, "${snapshot.todayMinutes} / ${snapshot.goalMinutes} min")
            val subtitle = if (snapshot.isGoalComplete) "Goal completed! 🎉" else "${(snapshot.goalMinutes - snapshot.todayMinutes).coerceAtLeast(0)}m left to goal"
            views.setTextViewText(R.id.tvWidgetCompactSubtitle, subtitle)
            views.setProgressBar(R.id.progressWidgetCompactGoal, 100, snapshot.goalProgressPercent, false)
            views.setTextViewText(R.id.btnWidgetCompactAction, "Start Focus")
            views.setOnClickPendingIntent(R.id.btnWidgetCompactAction, WidgetIntents.openModeSheet(context, appWidgetId))
        }

        views.setOnClickPendingIntent(R.id.widgetCompactRoot, WidgetIntents.openApp(context, appWidgetId))
        return views
    }

    // ==========================================
    // 3. WIDE WIDGET (4x1)
    // ==========================================
    private fun buildWide(context: Context, snapshot: WidgetSnapshot, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_wide)

        views.setTextViewText(R.id.tvWidgetWideStreak, streakLabel(snapshot.streak))

        if (snapshot.isActive) {
            views.setTextViewText(R.id.tvWidgetWideMode, snapshot.modeLabel.uppercase())
            views.setTextViewText(R.id.tvWidgetWideTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetWideDetailLeft, "Started ${snapshot.sessionStartFormatted}")
            views.setTextViewText(R.id.tvWidgetWideDetailRight, "${snapshot.sessionElapsedPercent}%")
            views.setProgressBar(R.id.progressWidgetWide, 100, snapshot.sessionElapsedPercent, false)
            views.setTextViewText(R.id.tvWidgetWideSub, "Ends ${snapshot.sessionEndFormatted} · ${snapshot.todayMinutes}/${snapshot.goalMinutes}m today")
            views.setTextViewText(R.id.btnWidgetWideAction, "View")
            views.setOnClickPendingIntent(R.id.btnWidgetWideAction, WidgetIntents.openApp(context, appWidgetId))
        } else if (snapshot.isPaused) {
            views.setTextViewText(R.id.tvWidgetWideMode, "PAUSED")
            views.setTextViewText(R.id.tvWidgetWideTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetWideDetailLeft, "Session Paused")
            views.setTextViewText(R.id.tvWidgetWideDetailRight, "--")
            views.setProgressBar(R.id.progressWidgetWide, 100, snapshot.sessionElapsedPercent, false)
            views.setTextViewText(R.id.tvWidgetWideSub, "Tap Resume to continue focus")
            views.setTextViewText(R.id.btnWidgetWideAction, "Resume")
            views.setOnClickPendingIntent(R.id.btnWidgetWideAction, WidgetIntents.resumeSession(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.tvWidgetWideMode, "TODAY'S GOAL")
            views.setTextViewText(R.id.tvWidgetWideTimer, "${snapshot.todayMinutes}/${snapshot.goalMinutes}m")
            views.setTextViewText(R.id.tvWidgetWideDetailLeft, if (snapshot.isGoalComplete) "Goal completed!" else "${snapshot.goalMinutes - snapshot.todayMinutes}m remaining")
            views.setTextViewText(R.id.tvWidgetWideDetailRight, "${snapshot.goalProgressPercent}%")
            views.setProgressBar(R.id.progressWidgetWide, 100, snapshot.goalProgressPercent, false)
            views.setTextViewText(R.id.tvWidgetWideSub, snapshot.nextScheduleFormatted ?: "Ready for your next focus session")
            views.setTextViewText(R.id.btnWidgetWideAction, "Start")
            views.setOnClickPendingIntent(R.id.btnWidgetWideAction, WidgetIntents.openModeSheet(context, appWidgetId))
        }

        views.setOnClickPendingIntent(R.id.widgetWideRoot, WidgetIntents.openApp(context, appWidgetId))
        return views
    }

    // ==========================================
    // 4. LARGE WIDGET (4x2+ Mini Control Center)
    // ==========================================
    private fun buildLarge(context: Context, snapshot: WidgetSnapshot, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_full)

        // Health & Streak badges
        if (snapshot.isProtectionHealthy) {
            views.setTextViewText(R.id.tvWidgetFullHealth, "✓ Protected")
            views.setOnClickPendingIntent(R.id.tvWidgetFullHealth, WidgetIntents.fixProtection(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.tvWidgetFullHealth, "⚠ Attention")
            views.setOnClickPendingIntent(R.id.tvWidgetFullHealth, WidgetIntents.fixProtection(context, appWidgetId))
        }
        views.setTextViewText(R.id.tvWidgetFullStreak, streakLabel(snapshot.streak))

        // Hero Timer Section
        if (snapshot.isActive) {
            views.setTextViewText(R.id.tvWidgetFullMode, "🛡️ ${snapshot.modeLabel.uppercase()}")
            views.setTextViewText(R.id.tvWidgetFullTimer, snapshot.remainingFormatted)
            views.setTextViewText(
                R.id.tvWidgetFullTimerSub,
                "FOCUS REMAINING · Started ${snapshot.sessionStartFormatted} · Ends ${snapshot.sessionEndFormatted}"
            )
            views.setProgressBar(R.id.progressWidgetFullSession, 100, snapshot.sessionElapsedPercent, false)
            views.setTextViewText(R.id.btnWidgetFullAction, "View Session")
            views.setOnClickPendingIntent(R.id.btnWidgetFullAction, WidgetIntents.openApp(context, appWidgetId))
            views.setViewVisibility(R.id.containerWidgetFullSchedule, View.GONE)
        } else if (snapshot.isPaused) {
            views.setTextViewText(R.id.tvWidgetFullMode, "⏸️ SESSION PAUSED")
            views.setTextViewText(R.id.tvWidgetFullTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetFullTimerSub, "Blocking temporarily paused · Tap Resume")
            views.setProgressBar(R.id.progressWidgetFullSession, 100, snapshot.sessionElapsedPercent, false)
            views.setTextViewText(R.id.btnWidgetFullAction, "Resume Focus")
            views.setOnClickPendingIntent(R.id.btnWidgetFullAction, WidgetIntents.resumeSession(context, appWidgetId))
            views.setViewVisibility(R.id.containerWidgetFullSchedule, View.GONE)
        } else {
            views.setTextViewText(R.id.tvWidgetFullMode, "🛡️ FOCUS VAULT")
            views.setTextViewText(R.id.tvWidgetFullTimer, "${snapshot.todayMinutes} / ${snapshot.goalMinutes} min")
            views.setTextViewText(
                R.id.tvWidgetFullTimerSub,
                if (snapshot.isGoalComplete) "Today's goal completed! 🎉"
                else "${(snapshot.goalMinutes - snapshot.todayMinutes).coerceAtLeast(0)} min remaining to reach goal"
            )
            views.setProgressBar(R.id.progressWidgetFullSession, 100, snapshot.goalProgressPercent, false)
            views.setTextViewText(R.id.btnWidgetFullAction, "Start Focus")
            views.setOnClickPendingIntent(R.id.btnWidgetFullAction, WidgetIntents.openModeSheet(context, appWidgetId))

            // Smart schedule banner
            if (snapshot.nextScheduleFormatted != null) {
                views.setViewVisibility(R.id.containerWidgetFullSchedule, View.VISIBLE)
                views.setTextViewText(
                    R.id.tvWidgetFullScheduleText,
                    "⏰ Next: ${snapshot.nextScheduleTitle ?: "Scheduled Focus"} · ${snapshot.nextScheduleFormatted}"
                )
                views.setOnClickPendingIntent(R.id.containerWidgetFullSchedule, WidgetIntents.openSchedules(context, appWidgetId))
            } else {
                views.setViewVisibility(R.id.containerWidgetFullSchedule, View.GONE)
            }
        }

        // Bento Stats Grid (Always real data)
        views.setTextViewText(R.id.tvWidgetFullTodayGoal, "${snapshot.todayMinutes} / ${snapshot.goalMinutes} min")
        val weekHours = snapshot.weekTotalMinutes / 60
        val weekMins = snapshot.weekTotalMinutes % 60
        val weekGoalHours = snapshot.weekGoalMinutes / 60
        views.setTextViewText(R.id.tvWidgetFullWeekTotal, "${weekHours}h ${weekMins}m / ${weekGoalHours}h")
        views.setTextViewText(R.id.tvWidgetFullAvgSession, "${snapshot.avgSessionMinutes} min")
        val bestHours = snapshot.longestSessionMinutes / 60
        val bestMins = snapshot.longestSessionMinutes % 60
        views.setTextViewText(
            R.id.tvWidgetFullBestSession,
            if (bestHours > 0) "${bestHours}h ${bestMins}m" else "${bestMins} min"
        )

        // Secondary & Emergency Buttons
        views.setOnClickPendingIntent(R.id.btnWidgetFullPresets, WidgetIntents.openPresets(context, appWidgetId))

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
        0 -> "Start today"
        1 -> "🔥 1d streak"
        else -> "🔥 ${streak}d streak"
    }

    companion object {
        private const val TINY_MAX_DP = 100
        private const val WIDE_MIN_WIDTH_DP = 220
        private const val WIDE_MAX_HEIGHT_DP = 110
        private const val LARGE_MIN_WIDTH_DP = 220
        private const val LARGE_MIN_HEIGHT_DP = 110

        private const val DEBOUNCE_MILLIS = 200L

        private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val resizeJobs = ConcurrentHashMap<Int, Job>()

        fun resolveSizeTier(options: Bundle?): WidgetSizeTier {
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            return resolveSizeTier(minWidth, minHeight)
        }

        /**
         * Resolves responsive size tier based on explicit boundary rules:
         * 1. WIDE: minWidth >= 220dp AND minHeight <= 110dp (inclusive of 110dp height for 4x1 banner)
         * 2. LARGE: minWidth >= 220dp AND minHeight > 110dp (strictly taller than 110dp for 4x2+)
         * 3. TINY: minWidth < 100dp AND minHeight < 100dp (both dimensions must be small for 1x1 tile)
         * 4. COMPACT: default / fallback for 2x2 or intermediate resizes
         */
        fun resolveSizeTier(minWidth: Int, minHeight: Int): WidgetSizeTier {
            if (minWidth <= 0 && minHeight <= 0) return WidgetSizeTier.COMPACT

            return when {
                minHeight <= WIDE_MAX_HEIGHT_DP -> {
                    // Short layout (1 row). If narrow, use TINY; if wide enough, use WIDE.
                    if (minWidth < 150) WidgetSizeTier.TINY
                    else WidgetSizeTier.WIDE
                }
                else -> {
                    // Tall layout (2+ rows).
                    if (minWidth >= LARGE_MIN_WIDTH_DP) WidgetSizeTier.LARGE
                    else if (minWidth < TINY_MAX_DP && minHeight < TINY_MAX_DP) WidgetSizeTier.TINY
                    else WidgetSizeTier.COMPACT
                }
            }
        }
    }
}
