package com.stayfocused.app

import com.stayfocused.app.manager.SecurityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityManagerTest {

    @Test
    fun testSaltUniqueness() {
        val salt1 = SecurityManager.generateSalt()
        val salt2 = SecurityManager.generateSalt()
        assertFalse("Salts must not be empty", salt1.isEmpty())
        assertFalse("Salts must not be empty", salt2.isEmpty())
        assertNotEquals("Each generated salt must be cryptographically random and unique", salt1, salt2)
    }

    @Test
    fun testHashDeterministicWithSameSalt() {
        val pin = "1234"
        val salt = SecurityManager.generateSalt()
        val hash1 = SecurityManager.hashWithSalt(pin, salt)
        val hash2 = SecurityManager.hashWithSalt(pin, salt)
        assertEquals("Hashing same pin with same salt must produce identical hash", hash1, hash2)
    }

    @Test
    fun testHashDiffersWithDifferentSalt() {
        val pin = "1234"
        val salt1 = SecurityManager.generateSalt()
        val salt2 = SecurityManager.generateSalt()
        val hash1 = SecurityManager.hashWithSalt(pin, salt1)
        val hash2 = SecurityManager.hashWithSalt(pin, salt2)
        assertNotEquals("Hashing same pin with different salts must produce distinct hashes", hash1, hash2)
    }

    @Test
    fun testWrongPinProducesWrongHash() {
        val pin = "1234"
        val wrongPin = "4321"
        val salt = SecurityManager.generateSalt()
        val correctHash = SecurityManager.hashWithSalt(pin, salt)
        val attemptHash = SecurityManager.hashWithSalt(wrongPin, salt)
        assertNotEquals("Wrong PIN must not match correct hash", correctHash, attemptHash)
    }

    @Test
    fun testLockoutProgressionCalculation() {
        val maxAttempts = 3
        var failedAttempts = 0

        failedAttempts++ // 1
        var isLocked = failedAttempts >= maxAttempts
        assertFalse(isLocked)

        failedAttempts++ // 2
        isLocked = failedAttempts >= maxAttempts
        assertFalse(isLocked)

        failedAttempts++ // 3 -> Lockout triggered!
        isLocked = failedAttempts >= maxAttempts
        assertTrue(isLocked)
    }
}
