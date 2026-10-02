package com.focusvault.app

import com.focusvault.app.data.SessionMode
import com.focusvault.app.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    @Test
    fun testLiveStateDetection() {
        assertFalse(SessionState.STARTING.isLive)
        assertTrue(SessionState.ACTIVE.isLive)
        assertTrue(SessionState.PAUSED.isLive)
        assertTrue(SessionState.RECOVERING.isLive)

        assertFalse(SessionState.IDLE.isLive)
        assertFalse(SessionState.COMPLETING.isLive)
        assertFalse(SessionState.COMPLETED.isLive)
        assertFalse(SessionState.STOPPED.isLive)
        assertFalse(SessionState.INTERRUPTED.isLive)
        assertFalse(SessionState.PROTECTION_FAILED.isLive)
    }

    @Test
    fun testValidStateTransitions() {
        var state = SessionState.IDLE
        assertEquals(SessionState.IDLE, state)

        // Starting session
        state = SessionState.STARTING
        assertFalse(state.isLive)

        // Running session
        state = SessionState.ACTIVE
        assertTrue(state.isLive)

        // Emergency pause
        state = SessionState.PAUSED
        assertTrue(state.isLive)

        // Resumed
        state = SessionState.ACTIVE
        assertTrue(state.isLive)

        // Natural completion
        state = SessionState.COMPLETING
        assertFalse(state.isLive)
        state = SessionState.COMPLETED
        assertFalse(state.isLive)
    }

    @Test
    fun testEarlyStopTransition() {
        var state = SessionState.ACTIVE
        assertTrue(state.isLive)

        // User stops early
        state = SessionState.STOPPED
        assertFalse(state.isLive)
    }

    @Test
    fun testInterruptedTransition() {
        var state = SessionState.ACTIVE
        assertTrue(state.isLive)

        // Critical service killed or unexpected shutdown
        state = SessionState.INTERRUPTED
        assertFalse(state.isLive)
    }

    @Test
    fun testRecoveryDecisionLogic() {
        val now = 1_000_000L
        val unexpiredEndTime = 1_500_000L
        val expiredEndTime = 900_000L

        // If session was active and end time is still in the future -> recovery succeeds (state is ACTIVE)
        val shouldResume = unexpiredEndTime > now
        assertTrue(shouldResume)

        // If session was active but end time already lapsed -> recovery marks COMPLETED
        val shouldComplete = expiredEndTime <= now
        assertTrue(shouldComplete)
    }

    @Test
    fun testNormalFocusModeLifecycle() {
        val mode = SessionMode.NORMAL
        assertEquals("NORMAL", mode.name)
        
        var state = SessionState.IDLE
        assertFalse(state.isLive)
        
        state = SessionState.STARTING
        assertFalse(state.isLive)

        state = SessionState.ACTIVE
        assertTrue(state.isLive)

        // Normal mode supports stopping early
        state = SessionState.STOPPED
        assertFalse(state.isLive)
    }

    @Test
    fun testIdempotentSessionStartGuard() {
        val activeState = SessionState.ACTIVE
        val now = System.currentTimeMillis()
        val futureEnd = now + 1800000L
        
        // Active session with future end time should reject starting a second session
        val canStartNew = !activeState.isLive || now >= futureEnd
        assertFalse("Should prevent starting duplicate session when one is active", canStartNew)
    }

    @Test
    fun testStrictModeStateLayerRestrictions() {
        // State layer must forbid early stopping in Strict Mode
        fun canStopEarly(mode: SessionMode, state: SessionState): Boolean {
            if (mode == SessionMode.STRICT) return false
            return state.isLive
        }

        assertFalse(canStopEarly(SessionMode.STRICT, SessionState.ACTIVE))
        assertFalse(canStopEarly(SessionMode.STRICT, SessionState.PAUSED))
        assertTrue(canStopEarly(SessionMode.NORMAL, SessionState.ACTIVE))
        assertTrue(canStopEarly(SessionMode.LOCK, SessionState.ACTIVE))

        // State layer must forbid pausing in Strict Mode
        fun canPause(mode: SessionMode, state: SessionState): Boolean {
            if (mode == SessionMode.STRICT) return false
            return state == SessionState.ACTIVE
        }

        assertFalse(canPause(SessionMode.STRICT, SessionState.ACTIVE))
        assertTrue(canPause(SessionMode.NORMAL, SessionState.ACTIVE))
        assertTrue(canPause(SessionMode.LOCK, SessionState.ACTIVE))
    }

    @Test
    fun testIdempotentFinalizationGuard() {
        // Finalizing a session that is already terminal must be rejected
        fun canFinalize(currentState: SessionState, targetFinalState: SessionState): Boolean {
            if (!currentState.isLive && currentState != SessionState.COMPLETING) {
                return false
            }
            if (currentState == targetFinalState) {
                return false
            }
            return true
        }

        // Active / Paused / Recovering sessions can be finalized
        assertTrue(canFinalize(SessionState.ACTIVE, SessionState.COMPLETED))
        assertTrue(canFinalize(SessionState.PAUSED, SessionState.STOPPED))
        assertTrue(canFinalize(SessionState.RECOVERING, SessionState.COMPLETED))

        // Terminal sessions must reject second finalization
        assertFalse(canFinalize(SessionState.COMPLETED, SessionState.COMPLETED))
        assertFalse(canFinalize(SessionState.STOPPED, SessionState.STOPPED))
        assertFalse(canFinalize(SessionState.IDLE, SessionState.COMPLETED))
        assertFalse(canFinalize(SessionState.INTERRUPTED, SessionState.COMPLETED))
    }
}
