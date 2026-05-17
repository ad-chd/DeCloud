package com.decloud.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.databinding.ActivityReceiveBinding
import com.decloud.receiver.AdbCommandReceiver
import com.decloud.service.ReceiveService
import com.decloud.util.NetworkUtils
import com.decloud.util.QRCodeGenerator

class ReceiveActivity : BaseActivity() {

    companion object {
        const val EXTRA_USB_MODE = "extra_usb_mode"
        const val EXTRA_USB_TOTAL_FILES = "extra_usb_total_files"
    }

    private lateinit var binding: ActivityReceiveBinding
    private var receiveService: ReceiveService? = null
    private var serviceBound = false

    private var receiveStartTime: Long = 0
    private var totalReceivedFiles = 0
    private var totalReceivedBytes = 0L
    private var isReceiving = false
    private var isUsbMode = false
    private var usbTotalFiles = 0

    private val receivedFiles = mutableListOf<ReceivedFileInfo>()
    private lateinit var receivedFilesAdapter: ReceivedFilesAdapter

    data class ReceivedFileInfo(val name: String, val size: Long)

    // Local broadcast receiver for USB file progress from AdbCommandReceiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                AdbCommandReceiver.LOCAL_USB_FILE_RECEIVED -> {
                    val fileName = intent.getStringExtra("file_name") ?: ""
                    val index = intent.getIntExtra("index", 0)
                    val total = intent.getIntExtra("total", 0)
                    runOnUiThread { onUsbFileReceived(fileName, index, total) }
                }
                AdbCommandReceiver.LOCAL_USB_RECEIVE_DONE -> {
                    val totalFiles = intent.getIntExtra("total_files", 0)
                    val failed = intent.getIntExtra("failed", 0)
                    runOnUiThread { onUsbReceiveDone(totalFiles, failed) }
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ReceiveService.LocalBinder
            receiveService = binder.getService()
            serviceBound = true

            receiveService?.onReceiveReady = { ipAddress, port ->
                runOnUiThread { showWaitingState(ipAddress, port) }
            }

            receiveService?.onFileReceived = { fileName, fileIndex, fileSize ->
                runOnUiThread { onFileReceived(fileName, fileIndex, fileSize) }
            }

            receiveService?.onReceiveProgress = { totalFiles, totalBytes ->
                runOnUiThread { onReceiveProgress(totalFiles, totalBytes) }
            }

            receiveService?.onError = { error ->
                runOnUiThread { showErrorState(error) }
            }

            receiveService?.onStopped = {
                runOnUiThread { stopReceiveAndFinish() }
            }

            // If service is already running (e.g., activity was recreated or we bound AFTER the
            // service's startReceiveServer already fired onReceiveReady), show waiting state
            // using the IP/port the server actually bound to — NOT a fresh NetworkUtils query,
            // which could return a different address or "0.0.0.0".
            // Note: the onReceiveReady setter also replays automatically, so this is a defensive
            // fallback in case the callback was assigned before currentIp was populated.
            val svc = receiveService
            if (svc?.isServerRunning() == true) {
                val ip = svc.currentIp
                val port = svc.currentPort
                if (!ip.isNullOrBlank() && port > 0) {
                    runOnUiThread { showWaitingState(ip, port) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiveService = null
            serviceBound = false
        }
    }

    // True when user picked a mode (WiFi or USB) — used to decide what onStart does
    private var modeSelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        setupModeSelection()

        // Check if launched in USB mode (from AdbCommandReceiver broadcast)
        isUsbMode = intent.getBooleanExtra(EXTRA_USB_MODE, false)
        usbTotalFiles = intent.getIntExtra(EXTRA_USB_TOTAL_FILES, 0)

        if (isUsbMode) {
            // Launched by PC broadcast — go directly to USB receive
            modeSelected = true
            startUsbReceiveMode()
        } else {
            // Launched by user — show mode selection
            showModeSelection()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle re-launch while already open (FLAG_ACTIVITY_SINGLE_TOP)
        intent ?: return
        val newUsbMode = intent.getBooleanExtra(EXTRA_USB_MODE, false)
        if (newUsbMode) {
            isUsbMode = true
            modeSelected = true
            usbTotalFiles = intent.getIntExtra(EXTRA_USB_TOTAL_FILES, 0)
            // If already in USB waiting state, transition to receiving
            // If in mode selection or any other state, start USB receive
            startUsbReceiveMode()
            // Ensure broadcast receiver is registered
            val filter = IntentFilter().apply {
                addAction(AdbCommandReceiver.LOCAL_USB_FILE_RECEIVED)
                addAction(AdbCommandReceiver.LOCAL_USB_RECEIVE_DONE)
            }
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            LocalBroadcastManager.getInstance(this).registerReceiver(usbReceiver, filter)
        }
    }

    override fun onStart() {
        super.onStart()
        if (isUsbMode) {
            // Register for local USB broadcasts
            val filter = IntentFilter().apply {
                addAction(AdbCommandReceiver.LOCAL_USB_FILE_RECEIVED)
                addAction(AdbCommandReceiver.LOCAL_USB_RECEIVE_DONE)
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(usbReceiver, filter)
        }
        if (!isUsbMode && modeSelected) {
            // Bind to WiFi receive service (only after user picked WiFi mode)
            Intent(this, ReceiveService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isUsbMode) {
            // Keep local receiver registered only while activity is visible
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
        }
        // Unbind from WiFi service if bound
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onBackPressed() {
        handleBackPress()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
    }

    private fun handleBackPress() {
        if (isReceiving) {
            // Transfer in progress — minimize to background, don't kill it
            moveTaskToBack(true)
            return
        }
        // If in a mode's waiting/complete/error state, go back to mode selection
        if (modeSelected && binding.layoutModeSelection.visibility != View.VISIBLE) {
            if (!isUsbMode && serviceBound) {
                receiveService?.stopReceiveServer()
            }
            modeSelected = false
            isUsbMode = false
            showModeSelection()
            return
        }
        if (isUsbMode) {
            finish()
        } else {
            stopReceiveAndFinish()
        }
    }

    private fun setupRecyclerView() {
        receivedFilesAdapter = ReceivedFilesAdapter(receivedFiles)
        binding.recyclerViewReceivedFiles.apply {
            layoutManager = LinearLayoutManager(this@ReceiveActivity)
            adapter = receivedFilesAdapter
        }
    }

    private fun setupButtons() {
        binding.btnCancelWaiting.setOnClickListener {
            stopReceiveAndFinish()
        }

        binding.btnStopReceive.setOnClickListener {
            stopReceiveAndFinish()
        }

        binding.btnDone.setOnClickListener {
            if (isUsbMode) {
                modeSelected = false
                isUsbMode = false
                showModeSelection()
            } else {
                stopReceiveAndFinish()
            }
        }

        binding.btnRetry.setOnClickListener {
            if (isUsbMode) {
                showUsbWaitingState()
            } else {
                startReceiveMode()
            }
        }
    }

    // ==================== MODE SELECTION ====================

    private fun setupModeSelection() {
        binding.cardModeWifi.setOnClickListener {
            modeSelected = true
            isUsbMode = false
            binding.layoutModeSelection.visibility = View.GONE
            startReceiveMode()
            // Bind to WiFi service now that mode is selected
            Intent(this, ReceiveService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }

        binding.cardModeUsb.setOnClickListener {
            modeSelected = true
            isUsbMode = true
            binding.layoutModeSelection.visibility = View.GONE
            showUsbWaitingState()
        }
    }

    private fun showModeSelection() {
        binding.toolbar.title = "Receive from PC"
        binding.layoutModeSelection.visibility = View.VISIBLE

        // Hide everything else
        binding.connectionWarning.visibility = View.GONE
        binding.cardConnectionInfo.visibility = View.GONE
        binding.ivQrCode.visibility = View.GONE
        binding.tvQrHint.visibility = View.GONE
        binding.cardInstructions.visibility = View.GONE
        binding.cardUsbInstructions.visibility = View.GONE
        binding.tvReceivedFilesHeader.visibility = View.GONE
        binding.recyclerViewReceivedFiles.visibility = View.GONE
        showState()  // Hide all state panels
    }

    // ==================== USB RECEIVE MODE ====================

    /**
     * Show USB waiting state — user selected USB mode manually.
     * Phone is ready, waiting for PC to push files via ADB.
     */
    private fun showUsbWaitingState() {
        // Reset state
        receivedFiles.clear()
        receivedFilesAdapter.notifyDataSetChanged()
        totalReceivedFiles = 0
        totalReceivedBytes = 0L
        isReceiving = false

        // Update toolbar
        binding.toolbar.title = "Receive via USB"

        // Hide WiFi-specific elements
        binding.cardConnectionInfo.visibility = View.GONE
        binding.ivQrCode.visibility = View.GONE
        binding.tvQrHint.visibility = View.GONE
        binding.cardInstructions.visibility = View.GONE
        binding.layoutModeSelection.visibility = View.GONE

        // Show USB instructions
        binding.cardUsbInstructions.visibility = View.VISIBLE

        // Show waiting state
        showState(waiting = true)
        binding.tvWaitingStatus.text = "Waiting for PC to send files via USB..."

        // Show connection info
        binding.connectionWarning.visibility = View.VISIBLE
        binding.connectionWarning.setBackgroundColor(ContextCompat.getColor(this, R.color.transfer_ready_bg))
        binding.connectionWarning.setTextColor(ContextCompat.getColor(this, R.color.transfer_ready_text))
        binding.connectionWarning.text = "USB Ready — Start transfer on PC"

        // Register for USB broadcasts
        val filter = IntentFilter().apply {
            addAction(AdbCommandReceiver.LOCAL_USB_FILE_RECEIVED)
            addAction(AdbCommandReceiver.LOCAL_USB_RECEIVE_DONE)
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        LocalBroadcastManager.getInstance(this).registerReceiver(usbReceiver, filter)
    }

    /**
     * Start USB receiving UI — called when PC broadcasts USB_RECEIVE_START
     * (from AdbCommandReceiver) or transitions from waiting state.
     */
    private fun startUsbReceiveMode() {
        // Reset state
        receivedFiles.clear()
        receivedFilesAdapter.notifyDataSetChanged()
        totalReceivedFiles = 0
        totalReceivedBytes = 0L
        isReceiving = true
        receiveStartTime = System.currentTimeMillis()

        // Update toolbar
        binding.toolbar.title = "Receiving via USB"

        // Hide WiFi-specific elements and mode selection
        binding.layoutModeSelection.visibility = View.GONE
        binding.connectionWarning.visibility = View.GONE
        binding.cardConnectionInfo.visibility = View.GONE
        binding.ivQrCode.visibility = View.GONE
        binding.tvQrHint.visibility = View.GONE
        binding.cardInstructions.visibility = View.GONE
        binding.cardUsbInstructions.visibility = View.GONE

        // Show receiving state directly
        showState(receiving = true)
        binding.tvReceivedCount.text = "0 / $usbTotalFiles files"
        binding.tvReceivedSize.text = ""
        binding.tvCurrentReceiveFile.text = "Receiving files from PC via USB..."

        // Show transfer mode indicator
        binding.connectionWarning.visibility = View.VISIBLE
        binding.connectionWarning.setBackgroundColor(ContextCompat.getColor(this, R.color.transfer_active_bg))
        binding.connectionWarning.setTextColor(ContextCompat.getColor(this, R.color.transfer_active_text))
        binding.connectionWarning.text = "Receiving via USB..."
    }

    private fun onUsbFileReceived(fileName: String, index: Int, total: Int) {
        if (!isReceiving) {
            // First file arrived — transition from waiting to receiving
            isReceiving = true
            usbTotalFiles = total
            receiveStartTime = System.currentTimeMillis()

            // Hide waiting elements, show receiving UI
            binding.cardUsbInstructions.visibility = View.GONE
            binding.toolbar.title = "Receiving via USB"
            showState(receiving = true)

            binding.connectionWarning.visibility = View.VISIBLE
            binding.connectionWarning.setBackgroundColor(ContextCompat.getColor(this, R.color.transfer_active_bg))
            binding.connectionWarning.setTextColor(ContextCompat.getColor(this, R.color.transfer_active_text))
            binding.connectionWarning.text = "Receiving via USB..."
        }

        totalReceivedFiles = index
        usbTotalFiles = total

        // Update receiving UI
        binding.tvReceivedCount.text = "$index / $total files"
        binding.tvReceivedSize.text = ""

        val shortName = if (fileName.length > 40) {
            "..." + fileName.takeLast(37)
        } else {
            fileName
        }
        binding.tvCurrentReceiveFile.text = "Received: $shortName"

        // Add to received files list
        val displayName = fileName.substringAfterLast("/")
        receivedFiles.add(0, ReceivedFileInfo(displayName, 0))
        receivedFilesAdapter.notifyItemInserted(0)

        // Show received files section
        binding.tvReceivedFilesHeader.visibility = View.VISIBLE
        binding.recyclerViewReceivedFiles.visibility = View.VISIBLE
        binding.recyclerViewReceivedFiles.scrollToPosition(0)
    }

    private fun onUsbReceiveDone(totalFiles: Int, failed: Int) {
        isReceiving = false
        totalReceivedFiles = totalFiles

        val duration = if (receiveStartTime > 0) {
            System.currentTimeMillis() - receiveStartTime
        } else {
            0L
        }

        showState(complete = true)

        val message = if (failed == 0) {
            "$totalFiles files received via USB"
        } else {
            "$totalFiles files received ($failed failed)"
        }
        binding.tvCompleteMessage.text = message
        binding.tvStatFiles.text = "$totalFiles"
        binding.tvStatSize.text = if (failed > 0) "$failed failed" else "USB"
        binding.tvStatDuration.text = formatDuration(duration)
    }

    // ==================== WIFI RECEIVE MODE ====================

    private fun startReceiveMode() {
        binding.toolbar.title = "Receive via Wi-Fi"
        binding.layoutModeSelection.visibility = View.GONE
        binding.cardUsbInstructions.visibility = View.GONE

        // Check network
        val connInfo = NetworkUtils.getConnectionInfo()
        if (connInfo.type == NetworkUtils.ConnectionType.NONE) {
            binding.connectionWarning.text = "No network connection. Enable Wi-Fi Hotspot or connect to a network."
            binding.connectionWarning.visibility = View.VISIBLE
        } else {
            binding.connectionWarning.visibility = View.GONE
        }

        // Reset state
        receivedFiles.clear()
        receivedFilesAdapter.notifyDataSetChanged()
        totalReceivedFiles = 0
        totalReceivedBytes = 0L
        isReceiving = false
        receiveStartTime = 0

        // Show waiting state
        showState(waiting = true)
        binding.tvWaitingStatus.text = "Starting receive server..."

        // Start the service
        val serviceIntent = Intent(this, ReceiveService::class.java).apply {
            action = ReceiveService.ACTION_START_RECEIVE
        }
        startService(serviceIntent)
    }

    private fun showWaitingState(ipAddress: String, port: Int) {
        showState(waiting = true)
        binding.tvWaitingStatus.text = "Waiting for PC to connect..."

        // Show connection info card
        binding.cardConnectionInfo.visibility = View.VISIBLE
        binding.tvIpAddress.text = ipAddress

        val connInfo = NetworkUtils.getConnectionInfo()
        binding.tvConnectionType.text = connInfo.displayName

        // Update connection warning with helpful info
        when (connInfo.type) {
            NetworkUtils.ConnectionType.WIFI_HOTSPOT -> {
                binding.connectionWarning.text = "Hotspot active — connect your PC to this phone's Wi-Fi"
                binding.connectionWarning.setBackgroundColor(ContextCompat.getColor(this, R.color.banner_hotspot_bg))
                binding.connectionWarning.setTextColor(ContextCompat.getColor(this, R.color.banner_hotspot_text))
                binding.connectionWarning.visibility = View.VISIBLE
            }
            NetworkUtils.ConnectionType.USB_TETHERING -> {
                binding.connectionWarning.text = "USB Tethering active — PC should be connected via USB"
                binding.connectionWarning.setBackgroundColor(ContextCompat.getColor(this, R.color.banner_usb_bg))
                binding.connectionWarning.setTextColor(ContextCompat.getColor(this, R.color.banner_usb_text))
                binding.connectionWarning.visibility = View.VISIBLE
            }
            NetworkUtils.ConnectionType.WIFI_LAN -> {
                binding.connectionWarning.text = "Wi-Fi connected — make sure PC is on the same network"
                binding.connectionWarning.setBackgroundColor(ContextCompat.getColor(this, R.color.banner_wifi_bg))
                binding.connectionWarning.setTextColor(ContextCompat.getColor(this, R.color.banner_wifi_text))
                binding.connectionWarning.visibility = View.VISIBLE
            }
            NetworkUtils.ConnectionType.NONE -> {
                binding.connectionWarning.text = "No network connection detected"
                binding.connectionWarning.setBackgroundColor(ContextCompat.getColor(this, R.color.banner_error_bg))
                binding.connectionWarning.setTextColor(ContextCompat.getColor(this, R.color.banner_error_text))
                binding.connectionWarning.visibility = View.VISIBLE
            }
        }

        // Generate and show QR code
        val qrBitmap = QRCodeGenerator.generateConnectionQR(ipAddress, port, 512)
        if (qrBitmap != null) {
            binding.ivQrCode.setImageBitmap(qrBitmap)
            binding.ivQrCode.visibility = View.VISIBLE
            binding.tvQrHint.visibility = View.VISIBLE
        }

        // Show instructions
        binding.cardInstructions.visibility = View.VISIBLE
    }

    private fun onFileReceived(fileName: String, fileIndex: Int, fileSize: Long) {
        if (!isReceiving) {
            isReceiving = true
            receiveStartTime = System.currentTimeMillis()
            showState(receiving = true)
            // Hide instructions and QR once transfer starts
            binding.cardInstructions.visibility = View.GONE
        }

        totalReceivedFiles = fileIndex
        totalReceivedBytes += fileSize

        // Update receiving UI
        binding.tvReceivedCount.text = "$totalReceivedFiles files"
        binding.tvReceivedSize.text = formatSize(totalReceivedBytes)

        val shortName = if (fileName.length > 40) {
            "..." + fileName.takeLast(37)
        } else {
            fileName
        }
        binding.tvCurrentReceiveFile.text = "Received: $shortName"

        // Add to received files list
        val displayName = fileName.substringAfterLast("/")
        receivedFiles.add(0, ReceivedFileInfo(displayName, fileSize))
        receivedFilesAdapter.notifyItemInserted(0)

        // Show received files section
        binding.tvReceivedFilesHeader.visibility = View.VISIBLE
        binding.recyclerViewReceivedFiles.visibility = View.VISIBLE

        // Auto-scroll to top
        binding.recyclerViewReceivedFiles.scrollToPosition(0)
    }

    private fun onReceiveProgress(totalFiles: Int, totalBytes: Long) {
        totalReceivedFiles = totalFiles
        totalReceivedBytes = totalBytes

        binding.tvReceivedCount.text = "$totalReceivedFiles files"
        binding.tvReceivedSize.text = formatSize(totalReceivedBytes)
    }

    // ==================== SHARED UI STATES ====================

    private fun showCompleteState() {
        isReceiving = false
        val duration = if (receiveStartTime > 0) {
            System.currentTimeMillis() - receiveStartTime
        } else {
            0L
        }

        showState(complete = true)

        binding.tvCompleteMessage.text = "$totalReceivedFiles files received"
        binding.tvStatFiles.text = "$totalReceivedFiles"
        binding.tvStatSize.text = formatSize(totalReceivedBytes)
        binding.tvStatDuration.text = formatDuration(duration)

        // Stop the WiFi server if running
        if (!isUsbMode) {
            receiveService?.stopReceiveServer()
        }
    }

    private fun showErrorState(error: String) {
        isReceiving = false
        showState(error = true)
        binding.tvErrorMessage.text = error

        if (!isUsbMode) {
            receiveService?.stopReceiveServer()
        }
    }

    private fun showState(
        waiting: Boolean = false,
        receiving: Boolean = false,
        complete: Boolean = false,
        error: Boolean = false
    ) {
        binding.layoutWaiting.visibility = if (waiting) View.VISIBLE else View.GONE
        binding.layoutReceiving.visibility = if (receiving) View.VISIBLE else View.GONE
        binding.layoutComplete.visibility = if (complete) View.VISIBLE else View.GONE
        binding.layoutError.visibility = if (error) View.VISIBLE else View.GONE
    }

    private fun stopReceiveAndFinish() {
        if (serviceBound && receiveService?.isServerRunning() == true) {
            receiveService?.stopReceiveServer()
        }
        finish()
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    // Simple adapter for received files list
    class ReceivedFilesAdapter(
        private val files: List<ReceivedFileInfo>
    ) : RecyclerView.Adapter<ReceivedFilesAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFileName: TextView = view.findViewById(android.R.id.text1)
            val tvFileSize: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.tvFileName.text = file.name
            holder.tvFileName.textSize = 14f
            holder.tvFileSize.text = if (file.size > 0) formatSize(file.size) else ""
            holder.tvFileSize.textSize = 12f
        }

        override fun getItemCount() = files.size

        private fun formatSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
                .coerceIn(0, units.size - 1)
            return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }
    }
}
