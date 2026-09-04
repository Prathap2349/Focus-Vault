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
        holder.binding.btnRemove.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION && index < sites.size) {
                val removed = sites.removeAt(index)
                notifyItemRemoved(index)
                onRemove(removed)
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
