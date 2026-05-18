package com.decloud.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts

import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.util.BackupCategory
import com.decloud.util.BackupManager
import com.decloud.util.CategoryScanResult
import com.decloud.util.ThemeManager
import com.decloud.ui.adapter.BackupCategoryAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BackupSummaryActivity : BaseActivity() {

    companion object {
        const val EXTRA_BACKUP_MODE = "backup_mode"

        private val BACKUP_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS
        )
    }

    private lateinit var adapter: BackupCategoryAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnContinue: Button
    private lateinit var scanningSection: LinearLayout
    private lateinit var tvScanningStatus: TextView
    private lateinit var tvTotalFiles: TextView
    private lateinit var tvTotalSize: TextView
    private lateinit var tvTotalExcluded: TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var tvLoadingMessage: TextView
    private lateinit var tvLoadingDetail: TextView

    private var backupMode = BackupManager.BackupMode.INTERNAL_ONLY
    private var scanJob: Job? = null
    private var backupJob: Job? = null
    private var scanResults = mutableListOf<CategoryScanResult>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Start scan regardless of result - denied categories will show "Permission required"
        startScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_summary)

        val modeName = intent.getStringExtra(EXTRA_BACKUP_MODE)
        backupMode = try {
            BackupManager.BackupMode.valueOf(modeName ?: "INTERNAL_ONLY")
        } catch (e: IllegalArgumentException) {
            BackupManager.BackupMode.INTERNAL_ONLY
        }

        setupViews()
        requestPermissionsAndScan()
    }

    private fun setupViews() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        recyclerView = findViewById(R.id.recyclerViewCategories)
        btnContinue = findViewById(R.id.btnContinue)
        scanningSection = findViewById(R.id.scanningSection)
        tvScanningStatus = findViewById(R.id.tvScanningStatus)
        tvTotalFiles = findViewById(R.id.tvTotalFiles)
        tvTotalSize = findViewById(R.id.tvTotalSize)
        tvTotalExcluded = findViewById(R.id.tvTotalExcluded)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvLoadingMessage = findViewById(R.id.tvLoadingMessage)
        tvLoadingDetail = findViewById(R.id.tvLoadingDetail)

        adapter = BackupCategoryAdapter(
            onCheckChanged = { _, _ ->
                updateTotals()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val allCategories = BackupCategory.values().toList()
        adapter.setItems(allCategories)

        btnContinue.isEnabled = false
        btnContinue.setOnClickListener {
            startBackup()
        }

        findViewById<Button>(R.id.btnCancelLoading).setOnClickListener {
            backupJob?.cancel()
            BackupManager.cancelBackup()
            hideLoadingOverlay()
        }
    }

    private fun requestPermissionsAndScan() {
        val needed = BACKUP_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startScan()
        }
    }

    private fun startScan() {
        val allCategories = BackupCategory.values().toList()

        scanJob = lifecycleScope.launch {
            BackupManager.scanCategories(
                context = this@BackupSummaryActivity,
                categories = allCategories,
                mode = backupMode,
                listener = object : BackupManager.BackupScanListener {
                    override fun onScanStarted() {
                        runOnUiThread {
                            scanningSection.visibility = View.VISIBLE
                            tvScanningStatus.text = "Scanning categories..."
                        }
                    }

                    override fun onCategoryStarted(category: BackupCategory) {
                        runOnUiThread {
                            tvScanningStatus.text = "Scanning ${category.displayName}..."
                            adapter.setCategoryScanning(category)
                        }
                    }

                    override fun onCategoryProgress(category: BackupCategory, scannedFiles: Int, currentFile: String) {
                        // Optionally update per-row progress
                    }

                    override fun onCategoryComplete(result: CategoryScanResult) {
                        runOnUiThread {
                            scanResults.add(result)
                            adapter.setCategoryResult(result.category, result)
                            updateTotals()
                        }
                    }

                    override fun onScanProgress(scannedFiles: Int, scannedDirs: Int, currentPath: String) {
                        // Not used for category scan
                    }

                    override fun onScanComplete(totalFiles: Int, totalDirs: Int, totalSize: Long, excludedFiles: Int, excludedSize: Long) {
                        runOnUiThread {
                            scanningSection.visibility = View.GONE
                            btnContinue.isEnabled = true
                            updateTotals()
                        }
                    }

                    override fun onScanError(error: String) {
                        runOnUiThread {
                            scanningSection.visibility = View.GONE
                            btnContinue.isEnabled = true
                            Toast.makeText(this@BackupSummaryActivity, error, Toast.LENGTH_LONG).show()
                            updateTotals()
                        }
                    }
                }
            )
        }
    }

    private fun updateTotals() {
        val totalFiles = adapter.getSelectedTotalFiles()
        val totalSize = adapter.getSelectedTotalSize()
        val totalExcluded = adapter.getSelectedExcludedCount()

        tvTotalFiles.text = formatNumber(totalFiles)
        tvTotalSize.text = formatSize(totalSize)
        tvTotalExcluded.text = formatNumber(totalExcluded)
    }

    private fun startBackup() {
        val selectedCategories = adapter.getSelectedCategories()
        if (selectedCategories.isEmpty()) {
            Toast.makeText(this, "Select at least one category", Toast.LENGTH_SHORT).show()
            return
        }

        showLoadingOverlay("Preparing backup...", "Adding files to selection...")

        backupJob = lifecycleScope.launch {
            BackupManager.startCategoryBackup(
                context = this@BackupSummaryActivity,
                categories = selectedCategories,
                mode = backupMode,
                scope = this,
                listener = object : BackupManager.BackupScanListener {
                    override fun onScanStarted() {
                        runOnUiThread {
                            updateLoadingOverlay("Preparing backup...", "Starting...")
                        }
                    }

                    override fun onCategoryStarted(category: BackupCategory) {
                        runOnUiThread {
                            updateLoadingOverlay("Preparing backup...", "Adding ${category.displayName}...")
                        }
                    }

                    override fun onCategoryProgress(category: BackupCategory, scannedFiles: Int, currentFile: String) {
                        runOnUiThread {
                            val shortFile = if (currentFile.length > 30) {
                                "..." + currentFile.takeLast(27)
                            } else currentFile
                            updateLoadingOverlay("Adding ${category.displayName}...", "$scannedFiles files · $shortFile")
                        }
                    }

                    override fun onCategoryComplete(result: CategoryScanResult) {}

                    override fun onScanProgress(scannedFiles: Int, scannedDirs: Int, currentPath: String) {}

                    override fun onScanComplete(totalFiles: Int, totalDirs: Int, totalSize: Long, excludedFiles: Int, excludedSize: Long) {
                        runOnUiThread {
                            hideLoadingOverlay()
                            if (totalFiles > 0) {
                                startActivity(Intent(this@BackupSummaryActivity, ModeSelectionActivity::class.java))
                                finish()
                            } else {
                                Toast.makeText(
                                    this@BackupSummaryActivity,
                                    "No accessible files found in selected categories",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }

                    override fun onScanError(error: String) {
                        runOnUiThread {
                            hideLoadingOverlay()
                            Toast.makeText(this@BackupSummaryActivity, error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    private fun showLoadingOverlay(message: String, detail: String) {
        loadingOverlay.visibility = View.VISIBLE
        tvLoadingMessage.text = message
        tvLoadingDetail.text = detail
        loadingOverlay.alpha = 0f
        loadingOverlay.animate().alpha(1f).setDuration(250).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun updateLoadingOverlay(message: String, detail: String) {
        tvLoadingMessage.text = message
        tvLoadingDetail.text = detail
    }

    private fun hideLoadingOverlay() {
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { loadingOverlay.visibility = View.GONE }
            .start()
    }   

    private fun formatNumber(n: Int): String {
        return when {
            n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
            else -> n.toString()
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 * 1024 ->
                String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024))
            bytes >= 1024L * 1024 * 1024 ->
                String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 ->
                String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L ->
                String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        backupJob?.cancel()
        super.onDestroy()
    }
}
