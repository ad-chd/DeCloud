package com.decloud.server

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Lightweight HTTP server that streams selected files as a single ZIP
 * Uses NanoHTTPD for minimal overhead
 */
class ZipStreamServer(
    port: Int,
    private val selectedFiles: List<File>,
    private val listener: TransferListener
) : NanoHTTPD(port) {

    interface TransferListener {
        fun onClientConnected()
        fun onTransferStarted(totalFiles: Int, totalSize: Long)
        fun onFileStarted(fileName: String, fileIndex: Int)
        fun onProgress(bytesTransferred: Long, totalBytes: Long)
        fun onTransferComplete()
        fun onTransferError(error: String)
    }

    private var totalBytesTransferred = 0L
    private var totalSize = 0L
    private var isTransferring = false

    init {
        totalSize = selectedFiles.sumOf { it.length() }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/" || uri == "/status" -> serveStatus()
            uri == "/info" -> serveInfo()
            uri == "/download" -> serveZipStream()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not Found"
            )
        }
    }

    /**
     * Serve status page for browser access
     */
    private fun serveStatus(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>DeCloud</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        max-width: 600px;
                        margin: 50px auto;
                        padding: 20px;
                        background: #f5f5f5;
                    }
                    .card {
                        background: white;
                        border-radius: 12px;
                        padding: 30px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    h1 { color: #333; margin-bottom: 10px; }
                    .info { color: #666; margin-bottom: 20px; }
                    .stats {
                        background: #f0f0f0;
                        padding: 15px;
                        border-radius: 8px;
                        margin-bottom: 20px;
                    }
                    .download-btn {
                        display: block;
                        background: #4CAF50;
                        color: white;
                        text-align: center;
                        padding: 15px 30px;
                        border-radius: 8px;
                        text-decoration: none;
                        font-size: 18px;
                        font-weight: bold;
                    }
                    .download-btn:hover { background: #45a049; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>DeCloud Ready</h1>
                    <p class="info">Your files are ready to download</p>
                    <div class="stats">
                        <p><strong>Files:</strong> ${selectedFiles.size}</p>
                        <p><strong>Total Size:</strong> ${formatSize(totalSize)}</p>
                    </div>
                    <a href="/download" class="download-btn">Download ZIP</a>
                </div>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    /**
     * Serve info as JSON for PC client
     */
    private fun serveInfo(): Response {
        val json = """
            {
                "status": "ready",
                "fileCount": ${selectedFiles.size},
                "totalSize": $totalSize,
                "totalSizeFormatted": "${formatSize(totalSize)}"
            }
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /**
     * Stream all files as a single ZIP
     * This is the "ONE HANDSHAKE" approach
     */
    private fun serveZipStream(): Response {
        if (isTransferring) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Transfer already in progress"
            )
        }

        isTransferring = true
        listener.onClientConnected()
        listener.onTransferStarted(selectedFiles.size, totalSize)

        // Create piped streams for streaming ZIP
        // Large buffer = fewer system calls = faster transfer
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 4 * 1024 * 1024) // 4MB buffer

        // Start ZIP writing in background thread
        thread(start = true, name = "ZipWriter") {
            try {
                ZipOutputStream(pipedOut).use { zipOut ->
                    zipOut.setLevel(0) // No compression for speed (STORE method)

                    selectedFiles.forEachIndexed { index, file ->
                        if (!file.exists() || !file.canRead()) {
                            return@forEachIndexed
                        }

                        listener.onFileStarted(file.name, index + 1)

                        try {
                            // Create entry with relative path
                            val entryName = file.name
                            val entry = ZipEntry(entryName)
                            entry.size = file.length()
                            entry.time = file.lastModified()

                            zipOut.putNextEntry(entry)

                            // Stream file bytes with large buffer for speed
                            FileInputStream(file).use { fis ->
                                val buffer = ByteArray(256 * 1024) // 256KB buffer for fast reads
                                var bytesRead: Int

                                while (fis.read(buffer).also { bytesRead = it } != -1) {
                                    zipOut.write(buffer, 0, bytesRead)
                                    totalBytesTransferred += bytesRead
                                    // Only update progress every 1MB to reduce overhead
                                    if (totalBytesTransferred % (1024 * 1024) < bytesRead) {
                                        listener.onProgress(totalBytesTransferred, totalSize)
                                    }
                                }
                            }

                            zipOut.closeEntry()

                        } catch (e: Exception) {
                            listener.onTransferError("Error processing ${file.name}: ${e.message}")
                        }
                    }
                }

                listener.onTransferComplete()

            } catch (e: Exception) {
                listener.onTransferError("Transfer failed: ${e.message}")
            } finally {
                isTransferring = false
                try {
                    pipedOut.close()
                } catch (e: Exception) {}
            }
        }

        // Generate filename with timestamp
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val filename = "transfer_$timestamp.zip"

        // Return chunked response (streaming)
        val response = newChunkedResponse(
            Response.Status.OK,
            "application/zip",
            pipedIn
        )

        response.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        response.addHeader("Cache-Control", "no-cache")

        return response
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun getTotalSize(): Long = totalSize
    fun getFileCount(): Int = selectedFiles.size
}
