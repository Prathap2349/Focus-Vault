package com.focusvault.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusvault.app.data.SessionMode
import com.focusvault.app.manager.SessionStateManager
import com.focusvault.app.util.PrefsManager
import kotlinx.coroutines.launch

class WidgetQuickStartActivity : AppCompatActivity() {

    private var chosenMode: SessionMode? = null
    private var pendingDurationMillis: Long = 0L

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SHOW_EMERGENCY = "show_emergency"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val showEmergency = intent.getBooleanExtra(EXTRA_SHOW_EMERGENCY, false)
        if (showEmergency) {
            if (PrefsManager.isSessionCurrentlyActive(this)) {
                if (PrefsManager.isLockModeActive(this)) {
                    LockPinDialog.promptAndVerify(this) { showEmergencySheet() }
                } else {
                    showEmergencySheet()
                }
            } else {
                finish()
            }
            return
        }
        
        if (PrefsManager.isSessionCurrentlyActive(this)) {
            finish()
            return
        }

        supportFragmentManager.setFragmentResultListener(
            WidgetModeQuickPickSheet.REQUEST_KEY, this
        ) { _, result ->
            val modeName = result.getString(WidgetModeQuickPickSheet.RESULT_MODE)
            if (modeName != null) {
                chosenMode = SessionMode.valueOf(modeName)
                if (pendingDurationMillis > 0) {
                    startFocusSession(chosenMode ?: return@setPinVerifyListener, pendingDurationMillis)
                } else {
                    showTimerSetup(chosenMode ?: return@setPinVerifyListener)
                }
            } else {
                finish()
            }
        }

        supportFragmentManager.setFragmentResultListener(
            QuickTimerSetupSheet.REQUEST_KEY, this
        ) { _, result ->
            val durationMillis = result.getLong(QuickTimerSetupSheet.RESULT_DURATION_MILLIS, 0)
            if (durationMillis > 0) {
                pendingDurationMillis = durationMillis
                if (chosenMode != null) {
                    startFocusSession(chosenMode ?: return@setPinVerifyListener, durationMillis)
                } else {
                    showCenteredModeSelection((durationMillis / 60_000L).toInt())
                }
            } else {
                finish()
            }
        }

        val preselectedMode = intent.getStringExtra(EXTRA_MODE)
        if (preselectedMode != null) {
            chosenMode = runCatching { SessionMode.valueOf(preselectedMode) }.getOrNull()
            if (chosenMode != null) {
                showTimerSetup(chosenMode ?: return@setPinVerifyListener)
            } else {
                val lastDurationMinutes = (PrefsManager.getLastChosenDurationMillis(this) / 60_000L).toInt().coerceAtLeast(1)
                showCenteredModeSelection(lastDurationMinutes)
            }
        } else {
            val lastDurationMinutes = (PrefsManager.getLastChosenDurationMillis(this) / 60_000L).toInt().coerceAtLeast(1)
            showCenteredModeSelection(lastDurationMinutes)
        }
    }

    private fun showCenteredModeSelection(durationMinutes: Int) {
        val sheet = WidgetModeQuickPickSheet.newInstance(durationMinutes)
        sheet.show(supportFragmentManager, WidgetModeQuickPickSheet.TAG)
    }

    private fun showTimerSetup(mode: SessionMode) {
        val duration = PrefsManager.getLastChosenDurationMillis(this)
        val sheet = QuickTimerSetupSheet.newInstance(mode, duration)
        sheet.show(supportFragmentManager, QuickTimerSetupSheet.TAG)
    }

    private fun startFocusSession(mode: SessionMode, durationMillis: Long) {
        when (mode) {
            SessionMode.STRICT -> {
                val intent = Intent(this, StrictModeConfirmActivity::class.java)
                intent.putExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, durationMillis)
                startActivity(intent)
                finish()
            }
            SessionMode.LOCK -> {
                val intent = Intent(this, LockModeConfirmActivity::class.java)
                intent.putExtra(SessionSetupActivity.EXTRA_DURATION_MILLIS, durationMillis)
                startActivity(intent)
                finish()
            }
            SessionMode.NORMAL -> {
                SessionStarter.startSession(this, durationMillis, SessionMode.NORMAL)
                finish()
            }
        }
    }

    private fun showEmergencySheet() {
        supportFragmentManager.setFragmentResultListener(
            EmergencyModeSheet.REQUEST_KEY, this
        ) { _, result ->
            val minutes = result.getInt(EmergencyModeSheet.RESULT_MINUTES, 0)
            val label = result.getString(EmergencyModeSheet.RESULT_LABEL) ?: "Emergency"
            if (minutes > 0) {
                lifecycleScope.launch {
                    SessionStateManager.pauseSession(applicationContext, minutes * 60_000L, label)
                    com.focusvault.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
                    finish()
                }
            } else {
                finish()
            }
        }

        val sheet = EmergencyModeSheet()
        sheet.show(supportFragmentManager, EmergencyModeSheet.TAG)
        supportFragmentManager.executePendingTransactions()
        sheet.dialog?.setOnDismissListener {
            finish()
        }
    }
}
