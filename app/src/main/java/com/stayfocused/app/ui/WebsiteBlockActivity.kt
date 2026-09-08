package com.stayfocused.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.stayfocused.app.adapter.SiteListAdapter
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.BlockedSite
import com.stayfocused.app.databinding.ActivityWebsiteBlockBinding
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.launch

class WebsiteBlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebsiteBlockBinding
    private lateinit var adapter: SiteListAdapter
    private val allSites = mutableListOf<BlockedSite>()
    private var searchQuery = ""

    companion object {
        val PRESET_PACKS = mapOf(
            "Social Media" to listOf("instagram.com", "facebook.com", "x.com", "tiktok.com", "reddit.com", "snapchat.com", "threads.net"),
            "Video & Streaming" to listOf("youtube.com", "netflix.com", "twitch.tv", "primevideo.com", "disneyplus.com", "hulu.com"),
            "Gaming & Chat" to listOf("discord.com", "roblox.com", "steamcommunity.com", "epicgames.com"),
            "Shopping" to listOf("amazon.com", "ebay.com", "aliexpress.com", "temu.com", "shein.com"),
            "News & Gossip" to listOf("buzzfeed.com", "dailymail.co.uk", "tmz.com", "9gag.com")
        )

        /**
         * Robust domain normalizer:
         * - Strips protocol, user info, ports, paths, query params, fragments, and leading www.
         * - Returns empty string if domain is invalid.
         */
        fun normalizeDomain(input: String): String {
            var d = input.trim().lowercase()
            if (d.startsWith("http://")) d = d.removePrefix("http://")
            if (d.startsWith("https://")) d = d.removePrefix("https://")
            if (d.contains("@")) d = d.substringAfterLast("@")
            if (d.contains("/")) d = d.substringBefore("/")
            if (d.contains("?")) d = d.substringBefore("?")
            if (d.contains("#")) d = d.substringBefore("#")
            if (d.contains(":")) d = d.substringBefore(":")
            d = d.removePrefix("www.")
            d = d.trim('.')

            // Validate domain structure (alphanumeric, hyphens, and at least one dot)
            val domainRegex = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
            return if (d.matches(domainRegex)) d else ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebsiteBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val db = AppDatabase.getInstance(applicationContext)
        binding.recyclerSites.layoutManager = LinearLayoutManager(this)

        adapter = SiteListAdapter(mutableListOf()) { removedSite ->
            lifecycleScope.launch {
                allSites.removeAll { it.domain == removedSite.domain }
                db.blockedSiteDao().upsert(removedSite.copy(isActive = false))
                syncFastCache(db)
                applySearch()
                updateSiteCounter()
            }
        }
        binding.recyclerSites.adapter = adapter

        lifecycleScope.launch {
            val existing = db.blockedSiteDao().getActiveDomainsOnce()
                .map { BlockedSite(it, true) }
            allSites.clear()
            allSites.addAll(existing)
            applySearch()
            updateSiteCounter()
            loadSuggestions(db)
        }

        binding.btnAddDomain.setOnClickListener {
            val raw = binding.etDomain.text.toString()
            val domain = normalizeDomain(raw)
            if (domain.isEmpty()) {
                Toast.makeText(this, "Please enter a valid domain (e.g. youtube.com)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (allSites.any { it.domain == domain }) {
                Toast.makeText(this, "$domain is already on your block list", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.etDomain.text.clear()
            val newSite = BlockedSite(domain, true)
            allSites.add(0, newSite)

            lifecycleScope.launch {
                db.blockedSiteDao().upsert(newSite)
                syncFastCache(db)
                applySearch()
                updateSiteCounter()
            }
        }

        binding.etSearchSites.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                applySearch()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnQuickAddCategory.setOnClickListener {
            showPresetPackPicker(db)
        }
    }

    private fun showPresetPackPicker(db: AppDatabase) {
        val packNames = PRESET_PACKS.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add Preset Website Pack")
            .setItems(packNames) { _, which ->
                val chosenCategory = packNames[which]
                val domains = PRESET_PACKS[chosenCategory] ?: return@setItems
                var addedCount = 0

                lifecycleScope.launch {
                    domains.forEach { rawDomain ->
                        val domain = normalizeDomain(rawDomain)
                        if (domain.isNotEmpty() && allSites.none { it.domain == domain }) {
                            val site = BlockedSite(domain, true)
                            allSites.add(0, site)
                            db.blockedSiteDao().upsert(site)
                            addedCount++
                        }
                    }
                    syncFastCache(db)
                    applySearch()
                    updateSiteCounter()
                    Toast.makeText(this@WebsiteBlockActivity, "Added $addedCount sites from $chosenCategory", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applySearch() {
        val filtered = if (searchQuery.isEmpty()) {
            allSites
        } else {
            allSites.filter { it.domain.contains(searchQuery, ignoreCase = true) }
        }
        adapter.updateList(filtered)
        binding.tvEmptySites.visibility = if (allSites.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateSiteCounter() {
        binding.tvSiteCount.text = "${allSites.size} websites blocked"
    }

    private suspend fun syncFastCache(db: AppDatabase) {
        val active = db.blockedSiteDao().getActiveDomainsOnce().toSet()
        PrefsManager.setBlockedDomains(this, active)
    }

    private fun loadSuggestions(db: AppDatabase) {
        val suggestions = PrefsManager.getTopSuggestedDomains(this, 5)
        if (suggestions.isEmpty()) {
            binding.layoutSuggestions.visibility = View.GONE
            return
        }

        binding.layoutSuggestions.visibility = View.VISIBLE
        binding.chipGroupSuggestions.removeAllViews()

        suggestions.forEach { domain ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = "+ $domain"
                isCheckable = false
                setOnClickListener {
                    if (allSites.none { it.domain == domain }) {
                        val site = BlockedSite(domain, true)
                        allSites.add(0, site)
                        lifecycleScope.launch {
                            db.blockedSiteDao().upsert(site)
                            syncFastCache(db)
                            applySearch()
                            updateSiteCounter()
                            loadSuggestions(db)
                        }
                    }
                }
            }
            binding.chipGroupSuggestions.addView(chip)
        }
    }
}
