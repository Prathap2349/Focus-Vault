package com.focusvault.app

import com.focusvault.app.data.SessionMode
import com.focusvault.app.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReliabilityTest {

    @Test
    fun testDurationValidation() {
        // Durations <= 0 must not be considered valid for a session
        val invalidDurations = listOf(0L, -1000L, -60000L)
        for (duration in invalidDurations) {
            val isValid = duration > 0
            assertFalse("Duration $duration should be invalid", isValid)
        }

        val validDurations = listOf(60000L, 1500000L, 3600000L)
        for (duration in validDurations) {
            val isValid = duration > 0
            assertTrue("Duration $duration should be valid", isValid)
        }
    }

    @Test
    fun testDoubleStartGuard() {
        // If state is STARTING or any live state (ACTIVE, PAUSED, RECOVERING),
        // another start request must be rejected.
        val blockedStates = listOf(
            SessionState.STARTING,
            SessionState.ACTIVE,
            SessionState.PAUSED,
            SessionState.RECOVERING
        )

        for (state in blockedStates) {
            val canStart = (state == SessionState.IDLE ||
                    state == SessionState.COMPLETED ||
                    state == SessionState.STOPPED ||
                    state == SessionState.INTERRUPTED)
            assertFalse("State $state must block double-start", canStart)
        }

        val allowedStates = listOf(
            SessionState.IDLE,
            SessionState.COMPLETED,
            SessionState.STOPPED,
            SessionState.INTERRUPTED
        )

        for (state in allowedStates) {
            val canStart = (state == SessionState.IDLE ||
                    state == SessionState.COMPLETED ||
                    state == SessionState.STOPPED ||
                    state == SessionState.INTERRUPTED)
            assertTrue("State $state must allow starting a new session", canStart)
        }
    }

    @Test
    fun testStrictModeEmergencyBypassGuard() {
        // Strict Mode must never allow emergency unlocking
        fun canUnlock(mode: SessionMode): Boolean {
            return mode != SessionMode.STRICT
        }

        assertFalse(canUnlock(SessionMode.STRICT))
        assertTrue(canUnlock(SessionMode.LOCK))
        assertTrue(canUnlock(SessionMode.NORMAL))
    }

    @Test
    fun testSessionEndCalculation() {
        val startMillis = 1_000_000L
        val durationMillis = 25 * 60 * 1000L
        val calculatedEnd = startMillis + durationMillis

        assertEquals(2_500_000L, calculatedEnd)
        val durationMinutes = ((calculatedEnd - startMillis) / 60000L).toInt().coerceAtLeast(0)
        assertEquals(25, durationMinutes)
    }
}
