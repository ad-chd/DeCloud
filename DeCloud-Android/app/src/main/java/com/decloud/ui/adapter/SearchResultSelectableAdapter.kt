package com.decloud.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.model.FileItem
import com.decloud.util.ThumbnailLoader
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

/**
 * Adapter for search results with selection capability
 * - Both files AND folders can be selected via checkbox
 * - Arrow icon to navigate to item's location
 */
class SearchResultSelectableAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onNavigateToItem: (FileItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<FileItem, SearchResultSelectableAdapter.ViewHolder>(SearchSelectableDiffCallback()) {

    // Track selected items by path (both files and folders)
    private val selectedPaths = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result_selectable, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Select all currently visible items (both files AND folders)
     */
    fun selectAll() {
        selectedPaths.addAll(currentList.map { it.path })
        notifyDataSetChanged()
        onSelectionChanged(selectedPaths.size)
    }

    /**
     * Deselect all items
     */
    fun deselectAll() {
        selectedPaths.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    /**
     * Get all selected items (files and folders)
     */
    fun getSelectedItems(): List<FileItem> {
        return currentList.filter { selectedPaths.contains(it.path) }
    }

    /**
     * Get count of selected items
     */
    fun getSelectedCount(): Int = selectedPaths.size

    /**
     * Check if an item is selected
     */
    fun isSelected(path: String): Boolean = selectedPaths.contains(path)

    /**
     * Toggle selection for a specific item (works for both files and folders)
     */
    fun toggleSelection(item: FileItem) {
        if (selectedPaths.contains(item.path)) {
            selectedPaths.remove(item.path)
        } else {
            selectedPaths.add(item.path)
        }
        onSelectionChanged(selectedPaths.size)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkbox: CheckBox = itemView.findViewById(R.id.checkbox)
        private val icon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val name: TextView = itemView.findViewById(R.id.tvName)
        private val path: TextView = itemView.findViewById(R.id.tvPath)
        private val size: TextView = itemView.findViewById(R.id.tvSize)
        private val arrow: ImageView = itemView.findViewById(R.id.ivArrow)
        private var thumbnailJob: kotlinx.coroutines.Job? = null
        private var currentPath: String? = null

        fun bind(item: FileItem) {
            val context = itemView.context
            cancelThumbnailLoad()
            currentPath = item.path

            // Set file name
            name.text = item.name

            // Set path (parent directory)
            path.text = item.file.parent ?: ""

            // Load thumbnail for images/videos, fallback to icon
            val supportsThumbnail = !item.isDirectory &&
                ThumbnailLoader.supportsThumbnail(item.extension)

            if (supportsThumbnail) {
                val cached = ThumbnailLoader.getCached(item.path)
                if (cached != null) {
                    icon.imageTintList = null
                    icon.setImageBitmap(cached)
                } else {
                    // Set placeholder while loading
                    val placeholderRes = if (ThumbnailLoader.isVideo(item.extension)) R.drawable.ic_video else R.drawable.ic_image
                    icon.setImageResource(placeholderRes)
                    icon.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.primary)
                    )
                    val isVideo = ThumbnailLoader.isVideo(item.extension)
                    thumbnailJob = ThumbnailLoader.loadAsync(item.path, isVideo) { bitmap ->
                        if (currentPath == item.path && bitmap != null) {
                            icon.imageTintList = null
                            icon.setImageBitmap(bitmap)
                        }
                    }
                }
            } else {
                val iconRes = when {
                    item.isDirectory -> R.drawable.ic_folder_outline
                    item.isAudio -> R.drawable.ic_audio
                    item.isDocument -> R.drawable.ic_document
                    else -> R.drawable.ic_file
                }
                icon.setImageResource(iconRes)
                icon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, if (item.isDirectory) R.color.folder_icon else R.color.primary)
                )
            }

            // Both files and folders can be selected
            checkbox.visibility = View.VISIBLE
            arrow.visibility = View.VISIBLE

            // Set size/folder indicator
            size.text = if (item.isDirectory) "Folder" else formatFileSize(item.size)

            // Set checkbox state
            checkbox.isChecked = selectedPaths.contains(item.path)

            // Highlight background if selected
            if (selectedPaths.contains(item.path)) {
                itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.selection_highlight))
            } else {
                itemView.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            }

            // Click on item toggles selection
            itemView.setOnClickListener {
                toggleSelection(item)
                checkbox.isChecked = selectedPaths.contains(item.path)
                // Update background
                if (selectedPaths.contains(item.path)) {
                    itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.selection_highlight))
                } else {
                    itemView.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                }
                onItemClick(item)
            }

            // Checkbox click also toggles
            checkbox.setOnClickListener {
                toggleSelection(item)
                // Update background
                if (selectedPaths.contains(item.path)) {
                    itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.selection_highlight))
                } else {
                    itemView.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
                }
                onItemClick(item)
            }

            // Arrow click navigates to item's location
            arrow.setOnClickListener {
                onNavigateToItem(item)
            }
        }

        fun cancelThumbnailLoad() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            currentPath?.let { ThumbnailLoader.cancelLoad(it) }
        }

        private fun formatFileSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
            val df = DecimalFormat("#,##0.#")
            return df.format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelThumbnailLoad()
    }

    class SearchSelectableDiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path && oldItem.name == newItem.name
        }
    }
}
