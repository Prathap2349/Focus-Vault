package com.focusvault.app.ui

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
import com.focusvault.app.R
import com.focusvault.app.data.AppDatabase
import com.focusvault.app.data.SessionHistoryEntry
import com.focusvault.app.data.SessionMode
import com.focusvault.app.data.SessionState
import com.focusvault.app.databinding.ActivityMainBinding
import com.focusvault.app.databinding.ItemRecentSessionBinding
import com.focusvault.app.service.AppBlockAccessibilityService
import com.focusvault.app.service.FocusVpnService
import com.focusvault.app.service.SessionTimerService
import com.focusvault.app.manager.ProtectionEngine
import com.focusvault.app.manager.ProtectionStatus
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.util.AnimationHelper
import com.focusvault.app.util.AppLockGate
import com.focusvault.app.util.EdgeToEdge
import com.focusvault.app.util.FocusStatsManager
import com.focusvault.app.util.HapticHelper
import com.focusvault.app.util.PrefsManager
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
                val duration = PrefsManager.getLastChosenDurationMillis(this)
                startFocusSession(mode, duration)
            }
        }

        supportFragmentManager.setFragmentResultListener(
            QuickTimerSetupSheet.REQUEST_KEY, this
        ) { _, result ->
            val durationMillis = result.getLong(QuickTimerSetupSheet.RESULT_DURATION_MILLIS, 0)
            if (durationMillis > 0) {
                PrefsManager.setLastChosenDurationMillis(this, durationMillis)
                refreshSessionUi()
                com.focusvault.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
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
                com.focusvault.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
            }
        }

        lifecycleScope.launch {
            SessionStateManager.sessionFlow.collect { session ->
                refreshSessionUi()
                refreshDashboardStats()
                
                // Update distraction count for the new UI card
                binding.tvDistractionsCount.text = (session?.distractionsBlocked ?: 0).toString()
            }
        }

        binding.btnClearHistory.setOnClickListener {
            HapticHelper.lightClick(it)
            confirmClearAllHistory()
        }

        binding.btnSettings.setOnClickListener {
            HapticHelper.lightClick(it)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.cardHeaderShield.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        }
        binding.cardProtectionStatus.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        }
        binding.btnManageApps.setOnClickListener {
            com.focusvault.app.util.HapticHelper.mediumClick(it)
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, AppSelectionActivity::class.java)) }
        }
        binding.btnManageSites.setOnClickListener {
            com.focusvault.app.util.HapticHelper.mediumClick(it)
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, WebsiteBlockActivity::class.java)) }
        }
        binding.btnManagePresets.setOnClickListener {
            com.focusvault.app.util.HapticHelper.mediumClick(it)
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, PresetsActivity::class.java)) }
        }
        binding.btnManageSchedules.setOnClickListener {
            com.focusvault.app.util.HapticHelper.mediumClick(it)
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, SchedulesActivity::class.java)) }
        }

        // Tapping the centerpiece timer hero opens duration adjustment
        binding.frameFocusRingContainer.setOnClickListener {
            if (!PrefsManager.isSessionCurrentlyActive(this)) {
                HapticHelper.lightClick(it)
                showCustomDurationPicker()
            }
        }

        binding.btnStartFocus.setOnClickListener {
            AnimationHelper.animateButtonPress(it) {
                HapticHelper.heavyClick(it)
                showFocusModeSelectionSheet()
            }
        }

        binding.btnQuickCustom.setOnClickListener {
            com.focusvault.app.util.HapticHelper.lightClick(it)
            HapticHelper.lightClick(it)
            showCustomDurationPicker()
        }

        binding.btnQuick25.setOnClickListener {
            com.focusvault.app.util.HapticHelper.lightClick(it)
            selectPresetDuration(25 * 60_000L)
        }
        binding.btnQuick45.setOnClickListener {
            com.focusvault.app.util.HapticHelper.lightClick(it)
            selectPresetDuration(45 * 60_000L)
        }
        binding.btnQuick60.setOnClickListener {
            com.focusvault.app.util.HapticHelper.lightClick(it)
            selectPresetDuration(60 * 60_000L)
        }
        binding.btnQuick90.setOnClickListener {
            com.focusvault.app.util.HapticHelper.lightClick(it)
            selectPresetDuration(90 * 60_000L)
        }
        binding.btnQuick120.setOnClickListener {
            com.focusvault.app.util.HapticHelper.lightClick(it)
            selectPresetDuration(120 * 60_000L)
        }
        binding.tvPermissionWarning.setOnClickListener { openAccessibilitySettings() }
        binding.tvNotificationWarning.setOnClickListener { requestNotificationPermissionIfNeeded() }
        binding.cardGoal.setOnClickListener {
            HapticHelper.lightClick(it)
            showGoalEditor()
        }

        binding.btnStopEarly.setOnClickListener {
            HapticHelper.mediumClick(it)
            val proceed = {
                showCustomDialog(
                    title = "Stop this focus session?",
                    message = "Your blocked apps and sites will unlock immediately.",
                    positiveText = "Stop",
                    positiveAction = {
                        lifecycleScope.launch {
                            SessionStateManager.stopSessionEarly(this@MainActivity, "Stopped early by user")
                            SessionTimerService.stopEarly(this@MainActivity)
                            refreshSessionUi()
                            refreshDashboardStats()
                        }
                    },
                    negativeText = "Keep going"
                )
            }
            if (PrefsManager.isLockModeActive(this)) {
                LockPinDialog.promptAndVerify(this) { proceed() }
            } else {
                proceed()
            }
        }

        binding.btnEmergencyUnlock.setOnClickListener {
            HapticHelper.mediumClick(it)
            triggerEmergencyUnlockFlow()
        }

        binding.btnEmergencyMode.setOnClickListener {
            HapticHelper.mediumClick(it)
            if (PrefsManager.isLockModeActive(this)) {
                LockPinDialog.promptAndVerify(this) { showEmergencyModeSheet() }
            } else {
                showEmergencyModeSheet()
            }
        }

        binding.btnResumeNow.setOnClickListener {
            HapticHelper.mediumClick(it)
            lifecycleScope.launch {
                SessionStateManager.resumeSession(this@MainActivity)
                refreshSessionUi()
                refreshDashboardStats()
                com.focusvault.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
            }
        }

        // Attach tactile spring touch physics to cards, chips, and action buttons
        listOf(
            binding.frameFocusRingContainer,
            binding.btnStartFocus,
            binding.btnQuickCustom,
            binding.btnQuick25,
            binding.btnQuick45,
            binding.btnQuick60,
            binding.btnQuick90,
            binding.btnQuick120,
            binding.btnManageApps,
            binding.btnManageSites,
            binding.btnManagePresets,
            binding.btnManageSchedules,
            binding.btnStopEarly,
            binding.btnResumeNow,
            binding.btnEmergencyMode,
            binding.btnEmergencyUnlock,
            binding.cardGoal,
            binding.cardProtectionStatus
        ).forEach { view ->
            AnimationHelper.attachSpringPressFeedback(view)
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
                showCustomDialog(
                    title = "Use Emergency Unlock? 🚨",
                    message = "You get 1 emergency unlock per day. This will end the current focus session early.\n\nNote: Strict Mode sessions cannot be emergency-unlocked.",
                    positiveText = "Use Unlock",
                    positiveAction = {
                        lifecycleScope.launch {
                            PrefsManager.consumeEmergencyUnlock(this@MainActivity)
                            SessionStateManager.stopSessionEarly(this@MainActivity, "Emergency unlock used")
                            SessionTimerService.stopEarly(this@MainActivity)
                            refreshSessionUi()
                            refreshDashboardStats()
                        }
                    },
                    negativeText = "Cancel"
                )
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
        if (PrefsManager.isSessionCurrentlyActive(this) && PrefsManager.isStrictModeActive(this)) {
            startActivity(Intent(this, StrictModeStatusActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
            return
        }

        refreshGreeting()
        refreshProtectionBanner()
        refreshCounts()
        refreshSessionUi()
        refreshDashboardStats()
        checkAccessibilityPermission()
        checkNotificationPermission()
        checkVpnConsentIfSessionActive()
        handleWidgetIntentExtras()
        com.focusvault.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
    }

    private fun refreshProtectionBanner() {
        val report = ProtectionEngine.evaluate(this)
        when (report.status) {
            ProtectionStatus.PROTECTION_ACTIVE -> {
                binding.tvHeaderShieldIcon.text = "🛡️"
                binding.tvHeaderShieldText.text = "Protected"
                binding.tvHeaderShieldText.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                applyShieldBackground(R.color.success_green_chip_bg, false)
                binding.tvProtectionStatusIcon.text = "🛡️"
                binding.tvProtectionStatusTitle.text = "System Protection Active"
                binding.tvProtectionStatusSub.text = "All protection services running · Checked ${report.getFormattedLastChecked()}"
            }
            ProtectionStatus.PROTECTION_DEGRADED -> {
                binding.tvHeaderShieldIcon.text = "⚠️"
                binding.tvHeaderShieldText.text = "Attention"
                binding.tvHeaderShieldText.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
                applyShieldBackground(R.color.warning_amber_chip_bg, false)
                binding.tvProtectionStatusIcon.text = "⚠️"
                binding.tvProtectionStatusTitle.text = "Protection Partially Active"
                binding.tvProtectionStatusSub.text = "${report.headlineMessage} · Checked ${report.getFormattedLastChecked()}"
            }
            ProtectionStatus.PROTECTION_FAILED -> {
                binding.tvHeaderShieldIcon.text = "✕"
                binding.tvHeaderShieldText.text = "Action Needed"
                binding.tvHeaderShieldText.setTextColor(ContextCompat.getColor(this, R.color.strict_red))
                applyShieldBackground(R.color.strict_red_chip_bg, true)
                binding.tvProtectionStatusIcon.text = "🔴"
                binding.tvProtectionStatusTitle.text = "Protection Requires Action"
                binding.tvProtectionStatusSub.text = "${report.headlineMessage} · Checked ${report.getFormattedLastChecked()}"
            }
        }
    }

    /** Time-of-day greeting for the dashboard header. */
    
    private var shieldPulseAnimator: android.animation.ValueAnimator? = null

    private fun applyShieldBackground(colorRes: Int, shouldPulse: Boolean) {
        binding.cardHeaderShield.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(this, colorRes))
        shieldPulseAnimator?.cancel()
        if (shouldPulse && !com.focusvault.app.util.AnimationHelper.isReduceMotion(this)) {
            shieldPulseAnimator = android.animation.ValueAnimator.ofFloat(1f, 0.6f).apply {
                duration = 800L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { va ->
                    binding.cardHeaderShield.alpha = va.animatedValue as Float
                }
                start()
            }
        } else {
            binding.cardHeaderShield.alpha = 1f
        }
    }

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
     * dashboard: the goal progress bar, streak chips, the weekly bar chart, and recent sessions. */
    private fun refreshDashboardStats() {
        lifecycleScope.launch {
            val stats = FocusStatsManager.getDashboardStats(applicationContext)

            // Today's Focus Goal
            val goalProgress = if (stats.goalMinutes > 0)
                (stats.todayMinutes.toFloat() / stats.goalMinutes.toFloat()).coerceIn(0f, 1f)
            else 0f

            AnimationHelper.animateOdometerRoll(
                textView = binding.tvGoalToday,
                startValue = 0,
                endValue = stats.todayMinutes,
                formatter = { minutesVal: Int -> "$minutesVal / ${stats.goalMinutes} min" }
            )
            binding.progressGoalBar.progress = (goalProgress * 100).toInt()
            val remaining = stats.goalMinutes - stats.todayMinutes
            
            binding.tvGoalRemaining.text = if (remaining <= 0) {
                if (stats.todayMinutes > 0 && !com.focusvault.app.util.PrefsManager.prefs(this@MainActivity).getBoolean("goal_celebrated_today", false)) {
                    com.focusvault.app.util.PrefsManager.prefs(this@MainActivity).edit().putBoolean("goal_celebrated_today", true).apply()
                    com.focusvault.app.util.HapticHelper.successHaptic(binding.cardGoal)
                    binding.cardGoal.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200)
                        .withEndAction { binding.cardGoal.animate().scaleX(1f).scaleY(1f).setDuration(200).start() }.start()
                }
                "Goal achieved! 🎉"
            } else {
                com.focusvault.app.util.PrefsManager.prefs(this@MainActivity).edit().putBoolean("goal_celebrated_today", false).apply()
                "${formatMinutes(remaining)} remaining"
            }


            // Streak Badge
            binding.tvStreakBadge.text = if (stats.streak > 0) "🔥 ${stats.streak}d streak" else "🌱 Start streak"

            // Weekly bar chart
            val primary = ContextCompat.getColor(this@MainActivity, R.color.brand_primary)
            val mutedBar = ColorUtils.setAlphaComponent(primary, 90)
            val goalLine = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            val labelColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            binding.chartWeekly.setData(
                bars = stats.weekByDay.map {
                    com.focusvault.app.ui.widget.WeeklyBarChartView.Bar(it.label, it.minutes, it.isToday)
                },
                goalValue = stats.goalMinutes,
                barColor = mutedBar,
                barHighlightColor = primary,
                goalLineColor = ColorUtils.setAlphaComponent(goalLine, 130),
                labelColor = labelColor
            )

            // Recent sessions
            renderRecentSessions(stats.recentSessions)
        }
    }

    private fun renderRecentSessions(sessions: List<SessionHistoryEntry>) {
        binding.containerRecentSessions.removeAllViews()
        binding.tvEmptySessions.visibility =
            if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnClearHistory.visibility =
            if (sessions.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        val whenFormat = SimpleDateFormat("EEE, h:mm a", Locale.getDefault())
        sessions.take(10).forEach { session ->
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

            row.btnDeleteSession.setOnClickListener {
                HapticHelper.lightClick(it)
                confirmDeleteSession(session)
            }
            binding.containerRecentSessions.addView(row.root)
        }
    }

    private fun confirmDeleteSession(session: SessionHistoryEntry) {
        showCustomDialog(
            title = "Delete Session Entry?",
            message = "Remove this ${session.durationMinutes} min session entry from your recent history?",
            positiveText = "Delete",
            positiveAction = {
                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(applicationContext)
                    db.sessionHistoryDao().deleteById(session.id)
                    refreshDashboardStats()
                }
            },
            negativeText = "Cancel"
        )
    }

    private fun confirmClearAllHistory() {
        showCustomDialog(
            title = "Clear All Session History?",
            message = "This will remove all session entries from your recent history.",
            positiveText = "Clear All",
            positiveAction = {
                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(applicationContext)
                    db.sessionHistoryDao().deleteAll()
                    refreshDashboardStats()
                }
            },
            negativeText = "Cancel"
        )
    }

    /** Lets the person set how many minutes/day they're aiming for - tapping the goal card. */
    private fun showGoalEditor() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefsManager.getDailyGoalMinutes(this@MainActivity).toString())
            setSelection(text.length)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        showCustomDialog(
            title = "Daily Focus Goal (Minutes)",
            message = "Set your target daily focus time:",
            customView = input,
            positiveText = "Save",
            positiveAction = {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes != null && minutes > 0) {
                    PrefsManager.setDailyGoalMinutes(this, minutes)
                    refreshDashboardStats()
                }
            },
            negativeText = "Cancel"
        )
    }

    private fun showCustomDialog(
        title: String,
        message: String,
        customView: android.view.View? = null,
        positiveText: String? = null,
        positiveAction: (() -> Unit)? = null,
        negativeText: String? = null,
        negativeAction: (() -> Unit)? = null
    ) {
        val dialogBinding = com.focusvault.app.databinding.DialogCustomAlertBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = title
        dialogBinding.tvDialogMessage.text = message

        if (customView != null) {
            dialogBinding.containerCustomView.visibility = android.view.View.VISIBLE
            dialogBinding.containerCustomView.removeAllViews()
            dialogBinding.containerCustomView.addView(customView)
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_StayFocused_Dialog)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (positiveText != null) {
            dialogBinding.btnDialogPositive.visibility = android.view.View.VISIBLE
            dialogBinding.btnDialogPositive.text = positiveText
            dialogBinding.btnDialogPositive.setOnClickListener {
                dialog.dismiss()
                positiveAction?.invoke()
            }
        }

        if (negativeText != null) {
            dialogBinding.btnDialogNegative.visibility = android.view.View.VISIBLE
            dialogBinding.btnDialogNegative.text = negativeText
            dialogBinding.btnDialogNegative.setOnClickListener {
                dialog.dismiss()
                negativeAction?.invoke()
            }
        }

        dialog.show()
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

    private fun selectPresetDuration(durationMillis: Long) {
        if (PrefsManager.isSessionCurrentlyActive(this)) return
        HapticHelper.lightClick(binding.root)
        PrefsManager.setLastChosenDurationMillis(this, durationMillis)
        refreshSessionUi()
        showFocusModeSelectionSheet()
    }

    private fun showCustomDurationPicker() {
        if (PrefsManager.isSessionCurrentlyActive(this)) return
        val currentDuration = PrefsManager.getLastChosenDurationMillis(this)
        QuickTimerSetupSheet.newInstance(SessionMode.NORMAL, currentDuration)
            .show(supportFragmentManager, QuickTimerSetupSheet.TAG)
    }

    private fun showFocusModeSelectionSheet() {
        if (PrefsManager.isSessionCurrentlyActive(this)) return
        FocusModeSelectionSheet().show(supportFragmentManager, FocusModeSelectionSheet.TAG)
    }

    /** Strict/Lock mode confirmation screens are full activities, but Lite (Normal) mode
     * can start immediately once the timer is set. */
    private fun startFocusSession(mode: SessionMode, durationMillis: Long) {
        PrefsManager.setLastChosenDurationMillis(this, durationMillis)
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
                // Vault Lock-In Sequence
                try {
                    binding.ringGoalProgress.playLockInAnimation()
                    AnimationHelper.animateVaultLockIn(binding.frameFocusRingContainer)
                    HapticHelper.successHaptic(binding.root)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Animation error during session start", e)
                }
                SessionStarter.startSession(this, durationMillis, SessionMode.NORMAL) {
                    refreshSessionUi()
                    refreshDashboardStats()
                }
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
        val duration = PrefsManager.getLastChosenDurationMillis(this)
        QuickTimerSetupSheet.newInstance(mode, duration).show(supportFragmentManager, QuickTimerSetupSheet.TAG)
    }

    private fun refreshCounts() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val apps = db.blockedAppDao().getAllOnce().count { it.isActive }
            val sites = db.blockedSiteDao().getActiveDomainsOnce().size
            val schedules = db.scheduledSessionDao().getAllOnce().count { it.isEnabled }
            
            val primaryColor = ContextCompat.getColor(this@MainActivity, R.color.brand_primary)
            val secondaryColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            
            if (apps > 0) {
                binding.tvBlockedAppsCount.text = "$apps blocked"
                binding.tvBlockedAppsCount.setTextColor(secondaryColor)
            } else {
                binding.tvBlockedAppsCount.text = "Tap to add"
                binding.tvBlockedAppsCount.setTextColor(primaryColor)
            }
            
            if (sites > 0) {
                binding.tvBlockedSitesCount.text = "$sites blocked"
                binding.tvBlockedSitesCount.setTextColor(secondaryColor)
            } else {
                binding.tvBlockedSitesCount.text = "Tap to add"
                binding.tvBlockedSitesCount.setTextColor(primaryColor)
            }
            
            if (schedules > 0) {
                binding.tvSchedulesCount.text = "$schedules active"
                binding.tvSchedulesCount.setTextColor(secondaryColor)
            } else {
                binding.tvSchedulesCount.text = "Tap to create"
                binding.tvSchedulesCount.setTextColor(primaryColor)
            }

        }
    }

    private fun refreshSessionUi() {
        countdownTicker?.cancel()
        val isActive = PrefsManager.isSessionCurrentlyActive(this)

        if (!isActive) {
            com.focusvault.app.util.AnimationHelper.stopBreathingAura(binding.frameFocusRingContainer)
            binding.cardHeroFocus.strokeColor = ContextCompat.getColor(this, R.color.card_border)
            binding.btnStartFocus.visibility = android.view.View.VISIBLE
            binding.containerActiveActions.visibility = android.view.View.GONE
            binding.btnEmergencyUnlock.visibility = android.view.View.GONE
            binding.tvHeroStateBadge.text = "READY TO FOCUS"
            binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            val idleDuration = PrefsManager.getLastChosenDurationMillis(this)
            binding.tvTimerHeroDigits.text = formatTime(idleDuration)
            binding.tvHeroSubtitle.text = "Tap timer to adjust · Tap Start to begin"
            binding.ringGoalProgress.applyFocusStateColors(isActive = false, isPaused = false, isStrict = false)
            binding.ringGoalProgress.progress = 0f
            return
        }

        val mode = PrefsManager.getSessionMode(this)
        val isStrict = mode == SessionMode.STRICT
        val isPaused = PrefsManager.isEmergencyPauseActive(this)

        binding.btnStartFocus.visibility = android.view.View.GONE
        binding.containerActiveActions.visibility = android.view.View.VISIBLE
        binding.btnResumeNow.visibility = if (isPaused) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnEmergencyMode.visibility = if (isStrict || isPaused) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnStopEarly.visibility = if (isStrict) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnEmergencyUnlock.visibility = if (!isStrict && !isPaused && PrefsManager.canUseEmergencyUnlockToday(this))
            android.view.View.VISIBLE else android.view.View.GONE

        if (isPaused) {
            com.focusvault.app.util.AnimationHelper.stopBreathingAura(binding.frameFocusRingContainer)
            binding.cardHeroFocus.strokeColor = ContextCompat.getColor(this, R.color.warning_amber)
            val pauseLabel = PrefsManager.getEmergencyPauseLabel(this)
            binding.tvHeroStateBadge.text = "⏸️ PAUSED · ${pauseLabel.uppercase()}"
            binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
            binding.tvHeroSubtitle.text = "Protection temporarily paused"
            binding.ringGoalProgress.applyFocusStateColors(isActive = false, isPaused = true, isStrict = false)

            val pauseRemaining = PrefsManager.getEmergencyPauseUntil(this) - System.currentTimeMillis()
            if (pauseRemaining <= 0) {
                refreshSessionUi()
                return
            }

            binding.ringGoalProgress.progress = 1f
            countdownTicker = object : CountDownTimer(pauseRemaining, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    binding.tvTimerHeroDigits.text = formatTime(millisUntilFinished)
                    binding.ringGoalProgress.progress = millisUntilFinished.toFloat() / pauseRemaining.toFloat()
                }
                override fun onFinish() {
                    refreshSessionUi()
                    refreshDashboardStats()
                }
            }.start()
            return
        }

        // Active Session
        binding.cardHeroFocus.strokeColor = ContextCompat.getColor(this, if (isStrict) R.color.strict_red else R.color.brand_primary)
        when (mode) {
            SessionMode.STRICT -> {
                binding.tvHeroStateBadge.text = "🔒 STRICT FOCUS"
                binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this, R.color.strict_red))
                binding.tvHeroSubtitle.text = "Unlocks & pausing disabled"
            }
            SessionMode.LOCK -> {
                binding.tvHeroStateBadge.text = "🔐 LOCK FOCUS"
                binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this, R.color.lock_blue))
                binding.tvHeroSubtitle.text = "PIN required to unlock early"
            }
            SessionMode.NORMAL -> {
                binding.tvHeroStateBadge.text = "🛡️ FOCUS ACTIVE"
                binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
                binding.tvHeroSubtitle.text = "Distractions blocked"
            }
        }

        val totalDuration = (PrefsManager.getSessionEndTime(this) - PrefsManager.getSessionStartTime(this)).coerceAtLeast(1000L)
        val remaining = PrefsManager.getSessionEndTime(this) - System.currentTimeMillis()
        if (remaining <= 0) {
            refreshSessionUi()
            return
        }

        val initialRatio = (remaining.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
        binding.ringGoalProgress.applyFocusStateColors(
            isActive = true,
            isPaused = false,
            isStrict = isStrict,
            remainingRatio = initialRatio
        )

        binding.tvTimerHeroDigits.text = formatTime(remaining)
        binding.ringGoalProgress.progress = initialRatio

        countdownTicker = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val ratio = (millisUntilFinished.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                binding.tvTimerHeroDigits.text = formatTime(millisUntilFinished)
                binding.ringGoalProgress.progress = ratio
                binding.ringGoalProgress.applyFocusStateColors(
                    isActive = true,
                    isPaused = false,
                    isStrict = isStrict,
                    remainingRatio = ratio
                )
            }
            override fun onFinish() {
                com.focusvault.app.util.AnimationHelper.stopBreathingAura(binding.frameFocusRingContainer)
                binding.cardHeroFocus.strokeColor = ContextCompat.getColor(this@MainActivity, R.color.success_green)
                binding.tvHeroStateBadge.text = "✓ SESSION COMPLETE"
                binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success_green))
                binding.ringGoalProgress.applyFocusStateColors(isActive = false, isPaused = false, isCompleted = true)
                com.focusvault.app.util.AnimationHelper.animateCelebrationBloom(binding.frameFocusRingContainer)
                com.focusvault.app.util.HapticHelper.successHaptic(binding.root)
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
