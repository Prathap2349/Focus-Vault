package com.stayfocused.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.stayfocused.app.databinding.ItemAppBinding
import com.stayfocused.app.util.InstalledAppInfo

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
        holder.binding.ivAppIcon.setImageDrawable(app.icon)
        val isChecked = selectedPackages.contains(app.packageName)
        holder.binding.cbSelected.isChecked = isChecked

        holder.binding.root.setOnClickListener {
            val newState = !selectedPackages.contains(app.packageName)
            if (newState) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
            holder.binding.cbSelected.isChecked = newState
            onToggle(app, newState)
        }
    }

    override fun getItemCount() = displayedApps.size

    fun updateList(newList: List<InstalledAppInfo>) {
        displayedApps = newList
        notifyDataSetChanged()
    }
}
