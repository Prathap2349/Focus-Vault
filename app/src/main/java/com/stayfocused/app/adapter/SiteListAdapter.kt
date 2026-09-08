package com.stayfocused.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.stayfocused.app.data.BlockedSite
import com.stayfocused.app.databinding.ItemSiteBinding

class SiteListAdapter(
    private var sites: MutableList<BlockedSite>,
    private val onRemove: (BlockedSite) -> Unit
) : RecyclerView.Adapter<SiteListAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSiteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val site = sites[position]
        holder.binding.tvDomain.text = site.domain
        
        if (site.isPermanent) {
            holder.binding.tvPermanentBadge.visibility = android.view.View.VISIBLE
            holder.binding.tvSiteSubtitle.text = "24/7 Round-the-Clock DNS Block"
        } else {
            holder.binding.tvPermanentBadge.visibility = android.view.View.GONE
            holder.binding.tvSiteSubtitle.text = "Session Local DNS Blocked"
        }

        val isVaultLock = site.isPermanent && com.stayfocused.app.util.PrefsManager.isPermanentVaultLockEnabled(holder.itemView.context)
        holder.binding.ivLock.visibility = if (isVaultLock) android.view.View.VISIBLE else android.view.View.GONE

        holder.binding.btnRemove.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION && index < sites.size) {
                val siteToRemove = sites[index]
                onRemove(siteToRemove)
            }
        }
    }

    override fun getItemCount() = sites.size

    fun addSite(site: BlockedSite) {
        sites.add(0, site)
        notifyItemInserted(0)
    }

    fun updateList(newList: List<BlockedSite>) {
        sites = newList.toMutableList()
        notifyDataSetChanged()
    }
}
