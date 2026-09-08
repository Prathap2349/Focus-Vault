package com.stayfocused.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.stayfocused.app.R
import com.stayfocused.app.databinding.DialogCustomAlertBinding
import com.stayfocused.app.util.AnimationHelper
import com.stayfocused.app.util.HapticHelper

object DialogHelper {

    fun showCustomDialog(
        context: Context,
        title: String,
        message: String? = null,
        customView: View? = null,
        positiveText: String? = "OK",
        positiveAction: (() -> Unit)? = null,
        negativeText: String? = "Cancel",
        negativeAction: (() -> Unit)? = null
    ): AlertDialog {
        val inflater = LayoutInflater.from(context)
        val binding = DialogCustomAlertBinding.inflate(inflater)

        binding.tvDialogTitle.text = title

        if (!message.isNullOrBlank()) {
            binding.tvDialogMessage.visibility = View.VISIBLE
            binding.tvDialogMessage.text = message
        } else {
            binding.tvDialogMessage.visibility = View.GONE
        }

        if (customView != null) {
            binding.containerCustomView.visibility = View.VISIBLE
            binding.containerCustomView.removeAllViews()
            binding.containerCustomView.addView(customView)
        } else {
            binding.containerCustomView.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_StayFocused_Dialog)
            .setView(binding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (positiveText != null) {
            binding.btnDialogPositive.visibility = View.VISIBLE
            binding.btnDialogPositive.text = positiveText
            AnimationHelper.attachSpringPressFeedback(binding.btnDialogPositive)
            binding.btnDialogPositive.setOnClickListener {
                HapticHelper.mediumClick(it)
                dialog.dismiss()
                positiveAction?.invoke()
            }
        } else {
            binding.btnDialogPositive.visibility = View.GONE
        }

        if (negativeText != null) {
            binding.btnDialogNegative.visibility = View.VISIBLE
            binding.btnDialogNegative.text = negativeText
            AnimationHelper.attachSpringPressFeedback(binding.btnDialogNegative)
            binding.btnDialogNegative.setOnClickListener {
                HapticHelper.lightClick(it)
                dialog.dismiss()
                negativeAction?.invoke()
            }
        } else {
            binding.btnDialogNegative.visibility = View.GONE
        }

        dialog.show()
        return dialog
    }

    fun createPillEditText(
        context: Context,
        hint: String,
        initialText: String = "",
        inputType: Int = android.text.InputType.TYPE_CLASS_TEXT
    ): EditText {
        val density = context.resources.displayMetrics.density
        return EditText(context).apply {
            this.hint = hint
            if (initialText.isNotEmpty()) {
                setText(initialText)
                setSelection(initialText.length)
            }
            this.inputType = inputType
            setBackgroundResource(R.drawable.bg_search_pill)
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun showSingleChoiceDialog(
        context: Context,
        title: String,
        items: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ): AlertDialog {
        val density = context.resources.displayMetrics.density
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        }

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }

        val brandColor = ContextCompat.getColor(context, R.color.brand_primary)
        val textColor = ContextCompat.getColor(context, R.color.text_primary)

        items.forEachIndexed { index, item ->
            val rb = RadioButton(context).apply {
                id = View.generateViewId()
                text = item
                textSize = 14f
                setTextColor(textColor)
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                buttonTintList = ColorStateList.valueOf(brandColor)
                isChecked = index == selectedIndex
            }
            radioGroup.addView(rb)
        }

        layout.addView(radioGroup)

        var chosen = selectedIndex
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val idx = radioGroup.indexOfChild(radioGroup.findViewById(checkedId))
            if (idx >= 0) chosen = idx
        }

        return showCustomDialog(
            context = context,
            title = title,
            customView = layout,
            positiveText = "Apply",
            positiveAction = { onSelected(chosen) },
            negativeText = "Cancel"
        )
    }
}
