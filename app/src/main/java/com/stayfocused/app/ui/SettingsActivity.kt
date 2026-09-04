package com.stayfocused.app.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.stayfocused.app.R
import com.stayfocused.app.databinding.ActivitySettingsBinding
import com.stayfocused.app.service.StayFocusedDeviceAdminReceiver
import com.stayfocused.app.util.BiometricHelper
import com.stayfocused.app.util.EdgeToEdge
import com.stayfocused.app.util.PrefsManager

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
        val options = arrayOf("System default", "Light", "Dark")
        val modes = arrayOf(PrefsManager.THEME_SYSTEM, PrefsManager.THEME_LIGHT, PrefsManager.THEME_DARK)
        val currentIndex = modes.indexOf(PrefsManager.getThemeMode(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Choose App Theme")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                PrefsManager.setThemeMode(this, modes[which])
                AppCompatDelegate.setDefaultNightMode(
                    when (modes[which]) {
                        PrefsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                        PrefsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshPaletteStatus() {
        val currentPalette = PrefsManager.getThemePalette(this)
        val paletteObj = com.stayfocused.app.util.ThemeManager.Palette.fromKey(currentPalette)
        binding.tvPaletteStatus.text = paletteObj.displayName
    }

    private fun showPalettePicker() {
        val palettes = com.stayfocused.app.util.ThemeManager.Palette.values()
        val names = palettes.map { it.displayName }.toTypedArray()
        val currentIndex = palettes.indexOfFirst { it.key == PrefsManager.getThemePalette(this) }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Choose Color Palette")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val chosen = palettes[which]
                PrefsManager.setThemePalette(this, chosen.key)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshGoalsStatus() {
        val daily = PrefsManager.getDailyGoalMinutes(this)
        val weekly = PrefsManager.getWeeklyGoalMinutes(this)
        val monthly = PrefsManager.getMonthlyGoalMinutes(this)
        binding.tvGoalsSummary.text = "Daily: ${daily / 60}h ${daily % 60}m · Weekly: ${weekly / 60}h · Monthly: ${monthly / 60}h"
    }

    private fun showGoalsEditorDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val tvDaily = android.widget.TextView(this).apply { text = "Daily Goal (minutes):" }
        val inputDaily = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefsManager.getDailyGoalMinutes(this@SettingsActivity).toString())
        }

        val tvWeekly = android.widget.TextView(this).apply {
            text = "Weekly Goal (minutes):"
            setPadding(0, 24, 0, 0)
        }
        val inputWeekly = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefsManager.getWeeklyGoalMinutes(this@SettingsActivity).toString())
        }

        val tvMonthly = android.widget.TextView(this).apply {
            text = "Monthly Goal (minutes):"
            setPadding(0, 24, 0, 0)
        }
        val inputMonthly = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefsManager.getMonthlyGoalMinutes(this@SettingsActivity).toString())
        }

        layout.addView(tvDaily)
        layout.addView(inputDaily)
        layout.addView(tvWeekly)
        layout.addView(inputWeekly)
        layout.addView(tvMonthly)
        layout.addView(inputMonthly)

        AlertDialog.Builder(this)
            .setTitle("Configure Focus Goals")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                inputDaily.text.toString().toIntOrNull()?.let { PrefsManager.setDailyGoalMinutes(this, it) }
                inputWeekly.text.toString().toIntOrNull()?.let { PrefsManager.setWeeklyGoalMinutes(this, it) }
                inputMonthly.text.toString().toIntOrNull()?.let { PrefsManager.setMonthlyGoalMinutes(this, it) }
                refreshGoalsStatus()
                Toast.makeText(this, "Focus goals saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
