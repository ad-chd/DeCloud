package com.decloud.ui.adapter

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
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
import com.decloud.util.MediaScanner
import com.decloud.util.ThumbnailLoader
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for displaying files in category view
 * Supports APK icon preview loading and native thumbnail loading
 */
class CategoryFileAdapter(
    private val context: Context,
    private val category: MediaScanner.Category,
    private val onItemClick: (FileItem) -> Unit,
    private val onItemSelect: (FileItem) -> Unit,
    private val onSelectionModeChanged: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<CategoryFileAdapter.FileViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val packageManager: PackageManager = context.packageManager

    // Cache for APK icons to avoid repeated loading
    private val apkIconCache = LruCache<String, Drawable>(50)

    // Coroutine scope for async icon loading
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Thumbnail toggle state - OFF by default
    private var thumbnailsEnabled = false

    // Selection mode - checkboxes only visible when true
    var isSelectionMode: Boolean = false
        private set

    // When true, only checkbox clicks toggle selection (row click is no-op in selection mode)
    var checkboxOnlySelection: Boolean = false

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
        differ.submitList(allItems) {
            onCommitted?.invoke()
        }
    }

    fun getItems(): List<FileItem> = differ.currentList

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
            if (!enabled) {
                ThumbnailLoader.clearCache()
            }
            notifyItemRangeChanged(0, differ.currentList.size, PAYLOAD_THUMBNAIL_TOGGLE)
        }
    }

    fun isThumbnailsEnabled(): Boolean = thumbnailsEnabled

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
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_file, parent, false)
        return FileViewHolder(view)
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

    init {
        setHasStableIds(true)
    }

    override fun onViewRecycled(holder: FileViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelIconLoading()
    }

    fun cleanup() {
        scope.cancel()
        apkIconCache.evictAll()
        ThumbnailLoader.clearCache()
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkbox)
        private val iconView: ImageView = itemView.findViewById(R.id.ivIcon)
        private val nameView: TextView = itemView.findViewById(R.id.tvName)
        private val sizeView: TextView = itemView.findViewById(R.id.tvSize)
        private val dateView: TextView = itemView.findViewById(R.id.tvDate)
        private val pathView: TextView = itemView.findViewById(R.id.tvPath)

        private var iconLoadJob: Job? = null
        private var currentPath: String? = null

        fun bind(item: FileItem) {
            currentPath = item.path
            nameView.text = item.name
            sizeView.text = item.getFormattedSize()
            dateView.text = dateFormat.format(Date(item.lastModified))

            // Show parent folder path
            val parentPath = File(item.path).parent?.let { path ->
                if (path.length > 40) {
                    "...${path.takeLast(37)}"
                } else {
                    path
                }
            } ?: ""
            pathView.text = parentPath

            // Set icon based on category/file type
            updateIcon(item)

            // Selection state - show checkbox only in selection mode
            checkBox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            updateSelection(item)

            // Click listeners
            checkBox.setOnClickListener {
                onItemSelect(item)
            }

            itemView.setOnClickListener {
                if (isSelectionMode && !checkboxOnlySelection) {
                    onItemSelect(item)
                } else if (!isSelectionMode) {
                    onItemClick(item)
                }
                // checkboxOnlySelection + selectionMode → row click is no-op
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    notifyDataSetChanged()
                    onSelectionModeChanged?.invoke(true)
                }
                onItemSelect(item)
                true
            }
        }

        fun updateIcon(item: FileItem) {
            // Cancel any pending icon load
            cancelIconLoading()

            // Remove tint for thumbnails
            iconView.imageTintList = null

            when {
                // APK files - load app icon (always show for APKs)
                category == MediaScanner.Category.APPLICATIONS && item.extension == "apk" -> {
                    loadApkIcon(item)
                }

                // Images/Videos - load thumbnail only if enabled
                thumbnailsEnabled && ThumbnailLoader.supportsThumbnail(item.extension) -> {
                    loadThumbnail(item)
                }

                // Default icons
                else -> {
                    setDefaultIcon(item)
                }
            }
        }

        private fun loadThumbnail(item: FileItem) {
            val isVideo = ThumbnailLoader.isVideo(item.extension)

            // Try to get cached thumbnail first
            val cached = ThumbnailLoader.getCached(item.path)
            if (cached != null) {
                iconView.setImageBitmap(cached)
                return
            }

            // Set placeholder while loading
            val placeholderRes = if (isVideo) R.drawable.ic_video else R.drawable.ic_image
            iconView.setImageResource(placeholderRes)
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.primary)
            )

            // Load thumbnail asynchronously
            iconLoadJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    ThumbnailLoader.getCached(item.path) ?: run {
                        // Trigger async load and wait for callback
                        null
                    }
                }

                // Use ThumbnailLoader's async method
                ThumbnailLoader.loadAsync(item.path, isVideo) { loadedBitmap ->
                    if (currentPath == item.path && loadedBitmap != null) {
                        iconView.imageTintList = null
                        iconView.setImageBitmap(loadedBitmap)
                    }
                }
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

        private fun loadApkIcon(item: FileItem) {
            // Check cache first
            val cachedIcon = apkIconCache.get(item.path)
            if (cachedIcon != null) {
                iconView.setImageDrawable(cachedIcon)
                return
            }

            // Set placeholder
            iconView.setImageResource(R.drawable.ic_apk)

            // Load icon asynchronously
            iconLoadJob = scope.launch {
                val icon = withContext(Dispatchers.IO) {
                    try {
                        val packageInfo = packageManager.getPackageArchiveInfo(item.path, PackageManager.GET_ACTIVITIES)
                        packageInfo?.applicationInfo?.let { appInfo ->
                            appInfo.sourceDir = item.path
                            appInfo.publicSourceDir = item.path
                            appInfo.loadIcon(packageManager)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                if (isActive && currentPath == item.path) {
                    if (icon != null) {
                        apkIconCache.put(item.path, icon)
                        iconView.setImageDrawable(icon)
                    } else {
                        iconView.setImageResource(R.drawable.ic_apk)
                    }
                }
            }
        }

        fun updateSelection(item: FileItem) {
            checkBox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = SelectionManager.isSelected(item)
        }

        fun cancelIconLoading() {
            iconLoadJob?.cancel()
            iconLoadJob = null
            currentPath?.let { ThumbnailLoader.cancelLoad(it) }
        }
    }
}
