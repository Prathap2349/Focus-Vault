package com.focusvault.app.util

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

object TimeUtils {
    fun getSecureCurrentTimeMillis(context: Context): Long {
        val prefs = context.getSharedPreferences("time_anchor_prefs", Context.MODE_PRIVATE)
        val lastWall = prefs.getLong("last_wall", 0L)
        val lastElapsed = prefs.getLong("last_elapsed", 0L)
        
        val currentWall = System.currentTimeMillis()
        val currentElapsed = SystemClock.elapsedRealtime()
        
        if (lastWall == 0L || currentElapsed < lastElapsed) {
            // Initial setup or device rebooted
            prefs.edit()
                .putLong("last_wall", currentWall)
                .putLong("last_elapsed", currentElapsed)
                .apply()
            return currentWall
        }
        
        val elapsedDiff = currentElapsed - lastElapsed
        val expectedWall = lastWall + elapsedDiff
        
        val wallDiff = abs(currentWall - expectedWall)
        
        // If the clock jumped by more than 1 minute, trust the elapsed time calculation
        if (wallDiff > 60_000) {
            return expectedWall
        } else {
            // Update anchor to keep it fresh and prevent drift accumulation
            prefs.edit()
                .putLong("last_wall", currentWall)
                .putLong("last_elapsed", currentElapsed)
                .apply()
            return currentWall
        }
    }
}
