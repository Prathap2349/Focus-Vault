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
        const val REQUEST_KEY = "quick_timer_setup_result"
        const val RESULT_DURATION_MILLIS = "duration_millis"

        fun newInstance(mode: SessionMode): QuickTimerSetupSheet {
            return QuickTimerSetupSheet().apply {
                arguments = Bundle().apply { putString(EXTRA_MODE, mode.name) }
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
            SessionMode.STRICT -> "🔒 Strict Mode"
            SessionMode.LOCK -> "🔐 Lock Mode"
            SessionMode.NORMAL -> "🎯 Focus Mode"
        }

        binding.pickerQuickHours.minValue = 0
        binding.pickerQuickHours.maxValue = 12
        binding.pickerQuickHours.value = 0

        binding.pickerQuickMinutes.minValue = 0
        binding.pickerQuickMinutes.maxValue = 59
        binding.pickerQuickMinutes.value = 25

        val pickerTextColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
        binding.pickerQuickHours.setTextColorCompat(pickerTextColor)
        binding.pickerQuickMinutes.setTextColorCompat(pickerTextColor)

        binding.btnQuickStartConfirm.setOnClickListener {
            val hours = binding.pickerQuickHours.value
            val minutes = binding.pickerQuickMinutes.value
            val totalMillis = (hours * 3600_000L) + (minutes * 60_000L)

            if (totalMillis <= 0) {
                Toast.makeText(context, "Pick a duration longer than 0 minutes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setFragmentResult(REQUEST_KEY, Bundle().apply { 
                putLong(RESULT_DURATION_MILLIS, totalMillis) 
            })
            dismiss()
        }
    }

    private fun NumberPicker.setTextColorCompat(color: Int) {
        try {
            val paintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
            paintField.isAccessible = true
            (paintField.get(this) as Paint).color = color
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
