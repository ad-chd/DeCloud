package com.decloud.ui

import android.content.Intent
import android.os.Bundle
import android.view.View

import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.decloud.databinding.ActivitySelectionBinding
import com.decloud.model.FileItem
import com.decloud.model.SelectionManager
import com.decloud.ui.adapter.BackupCategoryAdapter
import com.decloud.ui.adapter.CategoryDisplayItem
import com.decloud.util.BackupCategory
import com.decloud.util.CategoryScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Activity to view and edit the current selection by category.
 * Groups selected files into categories (Images, Videos, Audio, Documents, Other).
 * Unchecking a category removes all files in that category from the selection.
 */
class SelectionActivity : BaseActivity(), SelectionManager.SelectionListener {

    companion object {
        const val EXTRA_EDIT_ONLY = "edit_only"
    }

    private lateinit var binding: ActivitySelectionBinding
    private lateinit var adapter: BackupCategoryAdapter

    private var editOnly = false
    private var isRemoving = false
    private var suppressListenerUpdates = false

    // Track which file paths belong to each category
    private val categoryFiles = mutableMapOf<BackupCategory, MutableList<String>>()

    // Track which categories have been unchecked (files removed)
    private val removedCategories = mutableSetOf<BackupCategory>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editOnly = intent.getBooleanExtra(EXTRA_EDIT_ONLY, false)

        setupToolbar()
        setupRecyclerView()
        setupButtons()

