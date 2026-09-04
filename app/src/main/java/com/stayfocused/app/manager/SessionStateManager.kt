package com.stayfocused.app.manager

import android.content.Context
import android.content.Intent
import com.stayfocused.app.appwidget.WidgetUpdater
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.FocusSession
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.data.SessionState
import com.stayfocused.app.service.FocusVpnService
import com.stayfocused.app.service.SessionTimerService
import com.stayfocused.app.util.FocusStatsManager
import com.stayfocused.app.util.PrefsManager
import com.stayfocused.app.util.StreakManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single, authoritative source of truth for focus session lifecycle.
 * Manages atomic state transitions with validation to prevent impossible states.
 * Synchronizes Room database, fast memory cache, services, notifications, and home widgets.
 */
object SessionStateManager {

    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _sessionFlow = MutableStateFlow<FocusSession?>(null)
    val sessionFlow: StateFlow<FocusSession?> = _sessionFlow.asStateFlow()

    fun getSessionSnapshot(context: Context): FocusSession {
        return _sessionFlow.value ?: run {
            val mode = PrefsManager.getSessionMode(context)
            val state = PrefsManager.getSessionState(context)
            val end = PrefsManager.getSessionEndTime(context)
            FocusSession(
                mode = mode,
                state = state,
                startTimeMillis = 0L,
                endTimeMillis = end
            ).also { _sessionFlow.value = it }
        }
    }

    /**
     * Starts a new focus session.
     * Guaranteed idempotent: fails safely if another session is already live.
     */
    suspend fun startSession(
        context: Context,
        durationMillis: Long,
        mode: SessionMode,
        title: String = "Focus Session"
    ): Boolean = mutex.withLock {
        if (durationMillis <= 0) return false
        val current = _sessionFlow.value ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
        if (current != null && (current.state == SessionState.STARTING || (current.state.isLive && System.currentTimeMillis() < current.endTimeMillis))) {
            return false // Prevent starting a second active session or concurrent race
        }

        val startTime = System.currentTimeMillis()
        val endTime = startTime + durationMillis

        val startingSession = FocusSession(
            id = 1,
            mode = mode,
            state = SessionState.STARTING,
            startTimeMillis = startTime,
            endTimeMillis = endTime,
            pausedDurationMillis = 0L,
            pauseStartTimeMillis = 0L,
            title = title,
            distractionsBlocked = 0
        )

        // Write synchronously to fast cache
        PrefsManager.setSession(context, mode, SessionState.STARTING, endTime)

        // Transition to ACTIVE
        val activeSession = startingSession.copy(state = SessionState.ACTIVE)
        PrefsManager.setSession(context, mode, SessionState.ACTIVE, endTime)
        _sessionFlow.value = activeSession

        val db = AppDatabase.getInstance(context)
        db.focusSessionDao().upsert(activeSession)

        // Start foreground timer service
        SessionTimerService.start(context, durationMillis, mode)

        // Start VPN if website blocking is configured
        if (PrefsManager.getBlockedDomains(context).isNotEmpty()) {
            if (android.net.VpnService.prepare(context) == null) {
                try {
                    context.startService(Intent(context, FocusVpnService::class.java))
                } catch (e: Exception) {
                    // Handled gracefully if background restrictions apply
                }
            }
        }

        WidgetUpdater.requestUpdate(context)
        return true
    }

    /**
     * Pauses the active session (e.g. for Emergency Mode exceptions).
     * Strict Mode sessions cannot be paused.
     */
    suspend fun pauseSession(
        context: Context,
        durationMillis: Long,
        reasonLabel: String
    ): Boolean = mutex.withLock {
        val current = _sessionFlow.value ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
            ?: return false

        if (current.mode == SessionMode.STRICT) return false // Strict mode never permits pauses
        if (current.state != SessionState.ACTIVE) return false

        val pauseUntil = System.currentTimeMillis() + durationMillis
        PrefsManager.setEmergencyPause(context, pauseUntil, reasonLabel)
        PrefsManager.setSession(context, current.mode, SessionState.PAUSED, current.endTimeMillis)

        val pausedSession = current.copy(
            state = SessionState.PAUSED,
            pauseStartTimeMillis = System.currentTimeMillis()
        )
        _sessionFlow.value = pausedSession
        AppDatabase.getInstance(context).focusSessionDao().upsert(pausedSession)

        WidgetUpdater.requestUpdate(context)
        return true
    }

    /**
     * Resumes a paused session.
     * Extends session end time by the elapsed pause duration to keep focus target honest.
     */
    suspend fun resumeSession(context: Context): Boolean = mutex.withLock {
        val current = _sessionFlow.value ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
            ?: return false

        if (current.state != SessionState.PAUSED) return false

        val now = System.currentTimeMillis()
        val additionalPause = if (current.pauseStartTimeMillis > 0) (now - current.pauseStartTimeMillis).coerceAtLeast(0L) else 0L
        val newEndTime = current.endTimeMillis + additionalPause

        PrefsManager.clearEmergencyPause(context)
        PrefsManager.setSession(context, current.mode, SessionState.ACTIVE, newEndTime)

        val activeSession = current.copy(
            state = SessionState.ACTIVE,
            endTimeMillis = newEndTime,
            pausedDurationMillis = current.pausedDurationMillis + additionalPause,
            pauseStartTimeMillis = 0L
        )
        _sessionFlow.value = activeSession
        AppDatabase.getInstance(context).focusSessionDao().upsert(activeSession)

        WidgetUpdater.requestUpdate(context)
        return true
    }

