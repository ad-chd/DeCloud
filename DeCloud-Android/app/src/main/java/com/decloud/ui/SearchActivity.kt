package com.decloud.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.decloud.R
import com.decloud.databinding.ActivitySearchBinding
import com.decloud.model.FileItem
import com.decloud.model.SelectionManager
import com.decloud.ui.adapter.SearchResultSelectableAdapter
import com.decloud.util.FileScanner
import com.decloud.util.StorageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dedicated search activity with full-screen search experience
 * Features:
 * - Live search as user types (with debounce)
 * - Case-INSENSITIVE by default (case sensitive in advanced options)
 * - Advanced search filters (type, size, date, storage)
 * - Select files AND folders
 * - Navigate to item location with highlight
 * - Add selected items to transfer queue
 */
class SearchActivity : BaseActivity() {

    companion object {
        private const val TAG = "SearchActivity"
        const val EXTRA_SEARCH_ROOT = "search_root"
        const val EXTRA_NAVIGATE_TO_PATH = "navigate_to_path"
        const val EXTRA_HIGHLIGHT_ITEM = "highlight_item"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MAX_SEARCH_RESULTS = 500
    }

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: SearchResultSelectableAdapter

    // True when launched from the home screen (no scoped directory).
    // In this mode the storage filter omits the "Current directory" entry.
    private val isGlobalSearch: Boolean
        get() = !intent.hasExtra(EXTRA_SEARCH_ROOT)

    private fun buildStorageOptions(): List<String> {
        val options = mutableListOf<String>()
        if (!isGlobalSearch) options.add("Current directory")
        storageVolumes.forEach { options.add(it.getShortName()) }
        return options
    }

    private fun storageRootFor(position: Int): File {
        if (!isGlobalSearch && position == 0) {
            return intent.getStringExtra(EXTRA_SEARCH_ROOT)?.let { File(it) }
                ?: Environment.getExternalStorageDirectory()
        }
        val volumeIndex = if (isGlobalSearch) position else position - 1
        return storageVolumes.getOrNull(volumeIndex)?.path
            ?: Environment.getExternalStorageDirectory()
    }

    // All selectable option indices for the storage filter (0..optionCount-1).
    private fun allStorageIndices(): Set<Int> = buildStorageOptions().indices.toSet()

    // Current search roots derived from the selected storage indices.
    // De-duplicates by absolute path so "Current directory" under "Internal" doesn't double-search.
    private fun currentSearchRoots(): List<File> {
        val seen = HashSet<String>()
        val roots = mutableListOf<File>()
        for (idx in selectedStorageIndices.sorted()) {
            val root = storageRootFor(idx)
            if (seen.add(root.absolutePath)) roots.add(root)
        }
        if (roots.isEmpty()) {
            // Safety net: never run with an empty scope.
            roots.add(storageRootFor(0))
        }
        return roots
    }

    private fun scopeDisplayText(): String {
        val options = buildStorageOptions()
        val names = selectedStorageIndices.sorted().mapNotNull { options.getOrNull(it) }
        if (names.isEmpty()) return getDisplayPath(storageRootFor(0))
        return names.joinToString(", ")
    }
    private var searchJob: Job? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    // Advanced search options (case insensitive by default)
    private var isCaseSensitive = false
    private var isAdvancedPanelVisible = false
    private var hasFilterChanges = false

    // Filter options
    enum class TypeFilter { ALL, IMAGES, VIDEOS, AUDIO, OTHER }
    enum class SizeFilter { ANY, SMALL, MEDIUM, LARGE, HUGE, LARGEST_FIRST, SMALLEST_FIRST }
    enum class DateFilter { ANY, TODAY, WEEK, MONTH, YEAR }
    enum class DepthFilter { NORMAL, DEEP, UNLIMITED }

    // Current preview filters (what user sees while adjusting)
    private var typeFilter = TypeFilter.ALL
    private var sizeFilter = SizeFilter.ANY
    private var dateFilter = DateFilter.ANY
    private var depthFilter = DepthFilter.NORMAL
    // Storage filter is multi-select: a set of option indices from buildStorageOptions().
    // Default: all options ticked — user narrows from there.
    private val selectedStorageIndices: MutableSet<Int> = mutableSetOf()

