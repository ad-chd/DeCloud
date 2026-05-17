package com.decloud.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.decloud.R
import com.decloud.databinding.ActivityBrowseBinding
import com.decloud.model.FileItem
import com.decloud.model.FilterType
import com.decloud.model.SelectionManager
import com.decloud.model.SortType
import com.decloud.ui.adapter.FileAdapter
import com.decloud.util.FileScanner
import com.decloud.util.StorageUtils
import com.decloud.util.ThumbnailLoader
import com.decloud.util.ViewTypeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Stack

/**
 * Robust file browser activity that handles deep directories without crashing
 */
class BrowseActivity : BaseActivity(), SelectionManager.SelectionListener, SelectionManager.SelectionProgressListener {

    companion object {
        private const val TAG = "BrowseActivity"
        private const val TOAST_DEBOUNCE_MS = 3000L  // 3 seconds debounce for toasts
        private const val IDLE_DELAY_MS = 500L  // Time after scroll stops to start preloading
        private const val PRELOAD_COUNT = 20  // Number of items to preload when idle
        private const val REQUEST_SEARCH = 1001
        const val EXTRA_STORAGE_INDEX = "storage_index"
        const val EXTRA_INITIAL_PATH = "initial_path"
        const val EXTRA_HIGHLIGHT_ITEM = "highlight_item"
    }

    private lateinit var binding: ActivityBrowseBinding
    private lateinit var fileAdapter: FileAdapter

    private var currentDirectory: File = Environment.getExternalStorageDirectory()
    private val navigationStack = Stack<File>()
    private var storageRootPath: String = ""  // Root path for current storage

    private var currentSortType = SortType.NAME_ASC

    private var loadingJob: Job? = null
    private var isLoading = false

    // Toast debouncing - track last toast time per message
    private var lastToastTime: Long = 0
    private var lastToastMessage: String = ""

    // Thumbnails always enabled
    private val thumbnailsEnabled = true

    // Handler for idle detection
    private val idleHandler = Handler(Looper.getMainLooper())
    private var idleRunnable: Runnable? = null

    // Storage volumes
    private var storageVolumes: List<StorageUtils.StorageInfo> = emptyList()
    private var currentStorageIndex = 0

    // Loading overlay state
    private var isLoadingOverlayVisible = false

    // Stored scroll listener reference for cleanup
    private var scrollListener: RecyclerView.OnScrollListener? = null

    // View type state
    private var currentViewType = ViewTypeManager.ViewType.LIST

    // Track if we navigated here from search - for proper back navigation
    private var navigatedFromSearch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupStorage()
        setupRecyclerView()
        setupMoreOptions()
        setupButtons()
        setupScrollListener()
        setupLoadingOverlay()
        setupSearch()
        setupSwipeRefresh()

        // Enable thumbnails by default
        fileAdapter.setThumbnailsEnabled(true)

