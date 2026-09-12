package com.focusvault.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.focusvault.app.R
import com.focusvault.app.adapter.AppListAdapter
import com.focusvault.app.data.AppDatabase
import com.focusvault.app.data.BlockedApp
import com.focusvault.app.databinding.ActivityAppSelectionBinding
import com.focusvault.app.databinding.DialogCustomAlertBinding
import com.focusvault.app.util.AppCategory
import com.focusvault.app.util.AppUtils
import com.focusvault.app.util.HapticHelper
import com.focusvault.app.util.InstalledAppInfo
import com.focusvault.app.util.PrefsManager
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
                applyFilters()
                updateCounter()
                updateCategoryChipCounts()
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
            updateCategoryChipCounts()
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
            HapticHelper.mediumClick(it)
            val appsToSelect = getFilteredList()
            lifecycleScope.launch {
                val db = AppDatabase.getInstance(applicationContext)
                appsToSelect.forEach { app ->
                    selectedPackages.add(app.packageName)
                    db.blockedAppDao().upsert(BlockedApp(app.packageName, app.label, true))
                }
                syncFastCache(db)
                applyFilters()
                updateCounter()
                updateCategoryChipCounts()
            }
        }

        binding.btnClearAll.setOnClickListener {
            HapticHelper.mediumClick(it)
            if (selectedPackages.isEmpty()) return@setOnClickListener
            showClearConfirmationDialog()
        }
    }

    private fun showClearConfirmationDialog() {
        val dialogBinding = DialogCustomAlertBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = "Clear Blocked Apps?"
        dialogBinding.tvDialogMessage.text = "This will unblock all ${selectedPackages.size} selected app(s) in this list."

        val dialog = AlertDialog.Builder(this, R.style.Theme_StayFocused_Dialog)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnDialogPositive.visibility = View.VISIBLE
        dialogBinding.btnDialogPositive.text = "Clear All"
        dialogBinding.btnDialogPositive.setOnClickListener {
            dialog.dismiss()
            executeClearAll()
        }

        dialogBinding.btnDialogNegative.visibility = View.VISIBLE
        dialogBinding.btnDialogNegative.text = "Cancel"
        dialogBinding.btnDialogNegative.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun executeClearAll() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val appsToClear = if (currentCategory == AppCategory.ALL) allApps else getFilteredList()
            appsToClear.forEach { app ->
                selectedPackages.remove(app.packageName)
                db.blockedAppDao().upsert(BlockedApp(app.packageName, app.label, false))
            }
            syncFastCache(db)
            applyFilters()
            updateCounter()
            updateCategoryChipCounts()
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
        val sorted = filtered.sortedWith(
            compareByDescending<InstalledAppInfo> { selectedPackages.contains(it.packageName) }
                .thenBy { it.label.lowercase() }
        )
        adapter.updateList(sorted)
        binding.layoutEmptyState.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateCounter() {
        val count = selectedPackages.size
        binding.tvSelectedCount.text = "$count app${if (count != 1) "s" else ""} blocked"
    }

    private fun updateCategoryChipCounts() {
        val socialCount = allApps.count { it.category == AppCategory.SOCIAL && selectedPackages.contains(it.packageName) }
        val gamesCount = allApps.count { it.category == AppCategory.GAMES && selectedPackages.contains(it.packageName) }
        val entCount = allApps.count { it.category == AppCategory.ENTERTAINMENT && selectedPackages.contains(it.packageName) }
        val shopCount = allApps.count { it.category == AppCategory.SHOPPING && selectedPackages.contains(it.packageName) }
        val prodCount = allApps.count { it.category == AppCategory.PRODUCTIVITY && selectedPackages.contains(it.packageName) }

        binding.chipAll.text = "📱 All (${selectedPackages.size})"
        binding.chipSocial.text = if (socialCount > 0) "💬 Social ($socialCount)" else "💬 Social"
        binding.chipGames.text = if (gamesCount > 0) "🎮 Games ($gamesCount)" else "🎮 Games"
        binding.chipEntertainment.text = if (entCount > 0) "🍿 Entertainment ($entCount)" else "🍿 Entertainment"
        binding.chipShopping.text = if (shopCount > 0) "🛍️ Shopping ($shopCount)" else "🛍️ Shopping"
        binding.chipProductivity.text = if (prodCount > 0) "💼 Productivity ($prodCount)" else "💼 Productivity"
    }

    private suspend fun syncFastCache(db: AppDatabase) {
        val active = db.blockedAppDao().getActivePackageNamesOnce().toSet()
        PrefsManager.setBlockedPackages(this, active)
    }
}
