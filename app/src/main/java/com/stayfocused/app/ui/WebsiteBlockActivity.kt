package com.stayfocused.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.stayfocused.app.R
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
    private var activeFilter = FILTER_ALL

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_PERMANENT = 1
        private const val FILTER_SESSION = 2

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
        binding.btnBack.setOnClickListener { finish() }

        val db = AppDatabase.getInstance(applicationContext)
        binding.recyclerSites.layoutManager = LinearLayoutManager(this)

        adapter = SiteListAdapter(mutableListOf()) { siteToRemove ->
            if (siteToRemove.isPermanent && PrefsManager.isPermanentVaultLockEnabled(this)) {
                LockPinDialog.promptAndVerify(this) {
                    removeSite(siteToRemove, db)
                }
            } else {
                removeSite(siteToRemove, db)
            }
        }
        binding.recyclerSites.adapter = adapter

        lifecycleScope.launch {
            val existing = db.blockedSiteDao().getActiveSitesOnce()
            allSites.clear()
            allSites.addAll(existing)
            syncFastCache(db)
            applySearch()
            updateSiteCounter()
            loadSuggestions(db)
        }

        // Vault Lock Guard toggle setup
        binding.switchVaultLock.isChecked = PrefsManager.isPermanentVaultLockEnabled(this)
        binding.switchVaultLock.setOnClickListener {
            val isChecked = binding.switchVaultLock.isChecked
            if (isChecked) {
                if (!PrefsManager.hasLockPin(this)) {
                    LockPinDialog.promptSetOrChangePin(this) {
                        PrefsManager.setPermanentVaultLockEnabled(this, true)
                        adapter.notifyDataSetChanged()
                    }
                    if (!PrefsManager.hasLockPin(this)) {
                        binding.switchVaultLock.isChecked = false
                    }
                } else {
                    PrefsManager.setPermanentVaultLockEnabled(this, true)
                    adapter.notifyDataSetChanged()
                }
            } else {
                if (PrefsManager.hasLockPin(this)) {
                    LockPinDialog.promptAndVerify(this) {
                        PrefsManager.setPermanentVaultLockEnabled(this, false)
                        adapter.notifyDataSetChanged()
                    }
                    if (PrefsManager.isPermanentVaultLockEnabled(this)) {
                        binding.switchVaultLock.isChecked = true
                    }
                } else {
                    PrefsManager.setPermanentVaultLockEnabled(this, false)
                    adapter.notifyDataSetChanged()
                }
            }
        }

        // Filter chips setup
        binding.chipGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            activeFilter = when (checkedId) {
                R.id.chipFilterPermanent -> FILTER_PERMANENT
                R.id.chipFilterSession -> FILTER_SESSION
                else -> FILTER_ALL
            }
            applySearch()
        }

        binding.etDomain.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.tvDomainError.visibility = View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnAddDomain.setOnClickListener {
            val raw = binding.etDomain.text.toString()
            val domain = normalizeDomain(raw)
            if (domain.isEmpty()) {
                binding.tvDomainError.text = "Please enter a valid domain (e.g. youtube.com or reddit.com)"
                binding.tvDomainError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (allSites.any { it.domain == domain }) {
                binding.tvDomainError.text = "$domain is already on your block list"
                binding.tvDomainError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val isPermanent = binding.switchBlockPermanent.isChecked
            binding.tvDomainError.visibility = View.GONE
            binding.etDomain.text.clear()

            val newSite = BlockedSite(domain = domain, isActive = true, isPermanent = isPermanent)
            allSites.add(0, newSite)

            lifecycleScope.launch {
                db.blockedSiteDao().upsert(newSite)
                syncFastCache(db)
                applySearch()
                updateSiteCounter()
                Toast.makeText(
                    this@WebsiteBlockActivity,
                    if (isPermanent) "Added $domain to 24/7 Permanent Block" else "Added $domain to Session Block",
                    Toast.LENGTH_SHORT
                ).show()
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

        refreshVpnStatusCard()
    }

    override fun onResume() {
        super.onResume()
        refreshVpnStatusCard()
    }

    private fun removeSite(site: BlockedSite, db: AppDatabase) {
        lifecycleScope.launch {
            allSites.removeAll { it.domain == site.domain }
            db.blockedSiteDao().upsert(site.copy(isActive = false))
            syncFastCache(db)
            applySearch()
            updateSiteCounter()
            Toast.makeText(this@WebsiteBlockActivity, "Unblocked ${site.domain}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshVpnStatusCard() {
        val isRunning = com.stayfocused.app.manager.ProtectionEngine.isVpnRunning.get()
        if (isRunning) {
            binding.tvVpnStatusText.text = "VPN Status: Active (Filtering DNS)"
            binding.tvVpnStatusText.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.success_green))
            binding.btnDisconnectVpn.visibility = View.VISIBLE
            binding.btnDisconnectVpn.setOnClickListener {
                com.stayfocused.app.util.HapticHelper.mediumClick(it)
                val stopIntent = Intent(this, com.stayfocused.app.service.FocusVpnService::class.java).apply {
                    action = com.stayfocused.app.service.FocusVpnService.ACTION_STOP
                }
                startService(stopIntent)
                stopService(Intent(this, com.stayfocused.app.service.FocusVpnService::class.java))
                com.stayfocused.app.manager.ProtectionEngine.isVpnRunning.set(false)
                PrefsManager.setVpnManuallyStopped(this, true)
                Toast.makeText(this, "VPN disconnected — key icon hidden", Toast.LENGTH_SHORT).show()
                refreshVpnStatusCard()
            }
        } else {
            binding.tvVpnStatusText.text = "VPN Status: Disconnected"
            binding.tvVpnStatusText.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnDisconnectVpn.visibility = View.GONE
        }
    }

    private fun showPresetPackPicker(db: AppDatabase) {
        val packNames = PRESET_PACKS.keys.toList()
        val isPermanent = binding.switchBlockPermanent.isChecked

        DialogHelper.showSingleChoiceDialog(
            context = this,
            title = "Add Preset Website Pack 📦",
            items = packNames.map { "🌐 $it (${PRESET_PACKS[it]?.size ?: 0} domains)" },
            selectedIndex = 0
        ) { which ->
            val chosenCategory = packNames[which]
            val domains = PRESET_PACKS[chosenCategory] ?: return@showSingleChoiceDialog
            var addedCount = 0

            lifecycleScope.launch {
                domains.forEach { rawDomain ->
                    val domain = normalizeDomain(rawDomain)
                    if (domain.isNotEmpty() && allSites.none { it.domain == domain }) {
                        val site = BlockedSite(domain = domain, isActive = true, isPermanent = isPermanent)
                        allSites.add(0, site)
                        db.blockedSiteDao().upsert(site)
                        addedCount++
                    }
                }
                applySearch()
                updateSiteCounter()
                Toast.makeText(this@WebsiteBlockActivity, "Added $addedCount domains from $chosenCategory", Toast.LENGTH_SHORT).show()
                if (isPermanent && addedCount > 0) {
                    try {
                        startService(Intent(this@WebsiteBlockActivity, com.stayfocused.app.service.FocusVpnService::class.java))
                    } catch (_: Exception) { }
                    refreshVpnStatusCard()
                }
            }
        }
    }

    private fun applySearch() {
        val filteredByTab = when (activeFilter) {
            FILTER_PERMANENT -> allSites.filter { it.isPermanent }
            FILTER_SESSION -> allSites.filter { !it.isPermanent }
            else -> allSites
        }

        val filtered = if (searchQuery.isEmpty()) {
            filteredByTab
        } else {
            filteredByTab.filter { it.domain.contains(searchQuery, ignoreCase = true) }
        }

        val sorted = filtered.sortedWith(
            compareByDescending<BlockedSite> { it.isPermanent }
                .thenBy { it.domain.lowercase() }
        )
        adapter.updateList(sorted)
        binding.tvEmptySites.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateSiteCounter() {
        val countAll = allSites.size
        val countPermanent = allSites.count { it.isPermanent }
        val countSession = allSites.count { !it.isPermanent }

        binding.tvSiteCount.text = "$countAll website${if (countAll != 1) "s" else ""} blocked"
        binding.chipFilterAll.text = "All ($countAll)"
        binding.chipFilterPermanent.text = "24/7 Permanent ($countPermanent)"
        binding.chipFilterSession.text = "Session Only ($countSession)"
    }

    private suspend fun syncFastCache(db: AppDatabase) {
        val activeDomains = db.blockedSiteDao().getActiveDomainsOnce().toSet()
        val permanentDomains = db.blockedSiteDao().getPermanentActiveDomainsOnce().toSet()
        PrefsManager.setBlockedDomains(this, activeDomains)
        PrefsManager.setPermanentBlockedDomains(this, permanentDomains)

        // If permanent blocked sites exist, automatically start VPN service so 24/7 filtering is active
        if (permanentDomains.isNotEmpty() && !PrefsManager.isVpnManuallyStopped(this)) {
            if (android.net.VpnService.prepare(this) == null) {
                try {
                    startService(Intent(this, com.stayfocused.app.service.FocusVpnService::class.java))
                } catch (e: Exception) {
                    android.util.Log.e("WebsiteBlockActivity", "Failed to start 24/7 FocusVpnService", e)
                }
            }
        }
    }

    private fun loadSuggestions(db: AppDatabase) {
        val suggestions = PrefsManager.getTopSuggestedDomains(this, 5)
        if (suggestions.isEmpty()) {
            binding.layoutSuggestions.visibility = View.GONE
            return
        }

        binding.layoutSuggestions.visibility = View.VISIBLE
        binding.chipGroupSuggestions.removeAllViews()

        val isPermanent = binding.switchBlockPermanent.isChecked
        suggestions.forEach { domain ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = "+ $domain"
                isCheckable = false
                setOnClickListener {
                    if (allSites.none { it.domain == domain }) {
                        val site = BlockedSite(domain = domain, isActive = true, isPermanent = isPermanent)
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
