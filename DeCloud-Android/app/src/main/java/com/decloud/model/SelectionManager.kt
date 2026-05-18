package com.decloud.model

import kotlinx.coroutines.*
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext

/**
 * Singleton to manage file selection across the app
 * Stores selected file paths and provides utility methods
 */
object SelectionManager {

    // Set of selected file paths (not directories - only actual files)
    // Using synchronized set for thread safety during async operations
    private val selectedFiles: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // Set of selected directory paths (for UI state)
    private val selectedDirectories: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // Set of ALL directories found during selection (including empty ones)
    // This ensures folder structure is preserved exactly during transfer
    private val allDirectories: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // Track top-level user selections (what user explicitly selected, not recursive contents)
    // These are used for display purposes to show "2 folders, 3 files" instead of expanded counts
    private val topLevelSelectedFiles: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val topLevelSelectedFolders: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // The directory user was browsing when they made selections
    // This is the trim point for file paths when transferring
    private var browsingDirectory: String = ""

    // Backup mode flag - when true, files will be organized under storage prefixes
    private var backupMode: Boolean = false

    // Maps file paths to their backup prefixes (e.g., "Internal Storage" or "External Storage")
    private val backupPrefixMap = mutableMapOf<String, String>()

    // Maps storage prefix to root path (e.g., "Internal Storage" -> "/storage/emulated/0")
    private val storageRootMap = mutableMapOf<String, String>()

    // Listeners for selection changes (thread-safe to prevent ConcurrentModificationException)
    private val listeners = CopyOnWriteArrayList<SelectionListener>()

    // Track ongoing selection job
    private var selectionJob: Job? = null

    // Progress listener for async operations
    private var progressListener: SelectionProgressListener? = null

    // Snapshot of selection state before async operation (for cancel/restore)
    @Volatile
    private var selectionSnapshot: SelectionSnapshot? = null

    // Volatile flag to immediately stop IO thread from adding files during cancel
    @Volatile
    private var selectionCancelled = false

    // When true, selection is locked — toggleSelection, selectFile, deselectFile are no-ops
    @Volatile
    private var locked = false

    // Dirty flag — set true whenever selection changes, cleared by markClean()
    // Used by ReadyToSendActivity to skip expensive loadSelectedFiles() on resume
    @Volatile
    private var dirty = true

    /**
     * Lock selection — prevents any modifications (for transfer in progress)
     */
    fun lock() { locked = true }

    /**
     * Unlock selection — allows modifications again
     */
    fun unlock() { locked = false }

    /**
     * Check if selection is locked
     */
    fun isLocked(): Boolean = locked

    /**
     * Check if selection has changed since last markClean() call
     */
    fun isDirty(): Boolean = dirty

    /**
     * Mark selection as clean (called after UI has processed the current state)
     */
    fun markClean() { dirty = false }

    private data class SelectionSnapshot(
        val files: Set<String>,
        val directories: Set<String>,
        val allDirs: Set<String>,
        val topLevelFiles: Set<String>,
        val topLevelFolders: Set<String>
    )

    interface SelectionListener {
        fun onSelectionChanged(count: Int, totalSize: Long)
    }

    interface SelectionProgressListener {
        fun onSelectionStarted()
        fun onSelectionProgress(scannedFiles: Int)
        fun onSelectionComplete(totalFiles: Int)
    }

    fun addListener(listener: SelectionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SelectionListener) {
        listeners.remove(listener)
    }

    fun setProgressListener(listener: SelectionProgressListener?) {
        progressListener = listener
    }

    private fun notifyListeners() {
        dirty = true
        val count = selectedFiles.size
        val totalSize = getTotalSize()
        listeners.forEach { it.onSelectionChanged(count, totalSize) }
    }

    /**
     * Toggle selection for a file or folder
     * For directories, this runs async to avoid blocking UI
     */
    fun toggleSelection(fileItem: FileItem, scope: CoroutineScope? = null) {
        if (locked) return
        if (fileItem.isDirectory) {
            toggleDirectorySelectionAsync(fileItem, scope)
        } else {
            toggleFileSelection(fileItem)
            notifyListeners()
        }
    }

