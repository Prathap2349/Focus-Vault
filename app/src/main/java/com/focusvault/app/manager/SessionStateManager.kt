package com.focusvault.app.manager

import android.content.Context
import android.content.Intent
import com.focusvault.app.appwidget.WidgetUpdater
import com.focusvault.app.data.AppDatabase
import com.focusvault.app.data.FocusSession
import com.focusvault.app.data.SessionMode
import com.focusvault.app.data.SessionState
import com.focusvault.app.service.FocusVpnService
import com.focusvault.app.service.SessionTimerService
import com.focusvault.app.service.StayFocusedDeviceAdminReceiver
import com.focusvault.app.util.FocusStatsManager
import com.focusvault.app.util.PrefsManager
import com.focusvault.app.util.StreakManager
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
        val cached = _sessionFlow.value
        val now = System.currentTimeMillis()
        if (cached != null) {
            return if (cached.state.isLive && (cached.endTimeMillis <= 0L || now >= cached.endTimeMillis)) {
                val expired = cached.copy(state = SessionState.IDLE, startTimeMillis = 0L, endTimeMillis = 0L)
                _sessionFlow.value = expired
                expired
            } else {
                cached
            }
        }

        val mode = PrefsManager.getSessionMode(context)
        val state = PrefsManager.getSessionState(context)
        val end = PrefsManager.getSessionEndTime(context)
        val start = PrefsManager.getSessionStartTime(context)

        val effectiveState = if (state.isLive && (end <= 0L || now >= end)) SessionState.IDLE else state
        val effectiveEnd = if (effectiveState.isLive) end else 0L

        return FocusSession(
            mode = mode,
            state = effectiveState,
            startTimeMillis = if (effectiveState.isLive) start else 0L,
            endTimeMillis = effectiveEnd
        ).also { _sessionFlow.value = it }
    }

    /**
     * Centralized, idempotent finalization for all session termination outcomes.
     * Keeps state transition and idempotency checks atomic inside the mutex,
     * but executes all side-effects (service control, database I/O, stats, widgets)
     * outside the lock to prevent deadlocks and re-entrancy issues.
     */
    private suspend fun finalizeSession(
        context: Context,
        finalState: SessionState,
        completedNaturally: Boolean,
        stopReason: String = "",
        customEndTimeMillis: Long? = null
    ): Boolean {
        // Step 1: Atomic state validation and transition inside Mutex
        val sessionToFinalize = mutex.withLock {
            val current = _sessionFlow.value
                ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
                ?: return false

            // Idempotency: If not in a live state and not already COMPLETING, return false immediately
            if (!current.state.isLive && current.state != SessionState.COMPLETING) {
                return false
            }

            // Already in target final state
            if (current.state == finalState) {
                return false
            }

            // Atomically transition state to COMPLETING
            val completingSession = current.copy(state = SessionState.COMPLETING)
            _sessionFlow.value = completingSession
            PrefsManager.setSession(context, current.mode, SessionState.COMPLETING, current.endTimeMillis)

            current
        }

        // Step 2: Side effects executed safely outside the mutex
        val now = System.currentTimeMillis()
        val finalEndTime = customEndTimeMillis ?: now
        val mode = sessionToFinalize.mode

        // Stop timer foreground service
        try {
            context.stopService(Intent(context, SessionTimerService::class.java))
        } catch (e: Exception) {
            // Handled gracefully
        }

        // Stop session VPN if no permanent blocked domains are active
        try {
            if (PrefsManager.getPermanentBlockedDomains(context).isEmpty()) {
                val stopVpnIntent = Intent(context, FocusVpnService::class.java).apply {
                    action = FocusVpnService.ACTION_STOP
                }
                context.startService(stopVpnIntent)
                context.stopService(Intent(context, FocusVpnService::class.java))
            }
        } catch (e: Exception) {
            // Handled gracefully
        }

        // Clean up fast cache and emergency pause
        PrefsManager.clearEmergencyPause(context)
        PrefsManager.forceEndSession(context)
        PrefsManager.setSession(context, mode, finalState, 0L)

        // Record history and dashboard statistics exactly once
        FocusStatsManager.recordSessionEnd(
            context,
            mode = mode,
            startTimeMillis = sessionToFinalize.startTimeMillis,
            endTimeMillis = finalEndTime,
            completedNaturally = completedNaturally,
            title = sessionToFinalize.title,
            distractionsBlocked = sessionToFinalize.distractionsBlocked,
            stopReason = stopReason
        )

        // Record streak update only on natural completion
        if (completedNaturally) {
            StreakManager.recordCompletion(context)
        }

        // Finalize entity and persist to Room database
        val finalSession = sessionToFinalize.copy(
            state = finalState,
            startTimeMillis = 0L,
            endTimeMillis = 0L,
            pauseStartTimeMillis = 0L
        )

        val db = AppDatabase.getInstance(context)
        db.focusSessionDao().upsert(finalSession)

        // Publish terminal state to StateFlow
        mutex.withLock {
            _sessionFlow.value = finalSession
        }

        // Notify home widgets
        WidgetUpdater.requestUpdate(context)

        return true
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
    ): Boolean {
        if (durationMillis <= 0) return false

        // State-layer security boundary: Strict and Lock modes require active Device Admin
        if (mode == SessionMode.STRICT || mode == SessionMode.LOCK) {
            if (!StayFocusedDeviceAdminReceiver.isDeviceAdminActive(context)) {
                return false
            }
        }

        val activeSession = mutex.withLock {
            val current = _sessionFlow.value
                ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
            val now = System.currentTimeMillis()
            if (current != null && (current.state == SessionState.STARTING || (current.state.isLive && now < current.endTimeMillis))) {
                return false // Prevent starting a second active session or concurrent race
            }

            val startTime = now
            val endTime = startTime + durationMillis

            // Write synchronously to fast cache
            PrefsManager.setSession(context, mode, SessionState.ACTIVE, endTime)

            val session = FocusSession(
                id = 1,
                mode = mode,
                state = SessionState.ACTIVE,
                startTimeMillis = startTime,
                endTimeMillis = endTime,
                pausedDurationMillis = 0L,
                pauseStartTimeMillis = 0L,
                title = title,
                distractionsBlocked = 0
            )
            _sessionFlow.value = session
            session
        }

        // Side-effects outside mutex
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
    ): Boolean {
        if (durationMillis <= 0) return false

        val pausedSession = mutex.withLock {
            val current = _sessionFlow.value
                ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
                ?: return false

            if (current.mode == SessionMode.STRICT) return false // Strict mode never permits pauses
            if (current.state != SessionState.ACTIVE) return false

            val now = System.currentTimeMillis()
            val pauseUntil = now + durationMillis

            PrefsManager.setEmergencyPause(context, pauseUntil, reasonLabel)
            PrefsManager.setSession(context, current.mode, SessionState.PAUSED, current.endTimeMillis)

            val paused = current.copy(
                state = SessionState.PAUSED,
                pauseStartTimeMillis = now
            )
            _sessionFlow.value = paused
            paused
        }

        // Room and widget updates outside mutex
        val db = AppDatabase.getInstance(context)
        db.focusSessionDao().upsert(pausedSession)
        WidgetUpdater.requestUpdate(context)
        return true
    }

    /**
     * Resumes a paused session.
     * Extends session end time by the elapsed pause duration to keep focus target honest.
     */
    suspend fun resumeSession(context: Context): Boolean {
        val activeSession = mutex.withLock {
            val current = _sessionFlow.value
                ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
                ?: return false

            if (current.state != SessionState.PAUSED) return false

            val now = System.currentTimeMillis()
            val additionalPause = if (current.pauseStartTimeMillis > 0) (now - current.pauseStartTimeMillis).coerceAtLeast(0L) else 0L
            val newEndTime = current.endTimeMillis + additionalPause

            PrefsManager.clearEmergencyPause(context)
            PrefsManager.setSession(context, current.mode, SessionState.ACTIVE, newEndTime)

            val active = current.copy(
                state = SessionState.ACTIVE,
                endTimeMillis = newEndTime,
                pausedDurationMillis = current.pausedDurationMillis + additionalPause,
                pauseStartTimeMillis = 0L
            )
            _sessionFlow.value = active
            active
        }

        val db = AppDatabase.getInstance(context)
        db.focusSessionDao().upsert(activeSession)
        WidgetUpdater.requestUpdate(context)
        return true
    }

    /**
     * Stops the session early.
     * Disallowed in Strict Mode at the state layer.
     */
    suspend fun stopSessionEarly(context: Context, reason: String = "Stopped early"): Boolean {
        // Enforce Strict Mode restriction at the state manager boundary
        val currentMode = PrefsManager.getSessionMode(context)
        if (currentMode == SessionMode.STRICT) return false

        val current = _sessionFlow.value
            ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
        if (current != null && current.mode == SessionMode.STRICT) return false

        return finalizeSession(
            context = context,
            finalState = SessionState.STOPPED,
            completedNaturally = false,
            stopReason = reason
        )
    }

    /**
     * Completes the session when countdown timer reaches zero.
     * Delegates to centralized finalizer.
     */
    suspend fun completeSession(context: Context): Boolean {
        return finalizeSession(
            context = context,
            finalState = SessionState.COMPLETED,
            completedNaturally = true
        )
    }

    /**
     * Increments the distraction attempt counter when a blocked app or site is resisted.
     */
    fun recordDistractionAttempt(context: Context) {
        scope.launch {
            val updated = mutex.withLock {
                val current = _sessionFlow.value
                    ?: AppDatabase.getInstance(context).focusSessionDao().getSessionOnce()
                    ?: return@launch
                if (current.state.isLive) {
                    val u = current.copy(distractionsBlocked = current.distractionsBlocked + 1)
                    _sessionFlow.value = u
                    u
                } else null
            }
            if (updated != null) {
                AppDatabase.getInstance(context).focusSessionDao().upsert(updated)
            }
        }
    }

    /**
     * Recovers session state after process death or device reboot.
     * If the session's end time elapsed while the app was dead, completes it cleanly
     * through the centralized finalizer.
     * If still active, restores protection services and ticker.
     */
    suspend fun recoverSessionIfNeeded(context: Context) {
        val db = AppDatabase.getInstance(context)
        val current = db.focusSessionDao().getSessionOnce() ?: return
        val now = System.currentTimeMillis()

        if (current.state.isLive) {
            if (now >= current.endTimeMillis && current.endTimeMillis > 0L) {
                // Expired during process death or reboot: route through centralized finalizer
                finalizeSession(
                    context = context,
                    finalState = SessionState.COMPLETED,
                    completedNaturally = true,
                    customEndTimeMillis = current.endTimeMillis
                )
            } else {
                // Still active: restore
                val active = mutex.withLock {
                    val recovering = current.copy(state = SessionState.RECOVERING)
                    _sessionFlow.value = recovering
                    val act = current.copy(state = SessionState.ACTIVE)
                    _sessionFlow.value = act
                    PrefsManager.setSession(context, current.mode, SessionState.ACTIVE, current.endTimeMillis)
                    act
                }

                db.focusSessionDao().upsert(active)

                val remaining = active.endTimeMillis - now
                SessionTimerService.start(context, remaining, active.mode)

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
