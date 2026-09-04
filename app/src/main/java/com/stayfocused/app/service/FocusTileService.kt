package com.stayfocused.app.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.data.SessionState
import com.stayfocused.app.manager.ProtectionEngine
import com.stayfocused.app.manager.ProtectionStatus
import com.stayfocused.app.manager.SessionStateManager
import com.stayfocused.app.ui.MainActivity
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class FocusTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val context = applicationContext
        val isActive = PrefsManager.isSessionCurrentlyActive(context)

        if (isActive) {
            // Already active: open MainActivity to see progress or controls
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = android.app.PendingIntent.getActivity(
                    context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } else {
            // Start a quick 25-minute default Focus session
            CoroutineScope(Dispatchers.Main).launch {
                SessionStateManager.startSession(context, 25 * 60_000L, SessionMode.NORMAL, "Quick Focus")
                updateTileState()
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val context = applicationContext
        val sessionActive = PrefsManager.isSessionCurrentlyActive(context)
        val sessionState = PrefsManager.getSessionState(context)
        val protectionReport = ProtectionEngine.evaluate(context)

        if (protectionReport.status == ProtectionStatus.PROTECTION_FAILED) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Protection Down"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Tap to open app"
            }
        } else if (sessionActive) {
            if (sessionState == SessionState.PAUSED) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Focus Paused"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Emergency pause"
                }
            } else {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Focusing"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val remaining = (PrefsManager.getSessionEndTime(context) - System.currentTimeMillis()).coerceAtLeast(0L)
                    val mins = (remaining / 60_000L).toInt()
                    tile.subtitle = "${mins}m remaining"
                }
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Start Focus"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "25m Pomodoro"
            }
        }

        tile.icon = Icon.createWithResource(context, android.R.drawable.ic_lock_idle_lock)
        tile.updateTile()
    }
}
