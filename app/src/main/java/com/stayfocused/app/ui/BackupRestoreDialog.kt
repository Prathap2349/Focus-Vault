package com.stayfocused.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.BlockedApp
import com.stayfocused.app.data.BlockedSite
import com.stayfocused.app.data.FocusPreset
import com.stayfocused.app.data.ScheduledSession
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object BackupRestoreDialog {

    fun show(context: Context, onRestoreComplete: (() -> Unit)? = null) {
        val options = arrayOf("Copy Configuration to Clipboard", "Share Configuration", "Restore from Backup")
        AlertDialog.Builder(context)
            .setTitle("Backup & Restore")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToClipboard(context)
                    1 -> shareExport(context)
                    2 -> showRestoreDialog(context, onRestoreComplete)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showBackupDialog(context: Context) = show(context, null)

    private fun exportToClipboard(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val json = generateBackupJson(context)
            withContext(Dispatchers.Main) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("StayFocused Backup", json))
                Toast.makeText(context, "Configuration copied to clipboard!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareExport(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val json = generateBackupJson(context)
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "StayFocused Configuration Backup")
                    putExtra(Intent.EXTRA_TEXT, json)
                }
                context.startActivity(Intent.createChooser(intent, "Share StayFocused Backup"))
            }
        }
    }

    private fun showRestoreDialog(context: Context, onRestoreComplete: (() -> Unit)? = null) {
        val input = EditText(context).apply {
            hint = "Paste exported JSON configuration here…"
            minLines = 4
        }

        AlertDialog.Builder(context)
            .setTitle("Restore Configuration")
            .setMessage("Paste your exported configuration. Note: authentication secrets/PINs are never backed up.")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton

                CoroutineScope(Dispatchers.IO).launch {
                    val success = restoreFromJson(context, text)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(context, "Configuration restored successfully!", Toast.LENGTH_SHORT).show()
                            onRestoreComplete?.invoke()
                        } else {
                            Toast.makeText(context, "Failed to restore: invalid backup format", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun generateBackupJson(context: Context): String {
        val db = AppDatabase.getInstance(context)
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        // Blocked apps
        val apps = db.blockedAppDao().getAllOnce().filter { it.isActive }
        val appsArr = JSONArray()
        apps.forEach { app ->
            appsArr.put(JSONObject().apply {
                put("package", app.packageName)
                put("label", app.appLabel)
            })
        }
        root.put("blockedApps", appsArr)

        // Blocked sites
        val sites = db.blockedSiteDao().getActiveDomainsOnce()
        val sitesArr = JSONArray()
        sites.forEach { sitesArr.put(it) }
        root.put("blockedDomains", sitesArr)

        // Presets
        val presets = db.focusPresetDao().getAllPresetsOnce().filter { !it.isBuiltIn }
        val presetsArr = JSONArray()
        presets.forEach {
            presetsArr.put(JSONObject().apply {
                put("name", it.name)
                put("duration", it.durationMinutes)
                put("mode", it.mode.name)
            })
        }
        root.put("customPresets", presetsArr)

        // Goal
        root.put("dailyGoalMinutes", PrefsManager.getDailyGoalMinutes(context))

        return root.toString(2)
    }

    private suspend fun restoreFromJson(context: Context, jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            val db = AppDatabase.getInstance(context)

            // Restore apps
            if (root.has("blockedApps")) {
                val appsArr = root.getJSONArray("blockedApps")
                for (i in 0 until appsArr.length()) {
                    val item = appsArr.getJSONObject(i)
                    db.blockedAppDao().upsert(
                        BlockedApp(
                            packageName = item.getString("package"),
                            appLabel = item.optString("label", "App"),
                            isActive = true
                        )
                    )
                }
                val activeApps = db.blockedAppDao().getActivePackageNamesOnce().toSet()
                PrefsManager.setBlockedPackages(context, activeApps)
            }

            // Restore domains
            if (root.has("blockedDomains")) {
                val sitesArr = root.getJSONArray("blockedDomains")
                for (i in 0 until sitesArr.length()) {
                    val domain = sitesArr.getString(i)
                    db.blockedSiteDao().upsert(BlockedSite(domain, true))
                }
                val activeSites = db.blockedSiteDao().getActiveDomainsOnce().toSet()
                PrefsManager.setBlockedDomains(context, activeSites)
            }

            // Restore presets
            if (root.has("customPresets")) {
                val presetsArr = root.getJSONArray("customPresets")
                for (i in 0 until presetsArr.length()) {
                    val item = presetsArr.getJSONObject(i)
                    val mode = runCatching { SessionMode.valueOf(item.getString("mode")) }.getOrDefault(SessionMode.NORMAL)
                    db.focusPresetDao().upsert(
                        FocusPreset(
                            name = item.getString("name"),
                            durationMinutes = item.getInt("duration"),
                            mode = mode,
                            isBuiltIn = false
                        )
                    )
                }
            }

            // Restore goal
            if (root.has("dailyGoalMinutes")) {
                PrefsManager.setDailyGoalMinutes(context, root.getInt("dailyGoalMinutes"))
            }

            true
        } catch (e: Exception) {
            false
        }
    }
}
