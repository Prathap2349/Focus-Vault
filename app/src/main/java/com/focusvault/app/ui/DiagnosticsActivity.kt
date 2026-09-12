package com.focusvault.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.focusvault.app.R
import com.focusvault.app.databinding.ActivityDiagnosticsBinding
import com.focusvault.app.databinding.ItemDiagnosticBinding
import com.focusvault.app.manager.ProtectionEngine
import com.focusvault.app.manager.ProtectionStatus

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.focusvault.app.util.EdgeToEdge.apply(this, binding.root, useDarkIcons = !com.focusvault.app.util.EdgeToEdge.isNightModeActive(this))

        binding.btnBack.setOnClickListener { finish() }

        binding.btnRunFullTest.setOnClickListener {
            com.focusvault.app.util.HapticHelper.mediumClick(it)
            runDiagnostics(showToast = true)
        }
    }

    override fun onResume() {
        super.onResume()
        runDiagnostics(showToast = false)
    }

    private fun runDiagnostics(showToast: Boolean) {
        val report = ProtectionEngine.evaluate(this)

        binding.tvHealthScore.text = "${report.scorePercentage}% HEALTHY"
        binding.tvHealthSummary.text = report.headlineMessage

        when (report.status) {
            ProtectionStatus.PROTECTION_ACTIVE -> {
                binding.cardOverallStatus.setBackgroundResource(R.drawable.bg_gradient_normal)
            }
            ProtectionStatus.PROTECTION_DEGRADED -> {
                binding.cardOverallStatus.setBackgroundResource(R.drawable.bg_gradient_paused)
            }
            ProtectionStatus.PROTECTION_FAILED -> {
                binding.cardOverallStatus.setBackgroundResource(R.drawable.bg_gradient_strict)
            }
        }

        binding.containerHealthItems.removeAllViews()
        val inflater = LayoutInflater.from(this)

        report.items.forEach { item ->
            val row = ItemDiagnosticBinding.inflate(inflater, binding.containerHealthItems, false)
            row.tvDiagnosticTitle.text = item.title
            row.tvDiagnosticSubtitle.text = item.subtitle
            row.tvDiagnosticIcon.text = if (item.isHealthy) "🟢" else "🔴"

            if (!item.isHealthy && item.fixIntent != null) {
                row.btnDiagnosticFix.visibility = View.VISIBLE
                row.btnDiagnosticFix.text = item.fixActionTitle
                row.btnDiagnosticFix.setOnClickListener {
                    try {
                        startActivity(item.fixIntent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not open system settings automatically", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                row.btnDiagnosticFix.visibility = View.GONE
            }

            binding.containerHealthItems.addView(row.root)
        }

        if (showToast) {
            Toast.makeText(this, "Diagnostic test completed: ${report.scorePercentage}% healthy", Toast.LENGTH_SHORT).show()
        }
    }
}
