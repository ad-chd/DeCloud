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
import androidx.core.app.NotificationCompat
import com.decloud.R
import com.decloud.model.SelectionManager
import com.decloud.ui.ReadyToSendActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ADB-based Transfer Service - PARALLEL PULL MODE
 * Creates optimized manifest and sends to PC for parallel batched pulls
 *
 * PARALLEL PULL MODE Advantages:
 * - 40-50 MB/s (parallel ADB pulls)
 * - Smart batching (large files parallel, small files archived)
 * - Minimal handshakes
 * - Preserves directory structure
 */
class AdbTransferService : Service() {

    companion object {
        const val CHANNEL_ID = "DeCloudADBChannel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_PREPARE = "com.decloud.PREPARE"
        const val ACTION_CLEANUP = "com.decloud.CLEANUP"
        const val ACTION_STATUS = "com.decloud.STATUS"

        // File paths for streaming transfer
        private const val FILE_PATHS_NAME = "file_paths.txt"
        private const val TRANSFER_DIR_NAME = "transfer_temp"
        private const val STATUS_FILE_NAME = "status.json"
        private const val MANIFEST_FILE_NAME = "manifest.json"

        // PC daemon port (via ADB reverse)
        private const val PC_DAEMON_PORT = 5555

        // File size thresholds for batching (in bytes)
        private const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024  // 10 MB
        private const val SMALL_FILE_THRESHOLD = 1 * 1024 * 1024   // 1 MB
    }

