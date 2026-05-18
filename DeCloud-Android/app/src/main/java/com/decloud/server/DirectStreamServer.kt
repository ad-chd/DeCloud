package com.decloud.server

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.decloud.model.SelectionManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import android.os.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Direct File Stream Server - NO ZIP
 *
 * Endpoints:
 * - GET /info         -> Server info (file count, size)
 * - GET /manifest     -> Full file manifest with relative paths
 * - GET /file?path=X  -> Stream individual file by path
 * - GET /dirs         -> List of directories to create
 *
 * Benefits over ZIP:
 * - Perfect directory structure preservation
 * - Parallel downloads possible
 * - Resume support per file
 * - No compression overhead (faster for media files)
 * - Lower memory usage
 */
class DirectStreamServer(
    port: Int,
    private val listener: TransferListener? = null,
    private val context: Context? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DirectStreamServer"
        private const val BUFFER_SIZE = 2 * 1024 * 1024  // 2MB buffer for maximum throughput
        private const val PROGRESS_UPDATE_INTERVAL = 500L  // Update progress every 500ms
        private const val PROGRESS_FILE_INTERVAL = 100     // Update progress every 100 files
        private const val BATCH_SIZE = 1000                // Files per batch for large transfers

        /**
         * Generate session hash - MUST match PC's algorithm!
         * Based on: file count + total size + first 10 file paths
         */
        fun generateSessionHash(totalFiles: Int, totalSize: Long, samplePaths: List<String>): String {
            val data = "$totalFiles:$totalSize:${samplePaths.take(10).joinToString("|")}"
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(data.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }

    interface TransferListener {
        fun onClientConnected()
        fun onTransferStarted(totalFiles: Int, totalBytes: Long)
        fun onFileStarted(fileName: String, fileIndex: Int, totalFiles: Int)
        fun onFileProgress(fileName: String, bytesTransferred: Long, totalBytes: Long)
        fun onFileComplete(fileName: String)
        fun onTransferComplete(failedFiles: List<String> = emptyList())
        fun onTransferError(error: String)
        fun onTransferCancelled() { /* Optional: default empty implementation */ }
    }

    interface ReceiveListener {
        fun onReceiveStarted()
        fun onFileReceived(fileName: String, fileIndex: Int, fileSize: Long)
        fun onReceiveProgress(totalFiles: Int, totalBytes: Long)
        fun onReceiveError(error: String)
    }

    var receiveListener: ReceiveListener? = null

    // Cache manifest to avoid recalculating
    private var cachedManifest: FileManifest? = null
    private var transferStarted = false
    private var filesTransferred = 0
    private var transferCompleted = false  // Flag to prevent duplicate completion

    // Files that were selected but became inaccessible before manifest was built
    // Captured ONCE at manifest build time for accurate completion reporting
    var droppedFiles: List<String> = emptyList()
        private set

    // Track completed batches (must be before init block!)
    private var completedBatches = mutableSetOf<Int>()
    private var totalBatchesStarted = false

    // Track uploads from PC (must be before init block!)
    private var uploadedFilesCount = 0
    private var uploadedBytesTotal = 0L

    init {
        // Clear any stale cache when server is created
        clearCache()
    }

    override fun start() {
        // Clear cache on every start to ensure fresh data
        clearCache()
        Log.d(TAG, "Server starting, cache cleared. Selected files: ${SelectionManager.getSelectedCount()}")
        super.start()
    }

    data class FileManifest(
        val files: List<FileEntry>,
        val directories: List<String>,
        val totalSize: Long,
        val totalFiles: Int,
        val skippedCount: Int = 0,  // Files that couldn't be included
        val originalCount: Int = 0   // Original selection count for verification
    )

    data class FileEntry(
        val absolutePath: String,
        val relativePath: String,
        val size: Long,
        val lastModified: Long
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                uri == "/" -> serveStatus()
                uri == "/info" -> serveInfo()
                uri == "/manifest" -> serveManifest()
                uri == "/dirs" -> serveDirectories()
                uri.startsWith("/file") -> serveFile(session)
                uri.startsWith("/progress") -> handleProgress(session)
                uri == "/complete" -> handleComplete(session)
                uri == "/cancel" -> handleCancel()
                uri.startsWith("/error") -> handleError(session)
                uri == "/clear-session" -> handleClearSession()
                // PC → Phone upload endpoints
                uri == "/upload" && method == Method.POST -> handleUpload(session)
                uri == "/upload-status" -> serveUploadStatus()
                // Batch transfer endpoints for large file counts (200,000+)
                uri == "/batch-info" -> serveBatchInfo()
                uri.startsWith("/batch/") && uri.contains("/manifest") -> serveBatchManifest(session)
                uri.startsWith("/batch/") && uri.contains("/file") -> serveBatchFile(session)
                uri.startsWith("/batch/") && uri.contains("/complete") -> handleBatchComplete(session)
                // Tar bundling for small files (reduces HTTP overhead)
                uri == "/tar-bundle" -> handleTarBundle(session)
                // Last-resort: search file by name via MediaStore and stream via ContentResolver
                uri == "/retry-file" -> handleRetryFile(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving request: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    // Cache for progress calculations to avoid expensive recalculations
    private var lastProgressFilesDone = 0
    private var lastProgressBytes = 0L

    private fun handleProgress(session: IHTTPSession): Response {
        val params = session.parms
        val filesDone = params["done"]?.toIntOrNull() ?: 0
        val totalFiles = params["total"]?.toIntOrNull() ?: 0
        val currentFile = params["current"] ?: ""

        // Notify listener about progress (optimized for large file counts)
        if (filesDone > 0 && totalFiles > 0) {
            filesTransferred = filesDone

            // Only recalculate bytes if files have changed significantly
            // For large file counts, approximate using average file size
            val manifest = getManifest()
            val bytesTransferred: Long = if (manifest.totalFiles > 10000) {
                // For large file counts, use approximation
                val avgFileSize = manifest.totalSize / manifest.totalFiles
                filesDone * avgFileSize
            } else if (filesDone > lastProgressFilesDone) {
                // For smaller counts, calculate incrementally
                val newFiles = manifest.files.subList(lastProgressFilesDone, minOf(filesDone, manifest.files.size))
                lastProgressBytes + newFiles.sumOf { it.size }
            } else {
                lastProgressBytes
            }

            lastProgressFilesDone = filesDone
            lastProgressBytes = bytesTransferred

            listener?.onFileProgress(currentFile, bytesTransferred, manifest.totalSize)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
    }

    private fun handleComplete(session: IHTTPSession): Response {
        // INSTANT completion - trigger on separate thread to avoid any blocking
        // Use flag to prevent duplicate completion triggers
        if (!transferCompleted) {
            transferCompleted = true

            // Parse PC-reported failures from query params
            val pcFailedFiles = mutableListOf<String>()
            val failedParam = session.parms?.get("failedFiles")
            if (!failedParam.isNullOrEmpty()) {
                try {
                    val arr = org.json.JSONArray(failedParam)
                    for (i in 0 until arr.length()) {
                        pcFailedFiles.add(arr.optString(i, ""))
                    }
                } catch (e: Exception) {
                    // Fallback: comma-separated
                    pcFailedFiles.addAll(failedParam.split(",").filter { it.isNotEmpty() })
                }
            }

            Log.d(TAG, "Transfer complete signal received - PC reported ${pcFailedFiles.size} failed files, ${droppedFiles.size} dropped files")

            // Trigger on a new thread to ensure it's not blocked by anything
            Thread {
                listener?.onTransferComplete(pcFailedFiles)
            }.start()
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"complete"}""")
    }

    /**
     * Handle cancellation signal from PC
     * This is CRITICAL for sync - when PC cancels, phone must know immediately
     */
    private fun handleCancel(): Response {
        Log.d(TAG, "Transfer CANCELLED by PC")

        // Mark as completed to prevent further processing
        transferCompleted = true

        // Notify listener on separate thread
        Thread {
            listener?.onTransferCancelled()
            listener?.onTransferError("Transfer cancelled by PC")
        }.start()

        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"cancelled"}""")
    }

    /**
     * Handle error signal from PC
     * This notifies the phone when PC encounters an error
     */
    private fun handleError(session: IHTTPSession): Response {
        val params = session.parms
        val errorMessage = params["message"] ?: "Unknown error on PC"

        Log.e(TAG, "Transfer ERROR from PC: $errorMessage")

        // Notify listener on separate thread
        Thread {
            listener?.onTransferError("PC error: $errorMessage")
        }.start()

        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"error_received"}""")
    }

    /**
     * Handle clear session signal from PC
     * Called when transfer is fully complete - clears all cached data
     * Ensures no traces of the transfer session remain
     */
    private fun handleClearSession(): Response {
        Log.d(TAG, "Clear session requested by PC - cleaning up all cached data")

        // Clear all cached data (also resets completedBatches, totalBatchesStarted, upload counters)
        clearCache()

        // Reset upload tracking (not covered by clearCache)
        uploadedFilesCount = 0
        uploadedBytesTotal = 0L

        Log.d(TAG, "Session cleared successfully - no traces remaining")

        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"session_cleared"}""")
    }

    // ==================== PC → PHONE UPLOAD HANDLERS ====================

    /**
     * Handle file upload from PC
     * POST /upload?path=relative/path/file.jpg
     * Body contains the file data
     */
    private fun handleUpload(session: IHTTPSession): Response {
        try {
            val params = session.parms
            val relativePath = params["path"]?.let { URLDecoder.decode(it, "UTF-8") }
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing path parameter")

            // Get content length
            val contentLength = session.headers["content-length"]?.toLongOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing Content-Length")

            Log.d(TAG, "Receiving upload: $relativePath ($contentLength bytes)")

            // Create destination file
            val destFile = File(uploadDestination, relativePath)
            destFile.parentFile?.mkdirs()

            // Stream body to file
            val inputStream = session.inputStream
            FileOutputStream(destFile).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Long = 0
                var count: Int

                while (bytesRead < contentLength) {
                    count = inputStream.read(buffer, 0, minOf(buffer.size, (contentLength - bytesRead).toInt()))
                    if (count == -1) break
                    outputStream.write(buffer, 0, count)
                    bytesRead += count
                }

                outputStream.flush()
            }

            // Update counters
            uploadedFilesCount++
            uploadedBytesTotal += contentLength

            Log.d(TAG, "Upload complete: $relativePath (total: $uploadedFilesCount files, ${formatSize(uploadedBytesTotal)})")

            // Notify listener
            listener?.onFileComplete(relativePath)

            // Notify receive listener for receive-mode UI
            receiveListener?.onFileReceived(relativePath, uploadedFilesCount, contentLength)
            receiveListener?.onReceiveProgress(uploadedFilesCount, uploadedBytesTotal)

            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"status":"ok","path":"$relativePath","size":$contentLength}""")

        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            receiveListener?.onReceiveError("Upload error: ${e.message}")
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Upload error: ${e.message}")
        }
    }

    /**
     * Return upload status/progress
     * GET /upload-status
     */
    private fun serveUploadStatus(): Response {
        val json = JSONObject().apply {
            put("status", "ready")
            put("uploadedFiles", uploadedFilesCount)
            put("uploadedBytes", uploadedBytesTotal)
            put("uploadedBytesFormatted", formatSize(uploadedBytesTotal))
            put("destination", uploadDestination.absolutePath)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    // ==================== BATCH TRANSFER SYSTEM ====================
    // For handling 200,000+ files without memory overload
    // Files are split into batches of BATCH_SIZE (1000) files each

    // Upload destination: Internal Storage/DeCloud/Received
    // Environment.getExternalStorageDirectory() returns /storage/emulated/0 (Internal Storage)
    private val uploadDestination: File by lazy {
        File(Environment.getExternalStorageDirectory(), "DeCloud/Received").apply {
            if (!exists()) {
                mkdirs()
                Log.d(TAG, "Created upload destination: $absolutePath")
            }
        }
    }

    /**
     * GET /batch-info
     * Returns information about batches for large transfers
     */
    private fun serveBatchInfo(): Response {
        val manifest = getManifest()
        val totalFiles = manifest.totalFiles
        val batchCount = (totalFiles + BATCH_SIZE - 1) / BATCH_SIZE  // Ceiling division

        // Generate session hash for resume functionality
        val samplePaths = manifest.files.take(10).map { it.absolutePath }
        val sessionHash = generateSessionHash(totalFiles, manifest.totalSize, samplePaths)

        Log.d(TAG, "Batch info requested: $totalFiles files, $batchCount batches of $BATCH_SIZE each, sessionHash: $sessionHash")

        val json = JSONObject().apply {
            put("totalFiles", totalFiles)
            put("totalSize", manifest.totalSize)
            put("totalSizeFormatted", formatSize(manifest.totalSize))
            put("batchSize", BATCH_SIZE)
            put("batchCount", batchCount)
            put("directories", manifest.directories.size)
            put("skippedCount", manifest.skippedCount)
            put("originalCount", manifest.originalCount)
            put("protocol", "batch")  // Indicate batch protocol
            put("sessionHash", sessionHash)  // For resume functionality
        }

        // Notify that transfer is starting
        if (!totalBatchesStarted) {
            totalBatchesStarted = true
            listener?.onClientConnected()
            listener?.onTransferStarted(totalFiles, manifest.totalSize)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * GET /batch/{batchNumber}/manifest
     * Returns manifest for a specific batch only
     */
    private fun serveBatchManifest(session: IHTTPSession): Response {
        val uri = session.uri
        // Parse batch number from URI: /batch/0/manifest
        val batchNumber = uri.split("/").getOrNull(2)?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid batch number")

        val fullManifest = getManifest()
        val totalFiles = fullManifest.totalFiles
        val batchCount = (totalFiles + BATCH_SIZE - 1) / BATCH_SIZE

        if (batchNumber < 0 || batchNumber >= batchCount) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Batch $batchNumber not found (total: $batchCount)")
        }

        // Calculate start and end indices for this batch
        val startIndex = batchNumber * BATCH_SIZE
        val endIndex = minOf(startIndex + BATCH_SIZE, totalFiles)
        val batchFiles = fullManifest.files.subList(startIndex, endIndex)

        Log.d(TAG, "Serving batch $batchNumber manifest: files $startIndex to ${endIndex - 1} (${batchFiles.size} files)")

        // Build batch manifest JSON
        val filesArray = JSONArray()
        batchFiles.forEachIndexed { localIndex, file ->
            filesArray.put(JSONObject().apply {
                put("path", file.absolutePath)
                put("relativePath", file.relativePath)
                put("size", file.size)
                put("lastModified", file.lastModified)
                put("globalIndex", startIndex + localIndex)  // Index in full file list
                put("localIndex", localIndex)                 // Index within this batch
            })
        }

        // Build path mapping for this batch only
        val pathMapping = JSONObject()
        batchFiles.forEach { file ->
            pathMapping.put(file.absolutePath, file.relativePath)
        }

        val json = JSONObject().apply {
            put("batchNumber", batchNumber)
            put("batchSize", batchFiles.size)
            put("startIndex", startIndex)
            put("endIndex", endIndex - 1)
            put("totalBatches", batchCount)
            put("totalFiles", totalFiles)
            put("files", filesArray)
            put("pathMapping", pathMapping)
            // Only include directories in first batch
            if (batchNumber == 0) {
                val dirsArray = JSONArray()
                fullManifest.directories.forEach { dir -> dirsArray.put(dir) }
                put("directories", dirsArray)

                // Include directory mapping for structure preservation
                val directoryMapping = JSONObject()
                val selectedDirectories = SelectionManager.getSelectedDirectoryPaths()
                val allDirectories = SelectionManager.getAllDirectoryPaths()
                allDirectories.forEach { dirPath ->
                    val relativePath = getRelativePath(dirPath, selectedDirectories)
                    directoryMapping.put(dirPath, relativePath)
                }
                put("directoryMapping", directoryMapping)
            }
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * GET /batch/{batchNumber}/file?index={localIndex}
     * Download a file from a specific batch using local index
     */
    private fun serveBatchFile(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms

        // Parse batch number from URI: /batch/0/file
        val batchNumber = uri.split("/").getOrNull(2)?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid batch number")

        // Get local index within batch
        val localIndex = params["index"]?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing index parameter")

        val fullManifest = getManifest()
        val totalFiles = fullManifest.totalFiles
        val batchCount = (totalFiles + BATCH_SIZE - 1) / BATCH_SIZE

        if (batchNumber < 0 || batchNumber >= batchCount) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Batch $batchNumber not found")
        }

        // Calculate global index
        val globalIndex = batchNumber * BATCH_SIZE + localIndex

        if (globalIndex < 0 || globalIndex >= totalFiles) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File index $globalIndex out of range")
        }

        val fileEntry = fullManifest.files[globalIndex]
        val file = File(fileEntry.absolutePath)

        Log.d(TAG, "Serving batch $batchNumber file $localIndex (global: $globalIndex): ${file.name}")

        // Open file stream with automatic fallback (FileInputStream → ContentResolver)
        val (rawStream, fileSize) = try {
            openFileStream(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "BATCH FILE INACCESSIBLE after all attempts: ${file.absolutePath} - ${e.message}")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cannot read file: ${e.message}")
        }

        listener?.onFileStarted(file.name, globalIndex + 1, totalFiles)

        val inputStream = ProgressInputStream(rawStream, fileSize, file.name, listener)

        val mimeType = getMimeType(file.name)

        val response = newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, fileSize)
        response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        response.addHeader("X-Relative-Path", fileEntry.relativePath)
        response.addHeader("X-Global-Index", globalIndex.toString())
        response.addHeader("X-Batch-Number", batchNumber.toString())

        return response
    }

    /**
     * GET /batch/{batchNumber}/complete
     * Mark a batch as complete
     */
    private fun handleBatchComplete(session: IHTTPSession): Response {
        val uri = session.uri
        val batchNumber = uri.split("/").getOrNull(2)?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid batch number")

        completedBatches.add(batchNumber)

        val fullManifest = getManifest()
        val totalFiles = fullManifest.totalFiles
        val batchCount = (totalFiles + BATCH_SIZE - 1) / BATCH_SIZE
        val completedFiles = minOf((batchNumber + 1) * BATCH_SIZE, totalFiles)

        Log.d(TAG, "Batch $batchNumber complete. Total batches done: ${completedBatches.size}/$batchCount")

        // Update progress
        listener?.onFileProgress("Batch ${batchNumber + 1}/$batchCount complete", completedFiles.toLong(), totalFiles.toLong())

        // Check if all batches are complete
        if (completedBatches.size >= batchCount) {
            Log.d(TAG, "All batches complete! Triggering transfer complete.")
            if (!transferCompleted) {
                transferCompleted = true
                Thread {
                    listener?.onTransferComplete()
                }.start()
            }
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"status":"ok","batchNumber":$batchNumber,"completedBatches":${completedBatches.size},"totalBatches":$batchCount}""")
    }

    private fun serveStatus(): Response {
        val manifest = getManifest()
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>DeCloud</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: -apple-system, sans-serif; padding: 20px; max-width: 600px; margin: 0 auto; }
                    h1 { color: #2196F3; }
                    .info { background: #f5f5f5; padding: 15px; border-radius: 8px; margin: 10px 0; }
                    .ready { color: #4CAF50; font-weight: bold; }
                    code { background: #e0e0e0; padding: 2px 6px; border-radius: 4px; }
                </style>
            </head>
            <body>
                <h1>DeCloud</h1>
                <p class="ready">Ready to transfer!</p>
                <div class="info">
                    <p><strong>Files:</strong> ${manifest.totalFiles}</p>
                    <p><strong>Size:</strong> ${formatSize(manifest.totalSize)}</p>
                    <p><strong>Directories:</strong> ${manifest.directories.size}</p>
                </div>
                <h3>API Endpoints:</h3>
                <ul>
                    <li><code>GET /info</code> - Server info (JSON)</li>
                    <li><code>GET /manifest</code> - File manifest (JSON)</li>
                    <li><code>GET /dirs</code> - Directories to create (JSON)</li>
                    <li><code>GET /file?path=X</code> - Download file</li>
                </ul>
                <p><em>Use DeCloud PC app for automatic transfer</em></p>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveInfo(): Response {
        val manifest = getManifest()

        // Generate session hash for resume functionality
        val samplePaths = manifest.files.take(10).map { it.absolutePath }
        val sessionHash = generateSessionHash(manifest.totalFiles, manifest.totalSize, samplePaths)

        val json = JSONObject().apply {
            put("status", "ready")
            put("fileCount", manifest.totalFiles)
            put("totalSize", manifest.totalSize)
            put("totalSizeFormatted", formatSize(manifest.totalSize))
            put("directoryCount", manifest.directories.size)
            put("protocol", "direct")  // Indicate this is direct streaming, not ZIP
            put("skippedCount", manifest.skippedCount)  // Files that couldn't be included
            put("originalCount", manifest.originalCount)  // Original selection for verification
            put("sessionHash", sessionHash)  // For resume functionality - matches PC's hash
        }

        Log.d(TAG, "Info served with sessionHash: $sessionHash")

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveManifest(): Response {
        listener?.onClientConnected()

        val manifest = getManifest()

        val filesArray = JSONArray()
        manifest.files.forEach { file ->
            filesArray.put(JSONObject().apply {
                put("path", file.absolutePath)
                put("relativePath", file.relativePath)
                put("size", file.size)
                put("lastModified", file.lastModified)
            })
        }

        val dirsArray = JSONArray()
        manifest.directories.forEach { dir ->
            dirsArray.put(dir)
        }

        // Create pathMapping dictionary: absolutePath -> relativePath
        // This is used by PC to preserve directory structure exactly
        val pathMapping = JSONObject()
        manifest.files.forEach { file ->
            pathMapping.put(file.absolutePath, file.relativePath)
        }

        // Create directoryMapping: absolutePath -> relativePath for directories
        val directoryMapping = JSONObject()
        val selectedDirectories = SelectionManager.getSelectedDirectoryPaths()
        val allDirectories = SelectionManager.getAllDirectoryPaths()
        allDirectories.forEach { dirPath ->
            val relativePath = getRelativePath(dirPath, selectedDirectories)
            directoryMapping.put(dirPath, relativePath)
        }

        val json = JSONObject().apply {
            put("version", 3)  // Version 3 with skipped count for verification
            put("totalFiles", manifest.totalFiles)
            put("totalSize", manifest.totalSize)
            put("totalDirectories", manifest.directories.size)
            put("files", filesArray)
            put("directories", dirsArray)
            put("pathMapping", pathMapping)  // Key for structure preservation!
            put("directoryMapping", directoryMapping)  // For empty dirs
            put("isBackupMode", SelectionManager.isBackupMode())
            put("skippedCount", manifest.skippedCount)  // Files that couldn't be added
            put("originalCount", manifest.originalCount)  // Original selection for verification
        }

        if (!transferStarted) {
            transferStarted = true
            filesTransferred = 0
            listener?.onTransferStarted(manifest.totalFiles, manifest.totalSize)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveDirectories(): Response {
        val manifest = getManifest()

        val json = JSONArray()
        manifest.directories.forEach { dir ->
            json.put(dir)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveFile(session: IHTTPSession): Response {
        val params = session.parms
        val manifest = getManifest()

        // SIMPLE APPROACH: Use file index instead of path - no encoding issues!
        val indexParam = params["index"]
        val pathParam = params["path"]

        val file: File
        val fileIndex: Int

        if (indexParam != null) {
            // New simple method: request by index (e.g., /file?index=42)
            fileIndex = indexParam.toIntOrNull() ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid index"
            )

            if (fileIndex < 0 || fileIndex >= manifest.files.size) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Index out of range: $fileIndex"
                )
            }

            val fileEntry = manifest.files[fileIndex]
            file = File(fileEntry.absolutePath)

        } else if (pathParam != null) {
            // Legacy method: request by path (kept for compatibility)
            file = File(pathParam)
            fileIndex = manifest.files.indexOfFirst { it.absolutePath == pathParam }
            Log.d(TAG, "Serving file by path: ${file.name}")

        } else {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing 'index' or 'path' parameter"
            )
        }

        // Open file stream with automatic fallback (FileInputStream → ContentResolver)
        val (rawStream, fileSize) = try {
            openFileStream(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "FILE INACCESSIBLE after all attempts: ${file.absolutePath} - ${e.message}")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cannot read file: ${e.message}")
        }

        listener?.onFileStarted(file.name, fileIndex + 1, manifest.totalFiles)

        val inputStream = ProgressInputStream(rawStream, fileSize, file.name, listener)

        val mimeType = getMimeType(file.name)

        // Get relative path from manifest if available
        val relativePath = if (fileIndex >= 0 && fileIndex < manifest.files.size) {
            manifest.files[fileIndex].relativePath
        } else {
            file.name
        }

        val response = newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, fileSize)
        response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        response.addHeader("X-Relative-Path", relativePath)

        return response
    }

    private fun getManifest(): FileManifest {
        // Return cached if available
        cachedManifest?.let { return it }

        var selectedFiles = SelectionManager.getSelectedFiles()
        val selectedDirectories = SelectionManager.getSelectedDirectoryPaths()
        val allDirectories = SelectionManager.getAllDirectoryPaths()

        // Capture files that became inaccessible between selection and manifest build
        val allSelectedPaths = SelectionManager.getSelectedFilePaths()
        val accessiblePaths = selectedFiles.map { it.absolutePath }.toSet()
        droppedFiles = allSelectedPaths.filter { it !in accessiblePaths }
        if (droppedFiles.isNotEmpty()) {
            Log.w(TAG, "${droppedFiles.size} files became inaccessible since selection")
            Log.w(TAG, "Dropped files: ${droppedFiles.take(20).map { File(it).name }}")
        }

        Log.d(TAG, "Building manifest - Selected files: ${selectedFiles.size}, Selected dirs: ${selectedDirectories.size}, All dirs: ${allDirectories.size}")

        // If no files but directories exist, enumerate files from directories
        // This handles cases where async selection didn't complete
        if (selectedFiles.isEmpty() && selectedDirectories.isNotEmpty()) {
            Log.d(TAG, "No files found, enumerating from directories...")
            val filesFromDirs = mutableListOf<File>()
            for (dirPath in selectedDirectories) {
                val dir = File(dirPath)
                if (dir.exists() && dir.isDirectory) {
                    enumerateFilesRecursively(dir, filesFromDirs)
                }
            }
            selectedFiles = filesFromDirs
            Log.d(TAG, "Enumerated ${filesFromDirs.size} files from directories")
        }

        // CRITICAL: Track ALL files - don't silently drop any!
        val fileEntries = mutableListOf<FileEntry>()
        var skippedNotExists = 0
        var skippedNotReadable = 0
        var skippedNotFile = 0
        var skippedError = 0

        selectedFiles.forEach { file ->
            try {
                when {
                    !file.exists() -> {
                        skippedNotExists++
                        Log.w(TAG, "SKIPPED (not exists): ${file.absolutePath}")
                    }
                    !file.canRead() -> {
                        skippedNotReadable++
                        Log.w(TAG, "SKIPPED (not readable): ${file.absolutePath}")
                    }
                    !file.isFile -> {
                        skippedNotFile++
                        Log.w(TAG, "SKIPPED (not a file): ${file.absolutePath}")
                    }
                    else -> {
                        val relPath = getRelativePath(file.absolutePath, selectedDirectories)
                        fileEntries.add(FileEntry(
                            absolutePath = file.absolutePath,
                            relativePath = relPath,
                            size = file.length(),
                            lastModified = file.lastModified()
                        ))
                    }
                }
            } catch (e: Exception) {
                skippedError++
                Log.e(TAG, "SKIPPED (error): ${file.absolutePath} - ${e.message}")
            }
        }

        // CRITICAL VERIFICATION LOG
        // totalSkipped = files dropped during manifest build (second filter pass)
        // droppedFiles = files that became inaccessible between selection and manifest build (first filter pass)
        val totalSkipped = skippedNotExists + skippedNotReadable + skippedNotFile + skippedError
        val totalUnavailable = totalSkipped + droppedFiles.size  // Both filter passes combined
        Log.i(TAG, "=== MANIFEST VERIFICATION ===")
        Log.i(TAG, "Original user selection: ${allSelectedPaths.size}")
        Log.i(TAG, "After accessibility filter: ${selectedFiles.size} (${droppedFiles.size} dropped)")
        Log.i(TAG, "Added to manifest: ${fileEntries.size}")
        Log.i(TAG, "Skipped during manifest build: $totalSkipped")
        Log.i(TAG, "Total unavailable: $totalUnavailable")
        if (totalSkipped > 0) {
            Log.w(TAG, "  - Not exists: $skippedNotExists")
            Log.w(TAG, "  - Not readable: $skippedNotReadable")
            Log.w(TAG, "  - Not a file: $skippedNotFile")
            Log.w(TAG, "  - Errors: $skippedError")
        }
        if (allSelectedPaths.size != fileEntries.size + totalUnavailable) {
            Log.e(TAG, "CRITICAL: File count mismatch! ${allSelectedPaths.size} != ${fileEntries.size} + $totalUnavailable")
        }

        // Log first few entries for debugging
        fileEntries.take(3).forEach { entry ->
            Log.d(TAG, "Sample entry - Abs: ${entry.absolutePath}, Rel: ${entry.relativePath}")
        }

        // Get relative paths for directories
        val directoryPaths = allDirectories.map { dirPath ->
            getRelativePath(dirPath, selectedDirectories)
        }.distinct().sorted()

        val totalSize = fileEntries.sumOf { it.size }

        cachedManifest = FileManifest(
            files = fileEntries,
            directories = directoryPaths,
            totalSize = totalSize,
            totalFiles = fileEntries.size,
            skippedCount = totalUnavailable,   // Both filter passes: dropped + manifest-build skips
            originalCount = allSelectedPaths.size  // True user selection count (before any filtering)
        )

        Log.d(TAG, "Manifest ready: ${fileEntries.size} files ($totalUnavailable skipped), ${directoryPaths.size} directories, ${totalSize} bytes")

        return cachedManifest!!
    }

    private fun enumerateFilesRecursively(directory: File, fileList: MutableList<File>) {
        try {
            val files = directory.listFiles() ?: return
            for (file in files) {
                if (file.isDirectory) {
                    enumerateFilesRecursively(file, fileList)
                } else if (file.isFile && file.canRead()) {
                    fileList.add(file)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating directory ${directory.path}: ${e.message}")
        }
    }

    /**
     * Find the selection root for a file
     * Returns the path of the TOP-LEVEL selected directory that contains this file,
     * or null if the file was directly selected (not inside any selected directory)
     *
     * Uses minByOrNull to find the SHALLOWEST (shortest path) match - this is the
     * directory the user actually selected, not a nested subdirectory.
     *
     * COPIED FROM AdbTransferService.kt for consistency
     */
    private fun findSelectionRoot(filePath: String, selectedDirectories: List<String>): String? {
        return selectedDirectories
            .filter { dirPath ->
                filePath.startsWith(dirPath + "/") ||
                filePath.startsWith(dirPath + "\\")
            }
            .minByOrNull { it.length }  // SHALLOWEST match - same as ADB
    }

    /**
     * Get relative path for structure preservation
     * COPIED FROM AdbTransferService.kt for identical behavior
     *
     * - In backup mode: use storage prefix (e.g., "Internal Storage/DCIM/...")
     * - If file is inside a selected directory: preserve structure from PARENT of that directory
     * - If file was directly selected: use just the filename
     */
    private fun getRelativePath(absolutePath: String, selectedDirectories: List<String>): String {
        // In backup mode, use the backup relative path from SelectionManager
        if (SelectionManager.isBackupMode()) {
            return SelectionManager.getBackupRelativePath(absolutePath)
        }

        val selectionRoot = findSelectionRoot(absolutePath, selectedDirectories)

        return if (selectionRoot != null) {
            // File is inside a selected directory - preserve structure from PARENT of that directory
            // This is the key difference from the old implementation
            val parentOfRoot = File(selectionRoot).parent ?: ""
            if (parentOfRoot.isEmpty()) {
                // Selection root is at filesystem root
                absolutePath.removePrefix("/").removePrefix("\\").replace('\\', '/')
            } else {
                // Make path relative to parent of selection root (includes the root folder name)
                absolutePath.removePrefix(parentOfRoot)
                    .trimStart('/')
                    .trimStart('\\')
                    .replace('\\', '/')
            }
        } else {
            // File was directly selected (not inside any selected directory) - use just filename
            File(absolutePath).name
        }
    }


    /**
     * Try to open a file InputStream. Attempts:
     * 1. Direct FileInputStream (fastest, works for most files)
     * 2. ContentResolver via MediaStore (fallback for scoped storage issues)
     * Returns the InputStream and file size, or throws if all methods fail.
     */
    private fun openFileStream(absolutePath: String): Pair<InputStream, Long> {
        val file = File(absolutePath)

        // Attempt 1: Direct FileInputStream (with retries for transient locks)
        for (attempt in 1..3) {
            try {
                val fis = FileInputStream(file)
                val size = file.length()
                return Pair(BufferedInputStream(fis, BUFFER_SIZE), size)
            } catch (e: Exception) {
                if (attempt < 3) {
                    try { Thread.sleep(100) } catch (_: Exception) {}
                }
            }
        }

        // Attempt 2: ContentResolver fallback (handles scoped storage, special permissions)
        if (context != null) {
            Log.d(TAG, "FileInputStream failed for: $absolutePath — trying ContentResolver")
            try {
                val contentUri = getContentUriForPath(absolutePath)
                if (contentUri != null) {
                    val resolver = context.contentResolver
                    val inputStream = resolver.openInputStream(contentUri)
                    if (inputStream != null) {
                        // Get file size from ContentResolver
                        var size = file.length()
                        if (size <= 0L) {
                            resolver.query(contentUri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    size = cursor.getLong(0)
                                }
                            }
                        }
                        Log.d(TAG, "ContentResolver opened successfully: $absolutePath (size: $size)")
                        return Pair(BufferedInputStream(inputStream, BUFFER_SIZE), size)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ContentResolver also failed for: $absolutePath - ${e.message}")
            }
        }

        throw java.io.IOException("Cannot open file after all attempts: $absolutePath")
    }

    /**
     * Find a content:// URI for a file path by querying MediaStore.
     */
    private fun getContentUriForPath(absolutePath: String): Uri? {
        val resolver = context?.contentResolver ?: return null

        // Try multiple MediaStore collections
        val collections = arrayOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )

        for (collection in collections) {
            try {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA} = ?",
                    arrayOf(absolutePath),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        return Uri.withAppendedPath(collection, id.toString())
                    }
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun onFileTransferComplete(fileName: String) {
        filesTransferred++
        listener?.onFileComplete(fileName)

        // Don't trigger completion here - let /complete endpoint handle it
        // This avoids race conditions and ensures instant completion from PC signal
        // The PC will call /complete when all downloads are done
    }

    fun clearCache() {
        cachedManifest = null
        transferStarted = false
        filesTransferred = 0
        transferCompleted = false
        lastProgressFilesDone = 0
        lastProgressBytes = 0L
        // Reset batch tracking
        completedBatches.clear()
        totalBatchesStarted = false
    }

    // ==================== RETRY-FILE: MEDIASTORE SEARCH + CONTENTRESOLVER STREAM ====================

    /**
     * GET /retry-file?name=<filename>&size=<bytes>
     *
     * Last-resort endpoint for files that can't be read via File API.
     * Searches MediaStore by display name (and optionally size), then streams
     * the file via ContentResolver — completely bypassing java.io.File.
     */
    private fun handleRetryFile(session: IHTTPSession): Response {
        val ctx = context ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No context available"
        )

        val fileName = session.parms?.get("name")
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing 'name' parameter")
        val expectedSize = session.parms?.get("size")?.toLongOrNull() ?: -1L

        Log.d(TAG, "retry-file: searching MediaStore for '$fileName' (expected size: $expectedSize)")

        val resolver = ctx.contentResolver

        // Search all MediaStore collections for the file by DISPLAY_NAME
        val collections = arrayOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )

        for (collection in collections) {
            try {
                val selection = if (expectedSize > 0) {
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
                } else {
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                }
                val selectionArgs = if (expectedSize > 0) {
                    arrayOf(fileName, expectedSize.toString())
                } else {
                    arrayOf(fileName)
                }

                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        val fileSize = cursor.getLong(1)
                        val mimeType = cursor.getString(2) ?: "application/octet-stream"
                        val contentUri = Uri.withAppendedPath(collection, id.toString())

                        Log.d(TAG, "retry-file: FOUND '$fileName' in MediaStore — uri=$contentUri, size=$fileSize")

                        val inputStream = resolver.openInputStream(contentUri)
                            ?: return newFixedLengthResponse(
                                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to open ContentResolver stream"
                            )

                        val bufferedStream = BufferedInputStream(inputStream, BUFFER_SIZE)

                        val response = if (fileSize > 0) {
                            newFixedLengthResponse(Response.Status.OK, mimeType, bufferedStream, fileSize)
                        } else {
                            newChunkedResponse(Response.Status.OK, mimeType, bufferedStream)
                        }
                        response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                        response.addHeader("X-Retry-Source", "mediastore")
                        return response
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "retry-file: error searching collection $collection for '$fileName': ${e.message}")
            }
        }

        // Fallback: LIKE prefix search for long filenames (MediaStore may truncate DISPLAY_NAME at 255 bytes)
        if (fileName.length > 200) {
            val prefix = fileName.substring(0, 200)
            Log.d(TAG, "retry-file: exact match failed, trying LIKE prefix for long filename")
            for (collection in collections) {
                try {
                    val likeSelection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" +
                        if (expectedSize > 0) " AND ${MediaStore.MediaColumns.SIZE} = ?" else ""
                    val likeArgs = if (expectedSize > 0) {
                        arrayOf("$prefix%", expectedSize.toString())
                    } else {
                        arrayOf("$prefix%")
                    }

                    resolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE),
                        likeSelection,
                        likeArgs,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(0)
                            val fileSize = cursor.getLong(1)
                            val mimeType = cursor.getString(2) ?: "application/octet-stream"
                            val contentUri = Uri.withAppendedPath(collection, id.toString())

                            Log.d(TAG, "retry-file: FOUND via LIKE prefix — uri=$contentUri, size=$fileSize")

                            val inputStream = resolver.openInputStream(contentUri)
                                ?: return newFixedLengthResponse(
                                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to open stream"
                                )
                            val bufferedStream = BufferedInputStream(inputStream, BUFFER_SIZE)
                            val response = if (fileSize > 0) {
                                newFixedLengthResponse(Response.Status.OK, mimeType, bufferedStream, fileSize)
                            } else {
                                newChunkedResponse(Response.Status.OK, mimeType, bufferedStream)
                            }
                            response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                            response.addHeader("X-Retry-Source", "mediastore-like")
                            return response
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "retry-file LIKE fallback error for $collection: ${e.message}")
                }
            }
        }

        Log.e(TAG, "retry-file: '$fileName' NOT FOUND in any MediaStore collection")
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found in MediaStore: $fileName")
    }

    // ==================== TAR BUNDLING FOR SMALL FILES ====================

    /**
     * Stream multiple small files as a single tar archive.
     * Eliminates per-file HTTP overhead for thousands of small files.
     * Uses POSIX ustar format: 512-byte headers, no compression, no temp files.
     */
    private fun handleTarBundle(session: IHTTPSession): Response {
        val manifest = cachedManifest ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No manifest available"
        )

        val indicesParam = session.parms?.get("indices")
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing indices parameter"
            )

        val indices = indicesParam.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until manifest.files.size }

        if (indices.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No valid indices"
            )
        }

        Log.d(TAG, "Tar bundle request: ${indices.size} files")

        val pipedInput = PipedInputStream(BUFFER_SIZE)
        val pipedOutput = PipedOutputStream(pipedInput)

        Thread {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                for (index in indices) {
                    val entry = manifest.files[index]

                    try {
                        // Use openFileStream with automatic fallback (File → ContentResolver)
                        val (inputStream, fileSize) = openFileStream(entry.absolutePath)

                        writeTarHeader(pipedOutput, entry.relativePath, fileSize, File(entry.absolutePath).lastModified())

                        inputStream.use { fis ->
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                pipedOutput.write(buffer, 0, read)
                            }
                        }

                        // Pad to 512-byte boundary
                        val remainder = (fileSize % 512).toInt()
                        if (remainder != 0) pipedOutput.write(ByteArray(512 - remainder))
                    } catch (e: Exception) {
                        Log.e(TAG, "Tar bundle: SKIPPING file [$index] ${entry.relativePath} - ${e.message}")
                        // Don't write tar header if we can't open the file — just skip cleanly
                    }
                }
                // End-of-archive: two 512-byte zero blocks
                pipedOutput.write(ByteArray(1024))
                pipedOutput.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Tar bundle streaming error: ${e.message}")
            } finally {
                try { pipedOutput.close() } catch (_: Exception) {}
            }
        }.start()

        val response = newChunkedResponse(Response.Status.OK, "application/x-tar", pipedInput)
        response.addHeader("X-Tar-File-Count", indices.size.toString())
        return response
    }

    /**
     * Middle-truncate a filename to maxLen, preserving extension and both ends.
     * "VeryLongFileName.png" → "VeryLo~Name.png" — extension always safe.
     */
    private fun middleTruncateName(name: String, maxLen: Int): String {
        if (name.length <= maxLen) return name
        val dotIdx = name.lastIndexOf('.')
        val ext = if (dotIdx > 0 && name.length - dotIdx <= 10) name.substring(dotIdx) else ""
        val base = if (ext.isNotEmpty()) name.substring(0, dotIdx) else name
        val available = maxLen - ext.length - 1 // -1 for ~ separator
        if (available < 4) return name.take(maxLen) // Edge case: extension itself is huge
        val frontLen = (available + 1) / 2
        val backLen = available - frontLen
        return base.substring(0, frontLen) + "~" + base.substring(base.length - backLen) + ext
    }

    /**
     * Write a POSIX ustar tar header (512 bytes).
     * Handles paths > 100 chars via prefix/name split.
     * Filenames > 100 chars are middle-truncated preserving extension.
     */
    private fun writeTarHeader(out: OutputStream, relativePath: String, size: Long, lastModifiedMs: Long) {
        val header = ByteArray(512)

        // Split long paths: prefix (offset 345, max 155 chars) + name (offset 0, max 100 chars)
        val (prefix, shortName) = if (relativePath.length > 100) {
            val splitAt = relativePath.lastIndexOf('/', minOf(relativePath.length - 1, 155))
            if (splitAt > 0) {
                val dirPart = relativePath.substring(0, splitAt)
                val filePart = relativePath.substring(splitAt + 1)
                if (filePart.length > 100) {
                    Pair(dirPart.take(155), middleTruncateName(filePart, 100))
                } else {
                    Pair(dirPart.take(155), filePart)
                }
            } else {
                // No directory separator — entire path is a filename > 100 chars
                Pair("", middleTruncateName(relativePath, 100))
            }
        } else Pair("", relativePath)

        shortName.toByteArray(Charsets.UTF_8).copyInto(header, 0)                    // name (0-99)
        "0000644\u0000".toByteArray().copyInto(header, 100)                           // mode
        "0000000\u0000".toByteArray().copyInto(header, 108)                           // uid
        "0000000\u0000".toByteArray().copyInto(header, 116)                           // gid
        String.format("%011o\u0000", size).toByteArray().copyInto(header, 124)        // size (octal)
        String.format("%011o\u0000", lastModifiedMs / 1000).toByteArray().copyInto(header, 136) // mtime
        "        ".toByteArray().copyInto(header, 148)                                // checksum placeholder
        header[156] = '0'.code.toByte()                                               // typeflag: regular file
        "ustar\u0000".toByteArray().copyInto(header, 257)                             // magic
        "00".toByteArray().copyInto(header, 263)                                      // version
        if (prefix.isNotEmpty()) prefix.toByteArray(Charsets.UTF_8).copyInto(header, 345)

        // Calculate checksum (sum of unsigned bytes, checksum field treated as spaces)
        var checksum = 0
        for (b in header) checksum += (b.toInt() and 0xFF)
        String.format("%06o\u0000 ", checksum).toByteArray().copyInto(header, 148)

        out.write(header)
    }

    /**
     * InputStream wrapper that tracks progress
     */
    private inner class ProgressInputStream(
        private val inputStream: InputStream,
        private val totalSize: Long,
        private val fileName: String,
        private val listener: TransferListener?
    ) : InputStream() {

        private var bytesRead: Long = 0
        private var lastProgressUpdate: Long = 0

        override fun read(): Int {
            val byte = inputStream.read()
            if (byte != -1) {
                bytesRead++
                updateProgress()
            } else {
                onFileTransferComplete(fileName)
            }
            return byte
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = inputStream.read(b, off, len)
            if (count > 0) {
                bytesRead += count
                updateProgress()
            } else if (count == -1) {
                onFileTransferComplete(fileName)
            }
            return count
        }

        private fun updateProgress() {
            val now = System.currentTimeMillis()
            // Update progress every 500ms to avoid too many callbacks
            if (now - lastProgressUpdate > 500) {
                listener?.onFileProgress(fileName, bytesRead, totalSize)
                lastProgressUpdate = now
            }
        }

        override fun close() {
            inputStream.close()
        }

        override fun available(): Int = inputStream.available()
    }
}
