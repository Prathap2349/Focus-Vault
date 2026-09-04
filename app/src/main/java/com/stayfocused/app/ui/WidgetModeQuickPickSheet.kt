package com.stayfocused.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.stayfocused.app.data.SessionMode
import com.stayfocused.app.databinding.SheetWidgetModeQuickpickBinding

/**
 * The widget's "Start" button used to open [FocusModeSelectionSheet] - the same full card-with-
 * bullet-points picker used inside the app. On a widget, tapping Start already implies "I want
 * to focus right now"; showing the full Lite/Deep/Iron explainer again just adds a screen to
 * read before you can start. This sheet is the same 3-mode choice with just an icon + short
 * label per mode - no descriptions. Full mode details are one tap away via
 * "What do these mean?", which opens [FocusModeSelectionSheet] as a read/choose reference
 * instead of duplicating that copy here.
 */
class WidgetModeQuickPickSheet : BottomSheetDialogFragment() {

    private var _binding: SheetWidgetModeQuickpickBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "WidgetModeQuickPickSheet"
        const val REQUEST_KEY = "widget_quick_pick_result"
        const val RESULT_MODE = "chosen_mode"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetWidgetModeQuickpickBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.quickCardLite.setOnClickListener { choose(SessionMode.NORMAL) }
        binding.quickCardDeep.setOnClickListener { choose(SessionMode.LOCK) }
        binding.quickCardIron.setOnClickListener { choose(SessionMode.STRICT) }

        // Forward whatever gets chosen in the detailed sheet as our own result, so
        // WidgetQuickStartActivity only ever needs to listen for one REQUEST_KEY.
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
        setFragmentResult(REQUEST_KEY, Bundle().apply { putString(RESULT_MODE, mode.name) })
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
