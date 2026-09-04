package com.stayfocused.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.stayfocused.app.adapter.AppListAdapter
import com.stayfocused.app.data.AppDatabase
import com.stayfocused.app.data.BlockedApp
import com.stayfocused.app.databinding.ActivityAppSelectionBinding
import com.stayfocused.app.util.AppCategory
import com.stayfocused.app.util.AppUtils
import com.stayfocused.app.util.InstalledAppInfo
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private lateinit var adapter: AppListAdapter

    private var allApps: List<InstalledAppInfo> = emptyList()
    private val selectedPackages = mutableSetOf<String>()
    private var currentCategory: AppCategory = AppCategory.ALL
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerApps.layoutManager = LinearLayoutManager(this)

        adapter = AppListAdapter(emptyList(), selectedPackages) { app, isSelected ->
            lifecycleScope.launch {
                val db = AppDatabase.getInstance(applicationContext)
                db.blockedAppDao().upsert(BlockedApp(app.packageName, app.label, isSelected))
                syncFastCache(db)
                updateCounter()
            }
        }
        binding.recyclerApps.adapter = adapter

        setupSearch()
        setupCategories()
        setupQuickActions()
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val alreadyBlocked = db.blockedAppDao().getAllOnce()
                .filter { it.isActive }
                .map { it.packageName }
                .toSet()

            selectedPackages.clear()
            selectedPackages.addAll(alreadyBlocked)

            allApps = withContext(Dispatchers.IO) {
                AppUtils.getLaunchableApps(this@AppSelectionActivity)
            }

            applyFilters()
            updateCounter()
        }
    }

    private fun setupSearch() {
        binding.etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupCategories() {
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCategory = when (checkedIds.firstOrNull()) {
                binding.chipSocial.id -> AppCategory.SOCIAL
                binding.chipGames.id -> AppCategory.GAMES
                binding.chipEntertainment.id -> AppCategory.ENTERTAINMENT
                binding.chipShopping.id -> AppCategory.SHOPPING
                binding.chipProductivity.id -> AppCategory.PRODUCTIVITY
                else -> AppCategory.ALL
            }
            applyFilters()
        }
    }

    private fun setupQuickActions() {
        binding.btnQuickSelectCategory.setOnClickListener {
            val appsToSelect = getFilteredList()
            lifecycleScope.launch {
                val db = AppDatabase.getInstance(applicationContext)
                appsToSelect.forEach { app ->
                    selectedPackages.add(app.packageName)
                    db.blockedAppDao().upsert(BlockedApp(app.packageName, app.label, true))
                }
                syncFastCache(db)
                adapter.notifyDataSetChanged()
                updateCounter()
            }
        }

        binding.btnClearAll.setOnClickListener {
            lifecycleScope.launch {
                val db = AppDatabase.getInstance(applicationContext)
                val appsToClear = if (currentCategory == AppCategory.ALL) allApps else getFilteredList()
                appsToClear.forEach { app ->
                    selectedPackages.remove(app.packageName)
                    db.blockedAppDao().upsert(BlockedApp(app.packageName, app.label, false))
                }
                syncFastCache(db)
                adapter.notifyDataSetChanged()
                updateCounter()
            }
        }
    }

    private fun getFilteredList(): List<InstalledAppInfo> {
        return allApps.filter { app ->
            val matchesCategory = (currentCategory == AppCategory.ALL) || (app.category == currentCategory)
            val matchesSearch = searchQuery.isEmpty() || app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    private fun applyFilters() {
        val filtered = getFilteredList()
        adapter.updateList(filtered)
        binding.tvEmptyApps.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateCounter() {
        binding.tvSelectedCount.text = "${selectedPackages.size} apps selected"
    }

    private suspend fun syncFastCache(db: AppDatabase) {
        val active = db.blockedAppDao().getActivePackageNamesOnce().toSet()
        PrefsManager.setBlockedPackages(this, active)
    }
}
