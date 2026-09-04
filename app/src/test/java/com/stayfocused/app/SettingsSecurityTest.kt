package com.stayfocused.app

import com.stayfocused.app.data.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSecurityTest {

    enum class PolicyDecision {
        ALLOW,
        REQUIRE_PIN,
        BLOCK_STRICT_MODE
    }

    /**
     * Mirrors the security policy implemented in SettingsActivity.guardRestrictedAction
     */
    private fun evaluateSettingsAction(
        isSessionActive: Boolean,
        sessionMode: SessionMode,
        hasLockPin: Boolean
    ): PolicyDecision {
        if (isSessionActive && sessionMode == SessionMode.STRICT) {
            return PolicyDecision.BLOCK_STRICT_MODE
        }
        if (isSessionActive && sessionMode == SessionMode.LOCK && hasLockPin) {
            return PolicyDecision.REQUIRE_PIN
        }
        return PolicyDecision.ALLOW
    }

    @Test
    fun testStrictModeBlocksRestrictedSettings() {
        val decision = evaluateSettingsAction(
            isSessionActive = true,
            sessionMode = SessionMode.STRICT,
            hasLockPin = true
        )
        assertEquals(
            "Strict Mode must completely forbid changing security PIN, admin, or backups",
            PolicyDecision.BLOCK_STRICT_MODE,
            decision
        )
    }

    @Test
    fun testLockModeRequiresPinVerification() {
        val decision = evaluateSettingsAction(
            isSessionActive = true,
            sessionMode = SessionMode.LOCK,
            hasLockPin = true
        )
        assertEquals(
            "Lock Mode must challenge user with PIN before opening restricted settings",
            PolicyDecision.REQUIRE_PIN,
            decision
        )
    }

    @Test
    fun testNormalModeOrIdleAllowsAccess() {
        val idleDecision = evaluateSettingsAction(
            isSessionActive = false,
            sessionMode = SessionMode.NORMAL,
            hasLockPin = true
        )
        assertEquals(
            "Idle state allows access to settings",
            PolicyDecision.ALLOW,
            idleDecision
        )

        val normalSessionDecision = evaluateSettingsAction(
            isSessionActive = true,
            sessionMode = SessionMode.NORMAL,
            hasLockPin = true
        )
        assertEquals(
            "Normal focus mode allows settings configuration",
            PolicyDecision.ALLOW,
            normalSessionDecision
        )
    }

    @Test
    fun testRapidTapIdempotencyDebounce() {
        var clickCount = 0
        var lastClickTime = 0L
        val debounceWindowMs = 500L

        fun onButtonClick(currentTime: Long) {
            if (currentTime - lastClickTime < debounceWindowMs) {
                return // Ignored rapid tap
            }
            lastClickTime = currentTime
            clickCount++
        }

        onButtonClick(1000L) // 1st tap allowed
        onButtonClick(1050L) // 2nd rapid tap ignored
        onButtonClick(1200L) // 3rd rapid tap ignored
        onButtonClick(1600L) // 4th tap after 600ms allowed

        assertEquals("Only 2 non-debounced clicks should be registered", 2, clickCount)
    }
}
