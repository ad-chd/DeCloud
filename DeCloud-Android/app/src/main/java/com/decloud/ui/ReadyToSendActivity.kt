package com.decloud.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup

import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.decloud.R
import com.decloud.databinding.ActivityReadyToSendBinding
import com.decloud.model.FileItem
import com.decloud.model.FileType
import com.decloud.model.SelectionManager
import java.io.File
import com.decloud.service.TransferService
import com.decloud.service.TransferStatus
import com.decloud.service.AdbTransferService
import com.decloud.service.AdbTransferStatus
import com.decloud.ui.adapter.SelectedFileAdapter
import com.decloud.ui.adapter.TransferResultAdapter
import com.decloud.util.NetworkUtils
import com.decloud.util.TransferLogger

/**
 * Ready to send screen - shows summary and handles transfer
 */
class ReadyToSendActivity : BaseActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }

    private data class LoadResult(
        val totalFileCount: Int,
        val folderCount: Int,
        val topFileCount: Int,
        val totalSize: String,
        val displayItems: List<FileItem>,
        val subtitles: Map<Int, String>
    )

    private val REQUEST_BT_CONNECT = 1001

    private lateinit var binding: ActivityReadyToSendBinding
    private lateinit var selectedFileAdapter: SelectedFileAdapter
    private lateinit var transferResultAdapter: TransferResultAdapter

    private var transferService: TransferService? = null
    private var adbTransferService: AdbTransferService? = null
    private var serviceBound = false
    private var adbServiceBound = false

    // Transfer mode determined by user selection in ModeSelectionActivity
    private var useAdbMode = false
    private var autoStart = false

    // True when actual data transfer has begun (not just waiting for PC)
    // Used to decide whether to keep services running on app background
    private var isTransferActive = false

    // Tracks whether transfer completed - prevents Stopped from overwriting Complete screen
    private var transferCompleted = false

    // True when user clicked Cancel — prevents late status callbacks from overwriting the UI
    private var transferCancelled = false

    // When true, selection is locked — no adding/removing files allowed
    private var isTransferLocked = false

    // Tracks whether initial load has completed (to avoid double-load on first onResume)
    private var initialLoadDone = false

    // Cached load result — reused when SelectionManager hasn't changed (dirty flag is clean)
    private var cachedLoadResult: LoadResult? = null

    private fun lockSelection() {
        isTransferLocked = true
        SelectionManager.lock()
        selectedFileAdapter.setLocked(true)
        binding.btnEditSelection.visibility = View.GONE
    }

    private fun unlockSelection() {
        isTransferLocked = false
        SelectionManager.unlock()
        selectedFileAdapter.setLocked(false)
        binding.btnEditSelection.visibility = View.VISIBLE
    }

    // Transfer tracking
    private var transferStartTime: Long = 0
    private var transferEndTime: Long = 0
    private val transferResults = mutableListOf<TransferLogger.TransferResult>()
    private var currentTransferSummary: TransferLogger.TransferSummary? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TransferService.LocalBinder
            transferService = binder.getService()
            serviceBound = true

            // Set up callbacks
            transferService?.onStatusChanged = { status ->
                runOnUiThread { handleStatusChange(status) }
            }

            transferService?.onProgressChanged = { fileIndex, totalFiles, fileName, bytesTransferred, totalBytes ->
                runOnUiThread {
                    updateProgress(fileIndex, totalFiles, fileName, bytesTransferred, totalBytes)
                }
            }

            transferService?.onHotspotReady = { ssid, password, ipAddress ->
                runOnUiThread {
                    showHotspotCredentials(ssid, password, ipAddress)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            serviceBound = false
        }
    }

    private val adbServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AdbTransferService.LocalBinder
            adbTransferService = binder.getService()
            adbServiceBound = true

            // Set up callbacks
            adbTransferService?.onStatusChanged = { status ->
                runOnUiThread { handleAdbStatusChange(status) }
            }

            adbTransferService?.onProgressChanged = { current, total ->
                runOnUiThread {
                    updateAdbProgress(current, total)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            adbTransferService = null
            adbServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadyToSendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        autoStart = intent.getBooleanExtra(EXTRA_AUTO_START, false)

        // Determine mode early so toolbar and UI can use it
        useAdbMode = MainActivity.currentTransferMode == ModeSelectionActivity.MODE_ADB

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        loadSelectedFiles()
        checkConnectionStatus()
    }

    override fun onStart() {
        super.onStart()
        // Bind to HTTP transfer service
        Intent(this, TransferService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        // Bind to ADB transfer service
        Intent(this, AdbTransferService::class.java).also { intent ->
            bindService(intent, adbServiceConnection, Context.BIND_AUTO_CREATE)
        }

        // If we're coming back after stopping waiting services (onStop reset transferStartTime),
        // show the ready state so user can restart
        if (transferStartTime == 0L && !transferCompleted &&
            binding.layoutWaiting.visibility == View.VISIBLE) {
            showReadyState()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh file list when returning from SelectionActivity (edit selection)
        // Skip initial resume (onCreate already calls loadSelectedFiles)
        if (!initialLoadDone) {
            initialLoadDone = true
            return
        }
        if (binding.layoutReady.visibility == View.VISIBLE && !isTransferLocked) {
            // If all files were deselected in SelectionActivity, go back
            if (!SelectionManager.hasSelection()) {
                finish()
                return
            }
            // Skip expensive reload if selection hasn't changed (dirty flag)
            if (!SelectionManager.isDirty() && cachedLoadResult != null) {
                return
            }
            loadSelectedFiles()
        }
    }

    override fun onStop() {
        super.onStop()

        // Don't stop waiting services on screen lock/background - the foreground service
        // notification lets the user manage the service. Only stop on explicit Cancel.

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        if (adbServiceBound) {
            unbindService(adbServiceConnection)
            adbServiceBound = false
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
        binding.toolbar.title = "Ready to Send"
    }

    override fun onBackPressed() {
        handleBackPress()
    }

    private fun handleBackPress() {
        // If error popup is showing, dismiss it
        if (binding.layoutError.visibility == View.VISIBLE) {
            showReadyState()
            return
        }
        // If complete popup is showing, return to home
        if (binding.layoutComplete.visibility == View.VISIBLE) {
            transferCompleted = false
            transferStartTime = 0
            SelectionManager.deselectAll()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
            return
        }
        // If transfer is in progress, let it keep running in background
        // Services are foreground and will continue — user can come back via notification
        if (binding.layoutTransferring.visibility == View.VISIBLE ||
            binding.layoutWaiting.visibility == View.VISIBLE) {
            // Just minimize to background — don't kill the transfer
            moveTaskToBack(true)
            return
        }
        // Normal back — only allowed when not locked
        if (!isTransferLocked) {
            super.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        selectedFileAdapter = SelectedFileAdapter { fileItem ->
            // Remove item from selection (folder or file)
            if (fileItem.fileType == FileType.FOLDER) {
                SelectionManager.deselectAllInDirectory(File(fileItem.path))
            } else {
                SelectionManager.deselectFile(fileItem.path)
            }
            // Reload the list
            loadSelectedFiles()
            // Check if all files were removed
            if (!SelectionManager.hasSelection()) {
                // No files left, go back to browse
                finish()
            }
        }
        binding.recyclerViewFiles.apply {
            layoutManager = LinearLayoutManager(this@ReadyToSendActivity)
            adapter = selectedFileAdapter
        }

        // Setup results RecyclerView for transfer completion
        transferResultAdapter = TransferResultAdapter()
        binding.recyclerViewResults.apply {
            layoutManager = LinearLayoutManager(this@ReadyToSendActivity)
            adapter = transferResultAdapter
        }
    }

    private fun setupButtons() {
        binding.btnEditSelection.setOnClickListener {
            // Navigate to SelectionActivity to edit the selection
            // Pass EXTRA_EDIT_ONLY so SelectionActivity returns here instead of going to ModeSelection
            val intent = Intent(this, SelectionActivity::class.java)
            intent.putExtra(SelectionActivity.EXTRA_EDIT_ONLY, true)
            startActivity(intent)
        }

        binding.btnStart.setOnClickListener {
            startTransfer()
        }

        binding.btnCancel.setOnClickListener {
            stopTransfer()
        }

        binding.btnCancelTransfer.setOnClickListener {
            stopTransfer()
        }

        binding.btnDone.setOnClickListener {
            transferCompleted = false
            transferStartTime = 0
            SelectionManager.deselectAll()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        // Reset button - clear selections and restart fresh
        binding.btnReset.setOnClickListener {
            transferCompleted = false
            transferStartTime = 0
            SelectionManager.deselectAll()
            val intent = Intent(this, BrowseActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        // Close buttons for Complete and Error states
        binding.btnCloseComplete.setOnClickListener {
            transferCompleted = false
            transferStartTime = 0
            SelectionManager.deselectAll()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        binding.btnCloseError.setOnClickListener {
            showReadyState()
        }

        // Retry button
        binding.btnRetry.setOnClickListener {
            showReadyState()
        }
    }

    /** Hide top summary and expand bottom bar to fill screen for transfer/complete/error states */
    private fun expandBottomBar() {
        if (binding.summaryCard.visibility == View.GONE) return
        binding.summaryCard.visibility = View.GONE
        binding.tvSelectionHeader.visibility = View.GONE
        binding.recyclerViewFiles.visibility = View.GONE
        (binding.bottomBar.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            height = 0
            weight = 1f
        }
        binding.bottomBar.requestLayout()
    }

    /** Restore default layout with summary card and file list visible */
    private fun restoreDefaultLayout() {
        if (binding.summaryCard.visibility == View.VISIBLE) return
        binding.summaryCard.visibility = View.VISIBLE
        binding.tvSelectionHeader.visibility = View.VISIBLE
        binding.recyclerViewFiles.visibility = View.VISIBLE
        (binding.bottomBar.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            weight = 0f
        }
        binding.bottomBar.requestLayout()
    }

    // Show ready state (for retry or dismiss error)
    private fun showReadyState() {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        // Unlock selection — allow modifications again
        unlockSelection()

        // Restore full layout with summary card and file list
        restoreDefaultLayout()

        binding.layoutReady.visibility = View.VISIBLE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.connectionWarning.visibility = View.VISIBLE

        binding.toolbar.title = "Ready to Send"
        checkConnectionStatus()
    }

    private fun loadSelectedFiles() {
        // Show loading overlay while files are being counted
        showLoading("Loading files...", "Preparing your selection")

        lifecycleScope.launch {
            try {
                // Do heavy file processing in background
                val result = withContext(Dispatchers.IO) {
                    // Use getSelectedFiles() for accurate count — filters out inaccessible files
                    // This matches the manifest count the PC will actually transfer
                    val allSelectedFiles = SelectionManager.getSelectedFiles()
                    val totalFileCount = allSelectedFiles.size
                    val totalSize = SelectionManager.getFormattedTotalSize()

                    // Get top-level selections (what user actually picked)
                    val topFolders = SelectionManager.getTopLevelSelectedFolderPaths()
                    val topFiles = SelectionManager.getTopLevelSelectedFilePaths()

                    // Build display items: folders first, then files
                    val displayItems = mutableListOf<FileItem>()
                    val subtitles = mutableMapOf<Int, String>()

                    // Add folder items with file count subtitles
                    for (folderPath in topFolders) {
                        val folder = File(folderPath)
                        if (folder.exists()) {
                            val folderPrefix = folderPath + File.separator
                            val filesInFolder = allSelectedFiles.count {
                                it.absolutePath.startsWith(folderPrefix)
                            }
                            val folderItem = FileItem.fromFile(folder)
                            subtitles[displayItems.size] = "$filesInFolder file${if (filesInFolder != 1) "s" else ""}"
                            displayItems.add(folderItem)
                        }
                    }

                    // Add individual file items
                    for (filePath in topFiles) {
                        val file = File(filePath)
                        if (file.exists()) {
                            displayItems.add(FileItem.fromFile(file))
                        }
                    }

                    LoadResult(totalFileCount, topFolders.size, topFiles.size, totalSize, displayItems, subtitles)
                }

                // Cache result and mark selection as clean
                cachedLoadResult = result
                SelectionManager.markClean()

                // Update UI on main thread
                selectedFileAdapter.setItemsWithSubtitles(result.displayItems, result.subtitles)

                // Update summary card
                if (result.folderCount > 0) {
                    binding.layoutFolderStat.visibility = View.VISIBLE
                    binding.dividerFolderFile.visibility = View.VISIBLE
                    binding.tvFolderCount.text = result.folderCount.toString()
                } else {
                    binding.layoutFolderStat.visibility = View.GONE
                    binding.dividerFolderFile.visibility = View.GONE
                }
                binding.tvFileCount.text = result.totalFileCount.toString()
                binding.tvTotalSize.text = result.totalSize

                // Hide loading overlay - files are ready
                hideLoading()

                // Auto-start transfer if launched from resume
                if (autoStart) {
                    autoStart = false  // Only once
                    startTransfer()
                }

            } catch (e: Exception) {
                // Fallback to simple display
                val folderCount = SelectionManager.getTopLevelSelectedFolderPaths().size
                if (folderCount > 0) {
                    binding.layoutFolderStat.visibility = View.VISIBLE
                    binding.dividerFolderFile.visibility = View.VISIBLE
                    binding.tvFolderCount.text = folderCount.toString()
                }
                binding.tvFileCount.text = SelectionManager.getSelectedFiles().size.toString()
                binding.tvTotalSize.text = SelectionManager.getFormattedTotalSize()

                // Hide loading overlay even on fallback
                hideLoading()

                // Auto-start even on fallback
                if (autoStart) {
                    autoStart = false
                    startTransfer()
                }
            }
        }
    }

    private fun showLoading(message: String, detail: String = "") {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingMessage.text = message
        binding.tvLoadingDetail.text = detail
    }

    private fun hideLoading() {
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                if (!isFinishing && !isDestroyed) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.loadingOverlay.alpha = 1f
                }
            }
            .start()
    }

    private fun checkConnectionStatus() {
        val connectionInfo = NetworkUtils.getConnectionInfo()

        if (useAdbMode) {
            // ADB Mode selected
            binding.connectionWarning.visibility = View.VISIBLE
            binding.connectionWarning.setBackgroundColor(0xFF4CAF50.toInt()) // Green
            binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())

            if (isUsbDebuggingEnabled()) {
                binding.connectionWarning.text = "USB Transfer Ready\nConnect USB cable & open DeCloud on PC"
            } else {
                binding.connectionWarning.setBackgroundColor(0xFFFF9800.toInt()) // Orange
                binding.connectionWarning.text = "USB Transfer\nPlease enable USB Debugging in Developer Options"
            }
        } else {
            // WiFi Mode selected
            when (connectionInfo.type) {
                NetworkUtils.ConnectionType.WIFI_HOTSPOT -> {
                    binding.connectionWarning.visibility = View.VISIBLE
                    binding.connectionWarning.setBackgroundColor(0xFF4CAF50.toInt()) // Green
                    binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
                    binding.connectionWarning.text = "Hotspot Active\nConnect your PC to this phone's hotspot"
                }
                NetworkUtils.ConnectionType.USB_TETHERING -> {
                    binding.connectionWarning.visibility = View.VISIBLE
                    binding.connectionWarning.setBackgroundColor(0xFF2196F3.toInt()) // Blue
                    binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
                    binding.connectionWarning.text = "USB Tethering Active\nPC can connect over USB network"
                }
                NetworkUtils.ConnectionType.WIFI_LAN -> {
                    binding.connectionWarning.visibility = View.VISIBLE
                    binding.connectionWarning.setBackgroundColor(0xFF4CAF50.toInt()) // Green
                    binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
                    binding.connectionWarning.text = "Connected to WiFi\nMake sure PC is on same network"
                }
                NetworkUtils.ConnectionType.NONE -> {
                    binding.connectionWarning.visibility = View.VISIBLE
                    binding.connectionWarning.setBackgroundColor(0xFFFF9800.toInt()) // Orange
                    binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
                    binding.connectionWarning.text = "No Network\nEnable WiFi Hotspot to start"
                }
            }
        }
    }

    private fun isUsbDebuggingEnabled(): Boolean {
        // Check if USB debugging is enabled
        return try {
            android.provider.Settings.Global.getInt(
                contentResolver,
                android.provider.Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    private fun startTransfer() {
        // Show loading while preparing
        showLoading("Starting...", "")

        // Request BLUETOOTH_CONNECT permission for BT discovery (Android 12+, non-blocking)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BT_CONNECT
                )
            }
        }

        // Reset flags for new transfer
        transferCompleted = false
        transferCancelled = false
        isTransferActive = false

        // Lock selection — no modifications allowed during transfer
        lockSelection()

        try {
            // Track transfer start time
            transferStartTime = System.currentTimeMillis()
            transferResults.clear()

            if (useAdbMode) {
                // Use ADB mode
                startAdbTransfer()
            } else {
                // Use HTTP/Hotspot mode
                val serviceIntent = Intent(this, TransferService::class.java).apply {
                    action = TransferService.ACTION_START_SERVER
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                // UI will update via callback
                showWaitingState()
            }
        } catch (e: Exception) {
            // Release selection lock on any error during transfer setup
            unlockSelection()
            hideLoading()
            showErrorState("Failed to start transfer: ${e.message}")
        }
    }

    /**
     * Show a "Waiting for PC" state - phone is ready and retrying connection.
     * Transfer will start automatically once PC clicks Start Transfer.
     */
    private fun showWaitingForPcState(mode: String) {
        // Hide loading overlay
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.VISIBLE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        binding.tvStatus.text = "Ready via $mode"
        binding.tvServerAddress.visibility = View.VISIBLE
        binding.tvServerAddress.text = "Click 'Start Transfer' on the PC app.\nTransfer will begin automatically."

        // Update connection warning
        binding.connectionWarning.visibility = View.VISIBLE
        binding.connectionWarning.setBackgroundColor(0xFF4CAF50.toInt()) // Green - ready
        binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
        binding.connectionWarning.text = "Ready — Start transfer on PC"
        binding.toolbar.title = "Waiting for PC"
    }

    private fun startAdbTransfer() {
        // Start ADB transfer service
        val serviceIntent = Intent(this, AdbTransferService::class.java).apply {
            action = AdbTransferService.ACTION_PREPARE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        showAdbWaitingState()
    }

    private fun stopTransfer() {
        isTransferActive = false
        transferCancelled = true
        transferStartTime = 0

        when {
            useAdbMode -> {
                val serviceIntent = Intent(this, AdbTransferService::class.java).apply {
                    action = AdbTransferService.ACTION_CLEANUP
                }
                startService(serviceIntent)
            }
            else -> {
                // Stop WiFi server
                val serviceIntent = Intent(this, TransferService::class.java).apply {
                    action = TransferService.ACTION_STOP_SERVER
                }
                startService(serviceIntent)
            }
        }

        showReadyState()
    }

    /**
     * Stop services that are in waiting/connecting state (before actual data transfer).
     * Keeps file selection intact so user can restart when they come back.
     */
    private fun stopWaitingServices() {
        if (useAdbMode) {
            val serviceIntent = Intent(this, AdbTransferService::class.java).apply {
                action = AdbTransferService.ACTION_CLEANUP
            }
            try { startService(serviceIntent) } catch (_: Exception) {}
        } else {
            val serviceIntent = Intent(this, TransferService::class.java).apply {
                action = TransferService.ACTION_STOP_SERVER
            }
            try { startService(serviceIntent) } catch (_: Exception) {}
        }
    }

    private fun handleStatusChange(status: TransferStatus) {
        // Ignore late callbacks after user cancelled — UI already shows ready state
        if (transferCancelled && status !is TransferStatus.Stopped) return

        when (status) {
            is TransferStatus.CreatingHotspot -> {
                showWaitingState()
                binding.tvStatus.text = "Checking hotspot..."
                binding.tvServerAddress.visibility = View.GONE
            }
            is TransferStatus.OpeningHotspotSettings -> {
                showWaitingState()
                binding.tvStatus.text = "Enable Wi-Fi Hotspot in Settings"
                binding.connectionWarning.visibility = View.VISIBLE
                binding.connectionWarning.setBackgroundColor(0xFFFF9800.toInt()) // Orange
                binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
                binding.connectionWarning.text = "Turn ON Wi-Fi Hotspot in Settings"
            }
            is TransferStatus.HotspotFailed -> {
                binding.tvStatus.text = "Enable hotspot manually"
            }
            is TransferStatus.Waiting -> {
                showWaitingState()
                binding.tvStatus.text = "Ready — Waiting for PC"
                binding.tvServerAddress.text = status.ipAddress
                binding.tvServerAddress.visibility = View.VISIBLE
                binding.tvConnectionHint.visibility = View.VISIBLE
                binding.tvTapToCopy.visibility = View.VISIBLE

                // Copy IP on tap
                binding.tvServerAddress.setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("IP Address", status.ipAddress)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(this, "IP copied!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            is TransferStatus.Connected -> {
                binding.tvStatus.text = "PC Connected!"
            }
            is TransferStatus.Transferring -> {
                isTransferActive = true
                showTransferringState()
            }
            is TransferStatus.Complete -> {
                transferCompleted = true
                showCompleteState(status.failedFiles, status.droppedFiles)
            }
            is TransferStatus.Error -> {
                showErrorState(status.message)
            }
            is TransferStatus.Cancelled -> {
                showCancelledState()
            }
            is TransferStatus.Stopped -> {
                // Don't overwrite the completion screen - stopServer() fires
                // 1 second after Complete and would wipe the results
                if (!transferCompleted) {
                    showReadyState()
                }
            }
        }
    }

    private fun showCancelledState() {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        // Unlock selection so user can modify files for retry
        unlockSelection()

        // Restore full layout with summary card and file list
        restoreDefaultLayout()

        transferStartTime = 0

        // Show ready layout so user can retry
        binding.layoutReady.visibility = View.VISIBLE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        // Show cancelled message
        binding.connectionWarning.visibility = View.VISIBLE
        binding.connectionWarning.setBackgroundColor(0xFFFF9800.toInt()) // Orange
        binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
        binding.connectionWarning.text = "Transfer Cancelled by PC\n\nYou can start a new transfer when ready."
    }

    private fun showHotspotCredentials(ssid: String, password: String, ipAddress: String) {
        // Show hotspot is ready
        binding.connectionWarning.visibility = View.VISIBLE
        binding.connectionWarning.setBackgroundColor(0xFF4CAF50.toInt()) // Green
        binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
        binding.connectionWarning.text = "WiFi Ready!\nConnect your PC to this phone's network"
    }

    private fun updateProgress(
        fileIndex: Int,
        totalFiles: Int,
        fileName: String,
        cumulativeBytes: Long,
        totalTransferBytes: Long
    ) {
        // Use whichever metric is further along. Byte-based is more accurate but only updates
        // on file completion; file-count is responsive but coarse. Max keeps the bar moving.
        val bytePercent = if (totalTransferBytes > 0) {
            ((cumulativeBytes * 100L) / totalTransferBytes).toInt()
        } else 0
        val filePercent = if (totalFiles > 0) {
            ((fileIndex - 1) * 100) / totalFiles
        } else 0
        val percent = maxOf(bytePercent, filePercent).coerceIn(0, 100)

        binding.progressBar.progress = percent
        binding.tvProgressPercent.text = "$percent%"
        binding.tvCurrentFile.text = "Sending: $fileName"
        binding.tvFileProgress.text = "File $fileIndex of $totalFiles"

        val elapsed = (System.currentTimeMillis() - transferStartTime) / 1000.0
        val speed = if (elapsed > 0) cumulativeBytes / elapsed else 0.0
        val remainingBytes = (totalTransferBytes - cumulativeBytes).coerceAtLeast(0L)
        val eta = if (speed > 0) (remainingBytes / speed).toLong() else 0L

        binding.tvSpeed.text = "${formatSize(speed.toLong())}/s"
        binding.tvEta.text = formatTime(eta)
    }

    private fun showWaitingState() {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.VISIBLE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        binding.tvStatus.text = "Waiting for PC..."
        binding.tvServerAddress.visibility = View.GONE
        binding.tvConnectionHint.visibility = View.GONE
        binding.tvTapToCopy.visibility = View.GONE
        binding.toolbar.title = "Waiting for PC"
    }

    private fun showTransferringState() {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.VISIBLE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        binding.connectionWarning.visibility = View.VISIBLE
        binding.connectionWarning.setBackgroundColor(0xFF2196F3.toInt())
        binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
        binding.connectionWarning.text = "Transferring..."
        binding.toolbar.title = "Transferring"
    }

    private fun showCompleteState(pcFailedFiles: List<String> = emptyList(), droppedFiles: List<String> = emptyList()) {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        // Track transfer end time
        transferEndTime = System.currentTimeMillis()

        binding.toolbar.title = "Transfer Complete"

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE

        // Build transfer results for HTTP mode
        transferResults.clear()
        val selectedFiles = SelectionManager.getSelectedFiles()
        val failedSet = pcFailedFiles.toSet()

        selectedFiles.forEach { file ->
            val fileName = file.name
            val filePath = file.absolutePath
            val success = failedSet.isEmpty() || !failedSet.any { filePath.endsWith(it) || it.endsWith(fileName) }
            transferResults.add(TransferLogger.TransferResult(
                fileName = fileName,
                filePath = filePath,
                success = success,
                error = if (!success) "Transfer failed" else null
            ))
        }

        // Add PC-reported failures that aren't in the accessible file list
        for (failedPath in pcFailedFiles) {
            val alreadyListed = transferResults.any { r ->
                r.filePath.endsWith(failedPath) || failedPath.endsWith(r.fileName)
            }
            if (!alreadyListed) {
                val name = failedPath.substringAfterLast("/").substringAfterLast("\\").ifEmpty { failedPath }
                transferResults.add(TransferLogger.TransferResult(
                    fileName = name,
                    filePath = failedPath,
                    success = false,
                    error = "Transfer failed"
                ))
            }
        }

        // Add files that were dropped at manifest build time (captured by DirectStreamServer)
        for (droppedPath in droppedFiles) {
            val name = java.io.File(droppedPath).name
            transferResults.add(TransferLogger.TransferResult(
                fileName = name,
                filePath = droppedPath,
                success = false,
                error = "File became inaccessible before transfer"
            ))
        }

        val transferredCount = selectedFiles.size - pcFailedFiles.size
        val failedCount = pcFailedFiles.size + droppedFiles.size
        val duration = (transferEndTime - transferStartTime) / 1000

        // Update stats UI
        binding.tvTransferredCount.text = transferredCount.toString()
        binding.tvFailedCount.text = failedCount.toString()
        binding.tvTransferTime.text = formatTime(duration)

        // Update header
        if (failedCount == 0) {
            binding.ivCompleteIcon.setImageResource(R.drawable.ic_success)
            binding.tvCompleteTitle.text = "Transfer Complete!"
            binding.tvCompleteMessage.text = "All $transferredCount files transferred successfully"
            binding.connectionWarning.visibility = View.GONE
        } else {
            binding.ivCompleteIcon.setImageResource(R.drawable.ic_error_circle)
            binding.tvCompleteTitle.text = "Transfer Completed with Errors"
            binding.tvCompleteMessage.text = "$transferredCount transferred, $failedCount failed"
            binding.connectionWarning.visibility = View.VISIBLE
            binding.connectionWarning.setBackgroundColor(0xFFFF9800.toInt())
            binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
            binding.connectionWarning.text = "Some files could not be transferred"
        }

        // Populate results list
        transferResultAdapter.setResults(transferResults)

        // Create transfer summary for logging
        currentTransferSummary = TransferLogger.TransferSummary(
            startTime = transferStartTime,
            endTime = transferEndTime,
            totalSelected = transferredCount + failedCount,
            totalTransferred = transferredCount,
            totalSkipped = 0,
            totalFailed = failedCount,
            totalSize = SelectionManager.getTotalSize(),
            results = transferResults.toList()
        )

        // Setup Save Log button
        binding.btnSaveLog.visibility = View.VISIBLE
        binding.btnSaveLog.setOnClickListener {
            saveTransferLog()
        }
    }

    private fun showErrorState(message: String) {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        // Unlock selection so user can modify files for retry
        unlockSelection()

        transferStartTime = 0

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE

        binding.tvErrorMessage.text = message
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatTime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    // ADB Mode specific methods

    private fun showAdbWaitingState() {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.VISIBLE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.tvStatus.text = "Preparing..."
        binding.tvServerAddress.visibility = View.GONE
        binding.toolbar.title = "Preparing"
    }

    private fun handleAdbStatusChange(status: AdbTransferStatus) {
        // Ignore late callbacks after user cancelled — UI already shows ready state
        if (transferCancelled) return

        when (status) {
            is AdbTransferStatus.Preparing -> {
                showAdbWaitingState()
                binding.tvStatus.text = "Preparing..."
            }
            is AdbTransferStatus.WaitingForPc -> {
                showWaitingForPcState("USB")
            }
            is AdbTransferStatus.Sending -> {
                showAdbWaitingState()
                binding.tvStatus.text = "Connected to PC!"
            }
            is AdbTransferStatus.Processing -> {
                isTransferActive = true
                showAdbTransferInProgress()
            }
            is AdbTransferStatus.Progress -> {
                isTransferActive = true
                // Show detailed progress with actual file counts
                showAdbTransferProgress(status.percent, status.currentFile, status.completed, status.total)
            }
            is AdbTransferStatus.Complete -> {
                transferCompleted = true
                showAdbCompleteState(status.fileCount, status.failedFiles, status.droppedFiles, status.wasResumed, status.resumedFromFiles)
            }
            is AdbTransferStatus.Failed -> {
                showAdbFailedState(status.reason, status.failedFiles)
            }
            is AdbTransferStatus.Ready -> {
                showAdbReadyState(status.fileCount, status.skippedCount)
            }
            is AdbTransferStatus.Partial -> {
                showAdbPartialState(status.successCount, status.failedFiles, status.droppedFiles, status.canResume)
            }
            is AdbTransferStatus.Error -> {
                showErrorState(status.message)
            }
            is AdbTransferStatus.Cleaned -> {
                // Transfer complete, cleaned up
            }
        }
    }

    private fun showAdbReadyState(fileCount: Int, skippedCount: Int = 0) {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.VISIBLE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        binding.tvStatus.text = "Ready!"
        binding.tvServerAddress.visibility = View.VISIBLE

        // Show status
        val statusText = if (skippedCount > 0) {
            "$fileCount files ready ($skippedCount skipped)\nWaiting for PC..."
        } else {
            "$fileCount files ready\nWaiting for PC..."
        }
        binding.tvServerAddress.text = statusText

        // Hide cancel button - transfer will start automatically
        binding.btnCancel.visibility = View.GONE

        // Update connection warning
        binding.connectionWarning.visibility = View.VISIBLE
        if (skippedCount > 0) {
            binding.connectionWarning.setBackgroundColor(0xFFFF9800.toInt()) // Orange warning
            binding.connectionWarning.text = "$skippedCount files skipped (protected folders)"
        } else {
            binding.connectionWarning.setBackgroundColor(0xFF4CAF50.toInt()) // Green
            binding.connectionWarning.text = "Ready! Click 'Start Transfer' on PC"
        }
        binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun showAdbPartialState(successCount: Int, failedFiles: List<String>, droppedFiles: List<String> = emptyList(), canResume: Boolean = true) {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        // Unlock selection so user can modify files for retry
        unlockSelection()

        transferStartTime = 0

        // Track transfer end time
        transferEndTime = System.currentTimeMillis()

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE

        lastFailedFiles = failedFiles

        // Calculate stats
        val transferredCount = successCount
        val failedCount = failedFiles.size + droppedFiles.size
        val duration = (transferEndTime - transferStartTime) / 1000

        // Update stats UI
        binding.tvTransferredCount.text = transferredCount.toString()
        binding.tvFailedCount.text = failedCount.toString()
        binding.tvTransferTime.text = formatTime(duration)

        // Update header for partial transfer (resumable)
        binding.ivCompleteIcon.setImageResource(R.drawable.ic_error_circle)
        binding.tvCompleteTitle.text = "Transfer Interrupted"
        if (canResume) {
            binding.tvCompleteMessage.text = "$transferredCount files done - Can be resumed!\nReconnect to continue where you left off"
            binding.connectionWarning.visibility = View.VISIBLE
            binding.connectionWarning.setBackgroundColor(0xFF2196F3.toInt()) // Blue
            binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
            binding.connectionWarning.text = "You can continue this transfer later"
        } else {
            binding.tvCompleteMessage.text = "$transferredCount transferred, $failedCount failed"
            binding.connectionWarning.visibility = View.VISIBLE
            binding.connectionWarning.setBackgroundColor(0xFFFF9800.toInt()) // Orange
            binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
            binding.connectionWarning.text = "Some files could not be transferred"
        }

        // Build transfer results
        transferResults.clear()
        val selectedFiles = SelectionManager.getSelectedFiles()
        val failedSet = failedFiles.toSet()

        selectedFiles.forEach { file ->
            val fileName = file.name
            val filePath = file.absolutePath
            val success = !failedSet.any { filePath.endsWith(it) || it.endsWith(fileName) }
            transferResults.add(TransferLogger.TransferResult(
                fileName = fileName,
                filePath = filePath,
                success = success,
                error = if (!success) "Transfer incomplete (can resume)" else null
            ))
        }

        // Add files that were dropped at preparation time
        for (droppedPath in droppedFiles) {
            val name = java.io.File(droppedPath).name
            transferResults.add(TransferLogger.TransferResult(
                fileName = name,
                filePath = droppedPath,
                success = false,
                error = "File became inaccessible before transfer"
            ))
        }

        // Populate results list
        transferResultAdapter.setResults(transferResults)

        // Create transfer summary
        currentTransferSummary = TransferLogger.TransferSummary(
            startTime = transferStartTime,
            endTime = transferEndTime,
            totalSelected = SelectionManager.getSelectedCount(),
            totalTransferred = transferredCount,
            totalSkipped = 0,
            totalFailed = failedCount,
            totalSize = SelectionManager.getTotalSize(),
            results = transferResults.toList()
        )

        // Show save log button
        binding.btnSaveLog.visibility = View.VISIBLE
        binding.btnSaveLog.setOnClickListener {
            saveTransferLog()
        }
    }

    private fun updateAdbProgress(current: Int, total: Int) {
        binding.tvStatus.text = "Preparing..."
    }

    // Simple transfer in progress state
    private fun showAdbTransferInProgress() {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.VISIBLE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        binding.tvCurrentFile.text = "Starting transfer..."
        binding.tvFileProgress.text = "Preparing files..."
        binding.progressBar.progress = 0
        binding.tvProgressPercent.text = "0%"

        // Update connection warning to show mode
        binding.connectionWarning.visibility = View.VISIBLE
        binding.connectionWarning.setBackgroundColor(0xFF2196F3.toInt())
        binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
        binding.connectionWarning.text = "Transferring via USB..."
        binding.toolbar.title = "Transferring"

        // Show cancel button
        binding.btnCancel.visibility = View.VISIBLE
    }

    // Detailed progress with percentage, file name, and speed
    private fun showAdbTransferProgress(percent: Int, currentFile: String, completed: Int, total: Int) {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.VISIBLE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        // Cap at 99% until truly complete
        val displayPercent = if (completed >= total && total > 0) 100 else minOf(percent, 99)

        // Update progress bar
        binding.progressBar.progress = displayPercent
        binding.tvProgressPercent.text = "$displayPercent%"

        // Show current file
        val shortFile = if (currentFile.length > 30) {
            "..." + currentFile.takeLast(27)
        } else {
            currentFile
        }
        binding.tvCurrentFile.text = "Sending: $shortFile"

        // Hide speed - show file count instead
        binding.tvSpeed.text = ""

        // Show actual file progress from PC
        binding.tvFileProgress.text = "$completed of $total files"

        // Update connection warning with mode
        binding.connectionWarning.visibility = View.VISIBLE
        binding.connectionWarning.setBackgroundColor(0xFF2196F3.toInt())
        binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
        binding.connectionWarning.text = "Transferring via USB..."
        binding.toolbar.title = "Transferring $displayPercent%"

        // Show cancel button
        binding.btnCancel.visibility = View.VISIBLE
    }

    // Store failed files for export
    private var lastFailedFiles: List<String> = emptyList()

    private fun showAdbCompleteState(fileCount: Int, failedFiles: List<String>, droppedFiles: List<String> = emptyList(), wasResumed: Boolean = false, resumedFromFiles: Int = 0) {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        // Track transfer end time
        transferEndTime = System.currentTimeMillis()

        binding.toolbar.title = "Transfer Complete"

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE

        lastFailedFiles = failedFiles

        // Build transfer results from selected files + PC-reported failures + dropped files
        transferResults.clear()
        val selectedFiles = SelectionManager.getSelectedFiles()
        val failedSet = failedFiles.toSet()

        selectedFiles.forEach { file ->
            val fileName = file.name
            val filePath = file.absolutePath
            val success = !failedSet.any { filePath.endsWith(it) || it.endsWith(fileName) }
            transferResults.add(TransferLogger.TransferResult(
                fileName = fileName,
                filePath = filePath,
                success = success,
                error = if (!success) "Transfer failed" else null
            ))
        }

        // Add PC-reported failures that aren't in the accessible file list
        // (files that were in manifest but failed during transfer)
        for (failedPath in failedFiles) {
            val alreadyListed = transferResults.any { r ->
                r.filePath.endsWith(failedPath) || failedPath.endsWith(r.fileName)
            }
            if (!alreadyListed) {
                val name = failedPath.substringAfterLast("/").substringAfterLast("\\").ifEmpty { failedPath }
                transferResults.add(TransferLogger.TransferResult(
                    fileName = name,
                    filePath = failedPath,
                    success = false,
                    error = "Transfer failed"
                ))
            }
        }

        // Add files that were dropped at preparation time (captured by AdbTransferService)
        // These were inaccessible when the manifest was built — never sent to PC
        for (droppedPath in droppedFiles) {
            val name = java.io.File(droppedPath).name
            transferResults.add(TransferLogger.TransferResult(
                fileName = name,
                filePath = droppedPath,
                success = false,
                error = "File became inaccessible before transfer"
            ))
        }

        // Calculate stats — use PC-reported counts + dropped files
        val transferredCount = fileCount
        val failedCount = failedFiles.size + droppedFiles.size
        val duration = (transferEndTime - transferStartTime) / 1000

        // Update stats UI
        binding.tvTransferredCount.text = transferredCount.toString()
        binding.tvFailedCount.text = failedCount.toString()
        binding.tvTransferTime.text = formatTime(duration)

        // Update header
        if (failedCount == 0) {
            binding.ivCompleteIcon.setImageResource(R.drawable.ic_success)
            if (wasResumed) {
                binding.tvCompleteTitle.text = "Transfer Resumed & Complete!"
                binding.tvCompleteMessage.text = "All $transferredCount files done (resumed from $resumedFromFiles)"
                binding.connectionWarning.visibility = View.VISIBLE
                binding.connectionWarning.setBackgroundColor(0xFF4CAF50.toInt()) // Green
                binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
                binding.connectionWarning.text = "Successfully resumed previous transfer"
            } else {
                binding.tvCompleteTitle.text = "Transfer Complete!"
                binding.tvCompleteMessage.text = "All $transferredCount files transferred successfully"
                binding.connectionWarning.visibility = View.GONE
            }
        } else {
            binding.ivCompleteIcon.setImageResource(R.drawable.ic_error_circle)
            binding.tvCompleteTitle.text = "Transfer Completed with Errors"
            binding.tvCompleteMessage.text = "$transferredCount transferred, $failedCount failed"
            binding.connectionWarning.visibility = View.VISIBLE
            binding.connectionWarning.setBackgroundColor(0xFFFF9800.toInt()) // Orange
            binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
            binding.connectionWarning.text = "Some files could not be transferred"
        }

        // Populate results list
        transferResultAdapter.setResults(transferResults)

        // Create transfer summary for logging
        currentTransferSummary = TransferLogger.TransferSummary(
            startTime = transferStartTime,
            endTime = transferEndTime,
            totalSelected = SelectionManager.getSelectedCount(),
            totalTransferred = transferredCount,
            totalSkipped = 0,
            totalFailed = failedCount,
            totalSize = SelectionManager.getTotalSize(),
            results = transferResults.toList()
        )

        // Setup Save Log button
        binding.btnSaveLog.visibility = View.VISIBLE
        binding.btnSaveLog.setOnClickListener {
            saveTransferLog()
        }
    }

    private fun saveFailedFilesLog(failedFiles: List<String>) {
        try {
            // Get external storage root directory
            val externalStorageDir = android.os.Environment.getExternalStorageDirectory()

            // Create DeCloudLogs folder if it doesn't exist
            val logsDir = File(externalStorageDir, "DeCloudLogs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            // Verify folder was created successfully
            if (!logsDir.exists() || !logsDir.canWrite()) {
                throw Exception("Cannot create or write to DeCloudLogs folder")
            }

            // Create log file with timestamp
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = "Failed_$timestamp.txt"
            val logFile = File(logsDir, fileName)

            val content = buildString {
                appendLine("DeCloud - Failed Files Log")
                appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine("Total failed: ${failedFiles.size}")
                appendLine("=" .repeat(50))
                appendLine()
                failedFiles.forEach { appendLine(it) }
            }

            logFile.writeText(content)

            // Show success message
            android.widget.Toast.makeText(
                this,
                "Log saved to DeCloudLogs/$fileName",
                android.widget.Toast.LENGTH_LONG
            ).show()

            // Optionally open the file
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.provider",
                    logFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/plain")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Can't open file, just show toast
            }

        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "Could not save log: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveTransferLog() {
        val summary = currentTransferSummary ?: return

        try {
            val logFile = TransferLogger.saveLog(this, summary)
            if (logFile != null) {
                android.widget.Toast.makeText(
                    this,
                    "Log saved to ${TransferLogger.getLogDirectory()}",
                    android.widget.Toast.LENGTH_LONG
                ).show()

                // Try to open the log file
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "${packageName}.provider",
                        logFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "text/plain")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Can't open file, just show toast
                }
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Could not save log",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "Error saving log: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showAdbFailedState(reason: String, failedFiles: List<String>) {
        // Hide loading overlay if showing
        binding.loadingOverlay.visibility = View.GONE

        expandBottomBar()

        // Unlock selection so user can modify files for retry
        unlockSelection()

        transferStartTime = 0

        binding.layoutReady.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutTransferring.visibility = View.GONE
        binding.layoutComplete.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE

        lastFailedFiles = failedFiles

        var message = "Transfer failed"
        if (reason.isNotEmpty()) {
            message += "\n$reason"
        }
        if (failedFiles.isNotEmpty()) {
            if (failedFiles.size <= 5) {
                message += "\n\nFailed files:\n" + failedFiles.joinToString("\n") { "• $it" }
            } else {
                message += "\n\n${failedFiles.size} files failed"
                message += "\n(First 5):\n" + failedFiles.take(5).joinToString("\n") { "• $it" }
            }
        }

        binding.tvErrorMessage.text = message

        // Show save log button if many failed files
        if (failedFiles.size > 5) {
            binding.btnSaveErrorLog.visibility = View.VISIBLE
            binding.btnSaveErrorLog.setOnClickListener {
                saveFailedFilesLog(failedFiles)
            }
            binding.connectionWarning.visibility = View.VISIBLE
            binding.connectionWarning.setBackgroundColor(0xFFF44336.toInt()) // Red
            binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
            binding.connectionWarning.text = "Tap 'Save Log' for full list"
        } else {
            binding.btnSaveErrorLog.visibility = View.GONE
            binding.connectionWarning.visibility = View.VISIBLE
            binding.connectionWarning.setBackgroundColor(0xFFF44336.toInt()) // Red
            binding.connectionWarning.setTextColor(0xFFFFFFFF.toInt())
            binding.connectionWarning.text = "Transfer Failed"
        }
    }
}
