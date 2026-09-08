package com.stayfocused.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.FocusPreset
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.databinding.ActivityPresetsBinding
import com.stayfocused.app.databinding.ItemPresetBinding
import com.stayfocused.app.util.FocusStatsManager
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.launch

class PresetsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPresetsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPresetsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnAddCustomPreset.setOnClickListener {
            showCreatePresetDialog()
        }

        loadPresets()
    }

    private fun loadPresets() {
        val db = AppDatabase.getInstance(applicationContext)
        lifecycleScope.launch {
            db.focusPresetDao().observePresets().collect { presets ->
                binding.containerPresets.removeAllViews()
                val inflater = LayoutInflater.from(this@PresetsActivity)

                presets.forEach { preset ->
                    val row = ItemPresetBinding.inflate(inflater, binding.containerPresets, false)
                    row.tvPresetIcon.text = preset.icon
                    row.tvPresetName.text = preset.name
                    row.tvPresetDetails.text = "${preset.durationMinutes} min · ${preset.mode.name.lowercase().replaceFirstChar { it.uppercase() }} Mode"

                    val suggestion = FocusStatsManager.getAdaptivePresetSuggestion(this@PresetsActivity, preset.name, preset.durationMinutes)
                    if (suggestion != null) {
                        row.tvPresetSuggestion.visibility = android.view.View.VISIBLE
                        row.tvPresetSuggestion.text = "💡 ${suggestion.suggestionMessage}"
                        row.tvPresetSuggestion.setOnClickListener {
                            lifecycleScope.launch {
                                val updated = preset.copy(durationMinutes = suggestion.actualAvgMinutes)
                                db.focusPresetDao().upsert(updated)
                                Toast.makeText(this@PresetsActivity, "Updated '${preset.name}' preset to ${suggestion.actualAvgMinutes} min!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        row.tvPresetSuggestion.visibility = android.view.View.GONE
                    }

                    row.btnStartPreset.setOnClickListener {
                        if (PrefsManager.isSessionCurrentlyActive(this@PresetsActivity)) {
                            Toast.makeText(this@PresetsActivity, "A focus session is already active!", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val durationMillis = preset.durationMinutes * 60_000L
                        when (preset.mode) {
                            SessionMode.STRICT -> {
                                val intent = Intent(this@PresetsActivity, StrictModeConfirmActivity::class.java).apply {
                                    putExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, durationMillis)
                                }
                                startActivity(intent)
                                finish()
                            }
                            SessionMode.LOCK -> {
                                val intent = Intent(this@PresetsActivity, LockModeConfirmActivity::class.java).apply {
                                    putExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, durationMillis)
                                }
                                startActivity(intent)
                                finish()
                            }
                            SessionMode.NORMAL -> {
                                SessionStarter.startSession(this@PresetsActivity, durationMillis, SessionMode.NORMAL, preset.name)
                                finish()
                            }
                        }
                    }

                    if (!preset.isBuiltIn) {
                        row.root.setOnLongClickListener {
                            AlertDialog.Builder(this@PresetsActivity)
                                .setTitle("Delete Preset")
                                .setMessage("Delete custom preset '${preset.name}'?")
                                .setPositiveButton("Delete") { _, _ ->
                                    lifecycleScope.launch { db.focusPresetDao().delete(preset) }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                    }

                    binding.containerPresets.addView(row.root)
                }
            }
        }
    }

    private fun showCreatePresetDialog() {
        val nameInput = EditText(this).apply { hint = "Preset Name (e.g. Reading)" }
        val durationInput = EditText(this).apply {
            hint = "Duration (minutes)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PresetsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Focus Mode (Normal)", "Lock Mode (PIN Required)", "Strict Mode (Unbreakable)"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(nameInput)
            addView(durationInput)
            addView(modeSpinner)
        }

        AlertDialog.Builder(this)
            .setTitle("Create Custom Preset")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val duration = durationInput.text.toString().toIntOrNull() ?: 0
                val mode = when (modeSpinner.selectedItemPosition) {
                    1 -> SessionMode.LOCK
                    2 -> SessionMode.STRICT
                    else -> SessionMode.NORMAL
                }

                if (name.isEmpty() || duration <= 0) {
                    Toast.makeText(this, "Please enter a valid name and duration", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(applicationContext)
                    db.focusPresetDao().upsert(
                        FocusPreset(
                            name = name,
                            durationMinutes = duration,
                            mode = mode,
                            icon = "🎯",
                            isBuiltIn = false
                        )
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
