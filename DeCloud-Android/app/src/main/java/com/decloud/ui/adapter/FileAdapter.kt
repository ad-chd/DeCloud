package com.decloud.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.model.FileItem
import com.decloud.model.FileType
import com.decloud.model.SelectionManager
import com.decloud.util.ThumbnailLoader
import com.decloud.util.ViewTypeManager
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optimized adapter for displaying files in the file browser
 * Uses AsyncListDiffer for efficient background list updates
 * Shows thumbnails for images and videos (when enabled)
 */
class FileAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemSelect: (FileItem) -> Unit,
    private val onSelectionModeChanged: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    // Cached date format - reused for all items
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    // Thumbnail toggle state - OFF by default
    private var thumbnailsEnabled = false

    // Current view type
    private var currentViewType = ViewTypeManager.ViewType.LIST

    // Selection mode - checkboxes only visible when true
    var isSelectionMode: Boolean = false
        private set

    // Track loading jobs per position to cancel on recycle
    private val loadingJobs = mutableMapOf<Int, Job>()

    // DiffUtil callback for AsyncListDiffer
    private val diffCallback = object : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path &&
                   oldItem.size == newItem.size &&
                   oldItem.lastModified == newItem.lastModified
        }
    }

    // AsyncListDiffer runs diff calculation on background thread
    private val differ = AsyncListDiffer(this, diffCallback)

    // Store items separately for immediate access (differ updates async)
    private var allItems: List<FileItem> = emptyList()

    companion object {
        private const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
        private const val PAYLOAD_THUMBNAIL_TOGGLE = "thumbnail_toggle"
    }

    fun setItems(newItems: List<FileItem>, onCommitted: (() -> Unit)? = null) {
        // Store immediately for hasMediaFiles() to work right away
        allItems = newItems.toList()
        // AsyncListDiffer handles diffing in background thread automatically
        differ.submitList(allItems) {
            onCommitted?.invoke()
        }
    }

    /**
     * Update selection state without replacing the entire list
     */
    fun updateSelectionState() {
        // Auto-enter selection mode if SelectionManager has items
        if (!isSelectionMode && SelectionManager.getSelectedCount() > 0) {
            isSelectionMode = true
            notifyDataSetChanged()
            onSelectionModeChanged?.invoke(true)
            return
        }
        notifyItemRangeChanged(0, differ.currentList.size, PAYLOAD_SELECTION_CHANGED)
    }

    /**
     * Set selection mode on/off
     */
    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            notifyDataSetChanged()
            onSelectionModeChanged?.invoke(enabled)
        }
    }

    /**
     * Toggle thumbnail display on/off
     */
    fun setThumbnailsEnabled(enabled: Boolean) {
        if (thumbnailsEnabled != enabled) {
            thumbnailsEnabled = enabled
            // Clear cache when disabling to free memory
            if (!enabled) {
                ThumbnailLoader.clearCache()
            }
            notifyItemRangeChanged(0, differ.currentList.size, PAYLOAD_THUMBNAIL_TOGGLE)
        }
    }

    fun isThumbnailsEnabled(): Boolean = thumbnailsEnabled

    /**
     * Set the view type (List, Grid, Compact)
     */
    fun setViewType(viewType: ViewTypeManager.ViewType) {
        if (currentViewType != viewType) {
            currentViewType = viewType
            notifyDataSetChanged()
        }
    }

    fun getViewType(): ViewTypeManager.ViewType = currentViewType

    fun getItems(): List<FileItem> = differ.currentList

    /**
     * Get position of an item by its path
     * Returns -1 if not found
     */
    fun getPositionByPath(path: String): Int {
        return differ.currentList.indexOfFirst { it.path == path }
    }

    /**
     * Check if current list has any files that support thumbnails
     * Uses allItems instead of differ.currentList for immediate access
     */
    fun hasMediaFiles(): Boolean {
        return allItems.any { item ->
            !item.isDirectory && ThumbnailLoader.supportsThumbnail(item.extension)
        }
    }

    /**
     * Get list of media file paths for preloading
     */
    fun getMediaFilesForPreload(startPosition: Int, count: Int): List<Pair<String, Boolean>> {
        val list = differ.currentList
        val result = mutableListOf<Pair<String, Boolean>>()

        for (i in startPosition until minOf(startPosition + count, list.size)) {
            val item = list[i]
            if (!item.isDirectory && ThumbnailLoader.supportsThumbnail(item.extension)) {
                result.add(item.path to ThumbnailLoader.isVideo(item.extension))
            }
        }
        return result
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val layoutRes = when (currentViewType) {
            ViewTypeManager.ViewType.LIST -> R.layout.item_file
            ViewTypeManager.ViewType.GRID -> R.layout.item_file_grid
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return FileViewHolder(view)
    }

    override fun getItemViewType(position: Int): Int {
        // Return view type ordinal to force view holder recreation on type change
        return currentViewType.ordinal
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(differ.currentList[position])
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val item = differ.currentList[position]
        for (payload in payloads) {
            when (payload) {
                PAYLOAD_SELECTION_CHANGED -> holder.updateSelection(item)
                PAYLOAD_THUMBNAIL_TOGGLE -> holder.updateIcon(item)
            }
        }
    }

    override fun getItemCount(): Int = differ.currentList.size

    override fun getItemId(position: Int): Long {
        return differ.currentList[position].path.hashCode().toLong()
    }

    override fun onViewRecycled(holder: FileViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelThumbnailLoad()
    }

    init {
        setHasStableIds(true)
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val context: Context = itemView.context
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkbox)
        private val iconView: ImageView = itemView.findViewById(R.id.ivIcon)
        private val nameView: TextView = itemView.findViewById(R.id.tvName)
        private val sizeView: TextView = itemView.findViewById(R.id.tvSize)
        private val dateView: TextView = itemView.findViewById(R.id.tvDate)
        private val arrowView: ImageView = itemView.findViewById(R.id.ivArrow)
        // Grid view has a container wrapping the checkbox
        private val checkboxContainer: View? = itemView.findViewById(R.id.checkboxContainer)

        private var currentPath: String? = null
        private var thumbnailJob: Job? = null

        fun bind(item: FileItem) {
            currentPath = item.path
            nameView.text = item.name

            // Set icon based on file type
            updateIcon(item)

            // Size and date
            if (item.isDirectory) {
                sizeView.text = ""
                arrowView.visibility = View.VISIBLE
            } else {
                sizeView.text = item.getFormattedSize()
                arrowView.visibility = View.GONE
            }

            dateView.text = dateFormat.format(Date(item.lastModified))

            // Selection state - show checkbox only in selection mode
            checkBox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            updateSelection(item)

            // Click listeners
            checkBox.setOnClickListener {
                onItemSelect(item)
            }

            // Arrow navigates into directory (even in selection mode)
            if (item.isDirectory) {
                arrowView.isClickable = true
                arrowView.setOnClickListener {
                    onItemClick(item)
                }
            } else {
                arrowView.isClickable = false
                arrowView.setOnClickListener(null)
            }

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    // In selection mode, clicking toggles selection
                    onItemSelect(item)
                } else if (item.isDirectory) {
                    // Normal mode - navigate into directory
                    onItemClick(item)
                } else {
                    // Normal mode file click - do nothing (long-press to select)
                    onItemClick(item)
                }
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    // Enter selection mode
                    isSelectionMode = true
                    notifyDataSetChanged()
                    onSelectionModeChanged?.invoke(true)
                }
                // Select this item
                onItemSelect(item)
                true
            }
        }

        fun updateIcon(item: FileItem) {
            // Cancel any pending thumbnail load
            cancelThumbnailLoad()

            // Reset tint
            iconView.imageTintList = null

            // Check if this file supports thumbnails
            val supportsThumbnail = !item.isDirectory &&
                ThumbnailLoader.supportsThumbnail(item.extension)

            if (thumbnailsEnabled && supportsThumbnail) {
                // Try to get cached thumbnail first
                val cached = ThumbnailLoader.getCached(item.path)
                if (cached != null) {
                    iconView.setImageBitmap(cached)
                    return
                }

                // Set placeholder while loading
                val placeholderRes = if (ThumbnailLoader.isVideo(item.extension)) {
                    R.drawable.ic_video
                } else {
                    R.drawable.ic_image
                }
                iconView.setImageResource(placeholderRes)
                iconView.imageTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(R.color.primary)
                )

                // Load thumbnail asynchronously
                val isVideo = ThumbnailLoader.isVideo(item.extension)
                thumbnailJob = ThumbnailLoader.loadAsync(item.path, isVideo) { bitmap ->
                    // Only update if this ViewHolder is still showing the same item
                    if (currentPath == item.path && bitmap != null) {
                        iconView.imageTintList = null
                        iconView.setImageBitmap(bitmap)
                    }
                }
            } else {
                // Show default icon
                setDefaultIcon(item)
            }
        }

        private fun setDefaultIcon(item: FileItem) {
            val iconRes = when (item.fileType) {
                FileType.FOLDER -> R.drawable.ic_folder_outline
                FileType.IMAGE -> R.drawable.ic_image
                FileType.VIDEO -> R.drawable.ic_video
                FileType.AUDIO -> R.drawable.ic_audio
                FileType.DOCUMENT -> R.drawable.ic_document
                else -> R.drawable.ic_document
            }
            iconView.setImageResource(iconRes)
            val tintColor = if (item.fileType == FileType.FOLDER) R.color.folder_icon else R.color.primary
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(tintColor)
            )
        }

        /**
         * Update only the selection state (for efficient partial updates)
         */
        fun updateSelection(item: FileItem) {
            val visible = isSelectionMode
            checkBox.visibility = if (visible) View.VISIBLE else View.GONE
            // In grid view, toggle the entire container
            checkboxContainer?.visibility = if (visible) View.VISIBLE else View.GONE
            checkBox.isChecked = SelectionManager.isSelected(item)
        }

        fun cancelThumbnailLoad() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            currentPath?.let { ThumbnailLoader.cancelLoad(it) }
        }
    }
}