    // Committed filters (applied when user clicks "Apply")
    private var committedTypeFilter = TypeFilter.ALL
    private var committedSizeFilter = SizeFilter.ANY
    private var committedDateFilter = DateFilter.ANY
    private var committedDepthFilter = DepthFilter.NORMAL
    private var committedCaseSensitive = false
    private var committedStorageIndices: Set<Int> = emptySet()

    // Storage volumes
    private var storageVolumes: List<StorageUtils.StorageInfo> = emptyList()

    // Track current search query for re-search on filter change
    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get storage volumes first — needed to compute storage options.
        storageVolumes = StorageUtils.getStorageVolumes(this)

        // Default: every storage option is ticked. User narrows the scope from the filter sheet.
        selectedStorageIndices.addAll(allStorageIndices())
        committedStorageIndices = selectedStorageIndices.toSet()

        setupAdapter()
        setupSearchInput()
        setupButtons()
        setupAdvancedSearch()

        // Initial chip label + scope text reflect the "everything selected" default.
        updateStorageChipUI()

        // Show initial state
        showInitialState()

        // Focus search input and show keyboard
        binding.etSearch.requestFocus()
        binding.etSearch.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        // Reset all filters when exiting search
        resetAllFiltersQuietly()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // If advanced panel is open, close it first (this will restore committed filters)
        if (isAdvancedPanelVisible) {
            toggleAdvancedPanel()
            return
        }
        // If there are uncommitted filter changes when leaving the activity, restore them
        if (hasFilterChanges) {
            restoreCommittedFilters()
        }
        super.onBackPressed()
    }

    private fun setupAdapter() {
        adapter = SearchResultSelectableAdapter(
            onItemClick = { item ->
                // Item was selected/deselected - update UI
                updateSelectionUI()
            },
            onNavigateToItem = { item ->
                // Navigate to item's parent folder and highlight it
                navigateToItem(item)
            },
            onSelectionChanged = { count ->
                updateSelectionUI()
            }
        )

        binding.recyclerResults.apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            adapter = this@SearchActivity.adapter
            setHasFixedSize(true)
            itemAnimator = null
        }
    }

    private fun setupSearchInput() {
        // Live search with debounce
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                currentQuery = query

                // Cancel previous search
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchJob?.cancel()

                // Show/hide clear button
                binding.btnClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

                if (query.isEmpty()) {
                    showInitialState()
                    return
                }

                // Debounce search
                searchRunnable = Runnable {
                    executeSearch(query)
                }
                searchHandler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
            }
        })

        // Handle keyboard search action
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    // Cancel debounce and search immediately
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }
                    executeSearch(query)
                }
                // Hide keyboard
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun setupButtons() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Clear button
        binding.btnClear.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        // Advanced search toggle
        binding.btnAdvancedSearch.setOnClickListener {
            toggleAdvancedPanel()
        }

        // Select All
        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
            updateSelectionUI()
        }

        // Deselect All
        binding.btnDeselectAll.setOnClickListener {
            adapter.deselectAll()
            updateSelectionUI()
        }

        // Add to Transfer
        binding.btnAddToTransfer.setOnClickListener {
            addSelectedToTransfer()
        }
    }

    private fun setupAdvancedSearch() {
        // Reset button - clears all filters to default
        binding.btnResetFilters.setOnClickListener {
            resetAllFilters()
        }

        // Close button - dismiss the filter panel
        binding.btnCloseFilters.setOnClickListener {
            toggleAdvancedPanel()
        }

        // Apply button - commits filter changes
        binding.btnApplyFilters.setOnClickListener {
            // Commit current filters as the new baseline
            commitCurrentFilters()
            hasFilterChanges = false
            binding.btnApplyFilters.isEnabled = false
            toggleAdvancedPanel()  // Close panel after applying
            reSearchIfNeeded()
        }

        // Case sensitive checkbox (in advanced panel)
        binding.cbCaseSensitive.setOnCheckedChangeListener { _, isChecked ->
            isCaseSensitive = isChecked
            markFilterChanged()
        }

        // Type filter chip
        binding.chipTypeFilter.setOnClickListener {
            val options = arrayOf("All", "Images", "Videos", "Audio", "Other")
            showFilterBottomSheet("Type", options, typeFilter.ordinal) { position ->
                typeFilter = TypeFilter.values()[position]
                binding.chipTypeFilter.text = options[position]
                updateChipHighlight(binding.chipTypeFilter, position != 0)
                markFilterChanged()
            }
        }

        // Size filter chip
        binding.chipSizeFilter.setOnClickListener {
            val options = arrayOf("Any size", "Small (<1 MB)", "Medium (1-100 MB)", "Large (100 MB-1 GB)", "Huge (>1 GB)", "Largest first", "Smallest first")
            showFilterBottomSheet("Size", options, sizeFilter.ordinal) { position ->
                sizeFilter = SizeFilter.values()[position]
                binding.chipSizeFilter.text = options[position]
                updateChipHighlight(binding.chipSizeFilter, position != 0)
                markFilterChanged()
            }
        }

        // Date filter chip
        binding.chipDateFilter.setOnClickListener {
            val options = arrayOf("Any time", "Today", "This week", "This month", "This year")
            showFilterBottomSheet("Date", options, dateFilter.ordinal) { position ->
                dateFilter = DateFilter.values()[position]
                binding.chipDateFilter.text = options[position]
                updateChipHighlight(binding.chipDateFilter, position != 0)
                markFilterChanged()
            }
        }

        // Storage filter chip — multi-select. User can tick several storages at once;
        // by default everything is ticked and the user narrows the scope.
        binding.chipStorageFilter.setOnClickListener {
            showMultiSelectStorageSheet()
        }

        // Depth filter chip
        binding.chipDepthFilter.setOnClickListener {
            val options = arrayOf("Normal", "Deep", "Deepest (slower)")
            showFilterBottomSheet("Depth", options, depthFilter.ordinal) { position ->
                depthFilter = DepthFilter.values()[position]
                binding.chipDepthFilter.text = options[position]
                updateChipHighlight(binding.chipDepthFilter, position != 0)
                markFilterChanged()
            }
        }
    }

    private fun showFilterBottomSheet(
        title: String,
        options: Array<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val bottomSheet = BottomSheetDialog(this)
        val hPad = resources.getDimensionPixelSize(R.dimen.space_5xl)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val topPad = resources.getDimensionPixelSize(R.dimen.space_3xl)
            setPadding(0, topPad, 0, topPad)
        }

        // Title
        container.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.text_secondary))
            isAllCaps = true
            letterSpacing = 0.05f
            setPadding(hPad, resources.getDimensionPixelSize(R.dimen.space_3xl), hPad, resources.getDimensionPixelSize(R.dimen.space_md))
        })

        // Options
        for ((index, label) in options.withIndex()) {
            val isSelected = index == selectedIndex
            container.addView(createSheetItem(label, isSelected) {
                onSelected(index)
                bottomSheet.dismiss()
            })
        }

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }

    /**
     * Storage filter bottom sheet — multi-select.
     * Working copy of the selection lives in the sheet; committed to state only on Done.
     * At least one option must remain ticked (otherwise the search has no scope).
     */
    private fun showMultiSelectStorageSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val hPad = resources.getDimensionPixelSize(R.dimen.space_5xl)
        val options = buildStorageOptions()
        val working = HashSet(selectedStorageIndices)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val topPad = resources.getDimensionPixelSize(R.dimen.space_3xl)
            setPadding(0, topPad, 0, topPad)
        }

        container.addView(TextView(this).apply {
            text = "Storage"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.text_secondary))
            isAllCaps = true
            letterSpacing = 0.05f
            setPadding(hPad, resources.getDimensionPixelSize(R.dimen.space_3xl), hPad, resources.getDimensionPixelSize(R.dimen.space_md))
        })

        val rows = arrayOfNulls<LinearLayout>(options.size)
        // The toggle handler is shared across rows; captures `working` and the row slot.
        lateinit var buildRow: (Int) -> LinearLayout
        buildRow = { index ->
            val label = options[index]
            createSheetItem(label, index in working) {
                if (index in working) {
                    if (working.size == 1) {
                        Toast.makeText(this, "Select at least one storage", Toast.LENGTH_SHORT).show()
                        return@createSheetItem
                    }
                    working.remove(index)
                } else {
                    working.add(index)
                }
                // Swap the row with a fresh one to refresh the tick state.
                val old = rows[index]!!
                val pos = container.indexOfChild(old)
                val replacement = buildRow(index)
                rows[index] = replacement
                container.removeViewAt(pos)
                container.addView(replacement, pos)
            }
        }
        for (index in options.indices) {
            val row = buildRow(index)
            rows[index] = row
            container.addView(row)
        }

        // Done button
        val doneBtn = TextView(this).apply {
            text = "Done"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body_large))
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.primary))
            gravity = android.view.Gravity.CENTER
            val vPad = resources.getDimensionPixelSize(R.dimen.space_3xl)
            setPadding(hPad, vPad, hPad, vPad)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener {
                commitStorageSelection(working)
                bottomSheet.dismiss()
            }
        }
        container.addView(doneBtn)

        // Commit on dismiss as well — tapping outside shouldn't throw away the choices.
        bottomSheet.setOnDismissListener { commitStorageSelection(working) }

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }

    private fun commitStorageSelection(working: Set<Int>) {
        if (working == selectedStorageIndices) return
        selectedStorageIndices.clear()
        selectedStorageIndices.addAll(working)
        updateStorageChipUI()
        markFilterChanged()
    }

    private fun updateStorageChipUI() {
        val options = buildStorageOptions()
        val label = when {
            selectedStorageIndices.size == options.size -> "All storages"
            selectedStorageIndices.size == 1 -> options.getOrNull(selectedStorageIndices.first()) ?: "Storage"
            else -> "${selectedStorageIndices.size} locations"
        }
        binding.chipStorageFilter.text = label
        binding.tvSearchScope.text = "Searching in: ${scopeDisplayText()}"
        // Highlight whenever the selection is not the default "everything".
        val isDefault = selectedStorageIndices.size == options.size
        updateChipHighlight(binding.chipStorageFilter, !isDefault)
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
                setTextColor(ContextCompat.getColor(this@SearchActivity,
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

    private fun updateChipHighlight(chip: TextView, isActive: Boolean) {
        chip.setTextColor(ContextCompat.getColor(this,
            if (isActive) R.color.primary else R.color.text_primary
        ))
    }

    private fun updateChipTexts() {
        val typeOptions = arrayOf("All", "Images", "Videos", "Audio", "Other")
        val sizeOptions = arrayOf("Any size", "Small (<1 MB)", "Medium (1-100 MB)", "Large (100 MB-1 GB)", "Huge (>1 GB)", "Largest first", "Smallest first")
        val dateOptions = arrayOf("Any time", "Today", "This week", "This month", "This year")
        val depthOptions = arrayOf("Normal", "Deep", "Deepest (slower)")

        binding.chipTypeFilter.text = typeOptions[typeFilter.ordinal]
        binding.chipSizeFilter.text = sizeOptions[sizeFilter.ordinal]
        binding.chipDateFilter.text = dateOptions[dateFilter.ordinal]
        binding.chipDepthFilter.text = depthOptions[depthFilter.ordinal]

        updateStorageChipUI()

        updateChipHighlight(binding.chipTypeFilter, typeFilter != TypeFilter.ALL)
        updateChipHighlight(binding.chipSizeFilter, sizeFilter != SizeFilter.ANY)
        updateChipHighlight(binding.chipDateFilter, dateFilter != DateFilter.ANY)
        updateChipHighlight(binding.chipDepthFilter, depthFilter != DepthFilter.NORMAL)
    }

    private fun markFilterChanged() {
        hasFilterChanges = true
        binding.btnApplyFilters.isEnabled = true
        updateActiveFiltersText()
        // Still show live preview of results
        reSearchIfNeeded()
    }

    private fun toggleAdvancedPanel() {
        isAdvancedPanelVisible = !isAdvancedPanelVisible

        if (isAdvancedPanelVisible) {
            binding.advancedSearchPanel.visibility = View.VISIBLE
            binding.dividerAdvanced.visibility = View.VISIBLE
            binding.btnAdvancedSearch.setColorFilter(getColor(R.color.warning))
        } else {
            binding.advancedSearchPanel.visibility = View.GONE
            binding.dividerAdvanced.visibility = View.GONE
            binding.btnAdvancedSearch.setColorFilter(getColor(R.color.text_secondary))

            // If panel is closed without clicking Apply, restore committed filters
            if (hasFilterChanges) {
                restoreCommittedFilters()
            }
        }
    }

    /**
     * Restore filters to the last committed state
     * Called when panel is closed without clicking Apply
     */
    private fun restoreCommittedFilters() {
        // Restore filter values
        typeFilter = committedTypeFilter
        sizeFilter = committedSizeFilter
        dateFilter = committedDateFilter
        depthFilter = committedDepthFilter
        isCaseSensitive = committedCaseSensitive
        selectedStorageIndices.clear()
        selectedStorageIndices.addAll(committedStorageIndices)

        // Update UI elements to match committed state
        updateChipTexts()
        binding.cbCaseSensitive.isChecked = committedCaseSensitive

        // Clear changes flag and disable Apply button
        hasFilterChanges = false
        binding.btnApplyFilters.isEnabled = false

        // Update active filters text
        updateActiveFiltersText()

        // Re-search with committed filters
        reSearchIfNeeded()
    }

    /**
     * Commit current filters as the new baseline
     * Called when user clicks Apply
     */
    private fun commitCurrentFilters() {
        committedTypeFilter = typeFilter
        committedSizeFilter = sizeFilter
        committedDateFilter = dateFilter
        committedDepthFilter = depthFilter
        committedCaseSensitive = isCaseSensitive
        committedStorageIndices = selectedStorageIndices.toSet()
    }

    private fun resetAllFilters() {
        // Reset all filter values to defaults
        typeFilter = TypeFilter.ALL
        sizeFilter = SizeFilter.ANY
        dateFilter = DateFilter.ANY
        depthFilter = DepthFilter.NORMAL
        isCaseSensitive = false

        // Storage default: every option ticked.
        selectedStorageIndices.clear()
        selectedStorageIndices.addAll(allStorageIndices())

        // Also reset committed filters
        committedTypeFilter = TypeFilter.ALL
        committedSizeFilter = SizeFilter.ANY
        committedDateFilter = DateFilter.ANY
        committedDepthFilter = DepthFilter.NORMAL
        committedCaseSensitive = false
        committedStorageIndices = selectedStorageIndices.toSet()

        // Update UI elements
        binding.cbCaseSensitive.isChecked = false
        updateChipTexts()

        // Clear active filters text
        binding.tvActiveFilters.visibility = View.GONE

        // Mark as no changes and disable Apply button
        hasFilterChanges = false
        binding.btnApplyFilters.isEnabled = false

        // Re-search with reset filters
        reSearchIfNeeded()

        Toast.makeText(this, "Filters reset", Toast.LENGTH_SHORT).show()
    }

    /**
     * Reset filters without showing toast or triggering search
     * Used when exiting the search activity
     */
    private fun resetAllFiltersQuietly() {
        typeFilter = TypeFilter.ALL
        sizeFilter = SizeFilter.ANY
        dateFilter = DateFilter.ANY
        depthFilter = DepthFilter.NORMAL
        isCaseSensitive = false
        selectedStorageIndices.clear()
        selectedStorageIndices.addAll(allStorageIndices())
        hasFilterChanges = false

        // Also reset committed filters
        committedTypeFilter = TypeFilter.ALL
        committedSizeFilter = SizeFilter.ANY
        committedDateFilter = DateFilter.ANY
        committedDepthFilter = DepthFilter.NORMAL
        committedCaseSensitive = false
        committedStorageIndices = selectedStorageIndices.toSet()
    }

    private fun reSearchIfNeeded() {
        if (currentQuery.isNotEmpty()) {
            searchRunnable?.let { searchHandler.removeCallbacks(it) }
            searchJob?.cancel()
            executeSearch(currentQuery)
        }
        updateActiveFiltersText()
    }

    private fun updateActiveFiltersText() {
        val filters = mutableListOf<String>()

        if (typeFilter != TypeFilter.ALL) {
            filters.add(when (typeFilter) {
                TypeFilter.IMAGES -> "Images"
                TypeFilter.VIDEOS -> "Videos"
                TypeFilter.AUDIO -> "Audio"
                TypeFilter.OTHER -> "Other files"
                else -> ""
            })
        }
        if (isCaseSensitive) filters.add("Case sensitive")
        if (sizeFilter != SizeFilter.ANY) {
            filters.add(when (sizeFilter) {
                SizeFilter.SMALL -> "<1 MB"
                SizeFilter.MEDIUM -> "1-100 MB"
                SizeFilter.LARGE -> "100 MB-1 GB"
                SizeFilter.HUGE -> ">1 GB"
                SizeFilter.LARGEST_FIRST -> "Largest first"
                SizeFilter.SMALLEST_FIRST -> "Smallest first"
                else -> ""
            })
        }
        if (dateFilter != DateFilter.ANY) {
            filters.add(when (dateFilter) {
                DateFilter.TODAY -> "Today"
                DateFilter.WEEK -> "This week"
                DateFilter.MONTH -> "This month"
                DateFilter.YEAR -> "This year"
                else -> ""
            })
        }
        if (depthFilter != DepthFilter.NORMAL) {
            filters.add(when (depthFilter) {
                DepthFilter.DEEP -> "Deep search"
                DepthFilter.UNLIMITED -> "Unlimited depth"
                else -> ""
            })
        }

        if (filters.isNotEmpty()) {
            binding.tvActiveFilters.text = "Filters: ${filters.joinToString(", ")}"
            binding.tvActiveFilters.visibility = View.VISIBLE
        } else {
            binding.tvActiveFilters.visibility = View.GONE
        }
    }

    private fun executeSearch(query: String) {
        searchJob?.cancel()

        // Show searching status
        binding.statusBar.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Searching..."
        binding.emptyState.visibility = View.GONE

        // Clear previous selection when new search
        adapter.deselectAll()

        // Convert query to lowercase for case-insensitive search (default)
        val searchQuery = if (isCaseSensitive) query else query.lowercase()

        // Get max depth based on filter
        val maxDepth = when (depthFilter) {
            DepthFilter.NORMAL -> 10
            DepthFilter.DEEP -> 25
            DepthFilter.UNLIMITED -> 100  // Effectively unlimited
        }

        searchJob = lifecycleScope.launch {
            try {
                // Multi-root: iterate over every selected storage, merging results with path-dedup
                // so overlapping roots (e.g. "Current directory" nested under "Internal") don't
                // double-count. Each root streams partial results — we forward them to the UI
                // as they arrive, deferring the final "isComplete" signal until the last root finishes.
                val roots = currentSearchRoots()
                val seenPaths = HashSet<String>()
                val merged = mutableListOf<FileItem>()

                for ((rootIndex, root) in roots.withIndex()) {
                    val isLastRoot = rootIndex == roots.lastIndex
                    FileScanner.liveSearchAsyncWithFilters(
                        directory = root,
                        query = searchQuery,
                        caseSensitive = isCaseSensitive,
                        typeFilter = typeFilter,
                        sizeFilter = sizeFilter,
                        dateFilter = dateFilter,
                        maxDepth = maxDepth,
                        maxResults = MAX_SEARCH_RESULTS
                    ) { results, isComplete ->
                        // Merge in new items for this root, deduping by path across all roots so far.
                        for (item in results) {
                            val key = item.file.absolutePath
                            if (seenPaths.add(key)) merged.add(item)
                            if (merged.size >= MAX_SEARCH_RESULTS) break
                        }
                        val snapshot = merged.toList()
                        val effectiveComplete = isComplete && isLastRoot
                        runOnUiThread {
                            if (snapshot.isNotEmpty()) {
                                binding.emptyState.visibility = View.GONE
                                binding.recyclerResults.visibility = View.VISIBLE
                                val sortedResults = sortSearchResults(snapshot, query)
                                adapter.submitList(sortedResults)
                                binding.recyclerResults.scrollToPosition(0)
                                binding.tvStatus.text =
                                    "${snapshot.size} results${if (!effectiveComplete) "..." else ""}"
                            }

                            if (effectiveComplete) {
                                binding.progressBar.visibility = View.GONE
                                if (snapshot.isEmpty()) {
                                    showNoResultsState(query)
                                } else {
                                    binding.statusBar.visibility = View.GONE
                                }
                                updateSelectionUI()
                            }
                        }
                    }
                    if (merged.size >= MAX_SEARCH_RESULTS) break
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Search cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Search error: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Search failed"
                }
            }
        }
    }

    private fun sortSearchResults(results: List<FileItem>, query: String): List<FileItem> {
        // Always use lowercase for sorting comparison
        val queryLower = query.lowercase()

        // Check if sorting by size
        return when (sizeFilter) {
            SizeFilter.LARGEST_FIRST -> {
                results.sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }
                        .thenByDescending { it.size }
                )
            }
            SizeFilter.SMALLEST_FIRST -> {
                results.sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }
                        .thenBy { it.size }
                )
            }
            else -> {
                // Default sort: folders first, then by match quality
                results.sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }
                        .thenByDescending {
                            it.name.lowercase().startsWith(queryLower)
                        }
                        .thenBy {
                            it.name.lowercase()
                        }
                )
            }
        }
    }

    private fun showInitialState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.recyclerResults.visibility = View.GONE
        binding.statusBar.visibility = View.GONE
        binding.selectionBar.visibility = View.GONE

        binding.ivEmptyIcon.setImageResource(R.drawable.ic_search)
        binding.tvEmptyTitle.text = "Search for files"
        binding.tvEmptySubtitle.text = "Type to start searching"

        adapter.submitList(emptyList())
    }

    private fun showNoResultsState(query: String) {
        binding.emptyState.visibility = View.VISIBLE
        binding.recyclerResults.visibility = View.GONE
        binding.statusBar.visibility = View.GONE
        binding.selectionBar.visibility = View.GONE

        binding.ivEmptyIcon.setImageResource(R.drawable.ic_search)
        binding.tvEmptyTitle.text = "No results found"
        binding.tvEmptySubtitle.text = "Try a different search term or adjust filters"
    }

    private fun updateSelectionUI() {
        val count = adapter.getSelectedCount()
        val totalItems = adapter.currentList.size

        if (count > 0) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.btnAddToTransfer.text = "Add $count to Transfer"
            binding.btnSelectAll.visibility = if (count < totalItems) View.VISIBLE else View.GONE
        } else {
            binding.selectionBar.visibility = View.GONE
        }
    }

    private fun addSelectedToTransfer() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "No items selected", Toast.LENGTH_SHORT).show()
            return
        }

        // Add to global selection manager
        var addedCount = 0
        for (item in selectedItems) {
            if (!SelectionManager.isSelected(item)) {
                if (item.isDirectory) {
                    // For folders, select all files inside recursively
                    SelectionManager.selectAllInDirectory(item.file)
                } else {
                    SelectionManager.selectFile(item.path)
                }
                addedCount++
            }
        }

        if (addedCount > 0) {
            Toast.makeText(this, "Added $addedCount items to transfer", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Items already in transfer queue", Toast.LENGTH_SHORT).show()
        }

        // Clear local selection
        adapter.deselectAll()
        updateSelectionUI()
    }

    private fun navigateToItem(item: FileItem) {
        // Navigate to item's parent folder and highlight the item
        val parentFolder = item.file.parentFile ?: return

        // Launch BrowseActivity directly — keeps SearchActivity on the back stack
        // so pressing back returns to search results
        val intent = Intent(this, BrowseActivity::class.java).apply {
            putExtra(BrowseActivity.EXTRA_INITIAL_PATH, parentFolder.absolutePath)
            putExtra(BrowseActivity.EXTRA_HIGHLIGHT_ITEM, item.path)
            putExtra("from_search", true)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun getDisplayPath(file: File): String {
        return try {
            val internalRoot = Environment.getExternalStorageDirectory().absolutePath
            val path = file.absolutePath

            if (path.startsWith(internalRoot)) {
                "Internal Storage" + path.substring(internalRoot.length)
            } else {
                path
            }
        } catch (e: Exception) {
            file.absolutePath
        }
    }
}
