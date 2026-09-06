package com.stayfocused.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.databinding.SheetFocusModeSelectionBinding

/**
 * "Start Focus" no longer jumps straight into a timer - it opens this sheet first, so the
 * choice of how much friction you want (Lite/Deep/Iron) is explicit every time, not just a
 * side effect of which of three small buttons you happened to tap. The card descriptions
 * describe exactly what this app's Normal/Lock/Strict modes actually do today (see
 * MainActivity.guardSettingsAccess and SessionTimerService) - not the richer per-mode
 * emergency-timer mechanics from the original spec (hold-to-exit, a reason field, a 5/15-minute
 * cap), which don't exist yet and would need a real Emergency Mode System phase to back them.
 */
class FocusModeSelectionSheet : BottomSheetDialogFragment() {

    private var _binding: SheetFocusModeSelectionBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "FocusModeSelectionSheet"
        const val REQUEST_KEY = "focus_mode_selection_result"
        const val RESULT_MODE = "chosen_mode"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetFocusModeSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val modeCards = listOf(
            binding.cardModeLite,
            binding.cardModeDeep,
            binding.cardModeIron
        )

        // Spring physics on touch
        modeCards.forEach { card ->
            com.stayfocused.app.util.AnimationHelper.attachSpringPressFeedback(card)
        }

        // Staggered cascade entrance
        com.stayfocused.app.util.AnimationHelper.animateStaggeredCascade(
            views = modeCards,
            baseDelayMs = 40,
            stepDelayMs = 60
        )

        binding.cardModeLite.setOnClickListener { 
            com.stayfocused.app.util.HapticHelper.mediumClick(it)
            choose(SessionMode.NORMAL) 
        }
        binding.cardModeDeep.setOnClickListener { 
            com.stayfocused.app.util.HapticHelper.mediumClick(it)
            choose(SessionMode.LOCK) 
        }
        binding.cardModeIron.setOnClickListener { 
            com.stayfocused.app.util.HapticHelper.heavyClick(it)
            choose(SessionMode.STRICT) 
        }
    }

    private fun choose(mode: SessionMode) {
        setFragmentResult(REQUEST_KEY, Bundle().apply { putString(RESULT_MODE, mode.name) })
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
