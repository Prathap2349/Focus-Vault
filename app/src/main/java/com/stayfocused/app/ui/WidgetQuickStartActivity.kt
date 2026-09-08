package com.stayfocused.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.util.PrefsManager

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
                    startFocusSession(chosenMode!!, pendingDurationMillis)
                } else {
                    showTimerSetup(chosenMode!!)
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
                    startFocusSession(chosenMode!!, durationMillis)
                } else {
                    showCenteredModeSelection((durationMillis / 60_000L).toInt())
                }
            } else {
                finish()
            }
        }

        val preselectedMode = intent.getStringExtra(EXTRA_MODE)
        if (preselectedMode != null) {
            chosenMode = SessionMode.valueOf(preselectedMode)
            showTimerSetup(chosenMode!!)
        } else {
            showTimerSetup(SessionMode.NORMAL)
        }
    }

    private fun showCenteredModeSelection(durationMinutes: Int) {
        val sheet = WidgetModeQuickPickSheet.newInstance(durationMinutes)
        sheet.show(supportFragmentManager, WidgetModeQuickPickSheet.TAG)
        
        supportFragmentManager.executePendingTransactions()
        sheet.dialog?.setOnDismissListener {
            if (chosenMode == null) finish()
        }
    }

    private fun showTimerSetup(mode: SessionMode) {
        val duration = PrefsManager.getLastChosenDurationMillis(this)
        val sheet = QuickTimerSetupSheet.newInstance(mode, duration)
        sheet.show(supportFragmentManager, QuickTimerSetupSheet.TAG)
        
        supportFragmentManager.executePendingTransactions()
        sheet.dialog?.setOnDismissListener {
            if (pendingDurationMillis <= 0L && chosenMode == null) finish()
        }
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
                PrefsManager.setEmergencyPause(this, System.currentTimeMillis() + minutes * 60_000L, label)
                com.stayfocused.app.appwidget.WidgetUpdater.requestUpdate(applicationContext)
            }
            finish()
        }

        val sheet = EmergencyModeSheet()
        sheet.show(supportFragmentManager, EmergencyModeSheet.TAG)
        supportFragmentManager.executePendingTransactions()
        sheet.dialog?.setOnDismissListener {
            finish()
        }
    }
}