        SelectionManager.addListener(this)
        SelectionManager.setProgressListener(this)
        updateSelectionBar()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadingJob?.cancel()
        SelectionManager.removeListener(this)
        SelectionManager.setProgressListener(null)
        idleRunnable?.let { idleHandler.removeCallbacks(it) }
        scrollListener?.let { binding.recyclerView.removeOnScrollListener(it) }
        ThumbnailLoader.cancelPreload()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupStorage() {
        // Get available storage volumes
        storageVolumes = StorageUtils.getStorageVolumes(this)

        // Get storage index from intent (passed from MainActivity)
        currentStorageIndex = intent.getIntExtra(EXTRA_STORAGE_INDEX, 0)

        if (storageVolumes.isEmpty()) {
            // Fallback to internal storage
            currentDirectory = Environment.getExternalStorageDirectory()
            storageRootPath = currentDirectory.absolutePath
            loadDirectory(currentDirectory)
            return
        }

        // Ensure valid index
        if (currentStorageIndex >= storageVolumes.size) {
            currentStorageIndex = 0
        }

        // Set current directory to selected storage
        val selectedStorage = storageVolumes[currentStorageIndex]
        currentDirectory = selectedStorage.path
        storageRootPath = currentDirectory.absolutePath

        // Check if we need to navigate to a specific path (e.g. from SearchActivity)
        val initialPath = intent.getStringExtra(EXTRA_INITIAL_PATH)
        val highlightPath = intent.getStringExtra(EXTRA_HIGHLIGHT_ITEM)
        if (initialPath != null) {
            val targetDir = java.io.File(initialPath)
            if (targetDir.exists() && targetDir.isDirectory) {
                // Find which storage volume contains this path
                for (vol in storageVolumes) {
                    if (targetDir.absolutePath.startsWith(vol.path.absolutePath)) {
                        storageRootPath = vol.path.absolutePath
                        break
                    }
                }
                currentDirectory = targetDir
                highlightItemPath = highlightPath
                // Back press goes straight back to SearchActivity
                navigatedFromSearch = intent.getBooleanExtra("from_search", false)
                loadDirectory(targetDir)
                return
            }
        }

        // Load initial directory
        loadDirectory(currentDirectory)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            FileScanner.invalidateCache(currentDirectory)
            loadDirectory(currentDirectory)
        }
    }

    private fun updateBreadcrumb() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()

        // Build path segments from storage root
        val pathSegments = mutableListOf<Pair<String, File>>()

        // Add storage root
        val storageName = if (storageVolumes.isNotEmpty() && currentStorageIndex < storageVolumes.size) {
            storageVolumes[currentStorageIndex].getShortName()
        } else {
            "Storage"
        }
        pathSegments.add(storageName to File(storageRootPath))

        // Add subdirectories
        val relativePath = currentDirectory.absolutePath.removePrefix(storageRootPath)
        if (relativePath.isNotEmpty()) {
            val parts = relativePath.trim('/').split("/")
            var currentPath = storageRootPath
            for (part in parts) {
                if (part.isNotEmpty()) {
                    currentPath = "$currentPath/$part"
                    pathSegments.add(part to File(currentPath))
                }
            }
        }

        // Create breadcrumb views
        for ((index, segment) in pathSegments.withIndex()) {
            val (name, dir) = segment
            val isLast = index == pathSegments.size - 1

            // Add separator (except for first item)
            if (index > 0) {
                val separator = TextView(this).apply {
                    text = " › "
                    setTextColor(ContextCompat.getColor(this@BrowseActivity, R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
                }
                container.addView(separator)
            }

            // Add clickable segment
            val textView = TextView(this).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
                if (isLast) {
                    setTextColor(ContextCompat.getColor(this@BrowseActivity, R.color.primary))
                    setTypeface(null, Typeface.BOLD)
                } else {
                    setTextColor(ContextCompat.getColor(this@BrowseActivity, R.color.text_secondary))
                    // Make clickable
                    setOnClickListener {
                        navigateToBreadcrumb(dir)
                    }
                    // Add ripple effect
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                }
                val hPad = resources.getDimensionPixelSize(R.dimen.space_md)
                val vPad = resources.getDimensionPixelSize(R.dimen.space_xs)
                setPadding(hPad, vPad, hPad, vPad)
            }
            container.addView(textView)
        }

        // Scroll to end
        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun navigateToBreadcrumb(directory: File) {
        if (directory.absolutePath == currentDirectory.absolutePath) return

        // Clear stack up to this directory
        navigationStack.clear()

        // Build stack from storage root to target directory
        var current = File(storageRootPath)
        val targetPath = directory.absolutePath.removePrefix(storageRootPath).trim('/')
        if (targetPath.isNotEmpty()) {
            val parts = targetPath.split("/")
            for (i in 0 until parts.size - 1) {
                navigationStack.push(current)
                current = File(current, parts[i])
            }
        }

        currentDirectory = directory
        loadDirectory(directory)
    }

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(
            onItemClick = { fileItem ->
                if (fileItem.isDirectory) {
                    navigateToDirectory(fileItem.file)
                } else {
                    SelectionManager.setBrowsingDirectory(currentDirectory.absolutePath)
                    SelectionManager.toggleSelection(fileItem, lifecycleScope)
                    fileAdapter.updateSelectionState()
                }
            },
            onItemSelect = { fileItem ->
                SelectionManager.setBrowsingDirectory(currentDirectory.absolutePath)
                SelectionManager.toggleSelection(fileItem, lifecycleScope)
                fileAdapter.updateSelectionState()
            },
            onSelectionModeChanged = { enabled ->
                // Selection mode changed - update UI accordingly
                updateSelectionBar()
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@BrowseActivity)
            adapter = fileAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(30)  // Increased cache for smoother scrolling
            // Disable item animator for faster updates
            itemAnimator = null
        }
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

    private fun setupLoadingOverlay() {
        binding.btnCancelLoading.setOnClickListener {
            // Cancel any ongoing selection (this also restores previous state)
            SelectionManager.cancelSelection()
            loadingJob?.cancel()
            hideLoadingOverlay()

            // Update UI to reflect restored state
            fileAdapter.updateSelectionState()
            updateSelectionBar()
        }
    }

    private fun setupSearch() {
        // Launch SearchActivity when search button clicked
        binding.btnSearch.setOnClickListener {
            launchSearchActivity()
        }
    }

    private fun launchSearchActivity() {
        val intent = Intent(this, SearchActivity::class.java).apply {
            putExtra(SearchActivity.EXTRA_SEARCH_ROOT, currentDirectory.absolutePath)
        }
        startActivityForResult(intent, REQUEST_SEARCH)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    // Item to highlight after navigation
    private var highlightItemPath: String? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SEARCH && resultCode == RESULT_OK) {
            // User clicked on an item in search results - navigate to its location
            data?.getStringExtra(SearchActivity.EXTRA_NAVIGATE_TO_PATH)?.let { path ->
                val targetDir = File(path)
                if (targetDir.exists() && targetDir.isDirectory) {
                    // Get the item to highlight
                    highlightItemPath = data.getStringExtra(SearchActivity.EXTRA_HIGHLIGHT_ITEM)

                    // Mark that we came from search for proper back navigation
                    navigatedFromSearch = true

                    // Push current to stack and navigate
                    navigationStack.push(currentDirectory)
                    currentDirectory = targetDir
                    loadDirectory(targetDir)
                }
            }
        }

        // Update selection bar in case files were added via search
        fileAdapter.updateSelectionState()
        updateSelectionBar()
    }

    /**
     * Scroll to an item and highlight it with a blink animation.
     * Works for both files and folders. Called AFTER DiffUtil commits
     * so getPositionByPath() always finds the item.
     */
    private fun highlightItemInList(itemPath: String) {
        val position = fileAdapter.getPositionByPath(itemPath)
        if (position < 0) return

        // Scroll so the item is visible (offset puts it near the top)
        val layoutManager = binding.recyclerView.layoutManager
        if (layoutManager is LinearLayoutManager) {
            layoutManager.scrollToPositionWithOffset(position, 200)
        } else {
            binding.recyclerView.scrollToPosition(position)
        }

        // Wait for layout to complete, then animate
        binding.recyclerView.post {
            performHighlightAnimation(position, 0)
        }
    }

    /**
     * Blink-highlight the item at the given position.
     * Retries up to 3 times if the ViewHolder isn't laid out yet.
     */
    private fun performHighlightAnimation(position: Int, attempt: Int) {
        val viewHolder = binding.recyclerView.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {
            val view = viewHolder.itemView
            val highlightColor = ContextCompat.getColor(this, R.color.highlight_blink)
            val normalColor = ContextCompat.getColor(this, android.R.color.transparent)

            val handler = Handler(Looper.getMainLooper())
            var blinkCount = 0
            val blinkRunnable = object : Runnable {
                override fun run() {
                    if (blinkCount < 6) {
                        view.setBackgroundColor(if (blinkCount % 2 == 0) highlightColor else normalColor)
                        blinkCount++
                        handler.postDelayed(this, 250)
                    } else {
                        view.setBackgroundColor(normalColor)
                    }
                }
            }
            handler.post(blinkRunnable)
        } else if (attempt < 3) {
            // ViewHolder not yet laid out, retry after a short delay
            binding.recyclerView.postDelayed({
                performHighlightAnimation(position, attempt + 1)
            }, 200)
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

    private fun setupMoreOptions() {
        // Load saved view type
        currentViewType = ViewTypeManager.getViewType(this)
        applyViewType()

        binding.btnMoreOptions.setOnClickListener {
            showOptionsBottomSheet()
        }
    }

    private fun showOptionsBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val hPad = resources.getDimensionPixelSize(R.dimen.space_5xl)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val topPad = resources.getDimensionPixelSize(R.dimen.space_3xl)
            setPadding(0, topPad, 0, topPad)
        }

        // --- View section ---
        container.addView(createSheetSectionTitle("View"))

        val viewOptions = arrayOf(
            "List" to ViewTypeManager.ViewType.LIST,
            "Grid" to ViewTypeManager.ViewType.GRID
        )
        for ((label, viewType) in viewOptions) {
            val isSelected = viewType == currentViewType
            container.addView(createSheetItem(label, isSelected) {
                if (viewType != currentViewType) {
                    currentViewType = ViewTypeManager.cycleViewType(this)
                    applyViewType()
                }
                bottomSheet.dismiss()
            })
        }

        // --- Divider ---
        container.addView(createSheetDivider())

        // --- Sort section ---
        container.addView(createSheetSectionTitle("Sort by"))

        val sortOptions = arrayOf(
            "Name (A-Z)" to SortType.NAME_ASC,
            "Name (Z-A)" to SortType.NAME_DESC,
            "Size (Largest)" to SortType.SIZE_DESC,
            "Size (Smallest)" to SortType.SIZE_ASC,
            "Date (Newest)" to SortType.DATE_DESC,
            "Date (Oldest)" to SortType.DATE_ASC
        )
        for ((label, sortType) in sortOptions) {
            val isSelected = sortType == currentSortType
            container.addView(createSheetItem(label, isSelected) {
                if (sortType != currentSortType) {
                    currentSortType = sortType
                    loadDirectory(currentDirectory)
                    binding.recyclerView.scrollToPosition(0)
                }
                bottomSheet.dismiss()
            })
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
            setTextColor(ContextCompat.getColor(this@BrowseActivity, R.color.text_secondary))
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
            setBackgroundColor(ContextCompat.getColor(this@BrowseActivity, R.color.divider))
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
                setTextColor(ContextCompat.getColor(this@BrowseActivity,
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

    private fun setupButtons() {
        binding.btnSelectAll.setOnClickListener {
            SelectionManager.setBrowsingDirectory(currentDirectory.absolutePath)
            val items = fileAdapter.getItems()
            SelectionManager.selectAllInList(items, lifecycleScope)
            fileAdapter.updateSelectionState()
        }

        binding.btnDeselectAll.setOnClickListener {
            SelectionManager.deselectDirectChildrenOf(currentDirectory)
            fileAdapter.updateSelectionState()
            updateSelectionBar()
        }

        binding.btnReadyToSend.setOnClickListener {
            if (SelectionManager.hasSelection()) {
                startActivity(Intent(this, ModeSelectionActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }

        // Selection info area - click to edit selection
        binding.selectionInfoArea.setOnClickListener {
            if (SelectionManager.hasSelection()) {
                startActivity(Intent(this, SelectionActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }

    private fun applyViewType() {
        val layoutManager = when (currentViewType) {
            ViewTypeManager.ViewType.LIST -> LinearLayoutManager(this)
            ViewTypeManager.ViewType.GRID -> GridLayoutManager(this, resources.getInteger(R.integer.grid_span_count))
        }
        binding.recyclerView.layoutManager = layoutManager
        fileAdapter.setViewType(currentViewType)
    }

    /**
     * Show toast with debouncing - same message won't show again within 3 seconds
     */
    private fun showDebouncedToast(message: String) {
        val now = System.currentTimeMillis()
        if (message != lastToastMessage || now - lastToastTime > TOAST_DEBOUNCE_MS) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            lastToastTime = now
            lastToastMessage = message
        }
    }

    private fun navigateToDirectory(directory: File) {
        // Check if directory is accessible before navigating
        if (!FileScanner.isDirectoryAccessible(directory)) {
            showDebouncedToast("Cannot access this folder")
            return
        }

        navigationStack.push(currentDirectory)
        currentDirectory = directory
        loadDirectory(directory)
    }

    private fun loadDirectory(directory: File) {
        currentDirectory = directory
        updateBreadcrumb()

        // Cancel any ongoing loading
        loadingJob?.cancel()
        cancelIdlePreload()
        ThumbnailLoader.cancelPreload()

        // Show loading state (inline, no overlay)
        showLoadingState()

        // Load files asynchronously with proper error handling
        loadingJob = lifecycleScope.launch {
            try {
                val files = try {
                    FileScanner.listFilesAsync(directory, currentSortType, FilterType.ALL)
                } catch (e: CancellationException) {
                    throw e // Re-throw cancellation
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading directory: ${e.message}")
                    emptyList()
                }

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    try {
                        // Check if we need to highlight an item from search
                        val pathToHighlight = highlightItemPath
                        highlightItemPath = null

                        fileAdapter.setItems(files) {
                            // This runs AFTER DiffUtil commits the new list
                            if (pathToHighlight != null) {
                                highlightItemInList(pathToHighlight)
                            } else {
                                binding.recyclerView.scrollToPosition(0)
                            }
                        }

                        if (files.isEmpty()) {
                            showEmptyState(directory)
                        } else {
                            showContentState()
                        }

                        // If thumbnails enabled and has media, start preload
                        if (thumbnailsEnabled && fileAdapter.hasMediaFiles()) {
                            scheduleIdlePreload()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating UI: ${e.message}")
                        showErrorState("Error displaying files")
                    }
                }
            } catch (e: CancellationException) {
                // Job was cancelled, ignore
                Log.d(TAG, "Loading cancelled for: ${directory.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}")
                withContext(Dispatchers.Main) {
                    showErrorState("Error loading folder")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    // Only hide loading if this is still the active job
                    // (prevents cancelled job from hiding overlay for a new load)
                    if (loadingJob?.isActive != true) {
                        hideLoadingState()
                        hideLoadingOverlay()
                    }
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun showLoadingState() {
        isLoading = true
        // Removed alpha change - it triggers expensive redraws and causes lag
        binding.tvEmptyState.visibility = View.GONE
    }

    private fun hideLoadingState() {
        isLoading = false
    }

    private fun showContentState() {
        binding.tvEmptyState.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
    }

    private fun showEmptyState(directory: File) {
        binding.recyclerView.visibility = View.GONE
        binding.tvEmptyState.visibility = View.VISIBLE

        binding.tvEmptyState.text = when {
            !directory.exists() -> "Folder does not exist"
            !directory.canRead() -> "Cannot access this folder"
            else -> "This folder is empty"
        }
    }

    private fun showErrorState(message: String) {
        binding.recyclerView.visibility = View.GONE
        binding.tvEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = message
    }

    private fun updateSelectionBar() {
        val globalCount = SelectionManager.getSelectedCount()
        val hasSelectionInCurrentDir = SelectionManager.hasSelectionInDirectory(currentDirectory)

        if (globalCount > 0) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = SelectionManager.getSelectionSummary()
            binding.tvSelectionHint.visibility = View.VISIBLE
            binding.btnReadyToSend.isEnabled = true

            // Show both Select All and Deselect side by side
            binding.selectActionBar.visibility = View.VISIBLE
        } else {
            // Hide both bars when nothing is selected
            binding.selectionBar.visibility = View.GONE
            binding.selectActionBar.visibility = View.GONE
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

        if (isLoading) {
            // Cancel loading and go back
            loadingJob?.cancel()
        }

        // If we came from search, go back to search activity (preserves search state)
        if (navigatedFromSearch) {
            navigatedFromSearch = false
            // Clear the navigation stack since we're going back to search
            navigationStack.clear()
            super.onBackPressed()
            return
        }

        if (navigationStack.isNotEmpty()) {
            currentDirectory = navigationStack.pop()
            loadDirectory(currentDirectory)
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh selection state when returning from SelectionActivity or other screens
        fileAdapter.updateSelectionState()
        updateSelectionBar()
    }

    override fun onSelectionChanged(count: Int, totalSize: Long) {
        runOnUiThread {
            // Exit selection mode when all items are deselected
            if (!SelectionManager.hasSelection() && fileAdapter.isSelectionMode) {
                fileAdapter.setSelectionMode(false)
            }
            fileAdapter.updateSelectionState()
            updateSelectionBar()
        }
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
            binding.btnDeselectAll.isEnabled = true
        }
    }
}
