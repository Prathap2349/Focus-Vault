package com.focusvault.app.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.focusvault.app.R
import com.focusvault.app.databinding.ActivitySettingsBinding
import com.focusvault.app.service.StayFocusedDeviceAdminReceiver
import com.focusvault.app.util.BiometricHelper
import com.focusvault.app.util.EdgeToEdge
import com.focusvault.app.util.PrefsManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val deviceAdminComponent by lazy {
        ComponentName(this, StayFocusedDeviceAdminReceiver::class.java)
    }

    private val deviceAdminLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { refreshAppLockStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdge.apply(this, binding.root, useDarkIcons = !EdgeToEdge.isNightModeActive(this))

        binding.btnBack.setOnClickListener { finish() }

        binding.btnAppLockPin.setOnClickListener {
            guardRestrictedAction {
                LockPinDialog.promptSetOrChangePin(this) {
                    refreshAppLockPinStatus()
                    refreshBiometricStatus()
                }
            }
        }

        binding.btnAppLock.setOnClickListener {
            guardRestrictedAction {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                if (dpm.isAdminActive(deviceAdminComponent)) {
                    Toast.makeText(
                        this,
                        "To turn uninstall protection off, confirm on the next system screen",
                        Toast.LENGTH_LONG
                    ).show()
                }
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Prevents Stay Focused from being uninstalled with a single accidental tap."
                    )
                }
                deviceAdminLauncher.launch(intent)
            }
        }

        binding.btnBiometric.setOnClickListener {
            guardRestrictedAction { toggleBiometricUnlock() }
        }

        binding.btnTheme.setOnClickListener { showThemePicker() }

        binding.btnThemePalette.setOnClickListener { showPalettePicker() }

        binding.btnAmoledMode.setOnClickListener {
            val next = !PrefsManager.isAmoledMode(this)
            PrefsManager.setAmoledMode(this, next)
            binding.switchAmoled.isChecked = next
            recreate()
        }

        binding.btnReduceMotion.setOnClickListener {
            val next = !PrefsManager.isReduceMotion(this)
            PrefsManager.setReduceMotion(this, next)
            binding.switchReduceMotion.isChecked = next
            Toast.makeText(this, if (next) "Reduce Motion enabled" else "Reduce Motion disabled", Toast.LENGTH_SHORT).show()
        }

        binding.btnAuraAnimation.setOnClickListener {
            val next = !PrefsManager.isAuraAnimationEnabled(this)
            PrefsManager.setAuraAnimationEnabled(this, next)
            binding.switchAura.isChecked = next
            Toast.makeText(this, if (next) "Breathing Aura enabled" else "Breathing Aura disabled", Toast.LENGTH_SHORT).show()
        }

        binding.btnHaptics.setOnClickListener {
            val next = !PrefsManager.isHapticsEnabled(this)
            PrefsManager.setHapticsEnabled(this, next)
            binding.switchHaptics.isChecked = next
            if (next) {
                com.focusvault.app.util.HapticHelper.mediumClick(it)
            }
            Toast.makeText(this, if (next) "Haptic feedback enabled" else "Haptic feedback disabled", Toast.LENGTH_SHORT).show()
        }

        binding.btnSound.setOnClickListener {
            val next = !PrefsManager.isSoundEnabled(this)
            PrefsManager.setSoundEnabled(this, next)
            binding.switchSound.isChecked = next
            Toast.makeText(this, if (next) "Completion sound enabled" else "Completion sound disabled", Toast.LENGTH_SHORT).show()
        }

        binding.btnGoalsConfig.setOnClickListener { showGoalsEditorDialog() }

        binding.btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.btnPrivacyCenter.setOnClickListener {
            startActivity(Intent(this, PrivacyCenterActivity::class.java))
        }

        binding.btnBackupRestore.setOnClickListener {
            guardRestrictedAction {
                BackupRestoreDialog.show(this) {
                    refreshAllStatuses()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatuses()
    }

    private fun refreshAllStatuses() {
        val isStrict = PrefsManager.isStrictModeActive(this)
        binding.bannerStrictMode.visibility = if (isStrict) android.view.View.VISIBLE else android.view.View.GONE

        // Dim & indicate locked status on security controls during Strict Mode
        binding.btnAppLockPin.alpha = if (isStrict) 0.6f else 1.0f
        binding.btnAppLock.alpha = if (isStrict) 0.6f else 1.0f
        binding.btnBiometric.alpha = if (isStrict) 0.6f else 1.0f
        binding.btnBackupRestore.alpha = if (isStrict) 0.6f else 1.0f

        refreshAppLockPinStatus()
        refreshAppLockStatus()
        refreshBiometricStatus()
        refreshThemeStatus()
        refreshPaletteStatus()
        refreshGoalsStatus()
        binding.switchAmoled.isChecked = PrefsManager.isAmoledMode(this)
        binding.switchReduceMotion.isChecked = PrefsManager.isReduceMotion(this)
        binding.switchAura.isChecked = PrefsManager.isAuraAnimationEnabled(this)
        binding.switchHaptics.isChecked = PrefsManager.isHapticsEnabled(this)
        binding.switchSound.isChecked = PrefsManager.isSoundEnabled(this)
    }

    /**
     * Anti-Bypass Guard:
     * - Strict Mode: outright blocks altering security, PINs, or restoring backups mid-session.
     * - Lock Mode: challenges for PIN first.
     * - Normal Mode: allows immediately.
     */
    private fun guardRestrictedAction(action: () -> Unit) {
        if (PrefsManager.isStrictModeActive(this)) {
            Toast.makeText(
                this,
                "🔒 Settings are locked during Strict Mode to prevent bypasses",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (PrefsManager.isLockModeActive(this)) {
            LockPinDialog.promptAndVerify(this) { action() }
        } else {
            action()
        }
    }

    private fun refreshAppLockPinStatus() {
        val isSet = PrefsManager.hasLockPin(this)
        binding.tvAppLockPinStatus.text = if (isSet) "PIN Set · Tap to change" else "Not set · Tap to configure"
        binding.tvAppLockPinStatus.setTextColor(
            resources.getColor(if (isSet) R.color.success_green else R.color.text_secondary, theme)
        )
    }

    private fun refreshAppLockStatus() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val isOn = dpm.isAdminActive(deviceAdminComponent)
        binding.tvAppLockStatus.text = if (isOn) "Active · Protected against quick uninstall" else "Off · Tap to activate"
        binding.tvAppLockStatus.setTextColor(
            resources.getColor(if (isOn) R.color.success_green else R.color.text_secondary, theme)
        )
    }

    private fun refreshBiometricStatus() {
        val hasPin = PrefsManager.hasLockPin(this)
        val isAvailable = BiometricHelper.isAvailable(this)
        val isEnabled = PrefsManager.isBiometricEnabled(this)

        binding.tvBiometricStatus.text = when {
            !hasPin -> "Set an App Lock PIN first"
            !isAvailable -> "No fingerprint or face unlock enrolled"
            isEnabled -> "Active · Fingerprint unlock enabled"
            else -> "Off · Tap to enable"
        }
        binding.tvBiometricStatus.setTextColor(
            resources.getColor(if (hasPin && isEnabled) R.color.success_green else R.color.text_secondary, theme)
        )
    }

    private fun toggleBiometricUnlock() {
        if (!PrefsManager.hasLockPin(this)) {
            Toast.makeText(this, "Set an App Lock PIN first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!BiometricHelper.isAvailable(this)) {
            Toast.makeText(
                this,
                "No fingerprint or face unlock is set up on this device. Add one in device Settings first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val newValue = !PrefsManager.isBiometricEnabled(this)
        PrefsManager.setBiometricEnabled(this, newValue)
        refreshBiometricStatus()
        Toast.makeText(
            this,
            if (newValue) "Fingerprint unlock turned on" else "Fingerprint unlock turned off",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshThemeStatus() {
        binding.tvThemeStatus.text = when (PrefsManager.getThemeMode(this)) {
            PrefsManager.THEME_LIGHT -> "Light theme"
            PrefsManager.THEME_DARK -> "Dark theme"
            else -> "System default"
        }
    }

    private fun showThemePicker() {
        val options = listOf("⚙️ System default", "☀️ Light theme", "🌙 Dark theme")
        val modes = arrayOf(PrefsManager.THEME_SYSTEM, PrefsManager.THEME_LIGHT, PrefsManager.THEME_DARK)
        val currentIndex = modes.indexOf(PrefsManager.getThemeMode(this)).coerceAtLeast(0)

        DialogHelper.showSingleChoiceDialog(
            context = this,
            title = "Choose App Theme 🎨",
            items = options,
            selectedIndex = currentIndex
        ) { which ->
            PrefsManager.setThemeMode(this, modes[which])
            AppCompatDelegate.setDefaultNightMode(
                when (modes[which]) {
                    PrefsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    PrefsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            recreate()
        }
    }

    private fun refreshPaletteStatus() {
        val currentPalette = PrefsManager.getThemePalette(this)
        val paletteObj = com.focusvault.app.util.ThemeManager.Palette.fromKey(currentPalette)
        binding.tvPaletteStatus.text = paletteObj.displayName
    }

    private fun showPalettePicker() {
        val palettes = com.focusvault.app.util.ThemeManager.Palette.values()
        val names = palettes.map { it.displayName }
        val currentIndex = palettes.indexOfFirst { it.key == PrefsManager.getThemePalette(this) }.coerceAtLeast(0)

        DialogHelper.showSingleChoiceDialog(
            context = this,
            title = "Choose Color Palette 🌈",
            items = names,
            selectedIndex = currentIndex
        ) { which ->
            val chosen = palettes[which]
            PrefsManager.setThemePalette(this, chosen.key)
            recreate()
        }
    }

    private fun refreshGoalsStatus() {
        val daily = PrefsManager.getDailyGoalMinutes(this)
        val weekly = PrefsManager.getWeeklyGoalMinutes(this)
        val monthly = PrefsManager.getMonthlyGoalMinutes(this)
        binding.tvGoalsSummary.text = "Daily: ${daily / 60}h ${daily % 60}m · Weekly: ${weekly / 60}h · Monthly: ${monthly / 60}h"
    }

    private fun showGoalsEditorDialog() {
        val density = resources.displayMetrics.density
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        }

        fun createLabel(text: String) = android.widget.TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            setPadding((4 * density).toInt(), (8 * density).toInt(), 0, (4 * density).toInt())
        }

        val inputDaily = DialogHelper.createPillEditText(
            this,
            "Daily goal in minutes",
            PrefsManager.getDailyGoalMinutes(this).toString(),
            android.text.InputType.TYPE_CLASS_NUMBER
        )
        val inputWeekly = DialogHelper.createPillEditText(
            this,
            "Weekly goal in minutes",
            PrefsManager.getWeeklyGoalMinutes(this).toString(),
            android.text.InputType.TYPE_CLASS_NUMBER
        )
        val inputMonthly = DialogHelper.createPillEditText(
            this,
            "Monthly goal in minutes",
            PrefsManager.getMonthlyGoalMinutes(this).toString(),
            android.text.InputType.TYPE_CLASS_NUMBER
        )

        layout.addView(createLabel("🎯 DAILY TARGET (MINUTES)"))
        layout.addView(inputDaily)
        layout.addView(createLabel("📅 WEEKLY TARGET (MINUTES)"))
        layout.addView(inputWeekly)
        layout.addView(createLabel("🗓️ MONTHLY TARGET (MINUTES)"))
        layout.addView(inputMonthly)

        DialogHelper.showCustomDialog(
            context = this,
            title = "Configure Focus Goals 🎯",
            message = "Set daily and long-term focus target thresholds in minutes:",
            customView = layout,
            positiveText = "Save Goals",
            positiveAction = {
                inputDaily.text.toString().toIntOrNull()?.let { PrefsManager.setDailyGoalMinutes(this, it) }
                inputWeekly.text.toString().toIntOrNull()?.let { PrefsManager.setWeeklyGoalMinutes(this, it) }
                inputMonthly.text.toString().toIntOrNull()?.let { PrefsManager.setMonthlyGoalMinutes(this, it) }
                refreshGoalsStatus()
                Toast.makeText(this, "Focus goals saved", Toast.LENGTH_SHORT).show()
            },
            negativeText = "Cancel"
        )
    }
}
