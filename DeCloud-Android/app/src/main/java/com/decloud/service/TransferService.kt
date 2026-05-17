package com.decloud.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.decloud.R
import com.decloud.hotspot.HotspotManager
import com.decloud.model.SelectionManager
import com.decloud.server.DirectStreamServer
import com.decloud.ui.ReadyToSendActivity
import com.decloud.util.BluetoothDiscoveryServer
import com.decloud.util.DiscoveryBroadcaster
import com.decloud.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service that keeps the transfer alive
 * This ensures the OS doesn't kill the process during transfer
 * Now uses DirectStreamServer for fast, non-ZIP file streaming
 */
class TransferService : Service(), DirectStreamServer.TransferListener {

    companion object {
        const val CHANNEL_ID = "DeCloudChannel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVER = "com.decloud.START_SERVER"
        const val ACTION_STOP_SERVER = "com.decloud.STOP_SERVER"
    }

    private val binder = LocalBinder()
    private var server: DirectStreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var hotspotManager: HotspotManager? = null
    private var discoveryBroadcaster: DiscoveryBroadcaster? = null
    private var bluetoothDiscoveryServer: BluetoothDiscoveryServer? = null
    private var totalFilesCount = 0
    private var isTransferActive = false  // True once PC starts downloading files
    private var currentDisplayedIp: String? = null  // Track displayed IP to detect changes
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Hotspot credentials (to display to user)
    var hotspotSSID: String = ""
        private set
    var hotspotPassword: String = ""
        private set

