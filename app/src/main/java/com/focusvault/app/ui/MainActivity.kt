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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.focusvault.app.R
import com.focusvault.app.data.AppDatabase
import com.focusvault.app.data.FocusPreset
import com.focusvault.app.data.SessionHistoryEntry
import com.focusvault.app.data.SessionMode
import com.focusvault.app.data.SessionState
import com.focusvault.app.databinding.ActivityMainBinding
import com.focusvault.app.databinding.ItemPresetBinding
import com.focusvault.app.databinding.ItemRecentSessionBinding
import com.focusvault.app.manager.ProtectionEngine
import com.focusvault.app.manager.ProtectionStatus
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.service.AppBlockAccessibilityService
import com.focusvault.app.service.FocusVpnService
import com.focusvault.app.service.SessionTimerService
import com.focusvault.app.service.StayFocusedDeviceAdminReceiver
import com.focusvault.app.util.AnimationHelper
import com.focusvault.app.util.AppLockGate
import com.focusvault.app.util.EdgeToEdge
import com.focusvault.app.util.FocusStatsManager
import com.focusvault.app.util.HapticHelper
import com.focusvault.app.util.PrefsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_OPEN_MODE_SHEET = "widget_auto_open_mode_sheet"
        const val EXTRA_AUTO_QUICK_START_MODE = "widget_auto_quick_start_mode"
        const val EXTRA_AUTO_EMERGENCY = "widget_auto_emergency"
    }

    private lateinit var binding: ActivityMainBinding
    private var countdownTicker: CountDownTimer? = null
    private var chosenMode: SessionMode = SessionMode.NORMAL
    private var presetsJob: Job? = null
    private var selectedRangeDays: Int = 7

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
                highlightDurationChip(durationMillis)
                com.focusvault.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
            }
        }

        supportFragmentManager.setFragmentResultListener(
            EmergencyModeSheet.REQUEST_KEY, this
        ) { _, result ->
            val minutes = result.getInt(EmergencyModeSheet.RESULT_MINUTES, 0)
            val label = result.getString(EmergencyModeSheet.RESULT_LABEL) ?: "Emergency"
            if (minutes > 0) {
                lifecycleScope.launch {
                    val paused = SessionStateManager.pauseSession(applicationContext, minutes * 60_000L, label)
                    if (paused) {
                        refreshSessionUi()
                        refreshDashboardStats()
                        com.focusvault.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
                    }
                }
            }
        }

        lifecycleScope.launch {
            SessionStateManager.sessionFlow.collect { session ->
                refreshSessionUi()
                refreshDashboardStats()
                binding.tvDistractionsCount.text = (session?.distractionsBlocked ?: 0).toString()
            }
        }

        setupBottomNavigation()
        setupModeSelectorCards()
        setupDurationChips()
        setupSettingsTab()
        setupPresetsTab()
        setupInsightsTab()

        binding.btnClearHistory.setOnClickListener {
            HapticHelper.lightClick(it)
            confirmClearAllHistory()
        }
        binding.btnEmptyStateStartFocus.setOnClickListener {
            HapticHelper.mediumClick(it)
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }

        binding.btnSettings.setOnClickListener {
            HapticHelper.lightClick(it)
            binding.bottomNavigation.selectedItemId = R.id.nav_settings
        }
        binding.cardHeaderShield.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        }
        binding.cardProtectionCenter.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        }
        binding.cardProtectionStatus.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        }
        binding.btnManageApps.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, AppSelectionActivity::class.java)) }
        }
        binding.btnManageSites.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, WebsiteBlockActivity::class.java)) }
        }
        binding.btnManagePresets.setOnClickListener {
            HapticHelper.lightClick(it)
            binding.bottomNavigation.selectedItemId = R.id.nav_presets
        }
        binding.btnManageSchedules.setOnClickListener {
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
                val duration = PrefsManager.getLastChosenDurationMillis(this)
                startFocusSession(chosenMode, duration)
            }
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

        // Attach tactile spring touch physics
        listOf(
            binding.frameFocusRingContainer,
            binding.btnStartFocus,
            binding.btnQuickCustom,
            binding.btnQuick10,
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
            binding.cardDistractions,
            binding.cardProtectionCenter,
            binding.cardHeaderShield,
            binding.cardModeFocus,
            binding.cardModeLock,
            binding.cardModeStrict,
            binding.btnSettingsDiagnostics,
            binding.btnSettingsAdmin,
            binding.btnSettingsPin,
            binding.btnSettingsTheme,
            binding.btnSettingsBackup,
            binding.btnAddCustomPresetMain
        ).forEach { view ->
            AnimationHelper.attachSpringPressFeedback(view)
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            HapticHelper.lightClick(binding.bottomNavigation)
            when (item.itemId) {
                R.id.nav_home -> {
                    switchTab(binding.scrollHome)
                    refreshSessionUi()
                    refreshCounts()
                    refreshProtectionBanner()
                    true
                }
                R.id.nav_insights -> {
                    switchTab(binding.scrollInsights)
                    refreshDashboardStats()
                    true
                }
                R.id.nav_presets -> {
                    switchTab(binding.scrollPresets)
                    loadPresetsMain()
                    true
                }
                R.id.nav_settings -> {
                    switchTab(binding.scrollSettings)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchTab(targetView: View) {
        val allTabs = listOf(binding.scrollHome, binding.scrollInsights, binding.scrollPresets, binding.scrollSettings)
        val currentIndex = allTabs.indexOfFirst { it.visibility == View.VISIBLE }
        val targetIndex = allTabs.indexOf(targetView)
        val direction = if (targetIndex > currentIndex) 40f else -40f

        allTabs.forEach { tab ->
            if (tab == targetView) {
                if (tab.visibility != View.VISIBLE) {
                    tab.alpha = 0f
                    tab.translationX = direction
                    tab.visibility = View.VISIBLE
                    tab.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(180L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            } else {
                tab.visibility = View.GONE
            }
        }
    }

    private fun setupModeSelectorCards() {
        updateModeCardSelection(SessionMode.NORMAL)

        binding.cardModeFocus.setOnClickListener {
            if (PrefsManager.isSessionCurrentlyActive(this)) return@setOnClickListener
            HapticHelper.lightClick(it)
            chosenMode = SessionMode.NORMAL
            updateModeCardSelection(SessionMode.NORMAL)
            animateCardSelection(binding.cardModeFocus)
        }

        binding.cardModeLock.setOnClickListener {
            if (PrefsManager.isSessionCurrentlyActive(this)) return@setOnClickListener
            HapticHelper.lightClick(it)
            chosenMode = SessionMode.LOCK
            updateModeCardSelection(SessionMode.LOCK)
            animateCardSelection(binding.cardModeLock)
        }

        binding.cardModeStrict.setOnClickListener {
            if (PrefsManager.isSessionCurrentlyActive(this)) return@setOnClickListener
            HapticHelper.lightClick(it)
            chosenMode = SessionMode.STRICT
            updateModeCardSelection(SessionMode.STRICT)
            animateCardSelection(binding.cardModeStrict)
        }
    }

    private fun animateCardSelection(card: View) {
        card.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(120L)
            .withEndAction {
                card.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start()
            }
            .start()
    }

    private fun updateModeCardSelection(mode: SessionMode) {
        val primaryColor = ContextCompat.getColor(this, R.color.brand_primary)
        val lockColor = ContextCompat.getColor(this, R.color.lock_blue)
        val strictColor = ContextCompat.getColor(this, R.color.strict_red)
        val borderColor = ContextCompat.getColor(this, R.color.card_border)
        val strokeWidthActive = (2 * resources.displayMetrics.density).toInt()
        val strokeWidthInactive = (1 * resources.displayMetrics.density).toInt()

        when (mode) {
            SessionMode.NORMAL -> {
                binding.cardModeFocus.strokeColor = primaryColor
                binding.cardModeFocus.strokeWidth = strokeWidthActive
                binding.cardModeLock.strokeColor = borderColor
                binding.cardModeLock.strokeWidth = strokeWidthInactive
                binding.cardModeStrict.strokeColor = borderColor
                binding.cardModeStrict.strokeWidth = strokeWidthInactive
            }
            SessionMode.LOCK -> {
                binding.cardModeFocus.strokeColor = borderColor
                binding.cardModeFocus.strokeWidth = strokeWidthInactive
                binding.cardModeLock.strokeColor = lockColor
                binding.cardModeLock.strokeWidth = strokeWidthActive
                binding.cardModeStrict.strokeColor = borderColor
                binding.cardModeStrict.strokeWidth = strokeWidthInactive
            }
            SessionMode.STRICT -> {
                binding.cardModeFocus.strokeColor = borderColor
                binding.cardModeFocus.strokeWidth = strokeWidthInactive
                binding.cardModeLock.strokeColor = borderColor
                binding.cardModeLock.strokeWidth = strokeWidthInactive
                binding.cardModeStrict.strokeColor = strictColor
                binding.cardModeStrict.strokeWidth = strokeWidthActive
            }
        }
    }

    private fun setupDurationChips() {
        val currentDuration = PrefsManager.getLastChosenDurationMillis(this)
        highlightDurationChip(currentDuration)

        binding.btnQuickCustom.setOnClickListener {
            HapticHelper.lightClick(it)
            showCustomDurationPicker()
        }
        binding.btnQuick10.setOnClickListener {
            HapticHelper.lightClick(it)
            selectPresetDuration(10 * 60_000L)
        }
        binding.btnQuick25.setOnClickListener {
            HapticHelper.lightClick(it)
            selectPresetDuration(25 * 60_000L)
        }
        binding.btnQuick45.setOnClickListener {
            HapticHelper.lightClick(it)
            selectPresetDuration(45 * 60_000L)
        }
        binding.btnQuick60.setOnClickListener {
            HapticHelper.lightClick(it)
            selectPresetDuration(60 * 60_000L)
        }
        binding.btnQuick90.setOnClickListener {
            HapticHelper.lightClick(it)
            selectPresetDuration(90 * 60_000L)
        }
        binding.btnQuick120.setOnClickListener {
            HapticHelper.lightClick(it)
            selectPresetDuration(120 * 60_000L)
        }
    }

    private fun highlightDurationChip(durationMillis: Long) {
        val minutes = durationMillis / 60_000L
        val primaryColor = ContextCompat.getColor(this, R.color.brand_primary)
        val textPrimaryColor = ContextCompat.getColor(this, R.color.text_primary)
        val borderColor = ContextCompat.getColor(this, R.color.card_border)

        val chips = listOf(
            binding.btnQuick10 to 10L,
            binding.btnQuick25 to 25L,
            binding.btnQuick45 to 45L,
            binding.btnQuick60 to 60L,
            binding.btnQuick90 to 90L,
            binding.btnQuick120 to 120L
        )

        var matched = false
        chips.forEach { (btn, chipMin) ->
            if (chipMin == minutes) {
                btn.setTextColor(primaryColor)
                (btn as? com.google.android.material.button.MaterialButton)?.strokeColor = ContextCompat.getColorStateList(this, R.color.brand_primary)
                matched = true
            } else {
                btn.setTextColor(textPrimaryColor)
                (btn as? com.google.android.material.button.MaterialButton)?.strokeColor = ContextCompat.getColorStateList(this, R.color.card_border)
            }
        }

        if (!matched) {
            binding.btnQuickCustom.setTextColor(primaryColor)
            (binding.btnQuickCustom as? com.google.android.material.button.MaterialButton)?.strokeColor = ContextCompat.getColorStateList(this, R.color.brand_primary)
        } else {
            binding.btnQuickCustom.setTextColor(textPrimaryColor)
            (binding.btnQuickCustom as? com.google.android.material.button.MaterialButton)?.strokeColor = ContextCompat.getColorStateList(this, R.color.card_border)
        }
    }

    private fun selectPresetDuration(durationMillis: Long) {
        if (PrefsManager.isSessionCurrentlyActive(this)) return
        PrefsManager.setLastChosenDurationMillis(this, durationMillis)
        highlightDurationChip(durationMillis)
        refreshSessionUi()
    }

    private fun setupInsightsTab() {
        binding.chipGroupInsightsRange.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedRangeDays = when (checkedIds.firstOrNull()) {
                R.id.chipRange30Days -> 30
                R.id.chipRangeAllTime -> 365
                else -> 7
            }
            refreshDashboardStats()
        }
    }

    private fun setupSettingsTab() {
        binding.btnSettingsDiagnostics.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        }

        binding.btnSettingsAdmin.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess { startActivity(Intent(this, SettingsActivity::class.java)) }
        }

        binding.btnSettingsPin.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess {
                LockPinDialog.promptSetOrChangePin(this) {}
            }
        }

        binding.btnSettingsTheme.setOnClickListener {
            HapticHelper.lightClick(it)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSettingsBackup.setOnClickListener {
            HapticHelper.lightClick(it)
            guardSettingsAccess {
                BackupRestoreDialog.show(this) {
                    refreshCounts()
                    refreshDashboardStats()
                    refreshProtectionBanner()
                }
            }
        }
    }

    private fun setupPresetsTab() {
        binding.btnAddCustomPresetMain.setOnClickListener {
            HapticHelper.lightClick(it)
            showCreatePresetDialogMain()
        }
    }

    private fun loadPresetsMain() {
        presetsJob?.cancel()
        val db = AppDatabase.getInstance(applicationContext)
        presetsJob = lifecycleScope.launch {
            db.focusPresetDao().observePresets().collectLatest { presets ->
                binding.containerPresetsMain.removeAllViews()
                binding.emptyPresetsContainerMain.visibility = if (presets.isEmpty()) View.VISIBLE else View.GONE
                val inflater = LayoutInflater.from(this@MainActivity)

                presets.forEach { preset ->
                    val row = ItemPresetBinding.inflate(inflater, binding.containerPresetsMain, false)
                    row.tvPresetIcon.text = preset.icon
                    row.tvPresetName.text = preset.name
                    row.tvPresetDetails.text = "${preset.durationMinutes} min · ${preset.mode.name.lowercase().replaceFirstChar { it.uppercase() }} Mode"

                    val suggestion = FocusStatsManager.getAdaptivePresetSuggestion(this@MainActivity, preset.name, preset.durationMinutes)
                    if (suggestion != null) {
                        row.tvPresetSuggestion.visibility = View.VISIBLE
                        row.tvPresetSuggestion.text = "💡 ${suggestion.suggestionMessage}"
                        row.tvPresetSuggestion.setOnClickListener {
                            lifecycleScope.launch {
                                val updated = preset.copy(durationMinutes = suggestion.actualAvgMinutes)
                                db.focusPresetDao().upsert(updated)
                                Toast.makeText(this@MainActivity, "Updated '${preset.name}' preset to ${suggestion.actualAvgMinutes} min!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        row.tvPresetSuggestion.visibility = View.GONE
                    }

                    row.btnStartPreset.setOnClickListener {
                        if (PrefsManager.isSessionCurrentlyActive(this@MainActivity)) {
                            Toast.makeText(this@MainActivity, "A focus session is already active!", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val durationMillis = preset.durationMinutes * 60_000L
                        when (preset.mode) {
                            SessionMode.STRICT -> {
                                val intent = Intent(this@MainActivity, StrictModeConfirmActivity::class.java).apply {
                                    putExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, durationMillis)
                                }
                                startActivity(intent)
                            }
                            SessionMode.LOCK -> {
                                val intent = Intent(this@MainActivity, LockModeConfirmActivity::class.java).apply {
                                    putExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, durationMillis)
                                }
                                startActivity(intent)
                            }
                            SessionMode.NORMAL -> {
                                SessionStarter.startSession(this@MainActivity, durationMillis, SessionMode.NORMAL, preset.name)
                                binding.bottomNavigation.selectedItemId = R.id.nav_home
                            }
                        }
                    }

                    if (preset.isBuiltIn) {
                        row.btnDeletePreset.visibility = View.GONE
                    } else {
                        row.btnDeletePreset.visibility = View.VISIBLE
                        row.btnDeletePreset.setOnClickListener {
                            DialogHelper.showCustomDialog(
                                context = this@MainActivity,
                                title = "Delete Preset 🗑️",
                                message = "Are you sure you want to delete the preset '${preset.name}'?",
                                positiveText = "Delete",
                                positiveAction = {
                                    lifecycleScope.launch {
                                        db.focusPresetDao().delete(preset)
                                        com.google.android.material.snackbar.Snackbar.make(binding.root, "Preset deleted", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                                    }
                                },
                                negativeText = "Cancel"
                            )
                        }
                    }

                    binding.containerPresetsMain.addView(row.root)
                }
            }
        }
    }

    private fun showCreatePresetDialogMain() {
        val density = resources.displayMetrics.density
        val nameInput = DialogHelper.createPillEditText(this, "Preset Name (e.g. Deep Reading)")
        val durationInput = DialogHelper.createPillEditText(
            this,
            "Duration in minutes (e.g. 45)",
            "45",
            android.text.InputType.TYPE_CLASS_NUMBER
        )
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("🟢 Focus Mode (Lite)", "🔐 Lock Mode (PIN Guarded)", "🔒 Strict Mode (Hardcore)")
            )
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }

        fun createLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding((4 * density).toInt(), (8 * density).toInt(), 0, (4 * density).toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            addView(createLabel("PRESET TITLE"))
            addView(nameInput)
            addView(createLabel("TARGET DURATION (MINUTES)"))
            addView(durationInput)
            addView(createLabel("PROTECTION MODE"))
            addView(modeSpinner)
        }

        DialogHelper.showCustomDialog(
            context = this,
            title = "Create Focus Preset 🎯",
            customView = container,
            positiveText = "Save Preset",
            positiveAction = {
                val name = nameInput.text.toString().trim()
                val duration = durationInput.text.toString().toIntOrNull() ?: 0
                val selectedMode = when (modeSpinner.selectedItemPosition) {
                    1 -> SessionMode.LOCK
                    2 -> SessionMode.STRICT
                    else -> SessionMode.NORMAL
                }

                if (name.isEmpty() || duration <= 0) {
                    Toast.makeText(this, "Please enter a valid name and duration", Toast.LENGTH_SHORT).show()
                    return@showCustomDialog
                }

                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(applicationContext)
                    db.focusPresetDao().upsert(
                        FocusPreset(
                            name = name,
                            durationMinutes = duration,
                            mode = selectedMode,
                            icon = "🎯",
                            isBuiltIn = false
                        )
                    )
                    Toast.makeText(this@MainActivity, "Preset '$name' created!", Toast.LENGTH_SHORT).show()
                }
            },
            negativeText = "Cancel"
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntentExtras()
    }

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
                binding.ivHeaderShieldIcon.setImageResource(R.drawable.ic_shield_check)
                binding.ivHeaderShieldIcon.setColorFilter(ContextCompat.getColor(this, R.color.success_green))
                applyShieldBackground(R.color.success_green_chip_bg, false)
                binding.tvProtectionStatusIcon.text = "🛡️"
                binding.tvProtectionStatusTitle.text = "System Protection Active"
                binding.tvProtectionStatusSub.text = "All protection services running · Checked ${report.getFormattedLastChecked()}"
                binding.tvProtectionCenterStatus.text = "100% ACTIVE"
                binding.tvProtectionCenterStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            }
            ProtectionStatus.PROTECTION_DEGRADED -> {
                binding.tvHeaderShieldIcon.text = "⚠️"
                binding.tvHeaderShieldText.text = "Attention"
                binding.tvHeaderShieldText.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
                binding.ivHeaderShieldIcon.setImageResource(R.drawable.ic_shield_alert)
                binding.ivHeaderShieldIcon.setColorFilter(ContextCompat.getColor(this, R.color.warning_amber))
                applyShieldBackground(R.color.warning_amber_chip_bg, false)
                binding.tvProtectionStatusIcon.text = "⚠️"
                binding.tvProtectionStatusTitle.text = "Protection Partially Active"
                binding.tvProtectionStatusSub.text = "${report.headlineMessage} · Checked ${report.getFormattedLastChecked()}"
                binding.tvProtectionCenterStatus.text = "PARTIALLY ACTIVE"
                binding.tvProtectionCenterStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
            }
            ProtectionStatus.PROTECTION_FAILED -> {
                binding.tvHeaderShieldIcon.text = "✕"
                binding.tvHeaderShieldText.text = "Action Needed"
                binding.tvHeaderShieldText.setTextColor(ContextCompat.getColor(this, R.color.strict_red))
                binding.ivHeaderShieldIcon.setImageResource(R.drawable.ic_shield_alert)
                binding.ivHeaderShieldIcon.setColorFilter(ContextCompat.getColor(this, R.color.strict_red))
                applyShieldBackground(R.color.strict_red_chip_bg, true)
                binding.tvProtectionStatusIcon.text = "🔴"
                binding.tvProtectionStatusTitle.text = "Protection Requires Action"
                binding.tvProtectionStatusSub.text = "${report.headlineMessage} · Checked ${report.getFormattedLastChecked()}"
                binding.tvProtectionCenterStatus.text = "ACTION REQUIRED"
                binding.tvProtectionCenterStatus.setTextColor(ContextCompat.getColor(this, R.color.strict_red))
            }
        }

        // Update protection signals checklist
        val a11yActive = isAccessibilityServiceEnabled()
        val adminActive = StayFocusedDeviceAdminReceiver.isDeviceAdminActive(this)
        val vpnConsent = VpnService.prepare(this) == null
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        val successColor = ContextCompat.getColor(this, R.color.success_green)
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)
        val warningColor = ContextCompat.getColor(this, R.color.warning_amber)

        if (a11yActive) {
            binding.ivSignalA11y.setImageResource(R.drawable.ic_check_circle)
            binding.ivSignalA11y.setColorFilter(successColor)
        } else {
            binding.ivSignalA11y.setImageResource(R.drawable.ic_close_circle)
            binding.ivSignalA11y.setColorFilter(warningColor)
        }

        if (adminActive) {
            binding.ivSignalAdmin.setImageResource(R.drawable.ic_check_circle)
            binding.ivSignalAdmin.setColorFilter(successColor)
        } else {
            binding.ivSignalAdmin.setImageResource(R.drawable.ic_close_circle)
            binding.ivSignalAdmin.setColorFilter(mutedColor)
        }

        if (vpnConsent) {
            binding.ivSignalVpn.setImageResource(R.drawable.ic_check_circle)
            binding.ivSignalVpn.setColorFilter(successColor)
        } else {
            binding.ivSignalVpn.setImageResource(R.drawable.ic_close_circle)
            binding.ivSignalVpn.setColorFilter(mutedColor)
        }

        if (notifGranted) {
            binding.ivSignalNotif.setImageResource(R.drawable.ic_check_circle)
            binding.ivSignalNotif.setColorFilter(successColor)
        } else {
            binding.ivSignalNotif.setImageResource(R.drawable.ic_close_circle)
            binding.ivSignalNotif.setColorFilter(mutedColor)
        }
    }

    private var shieldPulseAnimator: android.animation.ValueAnimator? = null

    private fun applyShieldBackground(colorRes: Int, shouldPulse: Boolean) {
        binding.cardHeaderShield.setCardBackgroundColor(ContextCompat.getColor(this, colorRes))
        shieldPulseAnimator?.cancel()
        if (shouldPulse && !AnimationHelper.isReduceMotion(this)) {
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
                if (stats.todayMinutes > 0 && !PrefsManager.prefs(this@MainActivity).getBoolean("goal_celebrated_today", false)) {
                    PrefsManager.prefs(this@MainActivity).edit().putBoolean("goal_celebrated_today", true).apply()
                    HapticHelper.successHaptic(binding.cardGoal)
                    binding.cardGoal.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200)
                        .withEndAction { binding.cardGoal.animate().scaleX(1f).scaleY(1f).setDuration(200).start() }.start()
                }
                "Goal achieved! 🎉"
            } else {
                PrefsManager.prefs(this@MainActivity).edit().putBoolean("goal_celebrated_today", false).apply()
                "${formatMinutes(remaining)} remaining"
            }

            // Streak Badge
            binding.tvStreakBadge.text = if (stats.streak > 0) "${stats.streak}d" else "0d"

            // Insights Top Metric Cards
            binding.tvInsightFocusTime.text = "${stats.todayMinutes}m"
            val totalDistractions = stats.recentSessions.sumOf { it.distractionsBlocked }
            binding.tvInsightDistractions.text = "$totalDistractions"
            binding.tvInsightStreak.text = "${stats.streak}d"

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
        binding.emptySessionsContainer.visibility =
            if (sessions.isEmpty()) View.VISIBLE else View.GONE
        binding.btnClearHistory.visibility =
            if (sessions.isEmpty()) View.GONE else View.VISIBLE

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
        customView: View? = null,
        positiveText: String? = null,
        positiveAction: (() -> Unit)? = null,
        negativeText: String? = null,
        negativeAction: (() -> Unit)? = null
    ) {
        val dialogBinding = com.focusvault.app.databinding.DialogCustomAlertBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = title
        dialogBinding.tvDialogMessage.text = message

        if (customView != null) {
            dialogBinding.containerCustomView.visibility = View.VISIBLE
            dialogBinding.containerCustomView.removeAllViews()
            dialogBinding.containerCustomView.addView(customView)
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_StayFocused_Dialog)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (positiveText != null) {
            dialogBinding.btnDialogPositive.visibility = View.VISIBLE
            dialogBinding.btnDialogPositive.text = positiveText
            dialogBinding.btnDialogPositive.setOnClickListener {
                dialog.dismiss()
                positiveAction?.invoke()
            }
        }

        if (negativeText != null) {
            dialogBinding.btnDialogNegative.visibility = View.VISIBLE
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

    private fun showCustomDurationPicker() {
        if (PrefsManager.isSessionCurrentlyActive(this)) return
        val currentDuration = PrefsManager.getLastChosenDurationMillis(this)
        QuickTimerSetupSheet.newInstance(chosenMode, currentDuration)
            .show(supportFragmentManager, QuickTimerSetupSheet.TAG)
    }

    private fun showFocusModeSelectionSheet() {
        if (PrefsManager.isSessionCurrentlyActive(this)) return
        FocusModeSelectionSheet().show(supportFragmentManager, FocusModeSelectionSheet.TAG)
    }

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

    private fun showEmergencyModeSheet() {
        if (!PrefsManager.isSessionCurrentlyActive(this)) return
        if (PrefsManager.isStrictModeActive(this)) return
        if (PrefsManager.isEmergencyPauseActive(this)) return
        EmergencyModeSheet().show(supportFragmentManager, EmergencyModeSheet.TAG)
    }

    private fun openSessionSetup(mode: SessionMode) {
        chosenMode = mode
        updateModeCardSelection(mode)
        val duration = PrefsManager.getLastChosenDurationMillis(this)
        QuickTimerSetupSheet.newInstance(mode, duration).show(supportFragmentManager, QuickTimerSetupSheet.TAG)
    }

    private fun refreshCounts() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val apps = db.blockedAppDao().getAllOnce().count { it.isActive }
            val sites = db.blockedSiteDao().getActiveDomainsOnce().size
            val schedules = db.scheduledSessionDao().getAllOnce().count { it.isEnabled }
            val presets = db.focusPresetDao().getAllOnce().size
            
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

            binding.tvPresetsCount.text = "$presets modes"
        }
    }

    fun refreshAfterCompletion() {
        refreshSessionUi()
        refreshDashboardStats()
    }

    private fun refreshSessionUi() {

        countdownTicker?.cancel()
        val isActive = PrefsManager.isSessionCurrentlyActive(this)

        if (!isActive) {
            AnimationHelper.stopBreathingAura(binding.frameFocusRingContainer)
            binding.cardHeroFocus.strokeColor = ContextCompat.getColor(this, R.color.card_border)
            binding.btnStartFocus.visibility = View.VISIBLE
            binding.containerActiveActions.visibility = View.GONE
            binding.btnEmergencyUnlock.visibility = View.GONE
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

        binding.btnStartFocus.visibility = View.GONE
        binding.containerActiveActions.visibility = View.VISIBLE
        binding.btnResumeNow.visibility = if (isPaused) View.VISIBLE else View.GONE
        binding.btnEmergencyMode.visibility = if (isStrict || isPaused) View.GONE else View.VISIBLE
        binding.btnStopEarly.visibility = if (isStrict) View.GONE else View.VISIBLE
        binding.btnEmergencyUnlock.visibility = if (!isStrict && !isPaused && PrefsManager.canUseEmergencyUnlockToday(this))
            View.VISIBLE else View.GONE

        if (isPaused) {
            AnimationHelper.stopBreathingAura(binding.frameFocusRingContainer)
            binding.cardHeroFocus.strokeColor = ContextCompat.getColor(this, R.color.warning_amber)
            val pauseLabel = PrefsManager.getEmergencyPauseLabel(this)
            binding.tvHeroStateBadge.text = "PAUSED · ${pauseLabel.uppercase()}"
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
                binding.tvHeroStateBadge.text = "STRICT FOCUS"
                binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this, R.color.strict_red))
                binding.tvHeroSubtitle.text = "Unlocks & pausing disabled"
            }
            SessionMode.LOCK -> {
                binding.tvHeroStateBadge.text = "LOCK FOCUS"
                binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this, R.color.lock_blue))
                binding.tvHeroSubtitle.text = "PIN required to unlock early"
            }
            SessionMode.NORMAL -> {
                binding.tvHeroStateBadge.text = "FOCUS ACTIVE"
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
                AnimationHelper.stopBreathingAura(binding.frameFocusRingContainer)
                binding.cardHeroFocus.strokeColor = ContextCompat.getColor(this@MainActivity, R.color.success_green)
                binding.tvHeroStateBadge.text = "SESSION COMPLETE"
                binding.tvHeroStateBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success_green))
                binding.ringGoalProgress.applyFocusStateColors(isActive = false, isPaused = false, isCompleted = true)
                AnimationHelper.animateCelebrationBloom(binding.frameFocusRingContainer)
                HapticHelper.successHaptic(binding.root)
                
                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(applicationContext)
                    val history = db.sessionHistoryDao().getRecent(1).firstOrNull()
                    val duration = history?.durationMinutes ?: (totalDuration / 60000).toInt()
                    val distractions = history?.distractionsBlocked ?: 0
                    SessionCompleteSheet.newInstance(duration, distractions)
                        .show(supportFragmentManager, SessionCompleteSheet.TAG)
                }
            }
        }.start()
    }

    private fun guardSettingsAccess(action: () -> Unit) {
        if (PrefsManager.isStrictModeActive(this)) {
            Toast.makeText(this, "Settings are locked during Strict Mode", Toast.LENGTH_SHORT).show()
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
        binding.tvNotificationWarning.visibility = if (granted) View.GONE else View.VISIBLE
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
            if (isAccessibilityServiceEnabled()) View.GONE else View.VISIBLE
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
