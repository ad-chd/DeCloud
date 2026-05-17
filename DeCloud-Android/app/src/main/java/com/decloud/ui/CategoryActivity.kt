package com.decloud.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.decloud.R
import com.decloud.databinding.ActivityCategoryBinding
import com.decloud.model.FileItem
import com.decloud.model.SelectionManager
import com.decloud.ui.adapter.CategoryFileAdapter
import com.decloud.util.MediaScanner
import com.decloud.util.StorageUtils
import com.decloud.util.ThumbnailLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Activity to display files from a specific category (Images, Videos, Audio, etc.)
 * Uses MediaStore for comprehensive file listing
 */
class CategoryActivity : BaseActivity(), SelectionManager.SelectionProgressListener {

    companion object {
        const val EXTRA_CATEGORY = "extra_category"
        private const val IDLE_DELAY_MS = 500L  // Time after scroll stops to start preloading
        private const val PRELOAD_COUNT = 20  // Number of items to preload when idle
    }

    // Sorting options
    enum class SortOption {
        NAME_ASC,
        NAME_DESC,
        SIZE_LARGEST,
        SIZE_SMALLEST,
        DATE_NEWEST,
        DATE_OLDEST
    }

    // Storage filter
    enum class StorageFilter {
        ALL,
        INTERNAL,
        EXTERNAL
    }

    private lateinit var binding: ActivityCategoryBinding
    private lateinit var fileAdapter: CategoryFileAdapter
    private lateinit var category: MediaScanner.Category

    private var loadingJob: Job? = null
    private var isLoading = false

    // Thumbnails always enabled
    private val thumbnailsEnabled = true

    // Current sort option
    private var currentSort = SortOption.NAME_ASC

    // Current storage filter
    private var currentStorageFilter = StorageFilter.ALL
    private var hasExternalStorage = false

    // Original file list (unsorted)
    private var originalFiles: List<FileItem> = emptyList()

    // Handler for idle detection
    private val idleHandler = Handler(Looper.getMainLooper())
    private var idleRunnable: Runnable? = null

    // Loading overlay state
    private var isLoadingOverlayVisible = false

    // Stored listener reference for cleanup
    private var selectionListener: SelectionManager.SelectionListener? = null
    private var scrollListener: RecyclerView.OnScrollListener? = null

    // Internal storage path for filtering
    private val internalStoragePath by lazy { Environment.getExternalStorageDirectory().absolutePath }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get category from intent
        val categoryName = intent.getStringExtra(EXTRA_CATEGORY) ?: MediaScanner.Category.IMAGES.name
        category = MediaScanner.Category.valueOf(categoryName)

        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupButtons()
        setupSorting()
        setupScrollListener()
        setupSelectionListener()
        setupLoadingOverlay()
        SelectionManager.setProgressListener(this)

