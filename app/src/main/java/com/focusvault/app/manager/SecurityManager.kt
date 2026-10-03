package com.focusvault.app.manager

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SecurityManager {

    private const val KEY_PIN_HASH_V4 = "pin_hash_v4"
    private const val KEY_SEC_ANSWER_HASH_V4 = "sec_answer_hash_v4"

    private const val KEY_PIN_HASH_V3 = "pin_hash_v3"
    private const val KEY_PIN_SALT_V3 = "pin_salt_v3"
    
    private const val KEY_PIN_HASH_V2 = "pin_hash_v2"
    private const val KEY_PIN_SALT_V2 = "pin_salt_v2"
    
    private const val KEY_SEC_QUESTION_INDEX = "security_question_index"
    
    private const val KEY_SEC_ANSWER_HASH_V3 = "sec_answer_hash_v3"
    private const val KEY_SEC_ANSWER_SALT_V3 = "sec_answer_salt_v3"
    
    private const val KEY_SEC_ANSWER_HASH_V2 = "sec_answer_hash_v2"
    private const val KEY_SEC_ANSWER_SALT_V2 = "sec_answer_salt_v2"

    private const val KEY_LEGACY_PIN = "lock_mode_pin"
    
    private const val KEY_FAILED_ATTEMPTS = "failed_pin_attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until_millis"

    const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 3
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_ITERATIONS_V3 = 10_000
    private const val KEY_LENGTH_BITS = 256

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("stay_focused_security", Context.MODE_PRIVATE)

    private fun fastPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("stay_focused_fast_cache", Context.MODE_PRIVATE)

    fun hasPin(context: Context): Boolean {
        migrateLegacyPinIfNeeded(context)
        if (!prefs(context).getString(KEY_PIN_HASH_V4, null).isNullOrEmpty()) return true
        if (!prefs(context).getString(KEY_PIN_HASH_V3, null).isNullOrEmpty()) return true
        if (!prefs(context).getString(KEY_PIN_HASH_V2, null).isNullOrEmpty()) return true
        return false
    }

    fun setPin(context: Context, pin: String) {
        val salt = generateSalt()
        val hash = hashWithSalt(pin, salt, PBKDF2_ITERATIONS)
        val v4String = "v4:$PBKDF2_ITERATIONS:$salt:$hash"
        prefs(context).edit()
            .putString(KEY_PIN_HASH_V4, v4String)
            .remove(KEY_PIN_HASH_V3)
            .remove(KEY_PIN_SALT_V3)
            .remove(KEY_PIN_HASH_V2)
            .remove(KEY_PIN_SALT_V2)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()

        fastPrefs(context).edit().remove(KEY_LEGACY_PIN).apply()
    }

    fun getLockoutRemainingSeconds(context: Context): Long {
        val lockoutUntil = prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0L)
        val diff = lockoutUntil - com.focusvault.app.util.TimeUtils.getSecureCurrentTimeMillis(context)
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

    private fun handleFailedAttempt(context: Context): PinVerifyResult {
        val currentFailed = prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        var newLockoutUntil = 0L
        
        // Escalating lockouts: 1m, 5m, 30m, 2h
        if (currentFailed >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            val lockoutMinutes = when (currentFailed - MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                0 -> 1L
                1 -> 5L
                2 -> 30L
                else -> 120L // 2 hours max
            }
            newLockoutUntil = com.focusvault.app.util.TimeUtils.getSecureCurrentTimeMillis(context) + (lockoutMinutes * 60_000L)
        }

        prefs(context).edit()
            .putInt(KEY_FAILED_ATTEMPTS, currentFailed)
            .putLong(KEY_LOCKOUT_UNTIL, newLockoutUntil)
            .apply()

        val remainingSec = if (newLockoutUntil > 0) (newLockoutUntil - com.focusvault.app.util.TimeUtils.getSecureCurrentTimeMillis(context) + 999) / 1000 else 0L
        val remainingAttempts = (MAX_ATTEMPTS_BEFORE_LOCKOUT - currentFailed).coerceAtLeast(0)

        return PinVerifyResult(
            isSuccess = false,
            isLockedOut = remainingSec > 0,
            lockoutSeconds = remainingSec,
            attemptsRemaining = remainingAttempts
        )
    }

    private fun handleSuccessAttempt(context: Context): PinVerifyResult {
        prefs(context).edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        return PinVerifyResult(isSuccess = true, isLockedOut = false)
    }

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

        val storedV4 = prefs(context).getString(KEY_PIN_HASH_V4, null)
        if (!storedV4.isNullOrEmpty() && storedV4.startsWith("v4:")) {
            val parts = storedV4.split(":")
            if (parts.size == 4) {
                val iters = parts[1].toIntOrNull() ?: PBKDF2_ITERATIONS
                val salt = parts[2]
                val hash = parts[3]
                if (hashWithSalt(attempt, salt, iters) == hash) {
                    return handleSuccessAttempt(context)
                }
            }
            return handleFailedAttempt(context)
        }

        val storedHashV3 = prefs(context).getString(KEY_PIN_HASH_V3, null)
        val storedSaltV3 = prefs(context).getString(KEY_PIN_SALT_V3, null)

        if (!storedHashV3.isNullOrEmpty() && !storedSaltV3.isNullOrEmpty()) {
            val attemptHash = hashWithSalt(attempt, storedSaltV3, PBKDF2_ITERATIONS_V3)
            if (attemptHash == storedHashV3) {
                setPin(context, attempt) // Silent upgrade to v4
                return handleSuccessAttempt(context)
            }
        } else {
            val storedHashV2 = prefs(context).getString(KEY_PIN_HASH_V2, null)
            val storedSaltV2 = prefs(context).getString(KEY_PIN_SALT_V2, null)
            if (!storedHashV2.isNullOrEmpty() && !storedSaltV2.isNullOrEmpty()) {
                val attemptHashV2 = hashWithSha256(attempt, storedSaltV2)
                if (attemptHashV2 == storedHashV2) {
                    setPin(context, attempt) // Silent upgrade to v4
                    return handleSuccessAttempt(context)
                }
            } else {
                return PinVerifyResult(isSuccess = false, isLockedOut = false, attemptsRemaining = 0)
            }
        }

        return handleFailedAttempt(context)
    }

    fun hasSecurityAnswer(context: Context): Boolean {
        if (!prefs(context).getString(KEY_SEC_ANSWER_HASH_V4, null).isNullOrEmpty()) return true
        if (!prefs(context).getString(KEY_SEC_ANSWER_HASH_V3, null).isNullOrEmpty()) return true
        if (!prefs(context).getString(KEY_SEC_ANSWER_HASH_V2, null).isNullOrEmpty()) return true
        return false
    }

    fun setSecurityAnswer(context: Context, questionIndex: Int, rawAnswer: String) {
        val normalized = rawAnswer.trim().lowercase()
        val salt = generateSalt()
        val hash = hashWithSalt(normalized, salt, PBKDF2_ITERATIONS)
        val v4String = "v4:$PBKDF2_ITERATIONS:$salt:$hash"
        prefs(context).edit()
            .putInt(KEY_SEC_QUESTION_INDEX, questionIndex)
            .putString(KEY_SEC_ANSWER_HASH_V4, v4String)
            .remove(KEY_SEC_ANSWER_HASH_V3)
            .remove(KEY_SEC_ANSWER_SALT_V3)
            .remove(KEY_SEC_ANSWER_HASH_V2)
            .remove(KEY_SEC_ANSWER_SALT_V2)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun getSecurityQuestionIndex(context: Context): Int =
        prefs(context).getInt(KEY_SEC_QUESTION_INDEX, 0)

    fun verifySecurityAnswer(context: Context, rawAnswer: String): PinVerifyResult {
        val lockoutSec = getLockoutRemainingSeconds(context)
        if (lockoutSec > 0) {
            return PinVerifyResult(
                isSuccess = false,
                isLockedOut = true,
                lockoutSeconds = lockoutSec,
                attemptsRemaining = 0
            )
        }

        val normalized = rawAnswer.trim().lowercase()
        
        val storedV4 = prefs(context).getString(KEY_SEC_ANSWER_HASH_V4, null)
        if (!storedV4.isNullOrEmpty() && storedV4.startsWith("v4:")) {
            val parts = storedV4.split(":")
            if (parts.size == 4) {
                val iters = parts[1].toIntOrNull() ?: PBKDF2_ITERATIONS
                val salt = parts[2]
                val hash = parts[3]
                if (hashWithSalt(normalized, salt, iters) == hash) {
                    return handleSuccessAttempt(context)
                }
            }
            return handleFailedAttempt(context)
        }

        val v3Hash = prefs(context).getString(KEY_SEC_ANSWER_HASH_V3, null)
        val v3Salt = prefs(context).getString(KEY_SEC_ANSWER_SALT_V3, null)
        
        if (!v3Hash.isNullOrEmpty() && !v3Salt.isNullOrEmpty()) {
            if (hashWithSalt(normalized, v3Salt, PBKDF2_ITERATIONS_V3) == v3Hash) {
                val qIdx = getSecurityQuestionIndex(context)
                setSecurityAnswer(context, qIdx, rawAnswer) // Silent upgrade to v4
                return handleSuccessAttempt(context)
            }
        } else {
            val v2Hash = prefs(context).getString(KEY_SEC_ANSWER_HASH_V2, null)
            val v2Salt = prefs(context).getString(KEY_SEC_ANSWER_SALT_V2, null)
            if (!v2Hash.isNullOrEmpty() && !v2Salt.isNullOrEmpty()) {
                if (hashWithSha256(normalized, v2Salt) == v2Hash) {
                    val qIdx = getSecurityQuestionIndex(context)
                    setSecurityAnswer(context, qIdx, rawAnswer) // Silent upgrade to v4
                    return handleSuccessAttempt(context)
                }
            } else {
                return PinVerifyResult(isSuccess = false, isLockedOut = false, attemptsRemaining = 0)
            }
        }

        return handleFailedAttempt(context)
    }

    private fun migrateLegacyPinIfNeeded(context: Context) {
        val legacyPin = fastPrefs(context).getString(KEY_LEGACY_PIN, null)
        if (!legacyPin.isNullOrEmpty() && !hasPin(context)) {
            setPin(context, legacyPin)
        }
    }

    fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }

    fun hashWithSalt(input: String, saltBase64: String, iterations: Int): String {
        val salt = Base64.getDecoder().decode(saltBase64)
        val spec = PBEKeySpec(input.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(hash)
    }

    fun hashWithSha256(input: String, saltBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.getDecoder().decode(saltBase64))
        val hashedBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashedBytes)
    }
}