    /**
     * Transfer strategies for optimized batching
     */
    enum class TransferStrategy {
        PARALLEL,    // For large files (>10MB) - parallel pulls
        ARCHIVE,     // For small files (<1MB) - tar batch
        SINGLE       // For medium files (1-10MB) - sequential pulls
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var isPreparingTransfer = false
    // Files that were selected but became inaccessible before transfer started
    // Captured ONCE at preparation time so completion UI can show them accurately
    private var droppedFilePaths: List<String> = emptyList()
    // Active socket to PC — stored so cleanupTransfer() can close it immediately
    @Volatile
    private var activeSocket: java.net.Socket? = null
    // Callbacks for UI updates
    var onStatusChanged: ((AdbTransferStatus) -> Unit)? = null
    var onProgressChanged: ((Int, Int) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): AdbTransferService = this@AdbTransferService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> prepareTransfer()
            ACTION_CLEANUP -> cleanupTransfer()
            ACTION_STATUS -> sendStatus()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Don't stop - keep running. The foreground notification lets user manage the service.
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupTransfer()
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeCloud ADB",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ADB file transfer notifications"
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
            "DeCloud::ADBTransferWakeLock"
        ).apply {
            acquire() // No timeout - released in onDestroy/cleanupTransfer
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

    /**
     * Prepare transfer by creating optimized manifest and sending to PC
     *
     * PARALLEL PULL MODE:
     * - Groups files by size (large/medium/small)
     * - Creates manifest with batching strategies
     * - Sends manifest to PC via ADB reverse socket
     * - PC executes parallel pulls based on strategy
     */
    private fun prepareTransfer() {
        if (isPreparingTransfer) {
            writeStatus("busy", "Transfer preparation already in progress")
            return
        }

        isPreparingTransfer = true
        startForeground(NOTIFICATION_ID, createNotification("Preparing transfer...", "Getting file list"))
        onStatusChanged?.invoke(AdbTransferStatus.Preparing)

        thread(start = true, name = "AdbPreparer") {
            try {
                val allSelectedPaths = SelectionManager.getSelectedFilePaths()
                val selectedFiles = SelectionManager.getSelectedFiles()
                val allDirectories = SelectionManager.getAllDirectoryPaths()

                // Capture files that became inaccessible between selection and transfer start
                // Store the actual paths NOW — they may become accessible again later
                val accessiblePaths = selectedFiles.map { it.absolutePath }.toSet()
                droppedFilePaths = allSelectedPaths.filter { it !in accessiblePaths }
                if (droppedFilePaths.isNotEmpty()) {
                    android.util.Log.w("AdbTransferService", "${droppedFilePaths.size} files became inaccessible since selection (selected=${allSelectedPaths.size}, accessible=${selectedFiles.size})")
                    android.util.Log.w("AdbTransferService", "Dropped files: ${droppedFilePaths.take(20).map { java.io.File(it).name }}")
                }

                if (selectedFiles.isEmpty() && allDirectories.isEmpty()) {
                    writeStatus("error", "No files or folders selected")
                    onStatusChanged?.invoke(AdbTransferStatus.Error("No files or folders selected"))
                    return@thread
                }

                writeStatus("preparing", "Analyzing files", selectedFiles.size)
                updateNotification("Preparing transfer...", "Analyzing ${selectedFiles.size} files, ${allDirectories.size} folders")

                // Get selected directories for flat copy logic
                val selectedDirectories = SelectionManager.getSelectedDirectoryPaths()

                // Validate files and group by size
                val largeFiles = mutableListOf<FileManifestEntry>()
                val mediumFiles = mutableListOf<FileManifestEntry>()
                val smallFiles = mutableListOf<FileManifestEntry>()
                val failedFiles = mutableListOf<String>()

                selectedFiles.forEachIndexed { index, file ->
                    try {
                        if (!file.exists() || !file.canRead()) {
                            failedFiles.add(file.name)
                            return@forEachIndexed
                        }

                        val fileSize = file.length()
                        val relativePath = getRelativePath(file.absolutePath, selectedDirectories)

                        val entry = FileManifestEntry(
                            relativePath = relativePath,
                            originalPath = file.absolutePath,
                            size = fileSize,
                            lastModified = file.lastModified(),
                            name = file.name
                        )

                        // Group by size
                        when {
                            fileSize > LARGE_FILE_THRESHOLD -> largeFiles.add(entry)
                            fileSize < SMALL_FILE_THRESHOLD -> smallFiles.add(entry)
                            else -> mediumFiles.add(entry)
                        }

                        onProgressChanged?.invoke(index + 1, selectedFiles.size)

                        // Update notification every 50 files
                        if (index % 50 == 0 || index == selectedFiles.size - 1) {
                            updateNotification(
                                "Analyzing: ${index + 1}/${selectedFiles.size}",
                                "Grouping files..."
                            )
                        }

                    } catch (e: Exception) {
                        failedFiles.add("${file.name}: ${e.message}")
                    }
                }

                val successCount = largeFiles.size + mediumFiles.size + smallFiles.size
                val skippedCount = failedFiles.size
                val totalProcessed = successCount + skippedCount

                // VERIFICATION: Ensure all files are accounted for
                android.util.Log.i("AdbTransferService", "=== BACKUP VERIFICATION ===")
                android.util.Log.i("AdbTransferService", "Selected files: ${selectedFiles.size}")
                android.util.Log.i("AdbTransferService", "Files in manifest: $successCount (large: ${largeFiles.size}, medium: ${mediumFiles.size}, small: ${smallFiles.size})")
                android.util.Log.i("AdbTransferService", "Skipped (inaccessible): $skippedCount")
                android.util.Log.i("AdbTransferService", "Total processed: $totalProcessed")

                // Sanity check: All selected files should be either in manifest or in failed list
                if (totalProcessed != selectedFiles.size) {
                    android.util.Log.e("AdbTransferService", "WARNING: File count mismatch! Selected=${selectedFiles.size}, Processed=$totalProcessed")
                } else {
                    android.util.Log.i("AdbTransferService", "✓ All files accounted for")
                }

                // Log skipped files for debugging
                if (skippedCount > 0) {
                    android.util.Log.w("AdbTransferService", "Skipped $skippedCount inaccessible files: ${failedFiles.take(10)}")
                }

                // If ALL files failed and no directories, report error
                if (successCount == 0 && allDirectories.isEmpty()) {
                    writeStatus("error", "No accessible files to transfer. $skippedCount files were inaccessible.")
                    onStatusChanged?.invoke(AdbTransferStatus.Error("No accessible files. $skippedCount files couldn't be read."))
                    return@thread
                }

                // Build directory list with relative paths (includes empty directories)
                val directoryEntries = allDirectories.mapNotNull { dirPath ->
                    try {
                        val relativePath = getRelativePath(dirPath, selectedDirectories)
                        DirectoryManifestEntry(
                            relativePath = relativePath,
                            originalPath = dirPath
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                android.util.Log.i("AdbTransferService", "Including ${directoryEntries.size} directories (including empty ones)")

                // Create optimized transfer manifest (no common parent needed for flat copy)
                val manifest = createOptimizedManifest(
                    largeFiles = largeFiles,
                    mediumFiles = mediumFiles,
                    smallFiles = smallFiles,
                    directories = directoryEntries,
                    commonParent = ""
                )

                // FINAL VERIFICATION: Check manifest contains all files
                val manifestFileCount = manifest.optInt("totalFiles", 0)
                val pathMappingCount = manifest.optJSONObject("pathMapping")?.length() ?: 0
                android.util.Log.i("AdbTransferService", "=== MANIFEST VERIFICATION ===")
                android.util.Log.i("AdbTransferService", "Manifest totalFiles: $manifestFileCount")
                android.util.Log.i("AdbTransferService", "PathMapping entries: $pathMappingCount")
                if (manifestFileCount == successCount && pathMappingCount == successCount) {
                    android.util.Log.i("AdbTransferService", "✓ Manifest complete - all $successCount accessible files included")
                } else {
                    android.util.Log.e("AdbTransferService", "ERROR: Manifest mismatch! Expected $successCount, got manifest=$manifestFileCount, pathMapping=$pathMappingCount")
                }

                // Calculate total size
                val totalSize = largeFiles.sumOf { it.size } +
                               mediumFiles.sumOf { it.size } +
                               smallFiles.sumOf { it.size }

                // Notify that files are ready and we're connecting to PC
                val readyMessage = if (skippedCount > 0) {
                    "$successCount files ready ($skippedCount skipped - inaccessible)"
                } else {
                    "$successCount files ready"
                }
                onStatusChanged?.invoke(AdbTransferStatus.Ready(successCount, skippedCount))
                updateNotification("Ready for transfer", readyMessage)
                writeStatus(
                    "ready",
                    "Ready for transfer",
                    totalFiles = successCount,
                    transferPath = filesDir.absolutePath,
                    totalSize = totalSize,
                    commonParent = ""
                )

                // Send manifest to PC via socket and wait for transfer completion
                // The sendManifestToPc function will send Complete/Error status when done
                updateNotification("Connecting to PC...", "Sending manifest...")
                val manifestSent = sendManifestToPc(manifest)

                // Only handle connection errors here - Complete/Error status is already sent from sendManifestToPc
                if (!manifestSent) {
                    // Connection failed or transfer failed - status already sent from sendManifestToPc
                    // Just write status file for external tools
                    writeStatus("error", "Transfer failed or connection lost")
                }
                // If manifestSent is true, the transfer completed successfully
                // and Complete status was already sent from sendManifestToPc
                // Don't send any more status updates here!

            } catch (e: Exception) {
                writeStatus("error", "Failed to prepare: ${e.message}")
                onStatusChanged?.invoke(AdbTransferStatus.Error(e.message ?: "Unknown error"))
            } finally {
                isPreparingTransfer = false
                // Release wake lock immediately - don't wait for onDestroy
                releaseWakeLock()
                // Stop foreground service and remove notification when transfer is done (success or failure)
                stopForeground(STOP_FOREGROUND_REMOVE)
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(NOTIFICATION_ID)
                stopSelf()
            }
        }
    }

    /**
     * Create optimized transfer manifest with batching strategies
     * Now includes directories (including empty ones) for exact structure preservation
     */
    private fun createOptimizedManifest(
        largeFiles: List<FileManifestEntry>,
        mediumFiles: List<FileManifestEntry>,
        smallFiles: List<FileManifestEntry>,
        directories: List<DirectoryManifestEntry>,
        commonParent: String
    ): JSONObject {
        val batches = JSONArray()
        val allFiles = largeFiles + mediumFiles + smallFiles

        // Create path mapping: originalPath -> relativePath for flat copy
        val pathMapping = JSONObject()
        allFiles.forEach { entry ->
            pathMapping.put(entry.originalPath, entry.relativePath)
        }

        // Create directory mapping: originalPath -> relativePath
        val directoryMapping = JSONObject()
        directories.forEach { entry ->
            directoryMapping.put(entry.originalPath, entry.relativePath)
        }

        // Batch 1: Large files → PARALLEL strategy
        if (largeFiles.isNotEmpty()) {
            batches.put(JSONObject().apply {
                put("batchId", "batch_large")
                put("strategy", TransferStrategy.PARALLEL.name)
                put("paths", JSONArray(largeFiles.map { it.originalPath }))
                put("totalSize", largeFiles.sumOf { it.size })
                put("fileCount", largeFiles.size)
            })
        }

        // Batch 2: Small files → ARCHIVE strategy
        if (smallFiles.isNotEmpty()) {
            batches.put(JSONObject().apply {
                put("batchId", "batch_small")
                put("strategy", TransferStrategy.ARCHIVE.name)
                put("paths", JSONArray(smallFiles.map { it.originalPath }))
                put("totalSize", smallFiles.sumOf { it.size })
                put("fileCount", smallFiles.size)
            })
        }

        // Batch 3: Medium files → SINGLE strategy
        if (mediumFiles.isNotEmpty()) {
            batches.put(JSONObject().apply {
                put("batchId", "batch_medium")
                put("strategy", TransferStrategy.SINGLE.name)
                put("paths", JSONArray(mediumFiles.map { it.originalPath }))
                put("totalSize", mediumFiles.sumOf { it.size })
                put("fileCount", mediumFiles.size)
            })
        }

        return JSONObject().apply {
            put("version", 2)  // Bumped version for directory support
            put("timestamp", System.currentTimeMillis())
            put("totalFiles", largeFiles.size + mediumFiles.size + smallFiles.size)
            put("totalDirectories", directories.size)
            put("totalSize", largeFiles.sumOf { it.size } + mediumFiles.sumOf { it.size } + smallFiles.sumOf { it.size })
            put("commonParent", commonParent)
            put("pathMapping", pathMapping)  // File path mapping for flat copy
            put("directoryMapping", directoryMapping)  // Directory path mapping (includes empty dirs)
            put("batches", batches)
            // Backup mode flag - tells PC to use "DeCloud_Backup" destination
            put("isBackupMode", SelectionManager.isBackupMode())
        }
    }

    /**
     * Send manifest to PC via ADB reverse socket connection
     * PC daemon listens on port 5555 (forwarded via adb reverse)
     * Now waits for transfer completion with progress updates
     */
    private fun sendManifestToPc(manifest: JSONObject): Boolean {
        return try {
            onStatusChanged?.invoke(AdbTransferStatus.WaitingForPc)
            updateNotification("Waiting for PC...", "Start transfer on PC app")

            // Retry connection until PC is ready (user may not have clicked Start yet)
            var socket: java.net.Socket? = null
            val maxAttempts = 120  // 2 minutes of retrying
            var attempt = 0

            while (socket == null && attempt < maxAttempts) {
                try {
                    socket = Socket("127.0.0.1", PC_DAEMON_PORT)
                } catch (e: java.net.ConnectException) {
                    attempt++
                    if (attempt % 10 == 0) {
                        android.util.Log.d("AdbTransferService", "Waiting for PC... attempt $attempt")
                    }
                    Thread.sleep(1000)  // Wait 1 second between retries
                }
            }

            if (socket == null) {
                onStatusChanged?.invoke(AdbTransferStatus.Error("PC not responding. Please start transfer on PC app."))
                return false
            }

            // Store socket reference so cleanupTransfer() can close it immediately
            activeSocket = socket

            onStatusChanged?.invoke(AdbTransferStatus.Sending)
            updateNotification("Connected to PC!", "Sending file list...")

            socket.soTimeout = 30 * 60 * 1000  // 30 minute timeout for large transfers

            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.println(manifest.toString())
            writer.flush()

            onStatusChanged?.invoke(AdbTransferStatus.Processing)
            updateNotification("Transfer in progress...", "PC is pulling files")

            // Wait for response (PROGRESS updates or final COMPLETE/ERROR)
            val reader = socket.getInputStream().bufferedReader()

            while (true) {
                val response = reader.readLine() ?: break

                try {
                    val responseJson = JSONObject(response)
                    val type = responseJson.optString("type", "")

                    when (type) {
                        "PROGRESS" -> {
                            val percent = responseJson.optInt("percent", 0)
                            val currentFile = responseJson.optString("currentFile", "")
                            val completed = responseJson.optInt("completed", 0)
                            val total = responseJson.optInt("total", 0)
                            onStatusChanged?.invoke(AdbTransferStatus.Progress(percent, currentFile, completed, total))
                            updateNotification(
                                "Transferring... $completed/$total",
                                if (currentFile.isNotEmpty()) currentFile else "Processing files"
                            )
                        }
                        "COMPLETE" -> {
                            val filesTransferred = responseJson.optInt("filesTransferred", 0)
                            val isResumed = responseJson.optBoolean("isResumed", false)
                            val resumedFromFiles = responseJson.optInt("resumedFromFiles", 0)

                            // Check for any failed files in successful response
                            val failedFilesArray = responseJson.optJSONArray("failedFiles")
                            val failedFiles = mutableListOf<String>()
                            if (failedFilesArray != null) {
                                for (i in 0 until failedFilesArray.length()) {
                                    failedFiles.add(failedFilesArray.optString(i, ""))
                                }
                            }

                            onStatusChanged?.invoke(AdbTransferStatus.Complete(filesTransferred, failedFiles, droppedFilePaths, isResumed, resumedFromFiles))
                            socket.close()
                            return true
                        }
                        "PARTIAL" -> {
                            // Partial transfer - some files done, can be resumed!
                            val filesTransferred = responseJson.optInt("filesTransferred", 0)
                            val errorMsg = responseJson.optString("error", "Partial transfer")
                            val isResumed = responseJson.optBoolean("isResumed", false)

                            val failedFilesArray = responseJson.optJSONArray("failedFiles")
                            val failedFiles = mutableListOf<String>()
                            if (failedFilesArray != null) {
                                for (i in 0 until failedFilesArray.length()) {
                                    failedFiles.add(failedFilesArray.optString(i, ""))
                                }
                            }

                            onStatusChanged?.invoke(AdbTransferStatus.Partial(filesTransferred, failedFiles, droppedFilePaths, true))
                            socket.close()
                            return false  // Return false so user knows it's incomplete
                        }
                        "ERROR" -> {
                            val errorMsg = responseJson.optString("error", "Unknown error")
                            val failedFilesArray = responseJson.optJSONArray("failedFiles")
                            val failedFiles = mutableListOf<String>()
                            if (failedFilesArray != null) {
                                for (i in 0 until failedFilesArray.length()) {
                                    failedFiles.add(failedFilesArray.optString(i, ""))
                                }
                            }

                            onStatusChanged?.invoke(AdbTransferStatus.Failed(errorMsg, failedFiles))
                            socket.close()
                            return false
                        }
                        "CANCELLED" -> {
                            val reason = responseJson.optString("reason", "Transfer cancelled by PC")
                            android.util.Log.i("AdbTransferService", "PC cancelled transfer: $reason")
                            onStatusChanged?.invoke(AdbTransferStatus.Failed("Cancelled from PC", emptyList()))
                            socket.close()
                            return false
                        }
                        "ABORTED" -> {
                            val reason = responseJson.optString("reason", "Transfer aborted")
                            val filesTransferred = responseJson.optInt("filesTransferred", 0)
                            android.util.Log.i("AdbTransferService", "PC aborted transfer: $reason ($filesTransferred files done)")
                            onStatusChanged?.invoke(AdbTransferStatus.Failed("Transfer aborted: $reason", emptyList()))
                            socket.close()
                            return false
                        }
                        else -> {
                            // Legacy response
                            if (response.trim() == "SUCCESS") {
                                onStatusChanged?.invoke(AdbTransferStatus.Complete(
                                    manifest.optInt("totalFiles", 0)
                                ))
                                socket.close()
                                return true
                            }
                        }
                    }
                } catch (jsonEx: Exception) {
                    // Not JSON, check for legacy SUCCESS/ERROR
                    if (response.trim() == "SUCCESS") {
                        onStatusChanged?.invoke(AdbTransferStatus.Complete(
                            manifest.optInt("totalFiles", 0)
                        ))
                        socket.close()
                        return true
                    } else if (response.trim() == "ERROR") {
                        onStatusChanged?.invoke(AdbTransferStatus.Failed("Transfer failed", emptyList()))
                        socket.close()
                        return false
                    }
                }
            }

            // readLine() returned null - PC disconnected unexpectedly
            onStatusChanged?.invoke(AdbTransferStatus.Error("Connection to PC lost"))

            socket.close()
            false
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("AdbTransferService", "Transfer timeout: ${e.message}")
            onStatusChanged?.invoke(AdbTransferStatus.Failed("Transfer timeout - PC may still be processing", emptyList()))
            false
        } catch (e: java.net.ConnectException) {
            android.util.Log.e("AdbTransferService", "Cannot connect to PC: ${e.message}")
            onStatusChanged?.invoke(AdbTransferStatus.Error("Cannot connect to PC. Is the PC app running?"))
            false
        } catch (e: Exception) {
            android.util.Log.e("AdbTransferService", "Failed to send manifest: ${e.message}")
            onStatusChanged?.invoke(AdbTransferStatus.Error("Connection error: ${e.message}"))
            false
        } finally {
            activeSocket = null
        }
    }

    /**
     * Find the selection root for a file
     * Returns the path of the TOP-LEVEL selected directory that contains this file,
     * or null if the file was directly selected (not inside any selected directory)
     *
     * Uses minByOrNull to find the SHALLOWEST (shortest path) match - this is the
     * directory the user actually selected, not a nested subdirectory that was
     * auto-selected when expanding the parent.
     */
    private fun findSelectionRoot(filePath: String, selectedDirectories: List<String>): String? {
        // Find the SHALLOWEST (top-level) selected directory that contains this file
        // This ensures we preserve structure from the directory user actually clicked
        return selectedDirectories
            .filter { dirPath ->
                filePath.startsWith(dirPath + "/") ||
                filePath.startsWith(dirPath + "\\")
            }
            .minByOrNull { it.length }  // Changed from maxByOrNull to minByOrNull
    }

    /**
     * Get relative path for flat copy approach
     * - In backup mode: use storage prefix (e.g., "Internal Storage/DCIM/...")
     * - If file is inside a selected directory: preserve structure from that directory
     * - If file was directly selected: use just the filename
     */
    private fun getRelativePath(absolutePath: String, selectedDirectories: List<String>): String {
        // In backup mode, use the backup relative path from SelectionManager
        if (SelectionManager.isBackupMode()) {
            return SelectionManager.getBackupRelativePath(absolutePath)
        }

        val selectionRoot = findSelectionRoot(absolutePath, selectedDirectories)

        return if (selectionRoot != null) {
            // File is inside a selected directory - preserve structure from that directory
            val parentOfRoot = File(selectionRoot).parent ?: ""
            if (parentOfRoot.isEmpty()) {
                // Selection root is at filesystem root
                absolutePath.removePrefix("/").removePrefix("\\")
            } else {
                // Make path relative to parent of selection root
                absolutePath.removePrefix(parentOfRoot)
                    .trimStart('/')
                    .trimStart('\\')
                    .replace('\\', '/')
            }
        } else {
            // File was directly selected - use just filename
            File(absolutePath).name
        }
    }

    /**
     * Write manifest file with metadata
     */
    private fun writeManifest(transferDir: File, entries: List<FileManifestEntry>, commonParent: String) {
        val manifestFile = File(transferDir, MANIFEST_FILE_NAME)

        val json = JSONObject().apply {
            put("version", 1)
            put("timestamp", System.currentTimeMillis())
            put("totalFiles", entries.size)
            put("totalSize", entries.sumOf { it.size })
            put("commonParent", commonParent)

            val filesArray = JSONArray()
            entries.forEach { entry ->
                filesArray.put(JSONObject().apply {
                    put("relativePath", entry.relativePath)
                    put("originalPath", entry.originalPath)
                    put("size", entry.size)
                    put("lastModified", entry.lastModified)
                    put("name", entry.name)
                })
            }
            put("files", filesArray)
        }

        manifestFile.writeText(json.toString(2))
    }

    /**
     * Write status file for PC polling
     */
    private fun writeStatus(
        status: String,
        message: String,
        totalFiles: Int = 0,
        transferPath: String = "",
        totalSize: Long = 0,
        commonParent: String = ""
    ) {
        try {
            val statusFile = File(filesDir, STATUS_FILE_NAME)
            val json = JSONObject().apply {
                put("status", status)
                put("message", message)
                put("timestamp", System.currentTimeMillis())
                put("totalFiles", totalFiles)
                put("transferPath", transferPath)
                put("totalSize", totalSize)
                put("commonParent", commonParent)
            }
            statusFile.writeText(json.toString())
        } catch (e: Exception) {
            // Ignore write errors
        }
    }

    private fun sendStatus() {
        // Status is written to file, PC can read it via adb
        // This action is just a trigger, actual status is in the file
    }

    /**
     * Cleanup transfer directory
     */
    private fun cleanupTransfer() {
        // Close the socket FIRST — this unblocks the reader thread and
        // triggers socket.on('close') on the PC, which kills all adb pull processes
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null

        thread {
            try {
                val transferDir = File(filesDir, TRANSFER_DIR_NAME)
                if (transferDir.exists()) {
                    transferDir.deleteRecursively()
                }
                writeStatus("cleaned", "Transfer directory cleaned")
            } catch (e: Exception) {
                writeStatus("error", "Cleanup failed: ${e.message}")
            }
        }
        // Release wake lock immediately
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        onStatusChanged?.invoke(AdbTransferStatus.Cleaned)
        stopSelf()
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, ReadyToSendActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AdbTransferService::class.java).apply {
            action = ACTION_CLEANUP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_transfer)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotification(title: String, content: String): Notification {
        return buildNotification(title, content)
    }

    private fun updateNotification(title: String, content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    /**
     * Data class for file manifest entries
     */
    private data class FileManifestEntry(
        val relativePath: String,
        val originalPath: String,
        val size: Long,
        val lastModified: Long,
        val name: String
    )

    /**
     * Data class for directory manifest entries (includes empty directories)
     */
    private data class DirectoryManifestEntry(
        val relativePath: String,
        val originalPath: String
    )
}

/**
 * Transfer status sealed class
 */
sealed class AdbTransferStatus {
    object Preparing : AdbTransferStatus()
    object WaitingForPc : AdbTransferStatus()
    object Sending : AdbTransferStatus()
    object Processing : AdbTransferStatus()
    data class Progress(val percent: Int, val currentFile: String, val completed: Int, val total: Int) : AdbTransferStatus()
    data class Complete(
        val fileCount: Int,
        val failedFiles: List<String> = emptyList(),
        val droppedFiles: List<String> = emptyList(),
        val wasResumed: Boolean = false,
        val resumedFromFiles: Int = 0
    ) : AdbTransferStatus()
    data class Failed(val reason: String, val failedFiles: List<String>) : AdbTransferStatus()
    data class Ready(val fileCount: Int, val skippedCount: Int = 0) : AdbTransferStatus()
    data class Partial(
        val successCount: Int,
        val failedFiles: List<String>,
        val droppedFiles: List<String> = emptyList(),
        val canResume: Boolean = true  // Flag to indicate transfer can be resumed
    ) : AdbTransferStatus()
    data class Error(val message: String) : AdbTransferStatus()
    object Cleaned : AdbTransferStatus()
}
