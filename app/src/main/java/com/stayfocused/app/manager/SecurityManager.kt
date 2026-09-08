package com.stayfocused.app.manager

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Manages PIN and security question authentication with cryptographic security:
 * - Never stores raw plaintext PINs; uses salted PBKDF2WithHmacSHA256 hashes (10,000 iterations).
 * - Migrates any legacy plaintext or v2 single-round SHA-256 PIN transparently to PBKDF2 v3 format.
 * - Rate-limits failed attempts with progressive lockouts (30s after 3 fails, 60s after 5 fails).
 * - Persists lockout timestamp to prevent bypasses via app restart.
 */
object SecurityManager {

    private const val PREFS = "stay_focused_security"
    private const val KEY_PIN_HASH_V3 = "pin_hash_v3"
    private const val KEY_PIN_SALT_V3 = "pin_salt_v3"
    private const val KEY_PIN_HASH_V2 = "pin_hash_v2"
    private const val KEY_PIN_SALT_V2 = "pin_salt_v2"
    private const val KEY_LEGACY_PIN = "lock_mode_pin" // From old PrefsManager
    private const val KEY_SEC_ANSWER_HASH_V3 = "sec_answer_hash_v3"
    private const val KEY_SEC_ANSWER_SALT_V3 = "sec_answer_salt_v3"
    private const val KEY_SEC_ANSWER_HASH_V2 = "sec_answer_hash_v2"
    private const val KEY_SEC_ANSWER_SALT_V2 = "sec_answer_salt_v2"
    private const val KEY_SEC_QUESTION_INDEX = "security_question_index"
    private const val KEY_FAILED_ATTEMPTS = "failed_pin_attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until_millis"

    private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 3
    private const val PBKDF2_ITERATIONS = 10_000
    private const val KEY_LENGTH_BITS = 256

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fastPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("stay_focused_fast_cache", Context.MODE_PRIVATE)

    fun hasPin(context: Context): Boolean {
        migrateLegacyPinIfNeeded(context)
        val v3 = prefs(context).getString(KEY_PIN_HASH_V3, null)
        if (!v3.isNullOrEmpty()) return true
        val v2 = prefs(context).getString(KEY_PIN_HASH_V2, null)
        return !v2.isNullOrEmpty()
    }

    fun setPin(context: Context, pin: String) {
        val salt = generateSalt()
        val hash = hashWithSalt(pin, salt)
        prefs(context).edit()
            .putString(KEY_PIN_HASH_V3, hash)
            .putString(KEY_PIN_SALT_V3, salt)
            .remove(KEY_PIN_HASH_V2)
            .remove(KEY_PIN_SALT_V2)
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

        val storedHashV3 = prefs(context).getString(KEY_PIN_HASH_V3, null)
        val storedSaltV3 = prefs(context).getString(KEY_PIN_SALT_V3, null)

        if (!storedHashV3.isNullOrEmpty() && !storedSaltV3.isNullOrEmpty()) {
            val attemptHash = hashWithSalt(attempt, storedSaltV3)
            if (attemptHash == storedHashV3) {
                // Success: reset failure counter
                prefs(context).edit()
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0L)
                    .apply()
                return PinVerifyResult(isSuccess = true, isLockedOut = false)
            }
        } else {
            // Lazy migration from v2 SHA-256
            val storedHashV2 = prefs(context).getString(KEY_PIN_HASH_V2, null)
            val storedSaltV2 = prefs(context).getString(KEY_PIN_SALT_V2, null)
            if (!storedHashV2.isNullOrEmpty() && !storedSaltV2.isNullOrEmpty()) {
                val attemptHashV2 = hashWithSha256(attempt, storedSaltV2)
                if (attemptHashV2 == storedHashV2) {
                    setPin(context, attempt) // Upgrade to PBKDF2 v3
                    return PinVerifyResult(isSuccess = true, isLockedOut = false)
                }
            } else {
                return PinVerifyResult(isSuccess = false, isLockedOut = false, attemptsRemaining = 0)
            }
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

    fun hasSecurityAnswer(context: Context): Boolean {
        val v3 = prefs(context).getString(KEY_SEC_ANSWER_HASH_V3, null)
        if (!v3.isNullOrEmpty()) return true
        val v2 = prefs(context).getString(KEY_SEC_ANSWER_HASH_V2, null)
        return !v2.isNullOrEmpty()
    }

    fun setSecurityAnswer(context: Context, questionIndex: Int, rawAnswer: String) {
        val normalized = rawAnswer.trim().lowercase()
        val salt = generateSalt()
        val hash = hashWithSalt(normalized, salt)
        prefs(context).edit()
            .putInt(KEY_SEC_QUESTION_INDEX, questionIndex)
            .putString(KEY_SEC_ANSWER_HASH_V3, hash)
            .putString(KEY_SEC_ANSWER_SALT_V3, salt)
            .remove(KEY_SEC_ANSWER_HASH_V2)
            .remove(KEY_SEC_ANSWER_SALT_V2)
            .apply()
    }

    fun getSecurityQuestionIndex(context: Context): Int =
        prefs(context).getInt(KEY_SEC_QUESTION_INDEX, 0)

    fun verifySecurityAnswer(context: Context, rawAnswer: String): Boolean {
        val normalized = rawAnswer.trim().lowercase()
        val v3Hash = prefs(context).getString(KEY_SEC_ANSWER_HASH_V3, null)
        val v3Salt = prefs(context).getString(KEY_SEC_ANSWER_SALT_V3, null)
        if (!v3Hash.isNullOrEmpty() && !v3Salt.isNullOrEmpty()) {
            return hashWithSalt(normalized, v3Salt) == v3Hash
        }
        val v2Hash = prefs(context).getString(KEY_SEC_ANSWER_HASH_V2, null)
        val v2Salt = prefs(context).getString(KEY_SEC_ANSWER_SALT_V2, null)
        if (!v2Hash.isNullOrEmpty() && !v2Salt.isNullOrEmpty()) {
            val ok = hashWithSha256(normalized, v2Salt) == v2Hash
            if (ok) {
                val qIdx = getSecurityQuestionIndex(context)
                setSecurityAnswer(context, qIdx, rawAnswer)
            }
            return ok
        }
        return false
    }

    private fun migrateLegacyPinIfNeeded(context: Context) {
        val legacyPin = fastPrefs(context).getString(KEY_LEGACY_PIN, null)
        val currentHashV3 = prefs(context).getString(KEY_PIN_HASH_V3, null)
        val currentHashV2 = prefs(context).getString(KEY_PIN_HASH_V2, null)
        if (!legacyPin.isNullOrEmpty() && currentHashV3.isNullOrEmpty() && currentHashV2.isNullOrEmpty()) {
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
        val salt = Base64.getDecoder().decode(saltBase64)
        val spec = PBEKeySpec(input.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(hash)
    }

    private fun hashWithSha256(input: String, saltBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.getDecoder().decode(saltBase64))
        val hashedBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashedBytes)
    }
}