    // Callbacks for UI updates
    var onStatusChanged: ((TransferStatus) -> Unit)? = null
    var onProgressChanged: ((Int, Int, String, Long, Long) -> Unit)? = null
    var onHotspotReady: ((ssid: String, password: String, ipAddress: String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): TransferService = this@TransferService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                // Start foreground immediately to prevent Android 12+ timeout
                startForeground(NOTIFICATION_ID, createNotification("DeCloud", "Starting..."))
                startServer()
            }
            ACTION_STOP_SERVER -> stopServer()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Don't stop - keep running. The foreground notification lets user manage the service.
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopServer()
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeCloud",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "File transfer notifications"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DeCloud::TransferWakeLock"
        ).apply {
            acquire() // No timeout - released in onDestroy/stopServer
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    fun startServer() {
        // Start foreground and show loading immediately on main thread
        startForeground(NOTIFICATION_ID, createNotification("Setting up...", "Please wait"))
        onStatusChanged?.invoke(TransferStatus.CreatingHotspot)

        serviceScope.launch {
            // Check selection in background
            val hasFiles = withContext(Dispatchers.IO) {
                SelectionManager.getSelectedFiles().isNotEmpty()
            }

            if (!hasFiles) {
                onStatusChanged?.invoke(TransferStatus.Error("No files selected"))
                return@launch
            }

            // Reset state
            isTransferActive = false
            currentDisplayedIp = null
            stopNetworkMonitoring()

            // Stop any existing server first to release the port
            try { server?.stop() } catch (_: Exception) {}
            server = null
            try { discoveryBroadcaster?.stopBroadcasting() } catch (_: Exception) {}
            discoveryBroadcaster = null
            try { bluetoothDiscoveryServer?.stop() } catch (_: Exception) {}
            bluetoothDiscoveryServer = null
            hotspotManager?.stopPolling()
            hotspotManager = null

            // Check if hotspot is needed and start server
            startHotspotAndServer()
        }
    }

    private fun startHotspotAndServer() {
        hotspotManager = HotspotManager(this)

        serviceScope.launch {
            // Network detection in background
            val connectionInfo = withContext(Dispatchers.IO) {
                NetworkUtils.getConnectionInfo()
            }

            if (connectionInfo.type != NetworkUtils.ConnectionType.NONE) {
                onHotspotReady?.invoke("", "", connectionInfo.ipAddress)
                startHttpServer()
                return@launch
            }

            // No network found — prompt user to enable hotspot or connect to same WiFi
            hotspotManager?.onHotspotDetected = { ipAddress ->
                onHotspotReady?.invoke("", "", ipAddress)
                startHttpServer()
            }

            onStatusChanged?.invoke(TransferStatus.OpeningHotspotSettings)
            hotspotManager?.openHotspotSettings()
            hotspotManager?.startPollingForNetwork()
        }
    }

    private fun startHttpServer() {
        serviceScope.launch {
            val hasFiles = withContext(Dispatchers.IO) {
                SelectionManager.getSelectedFiles().isNotEmpty()
            }
            val port = NetworkUtils.getServerPort()

            if (!hasFiles) {
                onStatusChanged?.invoke(TransferStatus.Error("No files selected"))
                return@launch
            }

            // Try to start server with retries in background
            val result = withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        val newServer = DirectStreamServer(port, this@TransferService, this@TransferService).apply {
                            start()
                        }
                        return@withContext ServerStartResult.Success(newServer)
                    } catch (e: Exception) {
                        lastError = e
                        if (attempt < 3) {
                            Thread.sleep(1000)
                        }
                    }
                }
                ServerStartResult.Failed(lastError)
            }

            when (result) {
                is ServerStartResult.Success -> {
                    server = result.server

                    val ipAddress = withContext(Dispatchers.IO) {
                        NetworkUtils.getServerIpAddress()
                    }
                    val serverUrl = "http://$ipAddress:$port"

                    // Start discovery services in background
                    withContext(Dispatchers.IO) {
                        try {
                            discoveryBroadcaster = DiscoveryBroadcaster().apply {
                                startBroadcasting(ipAddress, port)
                            }
                        } catch (_: Exception) {}

                        try {
                            bluetoothDiscoveryServer = BluetoothDiscoveryServer().apply {
                                start(ipAddress, port)
                            }
                        } catch (_: Exception) {}
                    }

                    updateNotification("Waiting for PC to connect...", serverUrl)
                    currentDisplayedIp = ipAddress
                    startNetworkMonitoring()
                    onStatusChanged?.invoke(TransferStatus.Waiting(ipAddress, port))
                }
                is ServerStartResult.Failed -> {
                    onStatusChanged?.invoke(TransferStatus.Error("Failed to start server: ${result.error?.message}"))
                }
            }
        }
    }

    private sealed class ServerStartResult {
        data class Success(val server: DirectStreamServer) : ServerStartResult()
        data class Failed(val error: Exception?) : ServerStartResult()
    }

    /**
     * Monitor network changes while server is running.
     * If the IP changes (e.g. hotspot→WiFi), refresh broadcasters and UI.
     * Server itself doesn't need restart since NanoHTTPD binds to 0.0.0.0.
     */
    private fun startNetworkMonitoring() {
        stopNetworkMonitoring()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Small delay to let the new network fully initialize
                mainHandler.postDelayed({ refreshIpIfChanged() }, 1500)
            }

            override fun onLost(network: Network) {
                mainHandler.postDelayed({ refreshIpIfChanged() }, 1500)
            }
        }

        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun stopNetworkMonitoring() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    /**
     * Called when network changes. If the IP changed and we're not mid-transfer,
     * update the displayed IP and restart broadcasters.
     */
    private fun refreshIpIfChanged() {
        // Don't refresh during active transfer — would disrupt the connection
        if (isTransferActive) return
        // Server must be running
        if (server == null) return

        val newIp = NetworkUtils.getServerIpAddress()
        if (newIp == currentDisplayedIp || newIp == "0.0.0.0") return

        android.util.Log.d("TransferService", "Network changed: $currentDisplayedIp → $newIp")
        currentDisplayedIp = newIp
        val port = NetworkUtils.getServerPort()

        // Restart discovery broadcasters with new IP
        try { discoveryBroadcaster?.stopBroadcasting() } catch (_: Exception) {}
        discoveryBroadcaster = DiscoveryBroadcaster().apply {
            startBroadcasting(newIp, port)
        }

        try { bluetoothDiscoveryServer?.stop() } catch (_: Exception) {}
        bluetoothDiscoveryServer = BluetoothDiscoveryServer().apply {
            start(newIp, port)
        }

        // Update notification and UI
        updateNotification("Waiting for PC to connect...", "http://$newIp:$port")
        onStatusChanged?.invoke(TransferStatus.Waiting(newIp, port))
    }

    fun stopServer() {
        stopNetworkMonitoring()
        isTransferActive = false
        currentDisplayedIp = null

        try { server?.stop() } catch (_: Exception) {}
        server = null

        // Stop UDP discovery broadcast
        try { discoveryBroadcaster?.stopBroadcasting() } catch (_: Exception) {}
        discoveryBroadcaster = null

        // Stop Bluetooth discovery server
        try { bluetoothDiscoveryServer?.stop() } catch (_: Exception) {}
        bluetoothDiscoveryServer = null

        // Stop hotspot polling
        hotspotManager?.stopPolling()
        hotspotManager = null
        hotspotSSID = ""
        hotspotPassword = ""

        // Release wake lock immediately - don't wait for onDestroy
        releaseWakeLock()

        stopForeground(STOP_FOREGROUND_REMOVE)

        // Explicitly cancel the notification (in case stopForeground doesn't fully remove it)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)

        onStatusChanged?.invoke(TransferStatus.Stopped)

        // Terminate the service completely
        stopSelf()
    }

    fun getServerInfo(): Pair<String, Int>? {
        return if (server != null) {
            Pair(NetworkUtils.getServerIpAddress(), NetworkUtils.getServerPort())
        } else null
    }

    fun isServerRunning(): Boolean = server != null

    private fun buildNotification(title: String, content: String, progress: Int = -1): Notification {
        val openIntent = Intent(this, ReadyToSendActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TransferService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_transfer)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun createNotification(title: String, content: String): Notification {
        return buildNotification(title, content)
    }

    private fun updateNotification(title: String, content: String, progress: Int = -1) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, content, progress))
    }

    // DirectStreamServer.TransferListener implementation

    private var currentFileIndex = 0
    private var currentFileName = ""
    private var currentFileBytesTransferred = 0L
    private var currentFileTotalBytes = 0L
    private var totalBytesTransferred = 0L
    private var totalTransferSize = 0L

    override fun onClientConnected() {
        onStatusChanged?.invoke(TransferStatus.Connected)
    }

    override fun onTransferStarted(totalFiles: Int, totalBytes: Long) {
        isTransferActive = true
        stopNetworkMonitoring()  // No need to monitor during active transfer
        totalFilesCount = totalFiles
        totalTransferSize = totalBytes
        totalBytesTransferred = 0L
        updateNotification("Transfer Started", "Sending $totalFiles files...")
        onStatusChanged?.invoke(TransferStatus.Transferring)
    }

    override fun onFileStarted(fileName: String, fileIndex: Int, totalFiles: Int) {
        currentFileIndex = fileIndex
        currentFileName = fileName
        totalFilesCount = totalFiles
        currentFileBytesTransferred = 0L
    }

    override fun onFileProgress(fileName: String, bytesTransferred: Long, totalBytes: Long) {
        currentFileBytesTransferred = bytesTransferred
        currentFileTotalBytes = totalBytes

        // Two metrics, take the maximum so the bar always advances:
        //   - byte-based: accurate but only ticks on file completion (slow to start, can sit at 0)
        //   - file-count: responsive but coarse
        val cumulativeBytes = totalBytesTransferred + bytesTransferred
        val bytePercent = if (totalTransferSize > 0) {
            ((cumulativeBytes * 100L) / totalTransferSize).toInt()
        } else 0
        val filePercent = if (totalFilesCount > 0) {
            ((currentFileIndex - 1) * 100) / totalFilesCount
        } else 0
        val progress = maxOf(bytePercent, filePercent).coerceIn(0, 100)

        updateNotification(
            "Sending: $currentFileName",
            "File $currentFileIndex of $totalFilesCount",
            progress
        )

        // Pass cumulative bytes and total transfer size — the UI computes its own max-of-both %.
        onProgressChanged?.invoke(
            currentFileIndex,
            totalFilesCount,
            currentFileName,
            cumulativeBytes,
            totalTransferSize
        )
    }

    override fun onFileComplete(fileName: String) {
        totalBytesTransferred += currentFileTotalBytes
    }

    override fun onTransferComplete(failedFiles: List<String>) {
        val droppedFiles = server?.droppedFiles ?: emptyList()
        onStatusChanged?.invoke(TransferStatus.Complete(failedFiles, droppedFiles))

        // Clear the server cache immediately
        server?.clearCache()

        // Stop server shortly after UI has updated (non-blocking).
        // Short window so the phone visually transitions to Complete without a noticeable lag,
        // while still allowing any in-flight HTTP responses to finish flushing.
        Thread {
            Thread.sleep(150)
            android.os.Handler(mainLooper).post {
                stopServer()
            }
        }.start()
    }

    override fun onTransferError(error: String) {
        onStatusChanged?.invoke(TransferStatus.Error(error))

        // Stop server on error to clean up notification and service
        Thread {
            Thread.sleep(500)
            android.os.Handler(mainLooper).post {
                stopServer()
            }
        }.start()
    }

    override fun onTransferCancelled() {
        onStatusChanged?.invoke(TransferStatus.Cancelled)

        // Stop server since transfer was cancelled
        Thread {
            Thread.sleep(500)  // Brief delay for UI update
            android.os.Handler(mainLooper).post {
                stopServer()
            }
        }.start()
    }
}

sealed class TransferStatus {
    object CreatingHotspot : TransferStatus()
    object OpeningHotspotSettings : TransferStatus()
    data class HotspotFailed(val error: String) : TransferStatus()
    data class Waiting(val ipAddress: String, val port: Int) : TransferStatus()
    object Connected : TransferStatus()
    object Transferring : TransferStatus()
    data class Complete(
        val failedFiles: List<String> = emptyList(),
        val droppedFiles: List<String> = emptyList()
    ) : TransferStatus()
    object Cancelled : TransferStatus()  // PC cancelled the transfer
    data class Error(val message: String) : TransferStatus()
    object Stopped : TransferStatus()
}
