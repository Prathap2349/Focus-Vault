package com.stayfocused.app.ui

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.util.PrefsManager

class WidgetQuickStartActivity : AppCompatActivity() {

    private var chosenMode: SessionMode? = null

    companion object {
        const val EXTRA_MODE = "mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                showTimerSetup(chosenMode!!)
            } else {
                finish()
            }
        }

        supportFragmentManager.setFragmentResultListener(
            QuickTimerSetupSheet.REQUEST_KEY, this
        ) { _, result ->
            val durationMillis = result.getLong(QuickTimerSetupSheet.RESULT_DURATION_MILLIS, 0)
            if (durationMillis > 0 && chosenMode != null) {
                startFocusSession(chosenMode!!, durationMillis)
            } else {
                finish()
            }
        }

        val preselectedMode = intent.getStringExtra(EXTRA_MODE)
        if (preselectedMode != null) {
            chosenMode = SessionMode.valueOf(preselectedMode)
            showTimerSetup(chosenMode!!)
        } else {
            showModeSelection()
        }
    }

    private fun showModeSelection() {
        val sheet = WidgetModeQuickPickSheet()
        sheet.show(supportFragmentManager, WidgetModeQuickPickSheet.TAG)
        
        // If the user dismisses the sheet without choosing, finish the activity
        supportFragmentManager.executePendingTransactions()
        sheet.dialog?.setOnDismissListener {
            if (chosenMode == null) finish()
        }
    }

    private fun showTimerSetup(mode: SessionMode) {
        val sheet = QuickTimerSetupSheet.newInstance(mode)
        sheet.show(supportFragmentManager, QuickTimerSetupSheet.TAG)
        
        supportFragmentManager.executePendingTransactions()
        sheet.dialog?.setOnDismissListener {
            // If the user dismisses timer setup, we could go back or finish. 
            // The prompt says "after i select the mode it will ask the timmer after i set that it eill start".
            // So if they cancel here, just finish.
            finish()
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
}
