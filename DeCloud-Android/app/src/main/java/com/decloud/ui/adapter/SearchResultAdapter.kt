package com.decloud.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.model.FileItem

/**
 * Adapter for displaying live search results
 * Uses ListAdapter with DiffUtil for efficient updates
 */
class SearchResultAdapter(
    private val onItemClick: (FileItem) -> Unit
) : ListAdapter<FileItem, SearchResultAdapter.ViewHolder>(SearchDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val name: TextView = itemView.findViewById(R.id.tvName)
        private val path: TextView = itemView.findViewById(R.id.tvPath)
        private val arrow: ImageView = itemView.findViewById(R.id.ivArrow)

        fun bind(item: FileItem) {
            val context = itemView.context
            name.text = item.name

            // Show relative path
            path.text = item.file.parent ?: ""

            // Set icon based on type — outline folder with muted tint
            val iconRes = if (item.isDirectory) R.drawable.ic_folder_outline else R.drawable.ic_file
            icon.setImageResource(iconRes)
            val tintColor = if (item.isDirectory) R.color.folder_icon else R.color.primary
            icon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, tintColor)
            )

            // Show arrow for directories to indicate navigation
            arrow.visibility = if (item.isDirectory) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    class SearchDiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path && oldItem.name == newItem.name
        }
    }
}
