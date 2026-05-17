package com.decloud.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.model.FileItem

/**
 * Data class for selection items in the list
 */
data class SelectionItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    var isSelected: Boolean = true
)

/**
 * Adapter for displaying selection items
 * Items are shown as CHECKED (selected) - unchecking deselects them
 */
class SelectionItemAdapter(
    private val onItemDeselect: (path: String, isDirectory: Boolean) -> Unit
) : RecyclerView.Adapter<SelectionItemAdapter.ViewHolder>() {

    private var items: List<SelectionItem> = emptyList()

    fun setItems(newItems: List<SelectionItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val name: TextView = itemView.findViewById(R.id.tvName)
        private val info: TextView = itemView.findViewById(R.id.tvInfo)
        private val checkbox: CheckBox = itemView.findViewById(R.id.checkbox)

        fun bind(item: SelectionItem) {
            val context = itemView.context
            name.text = item.name

            if (item.isDirectory) {
                icon.setImageResource(R.drawable.ic_folder_outline)
                icon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.folder_icon)
                )
                info.text = "Folder"
            } else {
                icon.setImageResource(R.drawable.ic_file)
                icon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.primary)
                )
                info.text = FileItem.formatFileSize(item.size)
            }

            // Show as checked (all displayed items are selected)
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = true

            // When unchecked, deselect the item
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) {
                    // User unchecked - deselect this item
                    onItemDeselect(item.path, item.isDirectory)
                }
            }

            // Click on row toggles checkbox (which triggers deselect)
            itemView.setOnClickListener {
                checkbox.isChecked = false  // This will trigger onCheckedChangeListener
            }
        }
    }
}
