package com.stayfocused.app.ui

import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.stayfocused.app.R
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.databinding.SheetQuickTimerSetupBinding

class QuickTimerSetupSheet : BottomSheetDialogFragment() {

    private var _binding: SheetQuickTimerSetupBinding? = null
    private val binding get() = _binding!!
    private lateinit var mode: SessionMode

    companion object {
        const val TAG = "QuickTimerSetupSheet"
        const val EXTRA_MODE = "mode"
        const val EXTRA_INITIAL_DURATION_MILLIS = "initial_duration_millis"
        const val REQUEST_KEY = "quick_timer_setup_result"
        const val RESULT_DURATION_MILLIS = "duration_millis"

        fun newInstance(mode: SessionMode, initialDurationMillis: Long = 0L): QuickTimerSetupSheet {
            return QuickTimerSetupSheet().apply {
                arguments = Bundle().apply { 
                    putString(EXTRA_MODE, mode.name)
                    putLong(EXTRA_INITIAL_DURATION_MILLIS, initialDurationMillis)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetQuickTimerSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        mode = SessionMode.valueOf(arguments?.getString(EXTRA_MODE) ?: SessionMode.NORMAL.name)
        
        binding.tvQuickModeLabel.text = when (mode) {
            SessionMode.STRICT -> "🔒 Strict Mode Duration"
            SessionMode.LOCK -> "🔐 Lock Mode Duration"
            SessionMode.NORMAL -> "🎯 Focus Mode Duration"
        }

        val initialDuration = arguments?.getLong(EXTRA_INITIAL_DURATION_MILLIS, 0L)?.takeIf { it > 0 }
            ?: com.stayfocused.app.util.PrefsManager.getLastChosenDurationMillis(requireContext())

        val initialTotalMinutes = (initialDuration / 60_000L).toInt().coerceAtLeast(1)
        val initialHours = (initialTotalMinutes / 60).coerceIn(0, 12)
        val initialMinutes = (initialTotalMinutes % 60).coerceIn(0, 59)

        binding.pickerQuickHours.minValue = 0
        binding.pickerQuickHours.maxValue = 12
        binding.pickerQuickHours.value = initialHours

        binding.pickerQuickMinutes.minValue = 0
        binding.pickerQuickMinutes.maxValue = 59
        binding.pickerQuickMinutes.value = initialMinutes

        val pickerTextColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
        binding.pickerQuickHours.setTextColorCompat(pickerTextColor)
        binding.pickerQuickMinutes.setTextColorCompat(pickerTextColor)

        fun updateSummary() {
            val h = binding.pickerQuickHours.value
            val m = binding.pickerQuickMinutes.value
            val totalMinutes = (h * 60) + m

            val heroText = when {
                totalMinutes == 0 -> "0 min"
                h == 0 -> "$m min"
                m == 0 -> "$h hr${if (h > 1) "s" else ""}"
                else -> "${h}h ${m}m"
            }
            binding.tvDurationHero.text = heroText

            val endTimeMillis = System.currentTimeMillis() + (totalMinutes * 60_000L)
            val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            val formattedEnd = timeFormat.format(java.util.Date(endTimeMillis))

            val summaryText = if (totalMinutes == 0) {
                "Please select at least 1 minute"
            } else {
                "Session ends at $formattedEnd"
            }
            binding.tvDurationSummary.text = summaryText

            // Animate scale bounce on hero text
            binding.tvDurationHero.animate()
                .scaleX(1.08f).scaleY(1.08f)
                .setDuration(80)
                .withEndAction {
                    binding.tvDurationHero.animate()
                        .scaleX(1.0f).scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }.start()
        }

        binding.pickerQuickHours.setOnValueChangedListener { _, _, _ -> updateSummary() }
        binding.pickerQuickMinutes.setOnValueChangedListener { _, _, _ -> updateSummary() }

        fun setDuration(hours: Int, minutes: Int) {
            binding.pickerQuickHours.value = hours.coerceIn(0, 12)
            binding.pickerQuickMinutes.value = minutes.coerceIn(0, 59)
            updateSummary()
            com.stayfocused.app.util.HapticHelper.lightClick(binding.root)
        }

        fun adjustDurationMinutes(deltaMinutes: Int) {
            val currentTotal = (binding.pickerQuickHours.value * 60) + binding.pickerQuickMinutes.value
            val newTotal = (currentTotal + deltaMinutes).coerceIn(1, 12 * 60 + 59)
            setDuration(newTotal / 60, newTotal % 60)
        }

        val chips = listOf(
            binding.chip15m,
            binding.chip25m,
            binding.chip45m,
            binding.chip60m,
            binding.chip90m,
            binding.chip120m,
            binding.chip180m
        )

        chips.forEach { chip ->
            com.stayfocused.app.util.AnimationHelper.attachSpringPressFeedback(chip)
        }

        val steppers = listOf(
            binding.btnStepMinus15,
            binding.btnStepMinus5,
            binding.btnStepPlus5,
            binding.btnStepPlus15,
            binding.btnStepPlus30
        )
        steppers.forEach { btn ->
            com.stayfocused.app.util.AnimationHelper.attachSpringPressFeedback(btn)
        }

        binding.btnStepMinus15.setOnClickListener { adjustDurationMinutes(-15) }
        binding.btnStepMinus5.setOnClickListener { adjustDurationMinutes(-5) }
        binding.btnStepPlus5.setOnClickListener { adjustDurationMinutes(5) }
        binding.btnStepPlus15.setOnClickListener { adjustDurationMinutes(15) }
        binding.btnStepPlus30.setOnClickListener { adjustDurationMinutes(30) }

        com.stayfocused.app.util.AnimationHelper.attachSpringPressFeedback(binding.btnQuickStartConfirm)

        // Staggered cascade animation for sheet content
        com.stayfocused.app.util.AnimationHelper.animateStaggeredCascade(
            listOf(
                binding.tvQuickModeLabel,
                binding.cardHeroDuration,
                binding.scrollChips,
                binding.layoutPickersContainer,
                binding.btnQuickStartConfirm
            ),
            baseDelayMs = 40,
            stepDelayMs = 50
        )

        binding.chip15m.setOnClickListener { setDuration(0, 15) }
        binding.chip25m.setOnClickListener { setDuration(0, 25) }
        binding.chip45m.setOnClickListener { setDuration(0, 45) }
        binding.chip60m.setOnClickListener { setDuration(1, 0) }
        binding.chip90m.setOnClickListener { setDuration(1, 30) }
        binding.chip120m.setOnClickListener { setDuration(2, 0) }
        binding.chip180m.setOnClickListener { setDuration(3, 0) }

        updateSummary()

        binding.btnQuickStartConfirm.setOnClickListener {
            val hours = binding.pickerQuickHours.value
            val minutes = binding.pickerQuickMinutes.value
            val totalMillis = (hours * 3600_000L) + (minutes * 60_000L)

            if (totalMillis <= 0) {
                Toast.makeText(context, "Pick a duration longer than 0 minutes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            com.stayfocused.app.util.PrefsManager.setLastChosenDurationMillis(requireContext(), totalMillis)
            com.stayfocused.app.util.HapticHelper.heavyClick(it)

            setFragmentResult(REQUEST_KEY, Bundle().apply { 
                putLong(RESULT_DURATION_MILLIS, totalMillis) 
            })
            dismiss()
        }
    }

    private fun NumberPicker.setTextColorCompat(color: Int) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                textColor = color
            }
            val paintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
            paintField.isAccessible = true
            (paintField.get(this) as? Paint)?.color = color
            invalidate()
        } catch (e: Exception) { }
        for (i in 0 until childCount) {
            (getChildAt(i) as? EditText)?.setTextColor(color)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
