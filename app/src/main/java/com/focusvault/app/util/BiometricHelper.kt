package com.focusvault.app.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricHelper {

    private const val ALLOWED = BIOMETRIC_STRONG or BIOMETRIC_WEAK

    /** True only when the device can actually authenticate right now (hardware present AND
     * at least one fingerprint/face enrolled). If someone disables/removes their last
     * enrolled biometric elsewhere in the OS, this naturally starts returning false again -
     * callers should always check this before offering biometric as an option, rather than
     * only checking it once when the setting was turned on. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    /** Shows the system biometric prompt. Calls [onSuccess] on a successful scan. Calls
     * [onFallback] if the person taps "Use PIN instead", cancels, or hits any error/lockout -
     * every one of those should fall through to the normal PIN dialog rather than leaving
     * them stuck, since a wrong fingerprint is not the same as a wrong PIN (there's no
     * separate "incorrect, try again" messaging needed here - the system prompt itself
     * already handles retry attempts before giving up and calling onFallback).
     */
    fun showPrompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFallback: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFallback()
            }
            // onAuthenticationFailed (one bad scan) is deliberately not overridden - the
            // system prompt stays open and lets the person retry on its own.
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Use PIN instead")
            .setAllowedAuthenticators(ALLOWED)
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }
}
