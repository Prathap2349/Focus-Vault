package com.focusvault.app.ui

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.focusvault.app.R
import com.focusvault.app.util.BiometricHelper
import com.focusvault.app.util.PrefsManager

/**
 * Modernized App Lock PIN & Recovery Dialog System for Focus Vault.
 * Features rounded pill input fields, clean security question selection dialogs,
 * biometric integration, and consistent Material card styling.
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
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val input = createPillEditText(context, "Enter your PIN (4-8 digits)", isPassword = true)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(input)
        }

        val lockoutRemaining = com.focusvault.app.manager.SecurityManager.getLockoutRemainingSeconds(context)
        val initialMsg = if (lockoutRemaining > 0) {
            "Too many incorrect attempts. Try again in $lockoutRemaining seconds."
        } else {
            "This action requires your master PIN to continue."
        }

        val dialog = showStyledDialog(
            context = context,
            title = "Enter Security PIN 🔒",
            message = initialMsg,
            customView = container,
            positiveBtnText = "Unlock",
            onPositive = { dlg, _ ->
                val result = com.focusvault.app.manager.SecurityManager.verifyPin(context, input.text.toString())
                if (result.isSuccess) {
                    dlg.dismiss()
                    onVerified()
                } else if (result.isLockedOut) {
                    input.text.clear()
                    Toast.makeText(context, "Locked for ${result.lockoutSeconds} seconds", Toast.LENGTH_SHORT).show()
                } else {
                    input.text.clear()
                    Toast.makeText(context, "Incorrect PIN. ${result.attemptsRemaining} attempts left", Toast.LENGTH_SHORT).show()
                }
            },
            negativeBtnText = "Cancel",
            neutralBtnText = "Forgot PIN?",
            onNeutral = {
                promptForgotPin(context) {
                    onVerified()
                }
            }
        )

        if (lockoutRemaining > 0) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        }
    }

    /** Full app-unlock gate shown when Stay Focused itself is opened/returned to and an App Lock PIN is set. */
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
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val input = createPillEditText(activity, "Enter your master PIN", isPassword = true)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(input)
        }

        val lockoutRemaining = com.focusvault.app.manager.SecurityManager.getLockoutRemainingSeconds(activity)
        val initialMsg = if (lockoutRemaining > 0) {
            "Too many incorrect attempts. Try again in $lockoutRemaining seconds."
        } else {
            "Enter your PIN to access Stay Focused."
        }

        val dialog = showStyledDialog(
            context = activity,
            title = "Stay Focused is Locked 🔐",
            message = initialMsg,
            customView = container,
            positiveBtnText = "Unlock",
            onPositive = { dlg, _ ->
                val result = com.focusvault.app.manager.SecurityManager.verifyPin(activity, input.text.toString())
                if (result.isSuccess) {
                    dlg.dismiss()
                    onUnlocked()
                } else if (result.isLockedOut) {
                    input.text.clear()
                    Toast.makeText(activity, "Locked for ${result.lockoutSeconds} seconds", Toast.LENGTH_SHORT).show()
                } else {
                    input.text.clear()
                    Toast.makeText(activity, "Incorrect PIN. ${result.attemptsRemaining} attempts left", Toast.LENGTH_SHORT).show()
                }
            },
            negativeBtnText = "Exit App",
            onNegative = { activity.finishAffinity() },
            neutralBtnText = "Forgot PIN?",
            onNeutral = {
                promptForgotPin(activity) {
                    onUnlocked()
                }
            },
            cancelable = false
        )

        if (lockoutRemaining > 0) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        }
    }

    /** Sets a PIN for the first time, or changes an existing one. */
    fun promptSetOrChangePin(context: Context, onDone: () -> Unit) {
        if (PrefsManager.hasLockPin(context)) {
            promptAndVerify(context) { promptNewPin(context, onDone) }
        } else {
            promptNewPin(context, onDone)
        }
    }

    /** Recovery question flow when a user forgets their PIN. */
    fun promptForgotPin(context: Context, onPinReset: () -> Unit) {
        if (!PrefsManager.hasSecurityAnswer(context)) {
            showStyledDialog(
                context = context,
                title = "Can't Recover PIN ⚠️",
                message = "No recovery question was set up for this PIN. You will need to uninstall and reinstall Stay Focused to clear it.",
                customView = null,
                positiveBtnText = "OK",
                onPositive = { dlg, _ -> dlg.dismiss() }
            )
            return
        }

        val questionIndex = PrefsManager.getSecurityQuestionIndex(context)
        val questionText = PrefsManager.SECURITY_QUESTIONS.getOrElse(questionIndex) {
            PrefsManager.SECURITY_QUESTIONS.first()
        }

        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val answerInput = createPillEditText(context, "Enter your recovery answer", isPassword = false)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * density).toInt(), pad, 0)
            addView(TextView(context).apply {
                text = "❓ $questionText"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
                setPadding(0, 0, 0, (12 * density).toInt())
            })
            addView(answerInput)
        }

        showStyledDialog(
            context = context,
            title = "Answer Security Question 🔑",
            message = "Verify your answer to reset your master PIN:",
            customView = container,
            positiveBtnText = "Verify",
            onPositive = { dlg, _ ->
                if (PrefsManager.verifySecurityAnswer(context, answerInput.text.toString())) {
                    dlg.dismiss()
                    promptNewPin(context, onPinReset)
                } else {
                    answerInput.text.clear()
                    Toast.makeText(context, "That answer didn't match. Try again.", Toast.LENGTH_SHORT).show()
                }
            },
            negativeBtnText = "Cancel"
        )
    }

    /** Prompts for a recovery question if none exists yet. */
    fun promptSecurityQuestionSetupIfNeeded(context: Context, onDone: () -> Unit) {
        if (PrefsManager.hasSecurityAnswer(context)) {
            onDone()
            return
        }

        var selectedQuestionIndex = 0
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val answerInput = createPillEditText(context, "Enter your answer", isPassword = false)
        val questionSelector = createQuestionSelector(context, PrefsManager.SECURITY_QUESTIONS, initialIndex = 0) { idx ->
            selectedQuestionIndex = idx
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * density).toInt(), pad, 0)
            addView(TextView(context).apply {
                text = "Pick a recovery question in case you ever forget your PIN:"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, 0, 0, (10 * density).toInt())
            })
            addView(questionSelector)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt()) })
            addView(answerInput)
        }

        showStyledDialog(
            context = context,
            title = "Set Up PIN Recovery 🛡️",
            message = "",
            customView = container,
            positiveBtnText = "Save",
            onPositive = { dlg, _ ->
                if (answerInput.text.isBlank()) {
                    Toast.makeText(context, "Please answer the recovery question", Toast.LENGTH_SHORT).show()
                } else {
                    PrefsManager.setSecurityAnswer(context, selectedQuestionIndex, answerInput.text.toString())
                    dlg.dismiss()
                    onDone()
                }
            },
            cancelable = false
        )
    }

    private fun promptNewPin(context: Context, onDone: () -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val gap = (10 * density).toInt()

        val newPin = createPillEditText(context, "New PIN (4-8 digits)", isPassword = true)
        val confirmPin = createPillEditText(context, "Confirm new PIN", isPassword = true)

        val needsSecuritySetup = !PrefsManager.hasSecurityAnswer(context)
        var selectedQuestionIndex = 0
        val answerInput = createPillEditText(context, "Answer to recovery question", isPassword = false)
        val questionSelector = createQuestionSelector(context, PrefsManager.SECURITY_QUESTIONS, initialIndex = 0) { idx ->
            selectedQuestionIndex = idx
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * density).toInt(), pad, 0)
            addView(newPin)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, gap) })
            addView(confirmPin)

            if (needsSecuritySetup) {
                addView(TextView(context).apply {
                    text = "Pick a recovery question in case you forget your PIN:"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, gap, 0, (8 * density).toInt())
                })
                addView(questionSelector)
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, gap) })
                addView(answerInput)
            }
        }

        showStyledDialog(
            context = context,
            title = if (PrefsManager.hasLockPin(context)) "Change Master PIN 🔐" else "Set Master PIN 🔒",
            message = "Create a 4-8 digit numeric PIN to protect focus settings and lock mode:",
            customView = container,
            positiveBtnText = "Save PIN",
            onPositive = { dlg, _ ->
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
                                selectedQuestionIndex,
                                answerInput.text.toString()
                            )
                        }
                        Toast.makeText(context, "Master PIN saved", Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                        onDone()
                    }
                }
            },
            negativeBtnText = "Cancel"
        )
    }

    private fun createPillEditText(context: Context, hintText: String, isPassword: Boolean = true): EditText {
        val density = context.resources.displayMetrics.density
        val hPad = (16 * density).toInt()
        val vPad = (12 * density).toInt()

        return EditText(context).apply {
            inputType = if (isPassword) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            hint = hintText
            textSize = 15f
            setPadding(hPad, vPad, hPad, vPad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_search_pill)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun createQuestionSelector(
        context: Context,
        questions: List<String>,
        initialIndex: Int = 0,
        onSelected: (Int) -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        val hPad = (16 * density).toInt()
        val vPad = (12 * density).toInt()
        var currentIndex = initialIndex.coerceIn(0, questions.size - 1)

        val label = TextView(context).apply {
            text = "❓ " + questions[currentIndex]
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
            setPadding(hPad, vPad, hPad, vPad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_search_pill)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }

        label.setOnClickListener {
            val items = questions.toTypedArray()
            AlertDialog.Builder(context)
                .setTitle("Select Recovery Question")
                .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                    currentIndex = which
                    label.text = "❓ " + questions[which]
                    onSelected(which)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        return label
    }

    private fun showStyledDialog(
        context: Context,
        title: String,
        message: String,
        customView: View?,
        positiveBtnText: String,
        onPositive: (AlertDialog, View?) -> Unit,
        negativeBtnText: String? = null,
        onNegative: (() -> Unit)? = null,
        neutralBtnText: String? = null,
        onNeutral: (() -> Unit)? = null,
        cancelable: Boolean = true
    ): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_alert, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvDialogMessage)
        val container = dialogView.findViewById<android.widget.FrameLayout>(R.id.containerCustomView)
        val btnPositive = dialogView.findViewById<android.widget.Button>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<android.widget.Button>(R.id.btnDialogNegative)

        tvTitle.text = title
        if (message.isNotBlank()) {
            tvMessage.text = message
            tvMessage.visibility = View.VISIBLE
        } else {
            tvMessage.visibility = View.GONE
        }

        if (customView != null) {
            container.addView(customView)
            container.visibility = View.VISIBLE
        } else {
            container.visibility = View.GONE
        }

        val builder = AlertDialog.Builder(context, R.style.Theme_StayFocused_Dialog)
            .setView(dialogView)
            .setCancelable(cancelable)

        val dialog = builder.create()

        btnPositive.text = positiveBtnText
        btnPositive.visibility = View.VISIBLE
        btnPositive.setOnClickListener {
            onPositive(dialog, customView)
        }

        if (negativeBtnText != null) {
            btnNegative.text = negativeBtnText
            btnNegative.visibility = View.VISIBLE
            btnNegative.setOnClickListener {
                dialog.dismiss()
                onNegative?.invoke()
            }
        }

        if (neutralBtnText != null) {
            val btnNeutral = android.widget.Button(context, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
                text = neutralBtnText
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
                setOnClickListener {
                    dialog.dismiss()
                    onNeutral?.invoke()
                }
            }
            val parentButtons = btnNegative.parent as? LinearLayout
            parentButtons?.addView(btnNeutral, 0)
        }

        dialog.show()
        return dialog
    }
}
