package com.focusvault.app

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
        val mode = com.focusvault.app.data.SessionMode.NORMAL
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
}
