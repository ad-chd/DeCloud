package com.decloud.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.decloud.model.FileItem
import com.decloud.model.SelectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages full backup operations using native Android APIs
 * Supports category-based backup with automatic exclusions
 */
object BackupManager {

    private const val TAG = "BackupManager"

    /**
     * Backup mode options - storage selection
     */
    enum class BackupMode {
        INTERNAL_ONLY,          // Only backup internal storage
        INTERNAL_AND_EXTERNAL   // Backup both internal and external storage
    }

    /**
     * Listener for backup scan progress
     */
    interface BackupScanListener {
        fun onScanStarted()
        fun onCategoryStarted(category: BackupCategory)
        fun onCategoryProgress(category: BackupCategory, scannedFiles: Int, currentFile: String)
        fun onCategoryComplete(result: CategoryScanResult)
        fun onScanProgress(scannedFiles: Int, scannedDirs: Int, currentPath: String)
        fun onScanComplete(totalFiles: Int, totalDirs: Int, totalSize: Long, excludedFiles: Int, excludedSize: Long)
        fun onScanError(error: String)
    }

    /**
     * Simple listener adapter for basic progress (backward compatibility)
     */
    abstract class SimpleBackupScanListener : BackupScanListener {
        override fun onCategoryStarted(category: BackupCategory) {}
        override fun onCategoryProgress(category: BackupCategory, scannedFiles: Int, currentFile: String) {}
        override fun onCategoryComplete(result: CategoryScanResult) {}
    }

    /**
     * Result of storage size calculation
     */
    data class StorageSizeInfo(
        val fileCount: Int,
        val dirCount: Int,
        val totalSize: Long,
        val excludedCount: Int = 0,
        val excludedSize: Long = 0
    ) {
        fun getFormattedSize(): String {
            return when {
                totalSize >= 1024L * 1024 * 1024 * 1024 ->
                    String.format("%.1f TB", totalSize / (1024.0 * 1024 * 1024 * 1024))
                totalSize >= 1024L * 1024 * 1024 ->
                    String.format("%.1f GB", totalSize / (1024.0 * 1024 * 1024))
                totalSize >= 1024L * 1024 ->
                    String.format("%.1f MB", totalSize / (1024.0 * 1024))
                totalSize >= 1024L ->
                    String.format("%.1f KB", totalSize / 1024.0)
                else -> "$totalSize B"
            }
        }

        fun getSummary(): String {
            val base = "$fileCount files, $dirCount folders (${getFormattedSize()})"
            return if (excludedCount > 0) {
                "$base\n${excludedCount} files excluded"
            } else base
        }
    }

    /**
     * Scan categories and return results for preview
     * This allows user to see what will be backed up before starting
     */
    suspend fun scanCategories(
        context: Context,
        categories: List<BackupCategory>,
        mode: BackupMode,
        listener: BackupScanListener
    ): List<CategoryScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CategoryScanResult>()

        listener.onScanStarted()

        for (category in categories) {
            listener.onCategoryStarted(category)

            // Check permission for this category
            val hasPermission = category.requiredPermission == null ||
                    ContextCompat.checkSelfPermission(context, category.requiredPermission) ==
                    PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                val noPermResult = CategoryScanResult(
                    category = category,
                    fileCount = 0,
                    totalSize = 0,
                    excludedCount = 0,
                    excludedSize = 0,
                    isEnabled = true,
                    hasPermission = false,
                    errorMessage = "Permission required: ${category.requiredPermission}"
                )
                results.add(noPermResult)
                listener.onCategoryComplete(noPermResult)
                continue
            }

            try {
                val result = when (category.sourceType) {
                    BackupCategory.SourceType.MEDIA_STORE -> {
                        scanMediaStoreCategory(context, category, mode, listener)
                    }
                    BackupCategory.SourceType.FOLDER -> {
                        scanFolderCategory(context, category, mode, listener)
                    }
                    BackupCategory.SourceType.CONTENT_PROVIDER -> {
                        scanContentProviderCategory(context, category, listener)
                    }
                }
                results.add(result)
                listener.onCategoryComplete(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning category ${category.name}: ${e.message}")
                val errorResult = CategoryScanResult(
                    category = category,
                    fileCount = 0,
                    totalSize = 0,
                    excludedCount = 0,
                    excludedSize = 0,
                    isEnabled = true,
                    hasPermission = true,
                    errorMessage = e.message
                )
                results.add(errorResult)
                listener.onCategoryComplete(errorResult)
            }
        }

