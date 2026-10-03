package com.focusvault.app.util

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

object TimeUtils {

    @Volatile private var cachedLastWall: Long = 0L
    @Volatile private var cachedLastElapsed: Long = 0L
    @Volatile private var lastPersistTime: Long = 0L

    fun getSecureCurrentTimeMillis(context: Context): Long {
        val currentWall = System.currentTimeMillis()
        val currentElapsed = SystemClock.elapsedRealtime()

        if (cachedLastWall == 0L) {
            val prefs = context.getSharedPreferences("time_anchor_prefs", Context.MODE_PRIVATE)
            cachedLastWall = prefs.getLong("last_wall", 0L)
            cachedLastElapsed = prefs.getLong("last_elapsed", 0L)
        }

        if (cachedLastWall == 0L || currentElapsed < cachedLastElapsed) {
            // Initial setup or device rebooted
            cachedLastWall = currentWall
            cachedLastElapsed = currentElapsed
            persistAnchor(context)
            return currentWall
        }

        val elapsedDiff = currentElapsed - cachedLastElapsed
        val expectedWall = cachedLastWall + elapsedDiff
        val wallDiff = abs(currentWall - expectedWall)

        val secureTime = if (wallDiff > 60_000) {
            expectedWall
        } else {
            currentWall
        }

        // Update in-memory anchor
        cachedLastWall = secureTime
        cachedLastElapsed = currentElapsed

        // Persist at most every 30s
        if (currentElapsed - lastPersistTime > 30_000) {
            persistAnchor(context)
        }

        return secureTime
    }

    fun persistAnchor(context: Context) {
        if (cachedLastWall == 0L) return
        val prefs = context.getSharedPreferences("time_anchor_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_wall", cachedLastWall)
            .putLong("last_elapsed", cachedLastElapsed)
            .apply()
        lastPersistTime = SystemClock.elapsedRealtime()
    }
}