    /**
     * Stops the session early.
     * Disallowed in Strict Mode.
     */
    suspend fun stopSessionEarly(context: Context, reason: String = "Stopped early"): Boolean = mutex.withLock {
        val current = _sessionFlow.value ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
            ?: return false

        if (current.mode == SessionMode.STRICT) return false // Strict mode cannot be stopped early
        if (!current.state.isLive) return false

        val now = System.currentTimeMillis()
        PrefsManager.forceEndSession(context)
        PrefsManager.clearEmergencyPause(context)

        val stoppedSession = current.copy(state = SessionState.STOPPED)
        _sessionFlow.value = stoppedSession

        val db = AppDatabase.getInstance(context)
        db.focusSessionDao().upsert(stoppedSession)

        // Record history for stopped early session
        FocusStatsManager.recordSessionEnd(
            context,
            mode = current.mode,
            startTimeMillis = current.startTimeMillis,
            endTimeMillis = now,
            completedNaturally = false,
            title = current.title,
            distractionsBlocked = current.distractionsBlocked,
            stopReason = reason
        )

        // Stop services
        try {
            context.stopService(Intent(context, SessionTimerService::class.java))
            context.stopService(Intent(context, FocusVpnService::class.java))
        } catch (e: Exception) { }

        WidgetUpdater.requestUpdate(context)
        return true
    }

    /**
     * Completes the session when countdown timer reaches zero.
     */
    suspend fun completeSession(context: Context): Unit = mutex.withLock {
        val current = _sessionFlow.value ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
            ?: return

        if (current.state == SessionState.COMPLETED) return

        val completingSession = current.copy(state = SessionState.COMPLETING)
        _sessionFlow.value = completingSession

        val now = System.currentTimeMillis()
        PrefsManager.setSession(context, current.mode, SessionState.COMPLETED, 0L)
        PrefsManager.clearEmergencyPause(context)

        val completedSession = current.copy(state = SessionState.COMPLETED)
        _sessionFlow.value = completedSession

        val db = AppDatabase.getInstance(context)
        db.focusSessionDao().upsert(completedSession)

        FocusStatsManager.recordSessionEnd(
            context,
            mode = current.mode,
            startTimeMillis = current.startTimeMillis,
            endTimeMillis = now,
            completedNaturally = true,
            title = current.title,
            distractionsBlocked = current.distractionsBlocked
        )

        StreakManager.recordCompletion(context)

        try {
            context.stopService(Intent(context, FocusVpnService::class.java))
        } catch (e: Exception) { }

        WidgetUpdater.requestUpdate(context)
    }

    /**
     * Increments the distraction attempt counter when a blocked app or site is resisted.
     */
    fun recordDistractionAttempt(context: Context) {
        scope.launch {
            mutex.withLock {
                val current = _sessionFlow.value ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
                    ?: return@launch
                if (current.state.isLive) {
                    val updated = current.copy(distractionsBlocked = current.distractionsBlocked + 1)
                    _sessionFlow.value = updated
                    AppDatabase.getInstance(context).focusSessionDao().upsert(updated)
                }
            }
        }
    }

    /**
     * Recovers session state after process death or device reboot.
     * If the session's end time elapsed while the app was dead, completes it properly.
     * If still active, restores protection services.
     */
    suspend fun recoverSessionIfNeeded(context: Context) = mutex.withLock {
        val db = AppDatabase.getInstance(context)
        val current = db.focusSessionDao().getSessionOnce() ?: return@withLock
        val now = System.currentTimeMillis()

        if (current.state.isLive) {
            if (now >= current.endTimeMillis) {
                // Expired during process death or reboot: finalize completion cleanly
                val completed = current.copy(state = SessionState.COMPLETED)
                _sessionFlow.value = completed
                db.focusSessionDao().upsert(completed)
                PrefsManager.setSession(context, current.mode, SessionState.COMPLETED, 0L)

                FocusStatsManager.recordSessionEnd(
                    context,
                    mode = current.mode,
                    startTimeMillis = current.startTimeMillis,
                    endTimeMillis = current.endTimeMillis,
                    completedNaturally = true,
                    title = current.title,
                    distractionsBlocked = current.distractionsBlocked
                )
                StreakManager.recordCompletion(context)
                WidgetUpdater.requestUpdate(context)
            } else {
                // Still active: restore
                val recovering = current.copy(state = SessionState.RECOVERING)
                _sessionFlow.value = recovering
                val active = current.copy(state = SessionState.ACTIVE)
                _sessionFlow.value = active
                db.focusSessionDao().upsert(active)
                PrefsManager.setSession(context, current.mode, SessionState.ACTIVE, current.endTimeMillis)

                val remaining = current.endTimeMillis - now
                SessionTimerService.start(context, remaining, current.mode)

                if (PrefsManager.getBlockedDomains(context).isNotEmpty() &&
                    android.net.VpnService.prepare(context) == null
                ) {
                    try {
                        context.startService(Intent(context, FocusVpnService::class.java))
                    } catch (e: Exception) { }
                }

                WidgetUpdater.requestUpdate(context)
            }
        }
    }
}
