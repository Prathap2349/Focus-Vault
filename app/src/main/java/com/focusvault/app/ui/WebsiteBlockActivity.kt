package com.focusvault.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.focusvault.app.R
import com.focusvault.app.adapter.SiteListAdapter
import com.focusvault.app.data.AppDatabase
import com.focusvault.app.data.BlockedSite
import com.focusvault.app.databinding.ActivityWebsiteBlockBinding
import com.focusvault.app.util.PrefsManager
import kotlinx.coroutines.launch

class WebsiteBlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebsiteBlockBinding
    private lateinit var adapter: SiteListAdapter
    private val allSites = mutableListOf<BlockedSite>()
    private var searchQuery = ""
    private var activeFilter = FILTER_ALL

    // Countdown ticker for the pause banner
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updatePauseBanner()
            if (PrefsManager.isPermanentBlockPaused(this@WebsiteBlockActivity)) {
                countdownHandler.postDelayed(this, 1000)
            }
        }
    }

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_PERMANENT = 1
        private const val FILTER_SESSION = 2
        private const val VPN_PERMISSION_REQUEST_CODE = 7301

        val PRESET_PACKS = mapOf(
            "Social Media" to listOf("instagram.com", "facebook.com", "x.com", "tiktok.com", "reddit.com", "snapchat.com", "threads.net"),
            "Video & Streaming" to listOf("youtube.com", "netflix.com", "twitch.tv", "primevideo.com", "disneyplus.com", "hulu.com"),
            "Gaming & Chat" to listOf("discord.com", "roblox.com", "steamcommunity.com", "epicgames.com"),
            "Shopping" to listOf("amazon.com", "ebay.com", "aliexpress.com", "temu.com", "shein.com"),
            "News & Gossip" to listOf("buzzfeed.com", "dailymail.co.uk", "tmz.com", "9gag.com")
        )

        /**
         * Robust domain normalizer:
         * - Delegates to DomainMatcher.normalizeBlockedDomain to strip protocol, user info,
         *   ports, paths, query params, fragments, wildcards, and leading www.
         * - Validates domain structure: returns empty string if domain is invalid.
         */
        fun normalizeDomain(input: String): String {
            val d = com.focusvault.app.service.DomainMatcher.normalizeBlockedDomain(input)
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
        
        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, target: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean = false
            override fun getSwipeDirs(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder): Int {
                if (viewHolder !is SiteListAdapter.SiteViewHolder) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }
            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                if (viewHolder is SiteListAdapter.SiteViewHolder) {
                    val pos = viewHolder.bindingAdapterPosition
                    val site = adapter.getSiteAt(pos)
                    if (site != null) {
                        PrefsManager.pauseIndividualSite(this@WebsiteBlockActivity, site.domain, 10 * 60 * 1000L) // 10 minutes
                        Toast.makeText(this@WebsiteBlockActivity, "${site.domain} paused for 10 mins", Toast.LENGTH_SHORT).show()
                        adapter.notifyItemChanged(pos)
                    }
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.recyclerSites)

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
        // Restart countdown ticker if we're returning to screen while paused
        if (PrefsManager.isPermanentBlockPaused(this)) {
            countdownHandler.removeCallbacks(countdownRunnable)
            countdownHandler.post(countdownRunnable)
        }
    }

    override fun onDestroy() {
        countdownHandler.removeCallbacks(countdownRunnable)
        super.onDestroy()
    }

    @Deprecated("Using deprecated onActivityResult for VPN permission compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                // User granted VPN permission — start the service
                startVpnService()
                Toast.makeText(this, "24/7 Blocking enabled 🛡️", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "VPN permission denied — 24/7 blocking requires this", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestVpnPermission() {
        val permIntent = VpnService.prepare(this)
        if (permIntent == null) {
            // Already authorized — start immediately
            startVpnService()
        } else {
            // Need to ask the user
            @Suppress("DEPRECATION")
            startActivityForResult(permIntent, VPN_PERMISSION_REQUEST_CODE)
        }
    }

    private fun startVpnService() {
        PrefsManager.setVpnManuallyStopped(this, false)
        try {
            startService(Intent(this, com.focusvault.app.service.FocusVpnService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("WebsiteBlockActivity", "Failed to start FocusVpnService", e)
        }
        // Give the service a moment to register before refreshing the card
        countdownHandler.postDelayed({ refreshVpnStatusCard() }, 600)
    }

    private fun refreshVpnStatusCard() {
        val isRunning = com.focusvault.app.manager.ProtectionEngine.isVpnRunning.get()
        val hasPermanentSites = PrefsManager.getPermanentBlockedDomains(this).isNotEmpty()
        val isPaused = PrefsManager.isPermanentBlockPaused(this)

        if (isRunning) {
            binding.tvVpnStatusText.text = "VPN Status: Active (Filtering DNS)"
            binding.tvVpnStatusText.setTextColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.success_green)
            )
            binding.btnDisconnectVpn.visibility = View.VISIBLE
            binding.btnEnableVpn.visibility = View.GONE
            // Show Pause button only when there are permanent sites and blocking isn't paused
            binding.btnPause247.visibility = if (hasPermanentSites && !isPaused) View.VISIBLE else View.GONE

            binding.btnDisconnectVpn.setOnClickListener {
                com.focusvault.app.util.HapticHelper.mediumClick(it)
                val stopIntent = Intent(this, com.focusvault.app.service.FocusVpnService::class.java).apply {
                    action = com.focusvault.app.service.FocusVpnService.ACTION_STOP
                }
                startService(stopIntent)
                stopService(Intent(this, com.focusvault.app.service.FocusVpnService::class.java))
                com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
                PrefsManager.setVpnManuallyStopped(this, true)
                Toast.makeText(this, "VPN disconnected — key icon hidden", Toast.LENGTH_SHORT).show()
                refreshVpnStatusCard()
            }

            binding.btnPause247.setOnClickListener {
                com.focusvault.app.util.HapticHelper.mediumClick(it)
                showPauseOptions()
            }
        } else {
            binding.tvVpnStatusText.text = "VPN Status: Disconnected"
            binding.tvVpnStatusText.setTextColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
            )
            binding.btnDisconnectVpn.visibility = View.GONE
            binding.btnPause247.visibility = View.GONE
            // Show "Enable Blocking" only if user has permanent sites to block
            binding.btnEnableVpn.visibility = if (hasPermanentSites) View.VISIBLE else View.GONE

            binding.btnEnableVpn.setOnClickListener {
                com.focusvault.app.util.HapticHelper.mediumClick(it)
                requestVpnPermission()
            }
        }

        // Pause banner
        updatePauseBanner()
    }

    private fun showPauseOptions() {
        val options = arrayOf("Pause for 30 minutes", "Pause for 1 hour", "Pause for 2 hours", "Pause for 3 hours")
        val durations = longArrayOf(30 * 60_000L, 60 * 60_000L, 2 * 60 * 60_000L, 3 * 60 * 60_000L)

        DialogHelper.showSingleChoiceDialog(
            context = this,
            title = "⏸ Pause 24/7 Blocking",
            items = options.toList(),
            selectedIndex = 0
        ) { which ->
            val pauseUntil = System.currentTimeMillis() + durations[which]
            PrefsManager.setPermanentBlockPause(this, pauseUntil)
            refreshVpnStatusCard()
            // Start countdown ticker
            countdownHandler.removeCallbacks(countdownRunnable)
            countdownHandler.post(countdownRunnable)
            Toast.makeText(this, "24/7 blocking paused — tap Resume Now to re-enable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePauseBanner() {
        val isPaused = PrefsManager.isPermanentBlockPaused(this)
        if (isPaused) {
            binding.layoutPauseBanner.visibility = View.VISIBLE
            binding.btnPause247.visibility = View.GONE

            val remainingMs = PrefsManager.getPermanentBlockPauseUntil(this) - System.currentTimeMillis()
            val totalSecs = (remainingMs / 1000).coerceAtLeast(0)
            val hours = totalSecs / 3600
            val mins = (totalSecs % 3600) / 60
            val secs = totalSecs % 60
            binding.tvPauseCountdown.text = if (hours > 0) {
                "Resumes in ${hours}h ${mins}m ${secs}s"
            } else {
                "Resumes in %02d:%02d".format(mins, secs)
            }

            binding.btnResumeNow.setOnClickListener {
                com.focusvault.app.util.HapticHelper.mediumClick(it)
                PrefsManager.clearPermanentBlockPause(this)
                countdownHandler.removeCallbacks(countdownRunnable)
                refreshVpnStatusCard()
                Toast.makeText(this, "24/7 blocking resumed ✅", Toast.LENGTH_SHORT).show()
            }
        } else {
            binding.layoutPauseBanner.visibility = View.GONE
            countdownHandler.removeCallbacks(countdownRunnable)
            // Re-show Pause button if VPN is still running
            if (com.focusvault.app.manager.ProtectionEngine.isVpnRunning.get() && allSites.any { it.isPermanent }) {
                binding.btnPause247.visibility = View.VISIBLE
            }
        }
    }

    private fun removeSite(site: BlockedSite, db: AppDatabase) {
        lifecycleScope.launch {
            allSites.removeAll { it.domain == site.domain }
            db.blockedSiteDao().upsert(site.copy(isActive = false))
            syncFastCache(db)
            applySearch()
            updateSiteCounter()
            refreshVpnStatusCard()
            Toast.makeText(this@WebsiteBlockActivity, "Unblocked ${site.domain}", Toast.LENGTH_SHORT).show()
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
                        startService(Intent(this@WebsiteBlockActivity, com.focusvault.app.service.FocusVpnService::class.java))
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

        // Whenever the user has 24/7 sites, always ensure the VPN is running —
        // critically: reset isVpnManuallyStopped so a prior manual disconnect
        // doesn't permanently prevent the service from starting on new additions.
        if (permanentDomains.isNotEmpty()) {
            PrefsManager.setVpnManuallyStopped(this, false)
            if (VpnService.prepare(this) == null) {
                // Permission already granted — start right away
                try {
                    startService(Intent(this, com.focusvault.app.service.FocusVpnService::class.java))
                } catch (e: Exception) {
                    android.util.Log.e("WebsiteBlockActivity", "Failed to start 24/7 FocusVpnService", e)
                }
            } else {
                // Need VPN permission — the Enable button in refreshVpnStatusCard() handles this
                android.util.Log.d("WebsiteBlockActivity", "VPN permission not yet granted; Enable button will prompt")
            }
        }
        refreshVpnStatusCard()
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
