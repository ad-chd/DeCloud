package com.decloud.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility to create and save transfer logs
 */
object TransferLogger {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    data class TransferResult(
        val fileName: String,
        val filePath: String,
        val success: Boolean,
        val error: String? = null
    )

    data class TransferSummary(
        val startTime: Long,
        val endTime: Long,
        val totalSelected: Int,
        val totalTransferred: Int,
        val totalSkipped: Int,
        val totalFailed: Int,
        val totalSize: Long,
        val results: List<TransferResult>
    )

    /**
     * Generate log content from transfer summary
     */
    fun generateLogContent(summary: TransferSummary): String {
        val sb = StringBuilder()

        // Header
        sb.appendLine("=" .repeat(60))
        sb.appendLine("DeCloud - Transfer Log")
        sb.appendLine("=".repeat(60))
        sb.appendLine()

        // Time info
        sb.appendLine("Started:  ${dateFormat.format(Date(summary.startTime))}")
        sb.appendLine("Finished: ${dateFormat.format(Date(summary.endTime))}")
        val duration = (summary.endTime - summary.startTime) / 1000
        val minutes = duration / 60
        val seconds = duration % 60
        sb.appendLine("Duration: ${minutes}m ${seconds}s")
        sb.appendLine()

        // Summary
        sb.appendLine("-".repeat(60))
        sb.appendLine("SUMMARY")
        sb.appendLine("-".repeat(60))
        sb.appendLine("Total Selected:    ${summary.totalSelected} files")
        sb.appendLine("Transferred:       ${summary.totalTransferred} files")
        sb.appendLine("Skipped:           ${summary.totalSkipped} files (inaccessible)")
        sb.appendLine("Failed:            ${summary.totalFailed} files")
        sb.appendLine("Total Size:        ${formatSize(summary.totalSize)}")
        sb.appendLine()

        // Success rate
        val successRate = if (summary.totalSelected > 0) {
            (summary.totalTransferred * 100) / summary.totalSelected
        } else 0
        sb.appendLine("Success Rate:      $successRate%")
        sb.appendLine()

        // File details
        sb.appendLine("-".repeat(60))
        sb.appendLine("FILE DETAILS")
        sb.appendLine("-".repeat(60))

        // Group by status
        val successful = summary.results.filter { it.success }
        val failed = summary.results.filter { !it.success }

        if (successful.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[TRANSFERRED - ${successful.size} files]")
            successful.forEach { result ->
                sb.appendLine("  ✓ ${result.filePath}")
            }
        }

        if (failed.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[FAILED - ${failed.size} files]")
            failed.forEach { result ->
                sb.appendLine("  ✗ ${result.filePath}")
                result.error?.let { sb.appendLine("    Error: $it") }
            }
        }

        sb.appendLine()
        sb.appendLine("=".repeat(60))
        sb.appendLine("End of Log")
        sb.appendLine("=".repeat(60))

        return sb.toString()
    }

    /**
     * Save log to Downloads folder
     */
    fun saveLog(context: Context, summary: TransferSummary): File? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadsDir, "DeCloud_Logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            val fileName = "transfer_log_${fileNameFormat.format(Date(summary.startTime))}.txt"
            val logFile = File(logDir, fileName)

            val content = generateLogContent(summary)
            logFile.writeText(content)

            logFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get log directory path
     */
    fun getLogDirectory(): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, "DeCloud_Logs").absolutePath
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}