        // Enable thumbnails by default
        fileAdapter.setThumbnailsEnabled(true)

        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        updateSelectionBar()
        fileAdapter.updateSelectionState()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadingJob?.cancel()
        fileAdapter.cleanup()
        idleRunnable?.let { idleHandler.removeCallbacks(it) }
        scrollListener?.let { binding.recyclerView.removeOnScrollListener(it) }
        selectionListener?.let { SelectionManager.removeListener(it) }
        ThumbnailLoader.cancelPreload()
        SelectionManager.setProgressListener(null)
    }

    private fun setupToolbar() {
        val title = when (category) {
            MediaScanner.Category.IMAGES -> "Images"
            MediaScanner.Category.VIDEOS -> "Videos"
            MediaScanner.Category.AUDIO -> "Audio"
            MediaScanner.Category.DOCUMENTS -> "Documents"
            MediaScanner.Category.DOWNLOADS -> "Downloads"
            MediaScanner.Category.APPLICATIONS -> "Applications"
        }
        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        fileAdapter = CategoryFileAdapter(
            context = this,
            category = category,
            onItemClick = { fileItem ->
                SelectionManager.toggleSelection(fileItem, lifecycleScope)
                fileAdapter.updateSelectionState()
                updateSelectionBar()
            },
            onItemSelect = { fileItem ->
                SelectionManager.toggleSelection(fileItem, lifecycleScope)
                fileAdapter.updateSelectionState()
                updateSelectionBar()
            },
            onSelectionModeChanged = { enabled ->
                updateSelectionBar()
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@CategoryActivity)
            adapter = fileAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(30)
            itemAnimator = null
        }
    }

    private fun setupSwipeRefresh() {
        // Disable swipe-to-refresh for categories - list updates automatically
        binding.swipeRefresh.isEnabled = false
    }

    private fun setupScrollListener() {
        scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)

                when (newState) {
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        // User stopped scrolling - schedule preload after delay
                        if (thumbnailsEnabled) {
                            scheduleIdlePreload()
                        }
                    }
                    RecyclerView.SCROLL_STATE_DRAGGING,
                    RecyclerView.SCROLL_STATE_SETTLING -> {
                        // User is scrolling - cancel any pending preload
                        cancelIdlePreload()
                        ThumbnailLoader.cancelPreload()
                    }
                }
            }
        }
        binding.recyclerView.addOnScrollListener(scrollListener!!)
    }

    private fun scheduleIdlePreload() {
        cancelIdlePreload()

        idleRunnable = Runnable {
            preloadVisibleThumbnails()
        }
        idleHandler.postDelayed(idleRunnable!!, IDLE_DELAY_MS)
    }

    private fun cancelIdlePreload() {
        idleRunnable?.let { idleHandler.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun preloadVisibleThumbnails() {
        if (!thumbnailsEnabled) return

        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()

        if (firstVisible == RecyclerView.NO_POSITION) return

        // Preload items around current visible range
        val preloadStart = maxOf(0, firstVisible - PRELOAD_COUNT / 2)
        val preloadCount = (lastVisible - firstVisible) + PRELOAD_COUNT

        val filesToPreload = fileAdapter.getMediaFilesForPreload(preloadStart, preloadCount)
        if (filesToPreload.isNotEmpty()) {
            ThumbnailLoader.preloadInBackground(filesToPreload)
        }
    }

    private fun setupButtons() {
        // Select All button
        binding.btnSelectAll.setOnClickListener {
            val items = fileAdapter.getItems()
            SelectionManager.selectAllInList(items, lifecycleScope)
            fileAdapter.updateSelectionState()
            updateSelectionBar()
        }

        // Deselect button
        binding.btnDeselectAll.setOnClickListener {
            SelectionManager.deselectAll()
            fileAdapter.updateSelectionState()
            updateSelectionBar()
        }

        // Ready to Send button
        binding.btnReadyToSend.setOnClickListener {
            if (SelectionManager.hasSelection()) {
                startActivity(Intent(this, ModeSelectionActivity::class.java))
            }
        }
    }

    private fun setupSorting() {
        // Check for external storage
        val storageVolumes = StorageUtils.getStorageVolumes(this)
        hasExternalStorage = storageVolumes.size > 1

        binding.btnSort.setOnClickListener {
            showSortBottomSheet()
        }
    }

    private fun showSortBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val hPad = resources.getDimensionPixelSize(R.dimen.space_5xl)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.space_3xl)
            setPadding(0, pad, 0, pad)
        }

        // Sort section
        container.addView(createSheetSectionTitle("Sort by"))

        val sortOptions = arrayOf(
            "Name (A-Z)" to SortOption.NAME_ASC,
            "Name (Z-A)" to SortOption.NAME_DESC,
            "Size (Largest first)" to SortOption.SIZE_LARGEST,
            "Size (Smallest first)" to SortOption.SIZE_SMALLEST,
            "Date (Newest first)" to SortOption.DATE_NEWEST,
            "Date (Oldest first)" to SortOption.DATE_OLDEST
        )

        for ((label, sortOption) in sortOptions) {
            val isSelected = sortOption == currentSort
            container.addView(createSheetItem(label, isSelected) {
                if (sortOption != currentSort) {
                    currentSort = sortOption
                    applySorting(showLoading = true)
                }
                bottomSheet.dismiss()
            })
        }

        // Storage filter section (only if external storage exists)
        if (hasExternalStorage) {
            container.addView(createSheetDivider())
            container.addView(createSheetSectionTitle("Storage"))

            val storageOptions = arrayOf(
                "All Storage" to StorageFilter.ALL,
                "Internal Only" to StorageFilter.INTERNAL,
                "External Only" to StorageFilter.EXTERNAL
            )

            for ((label, filter) in storageOptions) {
                val isSelected = filter == currentStorageFilter
                container.addView(createSheetItem(label, isSelected) {
                    if (filter != currentStorageFilter) {
                        currentStorageFilter = filter
                        applySorting(showLoading = true)
                    }
                    bottomSheet.dismiss()
                })
            }
        }

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }

    private fun createSheetSectionTitle(title: String): TextView {
        val hPad = resources.getDimensionPixelSize(R.dimen.space_5xl)
        return TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@CategoryActivity, R.color.text_secondary))
            isAllCaps = true
            letterSpacing = 0.05f
            setPadding(hPad, resources.getDimensionPixelSize(R.dimen.space_3xl), hPad, resources.getDimensionPixelSize(R.dimen.space_md))
        }
    }

    private fun createSheetDivider(): View {
        val hPad = resources.getDimensionPixelSize(R.dimen.space_5xl)
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.stroke_hairline)
            ).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_md)
                leftMargin = hPad
                rightMargin = hPad
            }
            setBackgroundColor(ContextCompat.getColor(this@CategoryActivity, R.color.divider))
        }
    }

    private fun createSheetItem(label: String, isSelected: Boolean, onClick: () -> Unit): LinearLayout {
        val hPad = resources.getDimensionPixelSize(R.dimen.space_5xl)
        val vPad = resources.getDimensionPixelSize(R.dimen.space_3xl)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }

            addView(TextView(context).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body_large))
                setTextColor(ContextCompat.getColor(this@CategoryActivity,
                    if (isSelected) R.color.primary else R.color.text_primary
                ))
                if (isSelected) setTypeface(null, Typeface.BOLD)
            })

            if (isSelected) {
                addView(android.widget.ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    layoutParams = LinearLayout.LayoutParams(
                        resources.getDimensionPixelSize(R.dimen.icon_standard),
                        resources.getDimensionPixelSize(R.dimen.icon_standard)
                    )
                })
            }
        }
    }

    private fun applySorting(showLoading: Boolean = false) {
        if (showLoading) {
            showLoadingOverlay("Sorting...", "")
        }

        // Apply storage filter first
        val filteredFiles = when (currentStorageFilter) {
            StorageFilter.ALL -> originalFiles
            StorageFilter.INTERNAL -> originalFiles.filter { it.path.startsWith(internalStoragePath) }
            StorageFilter.EXTERNAL -> originalFiles.filter { !it.path.startsWith(internalStoragePath) }
        }

        // Then apply sorting
        val sortedFiles = when (currentSort) {
            SortOption.NAME_ASC -> filteredFiles.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> filteredFiles.sortedByDescending { it.name.lowercase() }
            SortOption.SIZE_LARGEST -> filteredFiles.sortedByDescending { it.size }
            SortOption.SIZE_SMALLEST -> filteredFiles.sortedBy { it.size }
            SortOption.DATE_NEWEST -> filteredFiles.sortedByDescending { it.lastModified }
            SortOption.DATE_OLDEST -> filteredFiles.sortedBy { it.lastModified }
        }

        fileAdapter.setItems(sortedFiles) {
            // Scroll to top AFTER DiffUtil commits the new list
            binding.recyclerView.scrollToPosition(0)
            if (showLoading) {
                hideLoadingOverlay()
            }
        }
        binding.tvFileCount.text = "${sortedFiles.size} files"
    }

    private fun setupSelectionListener() {
        selectionListener = object : SelectionManager.SelectionListener {
            override fun onSelectionChanged(count: Int, totalSize: Long) {
                runOnUiThread {
                    // Exit selection mode when all items are deselected
                    if (!SelectionManager.hasSelection() && fileAdapter.isSelectionMode) {
                        fileAdapter.setSelectionMode(false)
                    }
                    updateSelectionBar()
                    fileAdapter.updateSelectionState()
                }
            }
        }
        SelectionManager.addListener(selectionListener!!)
    }

    private fun loadFiles() {
        loadingJob?.cancel()
        cancelIdlePreload()
        ThumbnailLoader.cancelPreload()

        showLoadingState()

        loadingJob = lifecycleScope.launch {
            try {
                val accumulated = mutableListOf<FileItem>()
                var isFirstBatch = true

                MediaScanner.streamFilesByCategory(this@CategoryActivity, category).collect { batch ->
                    accumulated.addAll(batch)

                    runOnUiThread {
                        if (isFirstBatch) {
                            isFirstBatch = false
                            hideLoadingState()
                            showContentState()
                        }

                        // Update the list with accumulated files
                        originalFiles = accumulated.toList()
                        applySorting()
                    }
                }

                runOnUiThread {
                    if (accumulated.isEmpty()) {
                        hideLoadingState()
                        showEmptyState()
                    } else {
                        // Final update
                        originalFiles = accumulated.toList()
                        applySorting()

                        if (thumbnailsEnabled && fileAdapter.hasMediaFiles()) {
                            scheduleIdlePreload()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideLoadingState()
                    showErrorState("Error loading files: ${e.message}")
                }
            }
        }
    }

    private fun setupLoadingOverlay() {
        binding.btnCancelLoading.setOnClickListener {
            // Cancel any ongoing selection
            SelectionManager.cancelSelection()
            loadingJob?.cancel()
            hideLoadingOverlay()
        }
    }

    /**
     * Show loading overlay with spinning animation
     */
    private fun showLoadingOverlay(message: String, detail: String = "") {
        if (isLoadingOverlayVisible) {
            // Just update the text if already visible
            binding.tvLoadingMessage.text = message
            binding.tvLoadingDetail.text = detail
            return
        }

        isLoadingOverlayVisible = true
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingMessage.text = message
        binding.tvLoadingDetail.text = detail

        // Animate fade in
        binding.loadingOverlay.alpha = 0f
        binding.loadingOverlay.animate()
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /**
     * Update loading overlay text without hiding
     */
    private fun updateLoadingOverlay(message: String, detail: String = "") {
        if (isLoadingOverlayVisible) {
            binding.tvLoadingMessage.text = message
            binding.tvLoadingDetail.text = detail
        }
    }

    /**
     * Hide loading overlay
     */
    private fun hideLoadingOverlay() {
        if (!isLoadingOverlayVisible) return

        isLoadingOverlayVisible = false
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (!isFinishing && !isDestroyed) {
                    binding.loadingOverlay.visibility = View.GONE
                }
            }
            .start()
    }

    private fun showLoadingState() {
        isLoading = true
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
    }

    private fun hideLoadingState() {
        isLoading = false
        binding.progressBar.visibility = View.GONE
    }

    private fun showContentState() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
    }

    private fun showEmptyState() {
        binding.recyclerView.visibility = View.GONE
        binding.tvEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = "No ${category.name.lowercase()} found"
    }

    private fun showErrorState(message: String) {
        binding.recyclerView.visibility = View.GONE
        binding.tvEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = message
    }

    private fun updateSelectionBar() {
        val count = SelectionManager.getSelectedCount()
        if (count > 0) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = SelectionManager.getSelectionSummary()
            binding.btnReadyToSend.isEnabled = true
            binding.btnDeselectAll.isEnabled = true
        } else {
            // Hide bottom bar entirely - let the file list use full screen
            binding.selectionBar.visibility = View.GONE
        }
    }

    override fun onBackPressed() {
        // Cancel any ongoing async selection and restore previous state
        if (SelectionManager.isSelectionInProgress()) {
            SelectionManager.cancelSelection()
            loadingJob?.cancel()
            hideLoadingOverlay()
            fileAdapter.updateSelectionState()
            updateSelectionBar()
            return
        }
        super.onBackPressed()
    }

    // SelectionProgressListener implementation
    override fun onSelectionStarted() {
        runOnUiThread {
            showLoadingOverlay("Processing...", "")
            binding.tvSelectionCount.text = "Processing..."
            binding.btnSelectAll.isEnabled = false
            binding.btnDeselectAll.isEnabled = false
            binding.btnReadyToSend.isEnabled = false
        }
    }

    override fun onSelectionProgress(scannedFiles: Int) {
        // No-op: keep showing "Processing..." without file counts
    }

    override fun onSelectionComplete(totalFiles: Int) {
        runOnUiThread {
            hideLoadingOverlay()
            fileAdapter.updateSelectionState()
            updateSelectionBar()
            binding.btnSelectAll.isEnabled = true
        }
    }
}