    private fun toggleFileSelection(fileItem: FileItem) {
        if (selectedFiles.contains(fileItem.path)) {
            selectedFiles.remove(fileItem.path)
            topLevelSelectedFiles.remove(fileItem.path)
        } else {
            // Only add if file is accessible
            if (isAccessible(fileItem.file)) {
                selectedFiles.add(fileItem.path)
                topLevelSelectedFiles.add(fileItem.path)
            }
        }
    }

    private fun toggleDirectorySelectionAsync(fileItem: FileItem, scope: CoroutineScope?) {
        val dirPath = fileItem.path

        // Cancel any ongoing selection
        selectionCancelled = true
        selectionJob?.cancel()
        selectionCancelled = false

        if (selectedDirectories.contains(dirPath)) {
            // Deselect - this is fast, do it sync
            // Note: deselectAllInDirectory also removes from selectedDirectories/topLevelSelectedFolders
            // and calls notifyListeners(), so no need to do it again here
            deselectAllInDirectory(fileItem.file)
        } else {
            // Select - this can be slow, do it async
            if (!isAccessible(fileItem.file)) return

            val actualScope = scope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())

            // Save snapshot before starting async operation (thread-safe)
            synchronized(selectedFiles) {
                synchronized(selectedDirectories) {
                    synchronized(allDirectories) {
                        selectionSnapshot = SelectionSnapshot(
                            files = selectedFiles.toSet(),
                            directories = selectedDirectories.toSet(),
                            allDirs = allDirectories.toSet(),
                            topLevelFiles = topLevelSelectedFiles.toSet(),
                            topLevelFolders = topLevelSelectedFolders.toSet()
                        )
                    }
                }
            }

            selectionJob = actualScope.launch {
                progressListener?.onSelectionStarted()

                var scannedCount = 0

                withContext(Dispatchers.IO) {
                    selectedDirectories.add(dirPath)
                    topLevelSelectedFolders.add(dirPath)  // Track as top-level user selection
                    scannedCount = selectAllInDirectoryAsync(fileItem.file) { count ->
                        // Update progress every 100 files
                        if (count % 100 == 0) {
                            launch(Dispatchers.Main) {
                                progressListener?.onSelectionProgress(count)
                            }
                        }
                    }
                }

                // Clear snapshot on successful completion
                selectionSnapshot = null
                progressListener?.onSelectionComplete(scannedCount)
                notifyListeners()
            }
        }
    }

    /**
     * Select a single file (only if accessible)
     */
    fun selectFile(path: String) {
        if (locked) return
        val file = File(path)
        if (isAccessible(file)) {
            selectedFiles.add(path)
            topLevelSelectedFiles.add(path)  // Track as top-level selection
            notifyListeners()
        }
    }

    /**
     * Deselect a single file
     */
    fun deselectFile(path: String) {
        if (locked) return
        selectedFiles.remove(path)
        topLevelSelectedFiles.remove(path)
        notifyListeners()
    }

    /**
     * Deselect a single file WITHOUT notifying listeners.
     * Use for batch operations where you'll call notifyListeners() once at the end.
     */
    fun deselectFileSilent(path: String) {
        if (locked) return
        selectedFiles.remove(path)
        topLevelSelectedFiles.remove(path)
    }

    /**
     * Manually trigger listener notification.
     * Call after a batch of deselectFileSilent() calls.
     */
    fun notifySelectionChanged() {
        dirty = true
        notifyListeners()
    }

    /**
     * Check if a file or folder is selected
     */
    fun isSelected(fileItem: FileItem): Boolean {
        return if (fileItem.isDirectory) {
            selectedDirectories.contains(fileItem.path)
        } else {
            selectedFiles.contains(fileItem.path)
        }
    }

    /**
     * Check if a file/directory is accessible (can be read)
     * Public so it can be used by other components
     */
    fun isAccessible(file: File): Boolean {
        return try {
            file.exists() && file.canRead()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Select all files in a directory recursively (async version)
     * Returns count of files selected
     * Also tracks ALL directories (including empty ones) for exact structure preservation
     */
    private suspend fun selectAllInDirectoryAsync(
        directory: File,
        onProgress: (Int) -> Unit
    ): Int {
        if (!directory.isDirectory) return 0
        if (!isAccessible(directory)) return 0

        // Track this directory (even if empty) for structure preservation
        allDirectories.add(directory.absolutePath)

        var count = 0

        try {
            val files = directory.listFiles() ?: return 0

            for (file in files) {
                // Check if job is cancelled or cancel flag is set
                if (!coroutineContext.isActive || selectionCancelled) break

                // Skip inaccessible files/directories
                if (!isAccessible(file)) continue

                if (file.isDirectory) {
                    if (selectionCancelled) break
                    selectedDirectories.add(file.absolutePath)
                    // allDirectories is added inside the recursive call
                    count += selectAllInDirectoryAsync(file, onProgress)
                } else {
                    if (selectionCancelled) break
                    selectedFiles.add(file.absolutePath)
                    count++
                    onProgress(count)
                }

                // Yield periodically to keep coroutine responsive
                if (count % 50 == 0) {
                    yield()
                }
            }
        } catch (e: Exception) {
            // Skip this directory on any error
        }

        return count
    }

    /**
     * Select all files in a directory recursively (sync version for backward compat)
     * Only selects accessible files - skips inaccessible ones
     * Also tracks ALL directories for structure preservation
     */
    fun selectAllInDirectory(directory: File) {
        if (!directory.isDirectory) return

        // Skip if directory is not accessible
        if (!isAccessible(directory)) return

        // Track this directory (even if empty) for structure preservation
        allDirectories.add(directory.absolutePath)

        try {
            directory.listFiles()?.forEach { file ->
                // Skip inaccessible files/directories
                if (!isAccessible(file)) return@forEach

                if (file.isDirectory) {
                    selectedDirectories.add(file.absolutePath)
                    selectAllInDirectory(file)  // Recursive - will skip inaccessible subdirs
                } else {
                    selectedFiles.add(file.absolutePath)
                }
            }
        } catch (e: Exception) {
            // Skip this directory on any error
        }
    }

    /**
     * Deselect all files in a directory recursively
     */
    fun deselectAllInDirectory(directory: File) {
        if (locked) return
        if (!directory.isDirectory) return

        // Remove the directory itself first
        selectedDirectories.remove(directory.absolutePath)
        allDirectories.remove(directory.absolutePath)
        topLevelSelectedFolders.remove(directory.absolutePath)

        // Also remove any files/dirs that start with this path (fast removal)
        // Synchronized iteration for thread safety
        val dirPrefix = directory.absolutePath + File.separator
        synchronized(selectedFiles) {
            selectedFiles.removeAll { it.startsWith(dirPrefix) }
        }
        synchronized(selectedDirectories) {
            selectedDirectories.removeAll { it.startsWith(dirPrefix) }
        }
        synchronized(allDirectories) {
            allDirectories.removeAll { it.startsWith(dirPrefix) }
        }
        synchronized(topLevelSelectedFiles) {
            topLevelSelectedFiles.removeAll { it.startsWith(dirPrefix) }
        }
        synchronized(topLevelSelectedFolders) {
            topLevelSelectedFolders.removeAll { it.startsWith(dirPrefix) }
        }
        notifyListeners()
    }

    /**
     * Deselect only files/folders that are direct children of the given directory
     * (Not recursive - only immediate children)
     */
    fun deselectDirectChildrenOf(directory: File) {
        if (locked) return
        if (!directory.isDirectory) return

        val dirPath = directory.absolutePath

        // Remove direct child files (files whose parent is this directory)
        synchronized(selectedFiles) {
            selectedFiles.removeAll { path ->
                val parent = File(path).parent
                parent == dirPath
            }
        }
        synchronized(topLevelSelectedFiles) {
            topLevelSelectedFiles.removeAll { path ->
                val parent = File(path).parent
                parent == dirPath
            }
        }

        // Remove direct child directories
        val directChildDirs: List<String>
        synchronized(selectedDirectories) {
            directChildDirs = selectedDirectories.filter { path ->
                val parent = File(path).parent
                parent == dirPath
            }
        }

        for (childDir in directChildDirs) {
            // Also remove all files under this child directory
            val childPrefix = childDir + File.separator
            synchronized(selectedFiles) {
                selectedFiles.removeAll { it.startsWith(childPrefix) }
            }
            synchronized(selectedDirectories) {
                selectedDirectories.removeAll { it.startsWith(childPrefix) || it == childDir }
            }
            synchronized(allDirectories) {
                allDirectories.removeAll { it.startsWith(childPrefix) || it == childDir }
            }
            synchronized(topLevelSelectedFiles) {
                topLevelSelectedFiles.removeAll { it.startsWith(childPrefix) }
            }
            synchronized(topLevelSelectedFolders) {
                topLevelSelectedFolders.removeAll { it.startsWith(childPrefix) || it == childDir }
            }
        }

        notifyListeners()
    }

    /**
     * Check if there are any selections in the given directory (including nested)
     */
    fun hasSelectionInDirectory(directory: File): Boolean {
        val dirPath = directory.absolutePath
        val dirPrefix = dirPath + File.separator

        // Check for any files under this directory (including nested)
        val hasFiles: Boolean
        synchronized(selectedFiles) {
            hasFiles = selectedFiles.any { path ->
                path.startsWith(dirPrefix)
            }
        }

        // Check for any directories under this directory (including nested)
        val hasDirs: Boolean
        synchronized(selectedDirectories) {
            hasDirs = selectedDirectories.any { path ->
                path.startsWith(dirPrefix)
            }
        }

        return hasFiles || hasDirs
    }

    /**
     * Get count of selections in the given directory (direct children only)
     */
    fun getSelectionCountInDirectory(directory: File): Int {
        val dirPath = directory.absolutePath

        val fileCount: Int
        synchronized(selectedFiles) {
            fileCount = selectedFiles.count { path ->
                File(path).parent == dirPath
            }
        }

        val dirCount: Int
        synchronized(selectedDirectories) {
            dirCount = selectedDirectories.count { path ->
                File(path).parent == dirPath
            }
        }

        return fileCount + dirCount
    }

    /**
     * Select all items in current folder (async)
     * Only selects accessible files - skips inaccessible ones
     */
    fun selectAllInList(items: List<FileItem>, scope: CoroutineScope? = null) {
        if (locked) return
        // Cancel any ongoing selection
        selectionCancelled = true
        selectionJob?.cancel()
        selectionCancelled = false

        val actualScope = scope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())

        // Save snapshot before starting async operation
        synchronized(selectedFiles) {
            synchronized(selectedDirectories) {
                synchronized(allDirectories) {
                    selectionSnapshot = SelectionSnapshot(
                        files = selectedFiles.toSet(),
                        directories = selectedDirectories.toSet(),
                        allDirs = allDirectories.toSet(),
                        topLevelFiles = topLevelSelectedFiles.toSet(),
                        topLevelFolders = topLevelSelectedFolders.toSet()
                    )
                }
            }
        }

        selectionJob = actualScope.launch {
            progressListener?.onSelectionStarted()

            // Use array to allow modification from inner lambda
            val cumulativeCount = intArrayOf(0)

            withContext(Dispatchers.IO) {
                for (item in items) {
                    if (!isActive || selectionCancelled) break

                    // Skip inaccessible items
                    if (!isAccessible(item.file)) continue

                    if (item.isDirectory) {
                        if (selectionCancelled) break
                        selectedDirectories.add(item.path)
                        topLevelSelectedFolders.add(item.path)  // Track as top-level user selection
                        selectAllInDirectoryAsync(item.file) { _ ->
                            cumulativeCount[0]++
                            if (cumulativeCount[0] % 100 == 0) {
                                launch(Dispatchers.Main) {
                                    progressListener?.onSelectionProgress(cumulativeCount[0])
                                }
                            }
                        }
                    } else {
                        if (selectionCancelled) break
                        selectedFiles.add(item.path)
                        topLevelSelectedFiles.add(item.path)  // Track as top-level user selection
                        cumulativeCount[0]++
                    }
                }
            }

            // Clear snapshot on successful completion
            selectionSnapshot = null
            progressListener?.onSelectionComplete(cumulativeCount[0])
            notifyListeners()
        }
    }

    /**
     * Cancel ongoing selection operation and restore previous state.
     * Uses a volatile flag to immediately stop the IO thread from adding
     * more files, then atomically restores the snapshot.
     */
    fun cancelSelection() {
        // 1. Set volatile flag FIRST — IO thread checks this on every file add
        selectionCancelled = true

        // 2. Cancel the coroutine job
        selectionJob?.cancel()
        selectionJob = null

        // 3. Restore previous selection state if snapshot exists
        //    Lock ALL collections at once to prevent any interleaved adds
        selectionSnapshot?.let { snapshot ->
            synchronized(selectedFiles) {
                synchronized(selectedDirectories) {
                    synchronized(allDirectories) {
                        synchronized(topLevelSelectedFiles) {
                            synchronized(topLevelSelectedFolders) {
                                selectedFiles.clear()
                                selectedFiles.addAll(snapshot.files)
                                selectedDirectories.clear()
                                selectedDirectories.addAll(snapshot.directories)
                                allDirectories.clear()
                                allDirectories.addAll(snapshot.allDirs)
                                topLevelSelectedFiles.clear()
                                topLevelSelectedFiles.addAll(snapshot.topLevelFiles)
                                topLevelSelectedFolders.clear()
                                topLevelSelectedFolders.addAll(snapshot.topLevelFolders)
                            }
                        }
                    }
                }
            }
            selectionSnapshot = null
            notifyListeners()
        }

        // 4. Reset flag so future selections work
        selectionCancelled = false
    }

    /**
     * Check if selection is in progress
     */
    fun isSelectionInProgress(): Boolean {
        return selectionJob?.isActive == true
    }

    /**
     * Deselect all items
     */
    fun deselectAll() {
        locked = false  // Force unlock so clear operations work
        cancelSelection()
        synchronized(selectedFiles) {
            selectedFiles.clear()
        }
        synchronized(selectedDirectories) {
            selectedDirectories.clear()
        }
        synchronized(allDirectories) {
            allDirectories.clear()
        }
        synchronized(topLevelSelectedFiles) {
            topLevelSelectedFiles.clear()
        }
        synchronized(topLevelSelectedFolders) {
            topLevelSelectedFolders.clear()
        }
        browsingDirectory = ""
        // Clear backup mode data
        backupMode = false
        backupPrefixMap.clear()
        storageRootMap.clear()
        notifyListeners()
    }

    /**
     * Get count of selected files (includes top-level empty folders)
     */
    fun getSelectedCount(): Int = selectedFiles.size + getEmptySelectedFolderCount()

    /**
     * Get total size of selected files
     */
    fun getTotalSize(): Long {
        synchronized(selectedFiles) {
            return selectedFiles.sumOf { path ->
                try {
                    File(path).length()
                } catch (e: Exception) {
                    0L
                }
            }
        }
    }

    /**
     * Get formatted total size string
     */
    fun getFormattedTotalSize(): String {
        return FileItem.formatFileSize(getTotalSize())
    }

    /**
     * Get list of all selected file paths
     */
    fun getSelectedFilePaths(): List<String> {
        synchronized(selectedFiles) {
            return selectedFiles.toList()
        }
    }

    /**
     * Get list of all selected files as File objects
     * Only returns files that are still accessible
     */
    fun getSelectedFiles(): List<File> {
        synchronized(selectedFiles) {
            return selectedFiles
                .map { File(it) }
                .filter { isAccessible(it) }
        }
    }

    /**
     * Remove inaccessible files from selection
     * Call this before transfer to clean up any files that became inaccessible
     */
    fun removeInaccessibleFiles(): Int {
        val inaccessible: List<String>
        synchronized(selectedFiles) {
            inaccessible = selectedFiles.filter { !isAccessible(File(it)) }
            selectedFiles.removeAll(inaccessible.toSet())
        }
        synchronized(topLevelSelectedFiles) {
            topLevelSelectedFiles.removeAll(inaccessible.toSet())
        }

        val inaccessibleDirs: List<String>
        synchronized(selectedDirectories) {
            inaccessibleDirs = selectedDirectories.filter { !isAccessible(File(it)) }
            selectedDirectories.removeAll(inaccessibleDirs.toSet())
        }
        synchronized(topLevelSelectedFolders) {
            topLevelSelectedFolders.removeAll(inaccessibleDirs.toSet())
        }

        if (inaccessible.isNotEmpty() || inaccessibleDirs.isNotEmpty()) {
            notifyListeners()
        }
        return inaccessible.size + inaccessibleDirs.size
    }

    /**
     * Get list of all selected directory paths
     */
    fun getSelectedDirectoryPaths(): List<String> {
        synchronized(selectedDirectories) {
            return selectedDirectories.toList()
        }
    }

    /**
     * Get list of ALL directories (including empty ones) for structure preservation
     * This ensures exact folder structure is replicated during transfer
     */
    fun getAllDirectoryPaths(): List<String> {
        synchronized(allDirectories) {
            return allDirectories.toList()
        }
    }

    /**
     * Check if any files or folders are selected
     */
    fun hasSelection(): Boolean = selectedFiles.isNotEmpty() || topLevelSelectedFolders.isNotEmpty()

    /**
     * Get count of selected folders (top-level user-selected directories only)
     */
    fun getSelectedFolderCount(): Int = topLevelSelectedFolders.size

    /**
     * Get count of top-level selected folders that contain no selected files
     * (empty folders that should still count toward selection)
     */
    private fun getEmptySelectedFolderCount(): Int {
        synchronized(selectedFiles) {
            return topLevelSelectedFolders.count { dirPath ->
                val dirPrefix = dirPath + File.separator
                selectedFiles.none { it.startsWith(dirPrefix) }
            }
        }
    }

    /**
     * Get selection summary string (detailed format: X Folders, Y Files, Z Size)
     * Shows only top-level user selections, not recursive contents
     */
    fun getSelectionSummary(): String {
        val fileCount = topLevelSelectedFiles.size
        val folderCount = topLevelSelectedFolders.size
        val size = getFormattedTotalSize()

        val parts = mutableListOf<String>()
        if (folderCount > 0) {
            parts.add("$folderCount folder${if (folderCount != 1) "s" else ""}")
        }
        if (fileCount > 0) {
            parts.add("$fileCount file${if (fileCount != 1) "s" else ""}")
        }

        return if (parts.isEmpty()) {
            "No files selected"
        } else {
            "${parts.joinToString(", ")} ($size)"
        }
    }

    /**
     * Get short selection summary (for compact display)
     * Shows only top-level user selections, not recursive contents
     */
    fun getShortSelectionSummary(): String {
        val fileCount = topLevelSelectedFiles.size
        val folderCount = topLevelSelectedFolders.size
        val size = getFormattedTotalSize()

        return when {
            folderCount > 0 && fileCount > 0 -> "$folderCount folders, $fileCount files • $size"
            folderCount > 0 -> "$folderCount folders • $size"
            fileCount > 0 -> "$fileCount files • $size"
            else -> "No selection"
        }
    }

    /**
     * Get count of top-level selected files (user selections, not recursive)
     */
    fun getTopLevelFileCount(): Int = topLevelSelectedFiles.size

    /**
     * Get count of top-level selected folders (user selections, not recursive)
     */
    fun getTopLevelFolderCount(): Int = topLevelSelectedFolders.size

    /**
     * Get list of top-level selected file paths (user selections only, not files inside folders)
     */
    fun getTopLevelSelectedFilePaths(): List<String> {
        synchronized(topLevelSelectedFiles) {
            return topLevelSelectedFiles.toList()
        }
    }

    /**
     * Get list of top-level selected folder paths (user selections only, not subdirectories)
     */
    fun getTopLevelSelectedFolderPaths(): List<String> {
        synchronized(topLevelSelectedFolders) {
            return topLevelSelectedFolders.toList()
        }
    }

    /**
     * Set the browsing directory (where user is when making selections)
     * This is used as the trim point for paths during transfer
     */
    fun setBrowsingDirectory(path: String) {
        browsingDirectory = path
    }

    /**
     * Get the browsing directory
     */
    fun getBrowsingDirectory(): String = browsingDirectory

    /**
     * Clear browsing directory (call when selection is cleared)
     */
    fun clearBrowsingDirectory() {
        browsingDirectory = ""
    }

    // ==================== Bulk Restore Methods ====================

    /**
     * Restore file selections from saved paths (for resume functionality).
     * Does NOT trigger async directory scanning - just adds paths directly.
     * Only adds files that still exist and are accessible.
     * Returns the number of files actually restored.
     */
    fun bulkAddFiles(
        filePaths: List<String>,
        directoryPaths: List<String>,
        topLevelFilePaths: List<String>,
        topLevelFolderPaths: List<String>,
        browsDir: String
    ): Int {
        var restored = 0

        synchronized(selectedFiles) {
            for (path in filePaths) {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    selectedFiles.add(path)
                    restored++
                }
            }
        }

        synchronized(selectedDirectories) {
            for (path in directoryPaths) {
                if (File(path).exists()) {
                    selectedDirectories.add(path)
                }
            }
        }

        synchronized(allDirectories) {
            for (path in directoryPaths) {
                if (File(path).exists()) {
                    allDirectories.add(path)
                }
            }
        }

        synchronized(topLevelSelectedFiles) {
            for (path in topLevelFilePaths) {
                if (File(path).exists()) {
                    topLevelSelectedFiles.add(path)
                }
            }
        }

        synchronized(topLevelSelectedFolders) {
            for (path in topLevelFolderPaths) {
                if (File(path).exists()) {
                    topLevelSelectedFolders.add(path)
                }
            }
        }

        if (browsDir.isNotEmpty()) {
            browsingDirectory = browsDir
        }

        notifyListeners()
        return restored
    }

    // ==================== Backup Mode Methods ====================

    /**
     * Enable or disable backup mode
     * In backup mode, files are organized under storage prefixes
     */
    fun setBackupMode(enabled: Boolean) {
        backupMode = enabled
        if (!enabled) {
            backupPrefixMap.clear()
            storageRootMap.clear()
        }
    }

    /**
     * Check if backup mode is enabled
     */
    fun isBackupMode(): Boolean = backupMode

    /**
     * Add files and directories for backup
     * @param files List of FileItems to add
     * @param directories List of directory paths (with backup prefix already applied)
     * @param storageRoot The root path of the storage (e.g., "/storage/emulated/0")
     * @param storagePrefix The prefix for this storage (e.g., "Internal Storage")
     */
    fun addBackupItems(
        files: List<FileItem>,
        directories: List<String>,
        storageRoot: String,
        storagePrefix: String
    ) {
        // Store the storage root mapping
        storageRootMap[storagePrefix] = storageRoot

        // Add all files
        for (file in files) {
            selectedFiles.add(file.path)
            topLevelSelectedFiles.add(file.path)  // Track as top-level selection
            backupPrefixMap[file.path] = storagePrefix
        }

        // Add all directories (these already have the prefix applied)
        allDirectories.addAll(directories)

        notifyListeners()
    }

    /**
     * Get the backup prefix for a file path
     */
    fun getBackupPrefix(filePath: String): String {
        return backupPrefixMap[filePath] ?: ""
    }

    /**
     * Get the storage root for a backup prefix
     */
    fun getStorageRoot(prefix: String): String {
        return storageRootMap[prefix] ?: ""
    }

    /**
     * Get all storage roots being used in backup
     */
    fun getStorageRoots(): Map<String, String> = storageRootMap.toMap()

    /**
     * Get the relative path for a file in backup mode
     * This returns the path relative to its storage root, prefixed with the storage name
     */
    fun getBackupRelativePath(filePath: String): String {
        val prefix = backupPrefixMap[filePath] ?: return filePath
        val storageRoot = storageRootMap[prefix] ?: return filePath

        val relativePath = if (filePath.startsWith(storageRoot)) {
            filePath.substring(storageRoot.length).trimStart('/', '\\')
        } else {
            File(filePath).name
        }

        return if (relativePath.isEmpty()) {
            prefix
        } else {
            "$prefix/$relativePath"
        }
    }
}