        SelectionManager.addListener(this)
        loadCategoryViewAsync()
    }

    override fun onResume() {
        super.onResume()
        if (!isRemoving) {
            rebuildCategoryViewAsync()
            updateSummary()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SelectionManager.removeListener(this)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (!isRemoving) finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isRemoving) return  // Block back during removal
        super.onBackPressed()
    }

    private fun setupRecyclerView() {
        adapter = BackupCategoryAdapter(
            onCheckChanged = { category, isChecked ->
                if (!isChecked && !isRemoving) {
                    removeCategory(category)
                }
            },
            onRowClicked = { category ->
                if (!isRemoving) {
                    navigateToCategoryDetail(category)
                }
            }
        )
        adapter.showChevron = true

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@SelectionActivity)
            adapter = this@SelectionActivity.adapter
        }
    }

    private fun navigateToCategoryDetail(category: BackupCategory) {
        val files = categoryFiles[category]
        if (files.isNullOrEmpty()) return
        startActivity(Intent(this, SelectionDetailActivity::class.java).apply {
            putExtra(SelectionDetailActivity.EXTRA_CATEGORY_NAME, category.name)
        })
    }

    private fun removeCategory(category: BackupCategory) {
        val files = categoryFiles[category] ?: return
        if (files.isEmpty()) return

        isRemoving = true
        suppressListenerUpdates = true

        // Show full-screen loading overlay to block all interaction
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingMessage.text = "Removing ${files.size} files..."

        lifecycleScope.launch {
            // Deselect all files in background — silent, no per-file notification
            withContext(Dispatchers.Default) {
                for (path in files) {
                    SelectionManager.deselectFileSilent(path)
                }
            }

            // Notify once after all files removed
            suppressListenerUpdates = false
            SelectionManager.notifySelectionChanged()

            // Rebuild category view in background
            removedCategories.add(category)

            val result = withContext(Dispatchers.Default) {
                val allFiles = SelectionManager.getSelectedFilePaths()
                val grouped = mutableMapOf<BackupCategory, MutableList<String>>()
                for (path in allFiles) {
                    val cat = categorizeFile(path)
                    grouped.getOrPut(cat) { mutableListOf() }.add(path)
                }

                val displayItems = mutableListOf<CategoryDisplayItem>()
                for (cat in displayCategories) {
                    val catFiles = grouped[cat]
                    val fileCount = catFiles?.size ?: 0
                    val wasRemoved = cat in removedCategories

                    if (fileCount > 0 || wasRemoved) {
                        val totalSize = catFiles?.sumOf { path ->
                            try { File(path).length() } catch (e: Exception) { 0L }
                        } ?: 0L

                        displayItems.add(
                            CategoryDisplayItem(
                                category = cat,
                                isChecked = fileCount > 0 && !wasRemoved,
                                isScanning = false,
                                scanResult = CategoryScanResult(
                                    category = cat,
                                    fileCount = fileCount,
                                    totalSize = totalSize,
                                    excludedCount = 0,
                                    excludedSize = 0L,
                                    isEnabled = true,
                                    hasPermission = true
                                )
                            )
                        )
                    }
                }

                Pair(grouped, displayItems)
            }

            // Back on main thread — update UI
            categoryFiles.clear()
            categoryFiles.putAll(result.first)
            applyCategoryView(result.second)
            updateSummaryFast()

            isRemoving = false
            binding.loadingOverlay.visibility = View.GONE
        }
    }

    private fun setupButtons() {
        binding.btnClearAll.setOnClickListener {
            binding.loadingOverlay.visibility = View.VISIBLE
            binding.tvLoadingMessage.text = "Clearing all..."
            suppressListenerUpdates = true

            lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    SelectionManager.deselectAll()
                }
                suppressListenerUpdates = false
                removedCategories.addAll(categoryFiles.keys)
                categoryFiles.clear()
                applyCategoryView(emptyList())
                updateSummary()
                binding.loadingOverlay.visibility = View.GONE
            }
        }

        binding.btnContinue.setOnClickListener {
            if (editOnly) {
                finish()
            } else if (SelectionManager.hasSelection()) {
                startActivity(Intent(this, ModeSelectionActivity::class.java))
                finish()
            } else {
                finish()
            }
        }
    }

    /**
     * Categorize a file path by its extension into a BackupCategory.
     */
    private fun categorizeFile(path: String): BackupCategory {
        return BackupCategory.fromFilePath(path)
    }

    private val displayCategories = listOf(
        BackupCategory.IMAGES,
        BackupCategory.VIDEOS,
        BackupCategory.AUDIO,
        BackupCategory.DOCUMENTS,
        BackupCategory.OTHER
    )

    /**
     * Load category view asynchronously — categorizes files and computes sizes in background.
     */
    private fun loadCategoryViewAsync() {
        // Show scanning state for all categories immediately
        val placeholders = displayCategories.map { category ->
            CategoryDisplayItem(
                category = category,
                isChecked = true,
                isScanning = true,
                scanResult = null
            )
        }
        adapter.setDisplayItems(placeholders)
        binding.recyclerView.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val allFiles = SelectionManager.getSelectedFilePaths()

                // Group files by category
                val grouped = mutableMapOf<BackupCategory, MutableList<String>>()
                for (path in allFiles) {
                    val category = categorizeFile(path)
                    grouped.getOrPut(category) { mutableListOf() }.add(path)
                }

                // Compute sizes per category
                val items = mutableListOf<CategoryDisplayItem>()
                for (category in displayCategories) {
                    val files = grouped[category]
                    val fileCount = files?.size ?: 0
                    if (fileCount > 0) {
                        val totalSize = files!!.sumOf { path ->
                            try { File(path).length() } catch (e: Exception) { 0L }
                        }
                        items.add(
                            CategoryDisplayItem(
                                category = category,
                                isChecked = true,
                                isScanning = false,
                                scanResult = CategoryScanResult(
                                    category = category,
                                    fileCount = fileCount,
                                    totalSize = totalSize,
                                    excludedCount = 0,
                                    excludedSize = 0L,
                                    isEnabled = true,
                                    hasPermission = true
                                )
                            )
                        )
                    }
                }

                Pair(grouped, items)
            }

            // Back on main thread
            categoryFiles.clear()
            categoryFiles.putAll(result.first)
            applyCategoryView(result.second)
            updateSummary()
        }
    }

    /**
     * Rebuild category view asynchronously.
     */
    private fun rebuildCategoryViewAsync() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val allFiles = SelectionManager.getSelectedFilePaths()
                val grouped = mutableMapOf<BackupCategory, MutableList<String>>()
                for (path in allFiles) {
                    val category = categorizeFile(path)
                    grouped.getOrPut(category) { mutableListOf() }.add(path)
                }

                val displayItems = mutableListOf<CategoryDisplayItem>()
                for (category in displayCategories) {
                    val files = grouped[category]
                    val fileCount = files?.size ?: 0
                    val wasRemoved = category in removedCategories

                    if (fileCount > 0 || wasRemoved) {
                        val totalSize = files?.sumOf { path ->
                            try { File(path).length() } catch (e: Exception) { 0L }
                        } ?: 0L

                        displayItems.add(
                            CategoryDisplayItem(
                                category = category,
                                isChecked = fileCount > 0 && !wasRemoved,
                                isScanning = false,
                                scanResult = CategoryScanResult(
                                    category = category,
                                    fileCount = fileCount,
                                    totalSize = totalSize,
                                    excludedCount = 0,
                                    excludedSize = 0L,
                                    isEnabled = true,
                                    hasPermission = true
                                )
                            )
                        )
                    }
                }

                Pair(grouped, displayItems)
            }

            categoryFiles.clear()
            categoryFiles.putAll(result.first)
            applyCategoryView(result.second)
        }
    }

    /**
     * Apply computed display items to UI.
     */
    private fun applyCategoryView(displayItems: List<CategoryDisplayItem>) {
        adapter.setDisplayItems(displayItems)

        if (displayItems.isEmpty() || displayItems.all { (it.scanResult?.fileCount ?: 0) == 0 }) {
            binding.recyclerView.visibility = View.GONE
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = "No items selected"
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE
        }

        if (!SelectionManager.hasSelection()) {
            binding.btnContinue.text = "Done"
        } else if (editOnly) {
            binding.btnContinue.text = "Done"
        } else {
            binding.btnContinue.text = "Continue"
        }
        binding.btnContinue.isEnabled = true
    }

    private fun updateSummary() {
        val fileCount = SelectionManager.getSelectedFilePaths().size
        val totalSize = SelectionManager.getFormattedTotalSize()
        binding.tvSelectionSummary.text = "$fileCount files"
        binding.tvTotalSize.text = totalSize
    }

    /**
     * Lightweight summary update using pre-computed values (no file I/O).
     */
    private fun updateSummaryFast() {
        val fileCount = SelectionManager.getSelectedFilePaths().size
        // Compute size from our cached categoryFiles instead of hitting disk
        val totalSize = categoryFiles.values.flatten().sumOf { path ->
            try { File(path).length() } catch (e: Exception) { 0L }
        }
        binding.tvSelectionSummary.text = "$fileCount files"
        binding.tvTotalSize.text = FileItem.formatFileSize(totalSize)
    }

    // SelectionListener — suppress during batch removal to avoid flooding main thread
    override fun onSelectionChanged(count: Int, totalSize: Long) {
        if (suppressListenerUpdates) return
        runOnUiThread {
            if (!suppressListenerUpdates) {
                updateSummary()
            }
        }
    }
}
