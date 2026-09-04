package com.stayfocused.app.ui

import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.stayfocused.app.R
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.SessionHistoryEntry
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.data.SessionState
import com.stayfocused.app.databinding.ActivityMainBinding
import com.stayfocused.app.databinding.ItemRecentSessionBinding
import com.stayfocused.app.service.AppBlockAccessibilityService
import com.stayfocused.app.service.FocusVpnService
import com.stayfocused.app.service.SessionTimerService
import com.stayfocused.app.manager.ProtectionEngine
import com.stayfocused.app.manager.ProtectionStatus
import com.stayfocused.app.manager.SessionStateManager
import com.stayfocused.app.util.AppLockGate
import com.stayfocused.app.util.EdgeToEdge
import com.stayfocused.app.util.FocusStatsManager
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** Set by the Small/Medium widget's generic Start button - if no session is active
         * when the app opens, immediately opens the Focus Mode Selection sheet instead of
         * silently assuming Normal mode, matching what tapping "Start Focus" in-app now does. */
        const val EXTRA_AUTO_OPEN_MODE_SHEET = "widget_auto_open_mode_sheet"

        /** Set by the Small/Large widget's Start button - if no session is active when the
         * app opens, immediately jumps to session setup for this mode instead of making the
         * person tap Quick Start again themselves. */
        const val EXTRA_AUTO_QUICK_START_MODE = "widget_auto_quick_start_mode"

        /** Set by the Medium/Large widget's Emergency button - if a session is active when
         * the app opens, immediately opens the Emergency Mode picker (Real Emergency /
         * Important Call / Travel Mode) - the same sheet [EmergencyModeSheet] shows from the
         * in-app 🚨 Emergency Mode button, still gated by Strict Mode never allowing it. This
         * is deliberately NOT the older, session-ending "Emergency unlock" button (still on
         * the session card too) - it just pauses blocking for a bounded window. */
        const val EXTRA_AUTO_EMERGENCY = "widget_auto_emergency"
    }

    private lateinit var binding: ActivityMainBinding
    private var countdownTicker: CountDownTimer? = null
    private var chosenMode: SessionMode? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Website blocking needs VPN permission to work", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Without notifications you won't be alerted when a session ends",
                Toast.LENGTH_LONG
            ).show()
        }
        checkNotificationPermission()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(
            this, binding.root,
            useDarkIcons = !EdgeToEdge.isNightModeActive(this)
        )

        supportFragmentManager.setFragmentResultListener(
            FocusModeSelectionSheet.REQUEST_KEY, this
        ) { _, result ->
            val modeName = result.getString(FocusModeSelectionSheet.RESULT_MODE) ?: return@setFragmentResultListener
            runCatching { SessionMode.valueOf(modeName) }.getOrNull()?.let { mode ->
                chosenMode = mode
                showQuickTimerSetup(mode) 
            }
        }

        supportFragmentManager.setFragmentResultListener(
            QuickTimerSetupSheet.REQUEST_KEY, this
        ) { _, result ->
            val durationMillis = result.getLong(QuickTimerSetupSheet.RESULT_DURATION_MILLIS, 0)
            if (durationMillis > 0 && chosenMode != null) {
                startFocusSession(chosenMode!!, durationMillis)
            }
        }

        supportFragmentManager.setFragmentResultListener(
            EmergencyModeSheet.REQUEST_KEY, this
        ) { _, result ->
            val minutes = result.getInt(EmergencyModeSheet.RESULT_MINUTES, 0)
            val label = result.getString(EmergencyModeSheet.RESULT_LABEL) ?: "Emergency"
            if (minutes > 0) {
                PrefsManager.setEmergencyPause(this, System.currentTimeMillis() + minutes * 60_000L, label)
                refreshSessionUi()
                refreshDashboardStats()
                com.stayfocused.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.cardProtectionStatus.setOnClickListener {
            guardSettingsAccess { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        }
        binding.btnManageApps.setOnClickListener {
            guardSettingsAccess { startActivity(Intent(this, AppSelectionActivity::class.java)) }
        }
        binding.btnManageSites.setOnClickListener {
            guardSettingsAccess { startActivity(Intent(this, WebsiteBlockActivity::class.java)) }
        }
        binding.btnManagePresets.setOnClickListener {
            guardSettingsAccess { startActivity(Intent(this, PresetsActivity::class.java)) }
        }
        binding.btnManageSchedules.setOnClickListener {
            guardSettingsAccess { startActivity(Intent(this, SchedulesActivity::class.java)) }
        }
        binding.btnStartFocus.setOnClickListener { showFocusModeSelectionSheet() }
        binding.btnQuick25.setOnClickListener { openQuickTimer(25 * 60_000L) }
        binding.btnQuick45.setOnClickListener { openQuickTimer(45 * 60_000L) }
        binding.btnQuick60.setOnClickListener { openQuickTimer(60 * 60_000L) }
        binding.btnQuick90.setOnClickListener { openQuickTimer(90 * 60_000L) }
        binding.btnQuick120.setOnClickListener { openQuickTimer(120 * 60_000L) }
        binding.tvPermissionWarning.setOnClickListener { openAccessibilitySettings() }
        binding.tvNotificationWarning.setOnClickListener { requestNotificationPermissionIfNeeded() }
        binding.cardGoal.setOnClickListener { showGoalEditor() }

        binding.btnStopEarly.setOnClickListener {
            val proceed = {
                AlertDialog.Builder(this)
                    .setTitle("Stop this focus session?")
                    .setMessage("Your blocked apps and sites will unlock immediately.")
                    .setPositiveButton("Stop") { _, _ ->
                        lifecycleScope.launch {
                            SessionStateManager.stopSessionEarly(this@MainActivity, "Stopped early by user")
                            SessionTimerService.stopEarly(this@MainActivity)
                            refreshSessionUi()
                            refreshDashboardStats()
                        }
                    }
                    .setNegativeButton("Keep going", null)
                    .show()
            }
            if (PrefsManager.isLockModeActive(this)) {
                LockPinDialog.promptAndVerify(this) { proceed() }
            } else {
                proceed()
            }
        }

        binding.btnEmergencyUnlock.setOnClickListener { triggerEmergencyUnlockFlow() }

        binding.btnEmergencyMode.setOnClickListener {
            if (PrefsManager.isLockModeActive(this)) {
                LockPinDialog.promptAndVerify(this) { showEmergencyModeSheet() }
            } else {
                showEmergencyModeSheet()
            }
        }

        binding.btnResumeNow.setOnClickListener {
            lifecycleScope.launch {
                SessionStateManager.resumeSession(this@MainActivity)
                refreshSessionUi()
                refreshDashboardStats()
                com.stayfocused.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Covers the case where the app is already open (foreground) and a widget button is
        // tapped, so no onPause/onResume cycle happens to trigger refreshAfterUnlock() below.
        handleWidgetIntentExtras()
    }

    /** Same confirmation flow as tapping the in-app Emergency unlock button - still gated by
     * the daily limit and by Strict Mode never allowing it. Pulled out into its own function
     * so the Medium/Large widget's Emergency button can trigger the exact same real flow via
     * [EXTRA_AUTO_EMERGENCY], instead of the widget needing (or being able) to bypass it. */
    private fun triggerEmergencyUnlockFlow() {
        val proceed = {
            if (PrefsManager.canUseEmergencyUnlockToday(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Use your emergency unlock?")
                    .setMessage("You get 1 emergency unlock per day. This will end the current session early. Strict Mode sessions cannot be emergency-unlocked.")
                    .setPositiveButton("Use it") { _, _ ->
                        lifecycleScope.launch {
                            PrefsManager.consumeEmergencyUnlock(this@MainActivity)
                            SessionStateManager.stopSessionEarly(this@MainActivity, "Emergency unlock used")
                            SessionTimerService.stopEarly(this@MainActivity)
                            refreshSessionUi()
                            refreshDashboardStats()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "No emergency unlocks left today", Toast.LENGTH_SHORT).show()
            }
        }
        if (PrefsManager.isLockModeActive(this)) {
            LockPinDialog.promptAndVerify(this) { proceed() }
        } else {
            proceed()
        }
    }

    /** Reads (and immediately clears) the widget deep-link extras set by [EXTRA_AUTO_QUICK_START_MODE]
     * and [EXTRA_AUTO_EMERGENCY]. Clearing them makes this safe to call more than once per
     * launch (onNewIntent and onResume can both fire for the same tap) without double-acting. */
    private fun handleWidgetIntentExtras() {
        if (intent.getBooleanExtra(EXTRA_AUTO_OPEN_MODE_SHEET, false)) {
            intent.removeExtra(EXTRA_AUTO_OPEN_MODE_SHEET)
            showFocusModeSelectionSheet()
        }
        intent.getStringExtra(EXTRA_AUTO_QUICK_START_MODE)?.let { modeName ->
            intent.removeExtra(EXTRA_AUTO_QUICK_START_MODE)
            if (!PrefsManager.isSessionCurrentlyActive(this)) {
                runCatching { SessionMode.valueOf(modeName) }.getOrNull()?.let { openSessionSetup(it) }
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_EMERGENCY, false)) {
            intent.removeExtra(EXTRA_AUTO_EMERGENCY)
            showEmergencyModeSheet()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            SessionStateManager.recoverSessionIfNeeded(applicationContext)
        }
        // If an App Lock PIN is set and the app has just come back to the foreground from
        // being fully backgrounded (or this is a fresh process after a force-stop/reboot),
        // require the PIN before showing anything else. AppLockGate.isUnlocked stays true
        // across purely internal navigation (e.g. returning from Manage Apps), so this only
        // fires when it should.
        if (PrefsManager.hasLockPin(this) && !AppLockGate.isUnlocked) {
            LockPinDialog.promptAppUnlock(this) {
                AppLockGate.isUnlocked = true
                refreshAfterUnlock()
            }
            return
        }
        refreshAfterUnlock()
    }

    private fun refreshAfterUnlock() {
        refreshGreeting()
        refreshProtectionBanner()
        refreshCounts()
        refreshSessionUi()
        refreshDashboardStats()
        checkAccessibilityPermission()
        checkNotificationPermission()
        checkVpnConsentIfSessionActive()
        handleWidgetIntentExtras()
        com.stayfocused.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
    }

    private fun refreshProtectionBanner() {
        val report = ProtectionEngine.evaluate(this)
        when (report.status) {
            ProtectionStatus.PROTECTION_ACTIVE -> {
                binding.tvProtectionStatusIcon.text = "🟢"
                binding.tvProtectionStatusTitle.text = "System Protection Active"
                binding.tvProtectionStatusSub.text = "All protection services running · Checked ${report.getFormattedLastChecked()}"
            }
            ProtectionStatus.PROTECTION_DEGRADED -> {
                binding.tvProtectionStatusIcon.text = "🟡"
                binding.tvProtectionStatusTitle.text = "Protection Partially Active"
                binding.tvProtectionStatusSub.text = "${report.headlineMessage} · Checked ${report.getFormattedLastChecked()}"
            }
            ProtectionStatus.PROTECTION_FAILED -> {
                binding.tvProtectionStatusIcon.text = "🔴"
                binding.tvProtectionStatusTitle.text = "Protection Requires Action"
                binding.tvProtectionStatusSub.text = "${report.headlineMessage} · Checked ${report.getFormattedLastChecked()}"
            }
        }
    }

    /** Time-of-day greeting for the dashboard header. */
    private fun refreshGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        binding.tvGreeting.text = when {
            hour < 5 -> "Still up? 🌙"
            hour < 12 -> "Good morning 👋"
            hour < 17 -> "Good afternoon ☀️"
            hour < 21 -> "Good evening 🌆"
            else -> "Winding down? 🌙"
        }
    }

    /** Loads today's/this-week's focus stats and updates every stat-driven view on the
     * dashboard: the goal ring, streak/productivity/week chips, the weekly bar chart, the
     * statistics row, and the recent sessions list. */
    private fun refreshDashboardStats() {
        lifecycleScope.launch {
            val stats = FocusStatsManager.getDashboardStats(applicationContext)

            // Today's Focus Time + goal ring
            val goalProgress = if (stats.goalMinutes > 0)
                (stats.todayMinutes.toFloat() / stats.goalMinutes.toFloat()).coerceIn(0f, 1f)
            else 0f
            binding.ringGoalProgress.setColors(
                ContextCompat.getColor(this@MainActivity, R.color.gradient_normal_start),
                ContextCompat.getColor(this@MainActivity, R.color.gradient_normal_end)
            )
            binding.ringGoalProgress.progress = goalProgress
            binding.tvTodayMinutes.text = formatMinutes(stats.todayMinutes)
            val remaining = stats.goalMinutes - stats.todayMinutes
            binding.tvRemainingGoal.text = if (remaining <= 0)
                "Goal reached today! 🎉 · Tap to edit"
            else
                "${formatMinutes(remaining)} left to reach your ${formatMinutes(stats.goalMinutes)} goal · Tap to edit"

            // Streak / productivity / this-week chips
            binding.tvStreakCount.text = when (stats.streak) {
                0 -> "Start today"
                1 -> "1 day"
                else -> "${stats.streak} days"
            }
            binding.tvProductivityScore.text = "${stats.productivityScore}%"
            binding.tvWeekTotal.text = formatHoursShort(stats.weekTotalMinutes)

            // Weekly bar chart
            val primary = ContextCompat.getColor(this@MainActivity, R.color.brand_primary)
            val mutedBar = ColorUtils.setAlphaComponent(primary, 90)
            val goalLine = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            val labelColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            binding.weeklyChart.setData(
                bars = stats.weekByDay.map {
                    com.stayfocused.app.ui.widget.WeeklyBarChartView.Bar(it.label, it.minutes, it.isToday)
                },
                goalValue = stats.goalMinutes,
                barColor = mutedBar,
                barHighlightColor = primary,
                goalLineColor = ColorUtils.setAlphaComponent(goalLine, 130),
                labelColor = labelColor
            )
            binding.tvWeekChartCaption.text =
                "Dashed line = your ${formatMinutes(stats.goalMinutes)} daily goal"

            // Statistics row
            binding.tvStatTotalHours.text = formatHoursShort(stats.weekTotalMinutes)
            binding.tvStatAvgSession.text = formatMinutes(stats.avgSessionMinutes)
            binding.tvStatLongestSession.text = formatMinutes(stats.longestSessionMinutes)

            // Smart Insights Tip
            binding.tvSmartInsight.text = stats.insights.summaryTip

            // Recent sessions
            renderRecentSessions(stats.recentSessions)
        }
    }

    private fun renderRecentSessions(sessions: List<SessionHistoryEntry>) {
        binding.containerRecentSessions.removeAllViews()
        binding.tvRecentSessionsEmpty.visibility =
            if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        val whenFormat = SimpleDateFormat("EEE, h:mm a", Locale.getDefault())
        sessions.forEach { session ->
            val row = ItemRecentSessionBinding.inflate(
                LayoutInflater.from(this), binding.containerRecentSessions, false
            )
            val (label, colorRes) = when (session.mode) {
                SessionMode.STRICT -> "Strict Mode" to R.color.strict_red
                SessionMode.LOCK -> "Lock Mode" to R.color.lock_blue
                SessionMode.NORMAL -> "Focus Mode" to R.color.brand_primary
            }
            row.tvSessionModeName.text = label
            row.tvSessionWhen.text = whenFormat.format(java.util.Date(session.endTimeMillis))
            row.tvSessionDuration.text = formatMinutes(session.durationMinutes)
            val dot = row.dotMode.background.mutate() as android.graphics.drawable.GradientDrawable
            dot.setColor(ContextCompat.getColor(this, colorRes))
            binding.containerRecentSessions.addView(row.root)
        }
    }

    /** Lets the person set how many minutes/day they're aiming for - tapping the goal card. */
    private fun showGoalEditor() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefsManager.getDailyGoalMinutes(this@MainActivity).toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Daily focus goal (minutes)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val minutes = input.text.toString().toIntOrNull()
                if (minutes != null) {
                    PrefsManager.setDailyGoalMinutes(this, minutes)
                    refreshDashboardStats()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatMinutes(minutes: Int): String {
        if (minutes <= 0) return "0 min"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m} min"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    private fun formatHoursShort(minutes: Int): String {
        val hours = minutes / 60f
        return if (minutes < 60) "${minutes}m" else String.format(Locale.US, "%.1fh", hours)
    }



    private fun openQuickTimer(durationMillis: Long) {
        if (PrefsManager.isSessionCurrentlyActive(this)) return
        supportFragmentManager.setFragmentResultListener(
            FocusModeSelectionSheet.REQUEST_KEY, this
        ) { _, result ->
            val modeName = result.getString(FocusModeSelectionSheet.RESULT_MODE) ?: return@setFragmentResultListener
            runCatching { SessionMode.valueOf(modeName) }.getOrNull()?.let { mode ->
                startFocusSession(mode, durationMillis)
            }
        }
        FocusModeSelectionSheet().show(supportFragmentManager, FocusModeSelectionSheet.TAG)
    }

    private fun showFocusModeSelectionSheet() {
        if (PrefsManager.isSessionCurrentlyActive(this)) return
        FocusModeSelectionSheet().show(supportFragmentManager, FocusModeSelectionSheet.TAG)
    }

    private fun showQuickTimerSetup(mode: SessionMode) {
        QuickTimerSetupSheet.newInstance(mode).show(supportFragmentManager, QuickTimerSetupSheet.TAG)
    }

    /** Strict/Lock mode confirmation screens are full activities, but Lite (Normal) mode
     * can start immediately once the timer is set. */
    private fun startFocusSession(mode: SessionMode, durationMillis: Long) {
        when (mode) {
            SessionMode.STRICT -> {
                val intent = Intent(this, StrictModeConfirmActivity::class.java)
                intent.putExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, durationMillis)
                startActivity(intent)
            }
            SessionMode.LOCK -> {
                val intent = Intent(this, LockModeConfirmActivity::class.java)
                intent.putExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, durationMillis)
                startActivity(intent)
            }
            SessionMode.NORMAL -> {
                SessionStarter.startSession(this, durationMillis, SessionMode.NORMAL)
                refreshSessionUi()
                refreshDashboardStats()
            }
        }
    }

    /** Strict Mode never gets a pause option - that's the whole point of Strict Mode - and
     * there's no reason to reopen the picker while a pause is already running. */
    private fun showEmergencyModeSheet() {
        if (!PrefsManager.isSessionCurrentlyActive(this)) return
        if (PrefsManager.isStrictModeActive(this)) return
        if (PrefsManager.isEmergencyPauseActive(this)) return
        EmergencyModeSheet().show(supportFragmentManager, EmergencyModeSheet.TAG)
    }

    private fun openSessionSetup(mode: SessionMode) {
        chosenMode = mode
        showQuickTimerSetup(mode)
    }

    private fun refreshCounts() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val apps = db.blockedAppDao().getAllOnce().count { it.isActive }
            val sites = db.blockedSiteDao().getActiveDomainsOnce().size
            val presets = db.focusPresetDao().getAllOnce().size
            val schedules = db.scheduledSessionDao().getAllOnce().count { it.isEnabled }
            binding.tvBlockedAppsCount.text = "$apps selected"
            binding.tvBlockedSitesCount.text = "$sites selected"
            binding.tvPresetsCount.text = "$presets templates"
            binding.tvSchedulesCount.text = if (schedules > 0) "$schedules active" else "None active"
        }
    }

    private fun refreshSessionUi() {
        countdownTicker?.cancel()
        val isActive = PrefsManager.isSessionCurrentlyActive(this)
        binding.cardSessionStatus.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.GONE
        binding.containerQuickStart.visibility = if (isActive) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnStartFocus.isEnabled = !isActive

        if (!isActive) return

        val mode = PrefsManager.getSessionMode(this)
        val isStrict = mode == SessionMode.STRICT
        val isPaused = PrefsManager.isEmergencyPauseActive(this)

        // Strict Mode: hide every early-exit / pause control entirely - zero exceptions,
        // ever. Normal and Lock both keep the controls visible - Lock just gates them behind
        // the PIN dialog set up in onCreate.
        binding.groupSessionButtons.visibility =
            if (isStrict || isPaused) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnResumeNow.visibility = if (isPaused) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnEmergencyUnlock.isEnabled = PrefsManager.canUseEmergencyUnlockToday(this)
        binding.btnEmergencyUnlock.text = if (binding.btnEmergencyUnlock.isEnabled)
            "Emergency unlock (1 left today)"
        else
            "No emergency unlocks left today"

        if (isPaused) {
            binding.tvSessionModeLabel.text = "⏸️ Blocking paused · ${PrefsManager.getEmergencyPauseLabel(this)}"
            binding.cardSessionStatus.setBackgroundResource(R.drawable.bg_gradient_paused)
            binding.ringSessionProgress.setColors(
                ContextCompat.getColor(this, R.color.gold_xp),
                ContextCompat.getColor(this, R.color.gold_xp),
                android.graphics.Color.argb(70, 255, 255, 255)
            )

            val pauseRemaining = PrefsManager.getEmergencyPauseUntil(this) - System.currentTimeMillis()
            if (pauseRemaining <= 0) { refreshSessionUi(); return }

            binding.ringSessionProgress.progress = 1f
            countdownTicker = object : CountDownTimer(pauseRemaining, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    binding.tvSessionCountdown.text = formatTime(millisUntilFinished)
                    binding.ringSessionProgress.progress = millisUntilFinished.toFloat() / pauseRemaining.toFloat()
                }
                override fun onFinish() {
                    // The pause has simply lapsed (no explicit "resume" write needed - see
                    // PrefsManager.isEmergencyPauseActive) - just redraw as a normal running
                    // session again.
                    refreshSessionUi()
                    refreshDashboardStats()
                }
            }.start()
            return
        }

        binding.tvSessionModeLabel.text = when (mode) {
            SessionMode.STRICT -> "🔒 Strict Mode Active"
            SessionMode.LOCK -> "🔐 Lock Mode Active"
            SessionMode.NORMAL -> "🎯 Focus Mode Active"
        }
        // The hero card's whole background is now a mode-colored gradient (rather than
        // tinting the label text), so the white label/countdown text stays readable in
        // every mode - see bg_gradient_normal/_lock/_strict.xml.
        val (heroBgRes, ringStart, ringEnd) = when (mode) {
            SessionMode.STRICT -> Triple(R.drawable.bg_gradient_strict, R.color.gradient_strict_start, R.color.gradient_strict_end)
            SessionMode.LOCK -> Triple(R.drawable.bg_gradient_lock, R.color.gradient_lock_start, R.color.gradient_lock_end)
            SessionMode.NORMAL -> Triple(R.drawable.bg_gradient_normal, R.color.gradient_normal_start, R.color.gradient_normal_end)
        }
        binding.cardSessionStatus.setBackgroundResource(heroBgRes)
        binding.ringSessionProgress.setColors(
            ContextCompat.getColor(this, ringStart),
            ContextCompat.getColor(this, ringEnd),
            android.graphics.Color.argb(70, 255, 255, 255)
        )

        val remaining = PrefsManager.getSessionEndTime(this) - System.currentTimeMillis()
        if (remaining <= 0) { refreshSessionUi(); return }

        binding.ringSessionProgress.progress = 1f
        countdownTicker = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.tvSessionCountdown.text = formatTime(millisUntilFinished)
                binding.ringSessionProgress.progress = millisUntilFinished.toFloat() / remaining.toFloat()
            }
            override fun onFinish() {
                refreshSessionUi()
                refreshDashboardStats()
            }
        }.start()
    }

    /** Guards the Manage Apps / Manage Sites screens - the two places someone could remove
     * a block mid-session. Strict Mode refuses outright; Lock Mode asks for the PIN first;
     * Normal Mode passes straight through. */
    private fun guardSettingsAccess(action: () -> Unit) {
        if (PrefsManager.isStrictModeActive(this)) {
            Toast.makeText(this, "Blocked apps/sites can't be changed during Strict Mode", Toast.LENGTH_SHORT).show()
            return
        }
        if (PrefsManager.isLockModeActive(this)) {
            LockPinDialog.promptAndVerify(this) { action() }
        } else {
            action()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkNotificationPermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        binding.tvNotificationWarning.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
    }


    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun checkAccessibilityPermission() {
        binding.tvPermissionWarning.visibility =
            if (isAccessibilityServiceEnabled()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, AppBlockAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (ComponentName.unflattenFromString(splitter.next()) == expectedComponent) return true
        }
        return false
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** If a session survived a reboot, VPN consent may have been silently revoked by the OS - re-ask. */
    private fun checkVpnConsentIfSessionActive() {
        if (!PrefsManager.isSessionCurrentlyActive(this)) return
        if (PrefsManager.getBlockedDomains(this).isEmpty()) return
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnPermissionLauncher.launch(consentIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        startService(Intent(this, FocusVpnService::class.java))
    }
}
