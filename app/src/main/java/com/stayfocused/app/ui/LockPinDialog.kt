package com.stayfocused.app.ui

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stayfocused.app.util.BiometricHelper
import com.stayfocused.app.util.PrefsManager

/**
 * This is the one PIN used everywhere Stay Focused needs one: unlocking a Lock Mode
 * session early, the general App Lock PIN protecting settings, and gating the app's own
 * launch (see MainActivity.onResume). One PIN, set once, changeable any time you can prove
 * you already know it - or recovered via the single security question set up alongside it.
 */
object LockPinDialog {

    fun promptAndVerify(context: Context, onVerified: () -> Unit) {
        val activity = context as? androidx.fragment.app.FragmentActivity
        if (activity != null && PrefsManager.isBiometricEnabled(context) && BiometricHelper.isAvailable(context)) {
            BiometricHelper.showPrompt(
                activity,
                title = "Verify it's you",
                onSuccess = onVerified,
                onFallback = { showPinVerifyDialog(context, onVerified) }
            )
        } else {
            showPinVerifyDialog(context, onVerified)
        }
    }

    private fun showPinVerifyDialog(context: Context, onVerified: () -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter your PIN"
        }

        val lockoutRemaining = com.stayfocused.app.manager.SecurityManager.getLockoutRemainingSeconds(context)
        val initialMsg = if (lockoutRemaining > 0) {
            "Too many incorrect attempts. Try again in $lockoutRemaining seconds."
        } else {
            "This action needs your PIN to continue."
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Enter your PIN")
            .setMessage(initialMsg)
            .setView(input)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Forgot PIN?", null)
            .create()

        dialog.setOnShowListener {
            val unlockBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (lockoutRemaining > 0) unlockBtn.isEnabled = false

            unlockBtn.setOnClickListener {
                val result = com.stayfocused.app.manager.SecurityManager.verifyPin(context, input.text.toString())
                if (result.isSuccess) {
                    dialog.dismiss()
                    onVerified()
                } else if (result.isLockedOut) {
                    input.text.clear()
                    dialog.setMessage("Too many incorrect attempts. Locked for ${result.lockoutSeconds} seconds.")
                    unlockBtn.isEnabled = false
                    Toast.makeText(context, "Locked for ${result.lockoutSeconds} seconds", Toast.LENGTH_SHORT).show()
                } else {
                    input.text.clear()
                    dialog.setMessage("Incorrect PIN. Attempts remaining: ${result.attemptsRemaining}")
                    Toast.makeText(context, "Incorrect PIN. ${result.attemptsRemaining} attempts left", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                promptForgotPin(context) {
                    dialog.dismiss()
                    onVerified()
                }
            }
        }
        dialog.show()
    }

    /** Full app-unlock gate shown when Stay Focused itself is opened/returned to and an App
     * Lock PIN is set. Unlike [promptAndVerify] (used for one-off gated actions inside the
     * app), this dialog cannot be dismissed by tapping outside it or pressing back - only a
     * correct PIN, successful recovery via "Forgot PIN?", or explicitly choosing "Exit App",
     * gets past it. */
    fun promptAppUnlock(activity: Activity, onUnlocked: () -> Unit) {
        val fragmentActivity = activity as? androidx.fragment.app.FragmentActivity
        if (fragmentActivity != null && PrefsManager.isBiometricEnabled(activity) && BiometricHelper.isAvailable(activity)) {
            BiometricHelper.showPrompt(
                fragmentActivity,
                title = "Unlock Stay Focused",
                onSuccess = onUnlocked,
                onFallback = { showAppUnlockPinDialog(activity, onUnlocked) }
            )
        } else {
            showAppUnlockPinDialog(activity, onUnlocked)
        }
    }

    private fun showAppUnlockPinDialog(activity: Activity, onUnlocked: () -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter your PIN"
        }

        val lockoutRemaining = com.stayfocused.app.manager.SecurityManager.getLockoutRemainingSeconds(activity)
        val initialMsg = if (lockoutRemaining > 0) {
            "Too many incorrect attempts. Try again in $lockoutRemaining seconds."
        } else {
            "Enter your PIN to continue."
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Stay Focused is locked")
            .setMessage(initialMsg)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Exit App") { _, _ -> activity.finishAffinity() }
            .setNeutralButton("Forgot PIN?", null)
            .create()

        dialog.setOnShowListener {
            val unlockBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (lockoutRemaining > 0) unlockBtn.isEnabled = false

            unlockBtn.setOnClickListener {
                val result = com.stayfocused.app.manager.SecurityManager.verifyPin(activity, input.text.toString())
                if (result.isSuccess) {
                    dialog.dismiss()
                    onUnlocked()
                } else if (result.isLockedOut) {
                    input.text.clear()
                    dialog.setMessage("Too many incorrect attempts. Locked for ${result.lockoutSeconds} seconds.")
                    unlockBtn.isEnabled = false
                    Toast.makeText(activity, "Locked for ${result.lockoutSeconds} seconds", Toast.LENGTH_SHORT).show()
                } else {
                    input.text.clear()
                    dialog.setMessage("Incorrect PIN. Attempts remaining: ${result.attemptsRemaining}")
                    Toast.makeText(activity, "Incorrect PIN. ${result.attemptsRemaining} attempts left", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                promptForgotPin(activity) {
                    dialog.dismiss()
                    onUnlocked()
                }
            }
        }
        dialog.show()
    }

    /** Sets a PIN for the first time, or changes an existing one (asks for the current PIN
     * first if one is already set). Safe to call any time - handles both cases. */
    fun promptSetOrChangePin(context: Context, onDone: () -> Unit) {
        if (PrefsManager.hasLockPin(context)) {
            promptAndVerify(context) { promptNewPin(context, onDone) }
        } else {
            promptNewPin(context, onDone)
        }
    }

    /** Walks someone who forgot their PIN through the single recovery question set up when
     * the PIN was first created. A correct answer is required before a new PIN can be set.
     * If cancelled at any point, onPinReset is simply never called - the caller's own lock
     * dialog (still showing underneath) is what actually keeps things locked. If no recovery
     * question was ever set (a PIN created before this feature existed), there's no way to
     * recover it, and this explains that instead. */
    fun promptForgotPin(context: Context, onPinReset: () -> Unit) {
        if (!PrefsManager.hasSecurityAnswer(context)) {
            AlertDialog.Builder(context)
                .setTitle("Can't recover this PIN")
                .setMessage(
                    "No recovery question was ever set up for this PIN, so it can't be reset " +
                        "this way. You'll need to uninstall and reinstall Stay Focused to clear it."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val question = PrefsManager.SECURITY_QUESTIONS.getOrElse(PrefsManager.getSecurityQuestionIndex(context)) {
            PrefsManager.SECURITY_QUESTIONS.first()
        }
        val answerInput = EditText(context).apply { hint = "Your answer" }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(TextView(context).apply {
                text = question
                textSize = 15f
                setPadding(0, 0, 0, pad / 2)
            })
            addView(answerInput)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Answer your recovery question")
            .setView(container)
            .setPositiveButton("Verify", null) // overridden below so a wrong answer doesn't dismiss
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (PrefsManager.verifySecurityAnswer(context, answerInput.text.toString())) {
                    dialog.dismiss()
                    promptNewPin(context, onPinReset)
                } else {
                    answerInput.text.clear()
                    Toast.makeText(context, "That answer didn't match", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    /** If no recovery question has ever been set up yet, prompts for one (question + answer)
     * and saves it, then calls [onDone]. If a recovery question already exists, calls
     * [onDone] immediately with no prompt. Any code path that can create the very first
     * PIN on this device (Settings' "App Lock PIN", or confirming a fresh Lock Mode PIN)
     * should route through this afterward, so "Forgot PIN?" recovery is never left
     * permanently unavailable just because of which screen happened to create the PIN. */
    fun promptSecurityQuestionSetupIfNeeded(context: Context, onDone: () -> Unit) {
        if (PrefsManager.hasSecurityAnswer(context)) {
            onDone()
            return
        }

        val questionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, PrefsManager.SECURITY_QUESTIONS)
        }
        val answerInput = EditText(context).apply { hint = "Your answer" }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(TextView(context).apply {
                text = "Pick a recovery question, in case you ever forget your PIN:"
                gravity = Gravity.START
                setPadding(0, 0, 0, pad / 2)
            })
            addView(questionSpinner)
            addView(answerInput)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Set up PIN recovery")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Save", null) // overridden below so a blank answer doesn't dismiss
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (answerInput.text.isBlank()) {
                    Toast.makeText(context, "Please answer the recovery question", Toast.LENGTH_SHORT).show()
                } else {
                    PrefsManager.setSecurityAnswer(context, questionSpinner.selectedItemPosition, answerInput.text.toString())
                    dialog.dismiss()
                    onDone()
                }
            }
        }
        dialog.show()
    }

    private fun promptNewPin(context: Context, onDone: () -> Unit) {
        val newPin = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "New PIN (4-8 digits)"
        }
        val confirmPin = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Confirm new PIN"
        }

        // Only ask for a recovery question the very first time a PIN is ever set on this
        // device - not every time it's changed afterward.
        val needsSecuritySetup = !PrefsManager.hasSecurityAnswer(context)
        val questionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, PrefsManager.SECURITY_QUESTIONS)
        }
        val answerInput = EditText(context).apply { hint = "Your answer" }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(newPin)
            addView(confirmPin)
            if (needsSecuritySetup) {
                addView(TextView(context).apply {
                    text = "Pick a recovery question, in case you ever forget your PIN:"
                    gravity = Gravity.START
                    setPadding(0, pad, 0, 0)
                })
                addView(questionSpinner)
                addView(answerInput)
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (PrefsManager.hasLockPin(context)) "Change your PIN" else "Set a PIN")
            .setView(container)
            .setPositiveButton("Save", null) // overridden below so a validation failure doesn't dismiss
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = newPin.text.toString()
                val confirm = confirmPin.text.toString()
                when {
                    pin.length < 4 -> Toast.makeText(context, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    pin != confirm -> Toast.makeText(context, "PINs don't match", Toast.LENGTH_SHORT).show()
                    needsSecuritySetup && answerInput.text.isBlank() ->
                        Toast.makeText(context, "Please answer the recovery question", Toast.LENGTH_SHORT).show()
                    else -> {
                        PrefsManager.setLockPin(context, pin)
                        if (needsSecuritySetup) {
                            PrefsManager.setSecurityAnswer(
                                context,
                                questionSpinner.selectedItemPosition,
                                answerInput.text.toString()
                            )
                        }
                        Toast.makeText(context, "PIN saved", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onDone()
                    }
                }
            }
        }
        dialog.show()
    }
}
