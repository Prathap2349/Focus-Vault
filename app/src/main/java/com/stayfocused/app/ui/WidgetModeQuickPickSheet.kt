package com.stayfocused.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.stayfocused.app.R
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.databinding.SheetWidgetModeQuickpickBinding

class WidgetModeQuickPickSheet : DialogFragment() {

    private var _binding: SheetWidgetModeQuickpickBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "WidgetModeQuickPickSheet"
        const val REQUEST_KEY = "widget_quick_pick_result"
        const val RESULT_MODE = "chosen_mode"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"

        fun newInstance(durationMinutes: Int = 0): WidgetModeQuickPickSheet {
            return WidgetModeQuickPickSheet().apply {
                arguments = Bundle().apply { putInt(EXTRA_DURATION_MINUTES, durationMinutes) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_StayFocused_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetWidgetModeQuickpickBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val minutes = arguments?.getInt(EXTRA_DURATION_MINUTES, 0) ?: 0
        if (minutes > 0) {
            binding.tvDurationHeader.text = "🎯 $minutes MINUTE FOCUS TARGET"
        }

        binding.quickCardLite.setOnClickListener { choose(SessionMode.NORMAL) }
        binding.quickCardDeep.setOnClickListener { choose(SessionMode.LOCK) }
        binding.quickCardIron.setOnClickListener { choose(SessionMode.STRICT) }

        childFragmentManager.setFragmentResultListener(
            FocusModeSelectionSheet.REQUEST_KEY, this
        ) { _, result ->
            val modeName = result.getString(FocusModeSelectionSheet.RESULT_MODE)
            if (modeName != null) {
                setFragmentResult(REQUEST_KEY, Bundle().apply { putString(RESULT_MODE, modeName) })
                dismiss()
            }
        }

        binding.tvQuickPickModeDetails.setOnClickListener {
            FocusModeSelectionSheet().show(childFragmentManager, FocusModeSelectionSheet.TAG)
        }
    }

    private fun choose(mode: SessionMode) {
        com.stayfocused.app.util.HapticHelper.mediumClick(binding.root)
        setFragmentResult(REQUEST_KEY, Bundle().apply { putString(RESULT_MODE, mode.name) })
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
