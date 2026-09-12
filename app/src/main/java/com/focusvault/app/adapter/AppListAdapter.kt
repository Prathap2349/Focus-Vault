package com.focusvault.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.focusvault.app.R
import com.focusvault.app.databinding.ItemAppBinding
import com.focusvault.app.util.HapticHelper
import com.focusvault.app.util.InstalledAppInfo

class AppListAdapter(
    private var displayedApps: List<InstalledAppInfo>,
    private val selectedPackages: MutableSet<String>,
    private val onToggle: (InstalledAppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = displayedApps[position]

        holder.binding.tvAppLabel.text = app.label
        val catName = app.category.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        holder.binding.tvAppCategory.text = "$catName • ${app.packageName}"
        holder.binding.ivAppIcon.setImageDrawable(app.icon)

        val isChecked = selectedPackages.contains(app.packageName)
        holder.binding.switchSelected.isChecked = isChecked
        updateRowState(holder.binding, isChecked)

        holder.binding.root.setOnClickListener {
            val newState = !selectedPackages.contains(app.packageName)
            if (newState) {
                selectedPackages.add(app.packageName)
            } else {
                selectedPackages.remove(app.packageName)
            }
            HapticHelper.lightClick(it)
            holder.binding.switchSelected.isChecked = newState
            updateRowState(holder.binding, newState)
            onToggle(app, newState)
        }
    }

    private fun updateRowState(binding: ItemAppBinding, isSelected: Boolean) {
        binding.viewAccentBar.visibility = if (isSelected) View.VISIBLE else View.GONE
        if (isSelected) {
            binding.layoutAppRow.setBackgroundResource(R.drawable.bg_item_app_selected)
        } else {
            val typedValue = android.util.TypedValue()
            binding.root.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            binding.layoutAppRow.setBackgroundResource(typedValue.resourceId)
        }
    }

    override fun getItemCount() = displayedApps.size

    fun updateList(newList: List<InstalledAppInfo>) {
        displayedApps = newList
        notifyDataSetChanged()
    }
}
