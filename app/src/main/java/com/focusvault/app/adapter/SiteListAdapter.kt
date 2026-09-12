package com.focusvault.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.focusvault.app.data.BlockedSite
import com.focusvault.app.databinding.ItemSiteBinding
import com.focusvault.app.ui.WebsiteBlockActivity

class SiteListAdapter(
    private var items: MutableList<Any>,
    private val onRemove: (BlockedSite) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SITE = 1
    }

    inner class SiteViewHolder(val binding: ItemSiteBinding) : RecyclerView.ViewHolder(binding.root)

    inner class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is String) TYPE_HEADER else TYPE_SITE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(32, 32, 32, 16)
                }
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.GRAY)
            }
            return HeaderViewHolder(tv)
        }
        val binding = ItemSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SiteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.textView.text = items[position] as String
        } else if (holder is SiteViewHolder) {
            val site = items[position] as BlockedSite
            holder.binding.tvDomain.text = site.domain
            
            if (site.isPermanent) {
                holder.binding.tvPermanentBadge.visibility = android.view.View.VISIBLE
                holder.binding.tvSiteSubtitle.text = "24/7 Round-the-Clock DNS Block"
            } else {
                holder.binding.tvPermanentBadge.visibility = android.view.View.GONE
                holder.binding.tvSiteSubtitle.text = "Session Local DNS Blocked"
            }

            val isVaultLock = site.isPermanent && com.focusvault.app.util.PrefsManager.isPermanentVaultLockEnabled(holder.itemView.context)
            holder.binding.ivLock.visibility = if (isVaultLock) android.view.View.VISIBLE else android.view.View.GONE

            holder.binding.btnRemove.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION && index < items.size) {
                    onRemove(items[index] as BlockedSite)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    fun getSiteAt(position: Int): BlockedSite? {
        val item = items.getOrNull(position)
        return if (item is BlockedSite) item else null
    }

    fun updateList(newList: List<BlockedSite>) {
        val grouped = mutableMapOf<String, MutableList<BlockedSite>>()
        val presets = WebsiteBlockActivity.PRESET_PACKS
        
        newList.forEach { site ->
            var foundCategory = "Uncategorized"
            for ((category, domains) in presets) {
                if (domains.contains(site.domain.lowercase())) {
                    foundCategory = category
                    break
                }
            }
            grouped.getOrPut(foundCategory) { mutableListOf() }.add(site)
        }

        val newItems = mutableListOf<Any>()
        val categories = grouped.keys.sortedWith(compareBy { if (it == "Uncategorized") 1 else 0 })
        
        for (category in categories) {
            newItems.add(category)
            newItems.addAll(grouped[category]!!.sortedBy { it.domain })
        }
        
        items = newItems
        notifyDataSetChanged()
    }
}
