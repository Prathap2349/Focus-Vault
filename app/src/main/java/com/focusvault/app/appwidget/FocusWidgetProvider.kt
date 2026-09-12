package com.focusvault.app.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.focusvault.app.R
import com.focusvault.app.data.SessionMode
import com.focusvault.app.util.PrefsManager
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
                        val views = createWidgetRemoteViews(context, snapshot, id, options)
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
                val views = createWidgetRemoteViews(context, snapshot, appWidgetId, newOptions)
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

    /**
     * Constructs genuinely responsive RemoteViews:
     * - On Android 12+ (API 31+), supplies an exact multi-size mapping (SizeF -> RemoteViews)
     *   allowing the Android system launcher to pick the best layout dynamically upon resize.
     * - On pre-Android 12, evaluates size tiers via resolveSizeTier() based on current widget options.
     */
    fun createWidgetRemoteViews(
        context: Context,
        snapshot: WidgetSnapshot,
        appWidgetId: Int,
        options: Bundle? = null
    ): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val tinyViews = buildTiny(context, snapshot, appWidgetId)
                val compactViews = buildCompact(context, snapshot, appWidgetId)
                val wideViews = buildWide(context, snapshot, appWidgetId)
                val largeViews = buildLarge(context, snapshot, appWidgetId)

                val sizeMap = mapOf(
                    // 1x1 tile (minimum 60x60 dp)
                    SizeF(60f, 60f) to tinyViews,
                    // 2x2 or narrow card (vertical layout: button at bottom)
                    SizeF(110f, 90f) to compactViews,
                    // 4x1 or wide banner (horizontal layout: button on right)
                    SizeF(150f, 50f) to wideViews,
                    // 4x2+ full dashboard (width >= 220dp, height >= 115dp)
                    SizeF(220f, 115f) to largeViews
                )
                return RemoteViews(sizeMap)
            } catch (e: Exception) {
                android.util.Log.w("FocusWidgetProvider", "Failed to construct responsive size map, falling back", e)
            }
        }

        val tier = resolveSizeTier(options)
        return buildRemoteViews(context, snapshot, appWidgetId, tier)
    }

    fun buildRemoteViews(
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
            views.setProgressBar(R.id.pbWidgetTinyRing, 100, snapshot.sessionElapsedPercent, false)
            views.setContentDescription(R.id.widgetTinyRoot, "Focus session active. ${snapshot.remainingFormatted} remaining. Tap to open.")
            views.setOnClickPendingIntent(R.id.widgetTinyRoot, WidgetIntents.openApp(context, appWidgetId))
        } else if (snapshot.isPaused) {
            views.setTextViewText(R.id.tvWidgetTinyIcon, "⏸️")
            views.setTextViewText(R.id.tvWidgetTinyValue, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetTinySub, "PAUSED")
            views.setProgressBar(R.id.pbWidgetTinyRing, 100, snapshot.sessionElapsedPercent, false)
            views.setContentDescription(R.id.widgetTinyRoot, "Focus session paused. Tap to resume.")
            views.setOnClickPendingIntent(R.id.widgetTinyRoot, WidgetIntents.resumeSession(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.tvWidgetTinyIcon, if (snapshot.streak > 0) "🔥" else "🛡️")
            views.setTextViewText(R.id.tvWidgetTinyValue, if (snapshot.streak > 0) "${snapshot.streak}d" else "Focus")
            views.setTextViewText(R.id.tvWidgetTinySub, if (snapshot.streak > 0) "STREAK" else "READY")
            views.setProgressBar(R.id.pbWidgetTinyRing, 100, snapshot.goalProgressPercent, false)
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
            views.setTextViewText(R.id.tvWidgetCompactMode, "FOCUS MODE")
            views.setTextViewText(R.id.tvWidgetCompactTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetCompactSubtitle, "remaining")
            views.setProgressBar(R.id.progressWidgetCompactGoal, 100, snapshot.sessionElapsedPercent, false)
            views.setTextViewText(R.id.tvWidgetCompactRingLabel, "🛡️")
            views.setTextViewText(R.id.btnWidgetCompactAction, "View Session")
            views.setOnClickPendingIntent(R.id.btnWidgetCompactAction, WidgetIntents.openApp(context, appWidgetId))
        } else if (snapshot.isPaused) {
            views.setTextViewText(R.id.tvWidgetCompactMode, "PAUSED")
            views.setTextViewText(R.id.tvWidgetCompactTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetCompactSubtitle, "paused")
            views.setProgressBar(R.id.progressWidgetCompactGoal, 100, snapshot.sessionElapsedPercent, false)
            views.setTextViewText(R.id.tvWidgetCompactRingLabel, "⏸️")
            views.setTextViewText(R.id.btnWidgetCompactAction, "Resume")
            views.setOnClickPendingIntent(R.id.btnWidgetCompactAction, WidgetIntents.resumeSession(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.tvWidgetCompactMode, "TODAY'S GOAL")
            views.setTextViewText(R.id.tvWidgetCompactTimer, "${snapshot.todayMinutes}/${snapshot.goalMinutes}m")
            views.setTextViewText(R.id.tvWidgetCompactSubtitle, if (snapshot.isGoalComplete) "Goal complete" else "Daily goal")
            views.setProgressBar(R.id.progressWidgetCompactGoal, 100, snapshot.goalProgressPercent, false)
            views.setTextViewText(R.id.tvWidgetCompactRingLabel, if (snapshot.streak > 0) "🔥" else "🛡️")
            views.setTextViewText(R.id.btnWidgetCompactAction, "Start")
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
            views.setTextViewText(R.id.tvWidgetWideMode, "FOCUS MODE")
            views.setTextViewText(R.id.tvWidgetWideTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetWideSub, "remaining")
            views.setTextViewText(R.id.btnWidgetWideAction, "View Session")
            views.setOnClickPendingIntent(R.id.btnWidgetWideAction, WidgetIntents.openApp(context, appWidgetId))
        } else if (snapshot.isPaused) {
            views.setTextViewText(R.id.tvWidgetWideMode, "PAUSED")
            views.setTextViewText(R.id.tvWidgetWideTimer, snapshot.remainingFormatted)
            views.setTextViewText(R.id.tvWidgetWideSub, "paused")
            views.setTextViewText(R.id.btnWidgetWideAction, "Resume")
            views.setOnClickPendingIntent(R.id.btnWidgetWideAction, WidgetIntents.resumeSession(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.tvWidgetWideMode, "TODAY'S GOAL")
            views.setTextViewText(R.id.tvWidgetWideTimer, "${snapshot.todayMinutes}/${snapshot.goalMinutes}m")
            views.setTextViewText(R.id.tvWidgetWideSub, if (snapshot.isGoalComplete) "Goal complete" else "Daily goal")
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
