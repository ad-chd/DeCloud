package com.decloud.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.decloud.databinding.ActivityCategoryBinding
import com.decloud.model.FileItem
import com.decloud.model.SelectionManager
import com.decloud.ui.adapter.CategoryFileAdapter
import com.decloud.util.BackupCategory
import com.decloud.util.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Activity showing individual files within a category for granular editing.
 * Launched from SelectionActivity when user taps a category row.
 * Only checkbox toggles selection — row tap is a no-op.
 */
class SelectionDetailActivity : BaseActivity(), SelectionManager.SelectionListener {

    companion object {
        const val EXTRA_CATEGORY_NAME = "extra_category_name"
    }

    private lateinit var binding: ActivityCategoryBinding
    private lateinit var fileAdapter: CategoryFileAdapter
    private lateinit var category: BackupCategory

    private var selectionListener: SelectionManager.SelectionListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME) ?: BackupCategory.IMAGES.name
        category = try {
            BackupCategory.valueOf(categoryName)
        } catch (e: IllegalArgumentException) {
            BackupCategory.OTHER
        }

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        setupSelectionListener()

        loadFiles()
    }

    override fun onDestroy() {
        super.onDestroy()
        fileAdapter.cleanup()
        selectionListener?.let { SelectionManager.removeListener(it) }
    }

    private fun setupToolbar() {
        binding.toolbar.title = "Selected ${category.displayName}"
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        // Map BackupCategory to MediaScanner.Category for the adapter
        val mediaScannerCategory = when (category) {
            BackupCategory.IMAGES -> MediaScanner.Category.IMAGES
            BackupCategory.VIDEOS -> MediaScanner.Category.VIDEOS
            BackupCategory.AUDIO -> MediaScanner.Category.AUDIO
            BackupCategory.DOCUMENTS -> MediaScanner.Category.DOCUMENTS
            else -> MediaScanner.Category.DOCUMENTS
        }

        fileAdapter = CategoryFileAdapter(
            context = this,
            category = mediaScannerCategory,
            onItemClick = { /* no-op */ },
            onItemSelect = { fileItem ->
                // Toggle individual file selection
                if (SelectionManager.isSelected(fileItem)) {
                    SelectionManager.deselectFile(fileItem.path)
                } else {
                    SelectionManager.selectFile(fileItem.path)
                }
                fileAdapter.updateSelectionState()
                updateFileCount()
                updateSelectionBar()
            },
            onSelectionModeChanged = null
        )

        // Always in selection mode, only checkbox toggles
        fileAdapter.setSelectionMode(true)
        fileAdapter.checkboxOnlySelection = true

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@SelectionDetailActivity)
            adapter = fileAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            itemAnimator = null
        }

        // Disable swipe refresh
        binding.swipeRefresh.isEnabled = false
    }

    private fun setupButtons() {
        // Hide "Select All", show "Deselect All" as the main action
        binding.btnSelectAll.visibility = View.GONE
        binding.btnSort.visibility = View.GONE

        binding.btnDeselectAll.isEnabled = true
        binding.btnDeselectAll.text = "Deselect All"
        binding.btnDeselectAll.setOnClickListener {
            deselectAllInCategory()
        }

        // Repurpose "Ready to Send" as "Done"
        binding.btnReadyToSend.text = "Done"
        binding.btnReadyToSend.isEnabled = true
        binding.btnReadyToSend.setOnClickListener {
            finish()
        }
    }

    private fun setupSelectionListener() {
        selectionListener = object : SelectionManager.SelectionListener {
            override fun onSelectionChanged(count: Int, totalSize: Long) {
                runOnUiThread {
                    fileAdapter.updateSelectionState()
                    updateFileCount()
                    updateSelectionBar()
                }
            }
        }
        SelectionManager.addListener(selectionListener!!)
    }

    private fun loadFiles() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        lifecycleScope.launch {
            val fileItems = withContext(Dispatchers.Default) {
                SelectionManager.getSelectedFilePaths()
                    .filter { BackupCategory.fromFilePath(it) == category }
                    .mapNotNull { path ->
                        try {
                            val file = File(path)
                            if (file.exists()) FileItem.fromFile(file) else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .sortedBy { it.name.lowercase() }
            }

            binding.progressBar.visibility = View.GONE

            if (fileItems.isEmpty()) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = "No ${category.displayName.lowercase()} selected"
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.recyclerView.visibility = View.VISIBLE
                binding.tvEmptyState.visibility = View.GONE
                fileAdapter.setItems(fileItems)
            }

            updateFileCount()
            updateSelectionBar()
        }
    }

    private fun updateFileCount() {
        val count = SelectionManager.getSelectedFilePaths()
            .count { BackupCategory.fromFilePath(it) == category }
        binding.tvFileCount.text = "$count files"
    }

    private fun updateSelectionBar() {
        val totalCount = SelectionManager.getSelectedCount()
        if (totalCount > 0) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = SelectionManager.getSelectionSummary()
            binding.btnReadyToSend.isEnabled = true
        } else {
            binding.selectionBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = "No files selected"
            binding.btnReadyToSend.isEnabled = true
        }
    }

    private fun deselectAllInCategory() {
        lifecycleScope.launch {
            val paths = withContext(Dispatchers.Default) {
                SelectionManager.getSelectedFilePaths()
                    .filter { BackupCategory.fromFilePath(it) == category }
            }

            withContext(Dispatchers.Default) {
                for (path in paths) {
                    SelectionManager.deselectFileSilent(path)
                }
            }
            SelectionManager.notifySelectionChanged()

            // Reload the list
            loadFiles()
        }
    }

    // SelectionListener
    override fun onSelectionChanged(count: Int, totalSize: Long) {
        runOnUiThread {
            updateFileCount()
            updateSelectionBar()
        }
    }
}