        // Calculate totals
        val totalFiles = results.sumOf { it.fileCount }
        val totalSize = results.sumOf { it.totalSize }
        val excludedFiles = results.sumOf { it.excludedCount }
        val excludedSize = results.sumOf { it.excludedSize }

        listener.onScanComplete(totalFiles, 0, totalSize, excludedFiles, excludedSize)

        results
    }

    /**
     * Scan MediaStore category (Images, Videos, Audio, Documents)
     */
    private suspend fun scanMediaStoreCategory(
        context: Context,
        category: BackupCategory,
        mode: BackupMode,
        listener: BackupScanListener
    ): CategoryScanResult = withContext(Dispatchers.IO) {
        var fileCount = 0
        var totalSize = 0L
        var excludedCount = 0
        var excludedSize = 0L
        val excludedFilesList = mutableListOf<Pair<String, String>>()

        val uri = category.contentUri ?: return@withContext CategoryScanResult(
            category = category,
            fileCount = 0,
            totalSize = 0,
            excludedCount = 0,
            excludedSize = 0,
            isEnabled = true,
            hasPermission = true
        )

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )

        // Build selection based on category
        val selection: String?
        val selectionArgs: Array<String>?

        when (category) {
            BackupCategory.DOCUMENTS -> {
                // Filter by document MIME types
                val mimeTypes = listOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain",
                    "text/csv"
                )
                val placeholders = mimeTypes.joinToString(",") { "?" }
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IN ($placeholders)"
                selectionArgs = mimeTypes.toTypedArray()
            }
            else -> {
                selection = null
                selectionArgs = null
            }
        }

        try {
            context.contentResolver.query(
                uri, projection, selection, selectionArgs, null
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(dataColumn) ?: continue
                        val name = cursor.getString(nameColumn) ?: continue
                        val size = cursor.getLong(sizeColumn)

                        // Check storage filter
                        if (mode == BackupMode.INTERNAL_ONLY) {
                            if (!isInternalStorage(path)) continue
                        }

                        // Check exclusions
                        if (ExclusionRules.shouldExclude(path, name)) {
                            excludedCount++
                            excludedSize += size
                            if (excludedFilesList.size < 200) {
                                excludedFilesList.add(name to ExclusionRules.getExclusionReason(path, name))
                            }
                            continue
                        }

                        fileCount++
                        totalSize += size

                        if (fileCount % 100 == 0) {
                            listener.onCategoryProgress(category, fileCount, name)
                        }
                    } catch (e: Exception) {
                        // Skip problematic entries
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning ${category.name}: ${e.message}")
        }

        CategoryScanResult(
            category = category,
            fileCount = fileCount,
            totalSize = totalSize,
            excludedCount = excludedCount,
            excludedSize = excludedSize,
            isEnabled = true,
            hasPermission = true,
            excludedFiles = excludedFilesList
        )
    }

    /**
     * Scan folder-based category (Downloads)
     */
    private suspend fun scanFolderCategory(
        context: Context,
        category: BackupCategory,
        mode: BackupMode,
        listener: BackupScanListener
    ): CategoryScanResult = withContext(Dispatchers.IO) {
        var fileCount = 0
        var totalSize = 0L
        var excludedCount = 0
        var excludedSize = 0L
        val excludedFilesList = mutableListOf<Pair<String, String>>()

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        if (downloadDir.exists() && downloadDir.canRead()) {
            fun scanDir(dir: File) {
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        if (!ExclusionRules.shouldExcludeFolder(file.name)) {
                            scanDir(file)
                        }
                    } else {
                        if (ExclusionRules.shouldExclude(file.absolutePath, file.name)) {
                            excludedCount++
                            excludedSize += file.length()
                            if (excludedFilesList.size < 200) {
                                excludedFilesList.add(file.name to ExclusionRules.getExclusionReason(file.absolutePath, file.name))
                            }
                        } else {
                            fileCount++
                            totalSize += file.length()
                        }
                    }
                }
            }
            scanDir(downloadDir)
        }

        CategoryScanResult(
            category = category,
            fileCount = fileCount,
            totalSize = totalSize,
            excludedCount = excludedCount,
            excludedSize = excludedSize,
            isEnabled = true,
            hasPermission = true,
            excludedFiles = excludedFilesList
        )
    }

    /**
     * Scan ContentProvider category (Contacts)
     */
    private suspend fun scanContentProviderCategory(
        context: Context,
        category: BackupCategory,
        listener: BackupScanListener
    ): CategoryScanResult = withContext(Dispatchers.IO) {
        val count = when (category) {
            BackupCategory.CONTACTS -> ContactsBackup.getContactCount(context)
            else -> 0
        }

        CategoryScanResult(
            category = category,
            fileCount = count,
            totalSize = 0, // Size not applicable for these
            excludedCount = 0,
            excludedSize = 0,
            isEnabled = true,
            hasPermission = true
        )
    }

    /**
     * Start category-based backup
     * Scans selected categories and adds files to SelectionManager
     */
    suspend fun startCategoryBackup(
        context: Context,
        categories: List<BackupCategory>,
        mode: BackupMode,
        scope: CoroutineScope,
        listener: BackupScanListener
    ) = withContext(Dispatchers.IO) {
        listener.onScanStarted()

        try {
            // Clear any existing selection
            SelectionManager.deselectAll()
            SelectionManager.setBackupMode(true)

            var totalFiles = 0
            var totalDirs = 0
            var totalSize = 0L
            var excludedFiles = 0
            var excludedSize = 0L

            for (category in categories) {
                if (!scope.isActive) break

                listener.onCategoryStarted(category)

                // Check permission
                val hasPermission = category.requiredPermission == null ||
                        ContextCompat.checkSelfPermission(context, category.requiredPermission) ==
                        PackageManager.PERMISSION_GRANTED

                if (!hasPermission) continue

                when (category.sourceType) {
                    BackupCategory.SourceType.MEDIA_STORE -> {
                        val result = addMediaStoreFilesToSelection(context, category, mode, scope, listener)
                        totalFiles += result.first
                        totalSize += result.second
                        excludedFiles += result.third
                    }
                    BackupCategory.SourceType.FOLDER -> {
                        val result = addFolderFilesToSelection(context, category, mode, scope, listener)
                        totalFiles += result.first
                        totalSize += result.second
                        excludedFiles += result.third
                    }
                    BackupCategory.SourceType.CONTENT_PROVIDER -> {
                        val result = exportContentProviderToFile(context, category, scope, listener)
                        totalFiles += result.first
                        totalSize += result.second
                    }
                }
            }

            listener.onScanComplete(totalFiles, totalDirs, totalSize, excludedFiles, excludedSize)
        } catch (e: Exception) {
            listener.onScanError("Backup scan failed: ${e.message}")
        }
    }

    /**
     * Add MediaStore files to SelectionManager
     */
    private suspend fun addMediaStoreFilesToSelection(
        context: Context,
        category: BackupCategory,
        mode: BackupMode,
        scope: CoroutineScope,
        listener: BackupScanListener
    ): Triple<Int, Long, Int> = withContext(Dispatchers.IO) {
        var fileCount = 0
        var totalSize = 0L
        var excludedCount = 0
        val filesToAdd = mutableListOf<FileItem>()

        val uri = category.contentUri ?: return@withContext Triple(0, 0L, 0)

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        // Build selection for documents
        val selection: String?
        val selectionArgs: Array<String>?
        if (category == BackupCategory.DOCUMENTS) {
            val mimeTypes = listOf(
                "application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain", "text/csv"
            )
            val placeholders = mimeTypes.joinToString(",") { "?" }
            selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IN ($placeholders)"
            selectionArgs = mimeTypes.toTypedArray()
        } else {
            selection = null
            selectionArgs = null
        }

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext() && scope.isActive) {
                    try {
                        val path = cursor.getString(dataColumn) ?: continue
                        val name = cursor.getString(nameColumn) ?: continue
                        val size = cursor.getLong(sizeColumn)
                        val file = File(path)

                        if (!file.exists() || !file.canRead()) continue

                        // Check storage filter
                        if (mode == BackupMode.INTERNAL_ONLY && !isInternalStorage(path)) continue

                        // Check exclusions
                        if (ExclusionRules.shouldExclude(path, name)) {
                            excludedCount++
                            continue
                        }

                        val storagePrefix = if (isInternalStorage(path)) "Internal Storage" else "External Storage"
                        filesToAdd.add(FileItem.fromFile(file).copy(backupPrefix = storagePrefix))
                        fileCount++
                        totalSize += size

                        // Batch add to SelectionManager
                        if (filesToAdd.size >= 500) {
                            SelectionManager.addBackupItems(
                                filesToAdd.toList(),
                                emptyList(),
                                getInternalStorageRoot().absolutePath,
                                "Internal Storage"
                            )
                            filesToAdd.clear()
                            listener.onCategoryProgress(category, fileCount, name)
                        }
                    } catch (e: Exception) {
                        // Skip problematic files
                    }
                }
            }

            // Add remaining files
            if (filesToAdd.isNotEmpty()) {
                SelectionManager.addBackupItems(
                    filesToAdd,
                    emptyList(),
                    getInternalStorageRoot().absolutePath,
                    "Internal Storage"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding ${category.name} to selection: ${e.message}")
        }

        Triple(fileCount, totalSize, excludedCount)
    }

    /**
     * Add folder files to SelectionManager
     */
    private suspend fun addFolderFilesToSelection(
        context: Context,
        category: BackupCategory,
        mode: BackupMode,
        scope: CoroutineScope,
        listener: BackupScanListener
    ): Triple<Int, Long, Int> = withContext(Dispatchers.IO) {
        var fileCount = 0
        var totalSize = 0L
        var excludedCount = 0
        val filesToAdd = mutableListOf<FileItem>()

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        fun scanDir(dir: File) {
            if (!scope.isActive) return

            dir.listFiles()?.forEach { file ->
                if (!scope.isActive) return

                if (file.isDirectory) {
                    if (!ExclusionRules.shouldExcludeFolder(file.name)) {
                        scanDir(file)
                    }
                } else {
                    if (ExclusionRules.shouldExclude(file.absolutePath, file.name)) {
                        excludedCount++
                    } else if (file.canRead()) {
                        filesToAdd.add(FileItem.fromFile(file).copy(backupPrefix = "Internal Storage"))
                        fileCount++
                        totalSize += file.length()
                    }
                }
            }
        }

        if (downloadDir.exists() && downloadDir.canRead()) {
            scanDir(downloadDir)
        }

        // Add to SelectionManager
        if (filesToAdd.isNotEmpty()) {
            SelectionManager.addBackupItems(
                filesToAdd,
                emptyList(),
                getInternalStorageRoot().absolutePath,
                "Internal Storage"
            )
        }

        Triple(fileCount, totalSize, excludedCount)
    }

    /**
     * Export content provider data (Contacts, Messages, Call Logs) to files
     * and add to SelectionManager
     */
    private suspend fun exportContentProviderToFile(
        context: Context,
        category: BackupCategory,
        scope: CoroutineScope,
        listener: BackupScanListener
    ): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val backupDir = File(context.cacheDir, "backup_exports")
        backupDir.mkdirs()

        when (category) {
            BackupCategory.CONTACTS -> {
                val result = ContactsBackup.exportToVcf(context, backupDir) { current, total ->
                    listener.onCategoryProgress(category, current, "Contact $current of $total")
                }
                if (result.success && result.filePath != null) {
                    val file = File(result.filePath)
                    val fileItem = FileItem.fromFile(file).copy(backupPrefix = "Backup")
                    SelectionManager.addBackupItems(
                        listOf(fileItem),
                        listOf("Backup"),
                        backupDir.absolutePath,
                        "Backup"
                    )
                    listener.onCategoryComplete(
                        CategoryScanResult(
                            category = category,
                            fileCount = result.contactCount,
                            totalSize = result.fileSize,
                            excludedCount = 0,
                            excludedSize = 0,
                            isEnabled = true,
                            hasPermission = true
                        )
                    )
                    Pair(1, result.fileSize)
                } else {
                    Log.e(TAG, "Contacts export failed: ${result.errorMessage}")
                    Pair(0, 0L)
                }
            }
            else -> Pair(0, 0L)
        }
    }

    // ==================== Legacy Full Backup Methods ====================

    /**
     * Quickly estimate storage size (uses approximation for speed)
     */
    suspend fun estimateStorageSize(
        context: Context,
        includeExternal: Boolean
    ): Pair<StorageSizeInfo, StorageSizeInfo?> = withContext(Dispatchers.IO) {
        val internalInfo = estimateDirectorySize(getInternalStorageRoot())

        val externalInfo = if (includeExternal) {
            val externalRoot = getExternalStorageRoot(context)
            if (externalRoot != null) {
                estimateDirectorySize(externalRoot)
            } else null
        } else null

        Pair(internalInfo, externalInfo)
    }

    /**
     * Estimate directory size (quick scan)
     */
    private fun estimateDirectorySize(root: File): StorageSizeInfo {
        var fileCount = 0
        var dirCount = 0
        var totalSize = 0L
        var excludedCount = 0
        var excludedSize = 0L

        fun scanDir(dir: File, depth: Int) {
            if (depth > 10) return

            try {
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory) {
                        dirCount++
                        if (file.canRead()) {
                            scanDir(file, depth + 1)
                        }
                    } else {
                        fileCount++
                        totalSize += file.length()
                    }
                }
            } catch (e: Exception) {
                // Skip inaccessible directories
            }
        }

        if (root.exists() && root.canRead()) {
            scanDir(root, 0)
        }

        return StorageSizeInfo(fileCount, dirCount, totalSize, excludedCount, excludedSize)
    }

    /**
     * Start full backup - scans storage and prepares SelectionManager
     */
    suspend fun startFullBackup(
        context: Context,
        mode: BackupMode,
        scope: CoroutineScope,
        listener: BackupScanListener
    ) = withContext(Dispatchers.IO) {
        listener.onScanStarted()

        try {
            SelectionManager.deselectAll()
            SelectionManager.setBackupMode(true)

            var totalFiles = 0
            var totalDirs = 0
            var totalSize = 0L
            var excludedFiles = 0
            var excludedSize = 0L

            // Scan internal storage
            val internalRoot = getInternalStorageRoot()
            if (internalRoot.exists() && internalRoot.canRead()) {
                val result = scanStorageForBackup(
                    rootDir = internalRoot,
                    storagePrefix = "Internal Storage",
                    scope = scope,
                    listener = listener,
                    currentFileCount = 0,
                    currentDirCount = 0
                )
                totalFiles += result.first
                totalDirs += result.second
                totalSize += result.third
            }

            // Scan external storage if requested
            if (mode == BackupMode.INTERNAL_AND_EXTERNAL) {
                val externalRoot = getExternalStorageRoot(context)
                if (externalRoot != null && externalRoot.exists() && externalRoot.canRead()) {
                    val result = scanStorageForBackup(
                        rootDir = externalRoot,
                        storagePrefix = "External Storage",
                        scope = scope,
                        listener = listener,
                        currentFileCount = totalFiles,
                        currentDirCount = totalDirs
                    )
                    totalFiles += result.first
                    totalDirs += result.second
                    totalSize += result.third
                }
            }

            listener.onScanComplete(totalFiles, totalDirs, totalSize, excludedFiles, excludedSize)
        } catch (e: Exception) {
            listener.onScanError("Backup scan failed: ${e.message}")
        }
    }

    /**
     * Scan a storage directory and add files to SelectionManager
     */
    private suspend fun scanStorageForBackup(
        rootDir: File,
        storagePrefix: String,
        scope: CoroutineScope,
        listener: BackupScanListener,
        currentFileCount: Int,
        currentDirCount: Int
    ): Triple<Int, Int, Long> = withContext(Dispatchers.IO) {
        var fileCount = 0
        var dirCount = 0
        var totalSize = 0L
        val filesToAdd = mutableListOf<FileItem>()
        val dirsToAdd = mutableListOf<String>()

        fun scanDirectory(dir: File, relativePath: String) {
            if (!scope.isActive) return

            try {
                val files = dir.listFiles() ?: return

                if (relativePath.isNotEmpty()) {
                    val fullRelativePath = "$storagePrefix/$relativePath"
                    dirsToAdd.add(fullRelativePath)
                    dirCount++
                }

                for (file in files) {
                    if (!scope.isActive) return

                    val itemRelativePath = if (relativePath.isEmpty()) {
                        file.name
                    } else {
                        "$relativePath/${file.name}"
                    }

                    if (file.isDirectory) {
                        if (file.canRead()) {
                            scanDirectory(file, itemRelativePath)
                        }
                    } else {
                        val fileItem = FileItem.fromFile(file).copy(backupPrefix = storagePrefix)
                        filesToAdd.add(fileItem)
                        fileCount++
                        totalSize += file.length()

                        if (fileCount % 100 == 0) {
                            listener.onScanProgress(
                                currentFileCount + fileCount,
                                currentDirCount + dirCount,
                                file.absolutePath
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip inaccessible directories
            }
        }

        dirsToAdd.add(storagePrefix)
        scanDirectory(rootDir, "")

        if (scope.isActive) {
            SelectionManager.addBackupItems(filesToAdd, dirsToAdd, rootDir.absolutePath, storagePrefix)
        }

        Triple(fileCount, dirCount, totalSize)
    }

    // ==================== Helper Methods ====================

    fun getInternalStorageRoot(): File {
        return Environment.getExternalStorageDirectory()
    }

    fun getExternalStorageRoot(context: Context): File? {
        val volumes = StorageUtils.getStorageVolumes(context)
        return volumes.firstOrNull { it.isRemovable && !it.isPrimary }?.path
    }

    fun hasExternalStorage(context: Context): Boolean {
        return getExternalStorageRoot(context) != null
    }

    private fun isInternalStorage(path: String): Boolean {
        val internalPath = getInternalStorageRoot().absolutePath
        return path.startsWith(internalPath)
    }

    fun cancelBackup() {
        SelectionManager.cancelSelection()
        SelectionManager.setBackupMode(false)
    }
}
