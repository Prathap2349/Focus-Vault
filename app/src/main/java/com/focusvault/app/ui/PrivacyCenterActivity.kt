package com.focusvault.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.focusvault.app.databinding.ActivityPrivacyCenterBinding

class PrivacyCenterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyCenterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}
