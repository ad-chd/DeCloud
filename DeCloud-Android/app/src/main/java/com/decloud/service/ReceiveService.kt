package com.decloud.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.decloud.R
import com.decloud.server.DirectStreamServer
import com.decloud.ui.ReceiveActivity
import com.decloud.util.BluetoothDiscoveryServer
import com.decloud.util.DiscoveryBroadcaster
import com.decloud.util.NetworkUtils

class ReceiveService : Service(), DirectStreamServer.ReceiveListener {

    companion object {
        private const val TAG = "ReceiveService"
        const val CHANNEL_ID = "DeCloudChannel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START_RECEIVE = "com.decloud.START_RECEIVE"
        const val ACTION_STOP_RECEIVE = "com.decloud.STOP_RECEIVE"
    }

    private val binder = LocalBinder()
    private var server: DirectStreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var discoveryBroadcaster: DiscoveryBroadcaster? = null
    private var bluetoothDiscoveryServer: BluetoothDiscoveryServer? = null

    // Last-known ready state — replayed when the activity binds after the server has already started.
    // Why: startService→onStartCommand→startReceiveServer can fire onReceiveReady before bindService
    // completes and the activity assigns its callback, so the event is lost without replay.
    var currentIp: String? = null
        private set
    var currentPort: Int = 0
        private set

    var onReceiveReady: ((ipAddress: String, port: Int) -> Unit)? = null
        set(value) {
            field = value
            val ip = currentIp
            if (value != null && ip != null) {
                value(ip, currentPort)
            }
        }
    var onFileReceived: ((fileName: String, fileIndex: Int, fileSize: Long) -> Unit)? = null
    var onReceiveProgress: ((totalFiles: Int, totalBytes: Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): ReceiveService = this@ReceiveService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECEIVE -> startReceiveServer()
            ACTION_STOP_RECEIVE -> stopReceiveServer()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopReceiveServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiveServer()
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
            "DeCloud::ReceiveWakeLock"
        ).apply {
            acquire() // No timeout - released in onDestroy/stopReceiveServer
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

    fun startReceiveServer() {
        // Stop any existing server first
        try {
            server?.stop()
        } catch (_: Exception) {}
        server = null

        startForeground(NOTIFICATION_ID, createNotification("Receive Mode", "Setting up..."))

        val port = NetworkUtils.getServerPort()

        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                server = DirectStreamServer(port, null, this@ReceiveService).apply {
                    receiveListener = this@ReceiveService
                    start()
                }

                val ipAddress = NetworkUtils.getServerIpAddress()
                val serverUrl = "http://$ipAddress:$port"

                // Start UDP broadcast so PC can auto-discover this phone
                discoveryBroadcaster = DiscoveryBroadcaster().apply {
                    startBroadcasting(ipAddress, port)
                }

                // Start Bluetooth discovery server (fallback)
                bluetoothDiscoveryServer = BluetoothDiscoveryServer().apply {
                    start(ipAddress, port)
                }

                updateNotification("Waiting for PC...", serverUrl)

                Log.d(TAG, "Receive server started at $serverUrl")
                currentIp = ipAddress
                currentPort = port
                onReceiveReady?.invoke(ipAddress, port)
                return // Success

            } catch (e: Exception) {
                lastError = e
                try { server?.stop() } catch (_: Exception) {}
                server = null
                if (attempt < 3) {
                    Thread.sleep(1000)
                }
            }
        }

        val errorMsg = "Failed to start receive server: ${lastError?.message}"
        Log.e(TAG, errorMsg)
        onError?.invoke(errorMsg)
    }

    fun stopReceiveServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null

        currentIp = null
        currentPort = 0

        try { discoveryBroadcaster?.stopBroadcasting() } catch (_: Exception) {}
        discoveryBroadcaster = null

        try { bluetoothDiscoveryServer?.stop() } catch (_: Exception) {}
        bluetoothDiscoveryServer = null

        releaseWakeLock()

        stopForeground(STOP_FOREGROUND_REMOVE)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)

        // Notify activity that receiving was stopped (e.g. from notification action)
        onStopped?.invoke()

        stopSelf()
    }

    fun isServerRunning(): Boolean = server != null

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, ReceiveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop Receiving" action button in the notification
        val stopIntent = Intent(this, ReceiveService::class.java).apply {
            action = ACTION_STOP_RECEIVE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, "Stop Receiving", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotification(title: String, content: String): Notification {
        return buildNotification(title, content)
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // DirectStreamServer.ReceiveListener implementation

    override fun onReceiveStarted() {
        updateNotification("Receiving files from PC...", "Transfer in progress")
    }

    override fun onFileReceived(fileName: String, fileIndex: Int, fileSize: Long) {
        updateNotification("Receiving files...", "File $fileIndex: $fileName")
        onFileReceived?.invoke(fileName, fileIndex, fileSize)
    }

    override fun onReceiveProgress(totalFiles: Int, totalBytes: Long) {
        onReceiveProgress?.invoke(totalFiles, totalBytes)
    }

    override fun onReceiveError(error: String) {
        Log.e(TAG, "Receive error: $error")
        onError?.invoke(error)
    }
}
