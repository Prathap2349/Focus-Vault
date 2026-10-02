package com.focusvault.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.focusvault.app.R
import com.focusvault.app.data.AppDatabase
import com.focusvault.app.databinding.SheetSessionCompleteBinding
import com.focusvault.app.util.AnimationHelper
import com.focusvault.app.util.FocusStatsManager
import com.focusvault.app.util.HapticHelper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class SessionCompleteSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSessionCompleteBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetSessionCompleteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val durationMinutes = arguments?.getInt(ARG_DURATION) ?: 0
        val distractions = arguments?.getInt(ARG_DISTRACTIONS) ?: 0

        binding.tvCompletedDuration.text = "$durationMinutes minutes"
        binding.tvCompletedDistractions.text = distractions.toString()
        
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val stats = FocusStatsManager.getDashboardStats(requireContext())
            binding.tvCompletedStreak.text = "${stats.streak}d"
        }

        AnimationHelper.animateCelebrationBloom(binding.ivSuccessIcon)

        binding.btnBackToHome.setOnClickListener {
            HapticHelper.mediumClick(it)
            dismiss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? MainActivity)?.refreshAfterCompletion()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SessionCompleteSheet"
        private const val ARG_DURATION = "arg_duration"
        private const val ARG_DISTRACTIONS = "arg_distractions"

        fun newInstance(durationMinutes: Int, distractions: Int): SessionCompleteSheet {
            val sheet = SessionCompleteSheet()
            val args = Bundle().apply {
                putInt(ARG_DURATION, durationMinutes)
                putInt(ARG_DISTRACTIONS, distractions)
            }
            sheet.arguments = args
            return sheet
        }
    }
}
