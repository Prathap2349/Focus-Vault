package com.focusvault.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.focusvault.app.databinding.SheetEmergencyModeBinding

/**
 * The Emergency Mode System: Real Emergency (pick 5/10/15 min), Important Call (fixed 5 min),
 * Travel Mode (fixed 30 min). All three do the same thing underneath - temporarily pause
 * blocking via PrefsManager.setEmergencyPause, see MainActivity - because the app currently
 * blocks by a simple package/domain blocklist with no allowlist concept, so there's no way to
 * honestly claim "only Phone/Contacts/Messages stay allowed" the way the original spec
 * describes for Important Call, or "only Maps/Camera/Payments" for Travel Mode. A real
 * allowlist-aware blocking mode (letting specific categories through while everything else
 * stays blocked) would be a solid follow-up, but it's a different, bigger change to the
 * blocking engine itself, not a bottom-sheet change.
 */
class EmergencyModeSheet : BottomSheetDialogFragment() {

    private var _binding: SheetEmergencyModeBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "EmergencyModeSheet"
        const val REQUEST_KEY = "emergency_mode_result"
        const val RESULT_MINUTES = "chosen_minutes"
        const val RESULT_LABEL = "chosen_label"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetEmergencyModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnEmergency5.setOnClickListener { choose(5, "Real Emergency") }
        binding.btnEmergency10.setOnClickListener { choose(10, "Real Emergency") }
        binding.btnEmergency15.setOnClickListener { choose(15, "Real Emergency") }
        binding.cardImportantCall.setOnClickListener { choose(5, "Important Call") }
        binding.cardTravelMode.setOnClickListener { choose(30, "Travel Mode") }
    }

    private fun choose(minutes: Int, label: String) {
        setFragmentResult(REQUEST_KEY, Bundle().apply {
            putInt(RESULT_MINUTES, minutes)
            putString(RESULT_LABEL, label)
        })
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
