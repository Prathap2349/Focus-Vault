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
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(48, 28, 48, 12)
                }
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(androidx.core.content.ContextCompat.getColor(parent.context, com.focusvault.app.R.color.text_secondary))
                letterSpacing = 0.08f
            }
            return HeaderViewHolder(tv)
        }
        val binding = ItemSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SiteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.textView.text = (items[position] as String).uppercase()
        } else if (holder is SiteViewHolder) {
            val site = items[position] as BlockedSite
            holder.binding.tvDomain.text = site.domain
            val isPaused = com.focusvault.app.util.PrefsManager.isIndividualSitePaused(holder.itemView.context, site.domain)
            
            if (isPaused) {
                holder.binding.tvPermanentBadge.visibility = android.view.View.VISIBLE
                holder.binding.tvPermanentBadge.text = "PAUSED"
                holder.binding.tvPermanentBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(holder.itemView.context, com.focusvault.app.R.color.warning_amber_chip_bg)
                )
                holder.binding.tvPermanentBadge.setTextColor(
                    androidx.core.content.ContextCompat.getColor(holder.itemView.context, com.focusvault.app.R.color.warning_amber)
                )
                holder.binding.tvSiteSubtitle.text = "Temporarily paused • 10m remaining"
            } else if (site.isPermanent) {
                holder.binding.tvPermanentBadge.visibility = android.view.View.VISIBLE
                holder.binding.tvPermanentBadge.text = "24/7 PERMANENT"
                holder.binding.tvPermanentBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(holder.itemView.context, com.focusvault.app.R.color.strict_red_chip_bg)
                )
                holder.binding.tvPermanentBadge.setTextColor(
                    androidx.core.content.ContextCompat.getColor(holder.itemView.context, com.focusvault.app.R.color.strict_red)
                )
                holder.binding.tvSiteSubtitle.text = "24/7 Security Rule Active"
            } else {
                holder.binding.tvPermanentBadge.visibility = android.view.View.VISIBLE
                holder.binding.tvPermanentBadge.text = "SESSION ONLY"
                holder.binding.tvPermanentBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(holder.itemView.context, com.focusvault.app.R.color.brand_primary_subtle)
                )
                holder.binding.tvPermanentBadge.setTextColor(
                    androidx.core.content.ContextCompat.getColor(holder.itemView.context, com.focusvault.app.R.color.brand_primary)
                )
                holder.binding.tvSiteSubtitle.text = "Active during Focus Sessions"
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
