package com.stayfocused.app.manager

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Manages PIN and security question authentication with cryptographic security:
 * - Never stores raw plaintext PINs; uses salted SHA-256 hashes.
 * - Migrates any legacy plaintext PIN transparently to salted hash format.
 * - Rate-limits failed attempts with progressive lockouts (30s after 3 fails, 60s after 5 fails).
 * - Persists lockout timestamp to prevent bypasses via app restart.
 */
object SecurityManager {

    private const val PREFS = "stay_focused_security"
    private const val KEY_PIN_HASH = "pin_hash_v2"
    private const val KEY_PIN_SALT = "pin_salt_v2"
    private const val KEY_LEGACY_PIN = "lock_mode_pin" // From old PrefsManager
    private const val KEY_SEC_ANSWER_HASH = "sec_answer_hash_v2"
    private const val KEY_SEC_ANSWER_SALT = "sec_answer_salt_v2"
    private const val KEY_SEC_QUESTION_INDEX = "security_question_index"
    private const val KEY_FAILED_ATTEMPTS = "failed_pin_attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until_millis"

    private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fastPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("stay_focused_fast_cache", Context.MODE_PRIVATE)

    fun hasPin(context: Context): Boolean {
        migrateLegacyPinIfNeeded(context)
        return !prefs(context).getString(KEY_PIN_HASH, null).isNullOrEmpty()
    }

    fun setPin(context: Context, pin: String) {
        val salt = generateSalt()
        val hash = hashWithSalt(pin, salt)
        prefs(context).edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, salt)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()

        // Clean up any legacy plaintext PIN in fast cache
        fastPrefs(context).edit().remove(KEY_LEGACY_PIN).apply()
    }

    /**
     * Checks if PIN verification is temporarily locked out due to repeated failures.
     * @return remaining lockout seconds, or 0 if not locked out.
     */
    fun getLockoutRemainingSeconds(context: Context): Long {
        val lockoutUntil = prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0L)
        val diff = lockoutUntil - System.currentTimeMillis()
        return if (diff > 0) (diff + 999) / 1000 else 0L
    }

    fun getRemainingAttempts(context: Context): Int {
        val failed = prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0)
        return (MAX_ATTEMPTS_BEFORE_LOCKOUT - failed).coerceAtLeast(0)
    }

    data class PinVerifyResult(
        val isSuccess: Boolean,
        val isLockedOut: Boolean,
        val lockoutSeconds: Long = 0,
        val attemptsRemaining: Int = 0
    )

    fun verifyPin(context: Context, attempt: String): PinVerifyResult {
        migrateLegacyPinIfNeeded(context)

        val lockoutSec = getLockoutRemainingSeconds(context)
        if (lockoutSec > 0) {
            return PinVerifyResult(
                isSuccess = false,
                isLockedOut = true,
                lockoutSeconds = lockoutSec,
                attemptsRemaining = 0
            )
        }

        val storedHash = prefs(context).getString(KEY_PIN_HASH, null)
        val storedSalt = prefs(context).getString(KEY_PIN_SALT, null)
        if (storedHash.isNullOrEmpty() || storedSalt.isNullOrEmpty()) {
            return PinVerifyResult(isSuccess = false, isLockedOut = false, attemptsRemaining = 0)
        }

        val attemptHash = hashWithSalt(attempt, storedSalt)
        if (attemptHash == storedHash) {
            // Success: reset failure counter
            prefs(context).edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .apply()
            return PinVerifyResult(isSuccess = true, isLockedOut = false)
        }

        // Failure: increment attempts and check for lockout
        val currentFailed = prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        var newLockoutUntil = 0L
        if (currentFailed >= 5) {
            newLockoutUntil = System.currentTimeMillis() + 60_000L // 60s lockout
        } else if (currentFailed >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            newLockoutUntil = System.currentTimeMillis() + 30_000L // 30s lockout
        }

        prefs(context).edit()
            .putInt(KEY_FAILED_ATTEMPTS, currentFailed)
            .putLong(KEY_LOCKOUT_UNTIL, newLockoutUntil)
            .apply()

        val remainingSec = if (newLockoutUntil > 0) (newLockoutUntil - System.currentTimeMillis() + 999) / 1000 else 0L
        val remainingAttempts = (MAX_ATTEMPTS_BEFORE_LOCKOUT - (currentFailed % MAX_ATTEMPTS_BEFORE_LOCKOUT)).coerceAtLeast(0)

        return PinVerifyResult(
            isSuccess = false,
            isLockedOut = remainingSec > 0,
            lockoutSeconds = remainingSec,
            attemptsRemaining = remainingAttempts
        )
    }

    fun hasSecurityAnswer(context: Context): Boolean =
        !prefs(context).getString(KEY_SEC_ANSWER_HASH, null).isNullOrEmpty()

    fun setSecurityAnswer(context: Context, questionIndex: Int, rawAnswer: String) {
        val normalized = rawAnswer.trim().lowercase()
        val salt = generateSalt()
        val hash = hashWithSalt(normalized, salt)
        prefs(context).edit()
            .putInt(KEY_SEC_QUESTION_INDEX, questionIndex)
            .putString(KEY_SEC_ANSWER_HASH, hash)
            .putString(KEY_SEC_ANSWER_SALT, salt)
            .apply()
    }

    fun getSecurityQuestionIndex(context: Context): Int =
        prefs(context).getInt(KEY_SEC_QUESTION_INDEX, 0)

    fun verifySecurityAnswer(context: Context, rawAnswer: String): Boolean {
        val storedHash = prefs(context).getString(KEY_SEC_ANSWER_HASH, null) ?: return false
        val storedSalt = prefs(context).getString(KEY_SEC_ANSWER_SALT, null) ?: return false
        val normalized = rawAnswer.trim().lowercase()
        return hashWithSalt(normalized, storedSalt) == storedHash
    }

    private fun migrateLegacyPinIfNeeded(context: Context) {
        val legacyPin = fastPrefs(context).getString(KEY_LEGACY_PIN, null)
        val currentHash = prefs(context).getString(KEY_PIN_HASH, null)
        if (!legacyPin.isNullOrEmpty() && currentHash.isNullOrEmpty()) {
            setPin(context, legacyPin)
        }
    }

    fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }

    fun hashWithSalt(input: String, saltBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.getDecoder().decode(saltBase64))
        val hashedBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashedBytes)
    }
}
