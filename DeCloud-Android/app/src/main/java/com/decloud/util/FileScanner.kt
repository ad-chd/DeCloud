package com.decloud.util

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import android.util.LruCache
import com.decloud.model.FileItem
import com.decloud.model.FilterType
import com.decloud.model.SortType
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Robust file scanner that handles deep directories without crashing
 * - Uses chunked loading to prevent memory issues
 * - Proper error handling for permission denied
 * - Caching for performance
 * - Safe coroutine handling
 */
object FileScanner {

    private const val TAG = "FileScanner"

    // Cache for directory listings (max 100 directories)
    private val directoryCache = LruCache<String, CachedDirectory>(100)

    // Cache for file counts
    private val fileCountCache = ConcurrentHashMap<String, Int>()

    // Cache validity duration (10 seconds)
    private const val CACHE_VALIDITY_MS = 10000L

    // Max items to load at once to prevent OOM
    private const val MAX_ITEMS_PER_LOAD = 1000

    // Timeout for directory listing
    private const val DIRECTORY_TIMEOUT_MS = 30000L

    private data class CachedDirectory(
        val items: List<FileItem>,
        val timestamp: Long,
        val sortType: SortType,
        val filterType: FilterType
    ) {
        fun isValid(sortType: SortType, filterType: FilterType): Boolean {
            return this.sortType == sortType &&
                   this.filterType == filterType &&
                   System.currentTimeMillis() - timestamp < CACHE_VALIDITY_MS
        }
    }

    /**
     * Get list of storage roots (Internal + SD Card)
     */
    fun getStorageRoots(context: Context): List<StorageRoot> {
        val roots = mutableListOf<StorageRoot>()

        try {
            // Internal Storage
            val internalStorage = Environment.getExternalStorageDirectory()
            if (internalStorage.exists() && internalStorage.canRead()) {
                roots.add(
                    StorageRoot(
                        name = "Internal Storage",
                        path = internalStorage.absolutePath,
                        file = internalStorage
                    )
                )
            }

            // SD Card and other external storage
            try {
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val storageVolumes = storageManager.storageVolumes

                for (volume in storageVolumes) {
                    val volumePath = getVolumePath(volume)
                    if (volumePath != null && volumePath != internalStorage.absolutePath) {
                        val file = File(volumePath)
                        if (file.exists() && file.canRead()) {
                            val description = volume.getDescription(context) ?: "SD Card"
                            roots.add(
                                StorageRoot(
                                    name = description,
                                    path = volumePath,
                                    file = file
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting storage volumes: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage roots: ${e.message}")
        }

        return roots
    }

    private fun getVolumePath(volume: android.os.storage.StorageVolume): String? {
        return try {
            val getPath = volume.javaClass.getMethod("getPath")
            getPath.invoke(volume) as? String
        } catch (e: Exception) {
            try {
                val getDirectory = volume.javaClass.getMethod("getDirectory")
                (getDirectory.invoke(volume) as? File)?.absolutePath
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Get quick access folders
     */
    fun getQuickAccessFolders(): List<QuickAccessFolder> {
        val folders = mutableListOf<QuickAccessFolder>()

        try {
            val base = Environment.getExternalStorageDirectory()

            val quickFolders = listOf(
                "DCIM" to "Camera Photos",
                "Pictures" to "Pictures",
                "Movies" to "Videos",
                "Download" to "Downloads",
                "Documents" to "Documents",
                "Music" to "Music"
            )

            for ((dirName, displayName) in quickFolders) {
                try {
                    val dir = File(base, dirName)
                    if (dir.exists() && dir.isDirectory && dir.canRead()) {
                        val fileCount = getEstimatedFileCount(dir)
                        folders.add(
                            QuickAccessFolder(
                                name = displayName,
                                path = dir.absolutePath,
                                file = dir,
                                fileCount = fileCount
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error accessing $dirName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting quick access folders: ${e.message}")
        }

        return folders
    }

    /**
     * Get estimated file count (fast, non-blocking)
     */
    private fun getEstimatedFileCount(directory: File): Int {
        return try {
            val cached = fileCountCache[directory.absolutePath]
            if (cached != null) return cached

            val count = directory.listFiles()?.size ?: 0
            fileCountCache[directory.absolutePath] = count
            count
        } catch (e: Exception) {
            0
        }
    }

    /**
     * List files in a directory with sorting and filtering (async with timeout)
     * This is the main method that should be used - it won't crash
     */
    suspend fun listFilesAsync(
        directory: File,
        sortType: SortType = SortType.NAME_ASC,
        filterType: FilterType = FilterType.ALL
    ): List<FileItem> {
        return try {
            withTimeout(DIRECTORY_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    listFilesSafe(directory, sortType, filterType)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout loading directory: ${directory.absolutePath}")
            emptyList()
        } catch (e: CancellationException) {
            // Coroutine was cancelled, rethrow
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error loading directory: ${e.message}")
            emptyList()
        }
    }

    /**
     * Safe file listing that won't crash
     */
    private fun listFilesSafe(
        directory: File,
        sortType: SortType,
        filterType: FilterType
    ): List<FileItem> {
        try {
            // Check cache first
            val cacheKey = directory.absolutePath
            val cached = directoryCache.get(cacheKey)
            if (cached != null && cached.isValid(sortType, filterType)) {
                return cached.items
            }

            // Validate directory
            if (!directory.exists()) {
                Log.w(TAG, "Directory does not exist: ${directory.absolutePath}")
                return emptyList()
            }

            if (!directory.isDirectory) {
                Log.w(TAG, "Not a directory: ${directory.absolutePath}")
                return emptyList()
            }

            if (!directory.canRead()) {
                Log.w(TAG, "Cannot read directory: ${directory.absolutePath}")
                return emptyList()
            }

            // List files with error handling
            val files = try {
                directory.listFiles()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception: ${e.message}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error listing files: ${e.message}")
                null
            }

            if (files == null) {
                Log.w(TAG, "listFiles returned null for: ${directory.absolutePath}")
                return emptyList()
            }

            // Limit the number of files to prevent OOM
            val limitedFiles = if (files.size > MAX_ITEMS_PER_LOAD) {
                Log.w(TAG, "Directory has ${files.size} items, limiting to $MAX_ITEMS_PER_LOAD")
                files.take(MAX_ITEMS_PER_LOAD)
            } else {
                files.toList()
            }

            // Convert to FileItem safely
            val items = mutableListOf<FileItem>()
            for (file in limitedFiles) {
                try {
                    // Skip hidden files
                    if (file.name.startsWith(".")) continue

                    // Create FileItem with error handling
                    val item = createFileItemSafe(file)
                    if (item != null) {
                        items.add(item)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating FileItem for ${file.name}: ${e.message}")
                }
            }

            // Apply filter
            val filteredItems = applyFilter(items, filterType)

            // Apply sort (folders first)
            val result = applySortWithFoldersFirst(filteredItems, sortType)

            // Cache the result
            directoryCache.put(cacheKey, CachedDirectory(result, System.currentTimeMillis(), sortType, filterType))

            return result

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory: ${e.message}")
            System.gc()
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in listFilesSafe: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Create FileItem safely without crashing
     */
    private fun createFileItemSafe(file: File): FileItem? {
        return try {
            FileItem(
                file = file,
                name = file.name ?: "Unknown",
                path = file.absolutePath ?: "",
                size = if (file.isDirectory) 0L else (file.length().takeIf { it >= 0 } ?: 0L),
                lastModified = file.lastModified().takeIf { it >= 0 } ?: 0L,
                isDirectory = file.isDirectory,
                extension = file.extension.lowercase(),
                mimeType = getMimeTypeSafe(file.extension.lowercase())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating FileItem: ${e.message}")
            null
        }
    }

    private fun getMimeTypeSafe(extension: String): String {
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun applyFilter(items: List<FileItem>, filterType: FilterType): List<FileItem> {
        return when (filterType) {
            FilterType.ALL -> items
            FilterType.IMAGES -> items.filter { it.isDirectory || it.isImage }
            FilterType.VIDEOS -> items.filter { it.isDirectory || it.isVideo }
            FilterType.AUDIO -> items.filter { it.isDirectory || it.isAudio }
            FilterType.DOCUMENTS -> items.filter { it.isDirectory || it.isDocument }
        }
    }

    private fun applySortWithFoldersFirst(items: List<FileItem>, sortType: SortType): List<FileItem> {
        val folders = items.filter { it.isDirectory }
        val files = items.filter { !it.isDirectory }

        val sortedFolders = sortItems(folders, sortType)
        val sortedFiles = sortItems(files, sortType)

        return sortedFolders + sortedFiles
    }

    private fun sortItems(items: List<FileItem>, sortType: SortType): List<FileItem> {
        return try {
            when (sortType) {
                SortType.NAME_ASC -> items.sortedBy { it.name.lowercase() }
                SortType.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
                SortType.SIZE_ASC -> items.sortedBy { it.size }
                SortType.SIZE_DESC -> items.sortedByDescending { it.size }
                SortType.DATE_ASC -> items.sortedBy { it.lastModified }
                SortType.DATE_DESC -> items.sortedByDescending { it.lastModified }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sorting items: ${e.message}")
            items
        }
    }

    /**
     * Get directory size (non-recursive, fast)
     */
    fun getDirectorySizeShallow(directory: File): Long {
        return try {
            directory.listFiles()?.sumOf {
                if (it.isFile) it.length() else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Count files in directory (non-recursive, fast)
     */
    fun countFilesShallow(directory: File): Int {
        return try {
            directory.listFiles()?.count { it.isFile } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Invalidate cache for a directory
     */
    fun invalidateCache(directory: File) {
        directoryCache.remove(directory.absolutePath)
    }

    /**
     * Clear all caches
     */
    fun clearCache() {
        try {
            directoryCache.evictAll()
            fileCountCache.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache: ${e.message}")
        }
    }

    /**
     * Check if directory is accessible
     */
    fun isDirectoryAccessible(directory: File): Boolean {
        return try {
            directory.exists() && directory.isDirectory && directory.canRead()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Live search - searches as user types with incremental results
     * Two-phase approach:
     * 1. Phase 1: Search current directory only (instant results)
     * 2. Phase 2: Search subdirectories recursively (progressive results)
     *
     * @param directory The directory to search in
     * @param query The search query (case-insensitive)
     * @param onResults Callback with results as they're found (called multiple times)
     * @param onComplete Callback when search is complete
     */
    suspend fun liveSearchAsync(
        directory: File,
        query: String,
        maxResults: Int = 50,
        onResults: (List<FileItem>, isComplete: Boolean) -> Unit
    ) {
        liveSearchAsyncWithCase(directory, query, caseSensitive = false, maxResults, onResults)
    }

    /**
     * Live search with case-sensitivity option
     * Two-phase approach for instant + progressive results
     *
     * @param directory The directory to search in
     * @param query The search query
     * @param caseSensitive If true, match case exactly
     * @param maxResults Maximum number of results
     * @param onResults Callback with results as they're found
     */
    suspend fun liveSearchAsyncWithCase(
        directory: File,
        query: String,
        caseSensitive: Boolean = false,
        maxResults: Int = 50,
        onResults: (List<FileItem>, isComplete: Boolean) -> Unit
    ) {
        if (query.isBlank()) {
            onResults(emptyList(), true)
            return
        }

        val searchQuery = if (caseSensitive) query.trim() else query.lowercase().trim()
        val allResults = mutableListOf<FileItem>()

        try {
            // Phase 1: Search current directory only (instant)
            val immediateResults = searchCurrentDirectoryOnly(directory, searchQuery, maxResults, caseSensitive)
            if (immediateResults.isNotEmpty()) {
                allResults.addAll(immediateResults)
                withContext(Dispatchers.Main) {
                    onResults(allResults.toList(), false)
                }
            }

            // Phase 2: Search subdirectories recursively
            if (allResults.size < maxResults && coroutineContext.isActive) {
                searchSubdirectoriesRecursive(
                    directory = directory,
                    queryLower = searchQuery,
                    maxResults = maxResults,
                    existingResults = allResults,
                    caseSensitive = caseSensitive,
                    onNewResults = { newItems ->
                        allResults.addAll(newItems)
                        // Emit updated results
                        onResults(allResults.toList(), false)
                    }
                )
            }

            // Final callback
            withContext(Dispatchers.Main) {
                onResults(allResults.toList(), true)
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Live search error: ${e.message}")
            withContext(Dispatchers.Main) {
                onResults(allResults.toList(), true)
            }
        }
    }

    /**
     * Search only the current directory (no recursion) - very fast
     */
    private fun searchCurrentDirectoryOnly(
        directory: File,
        query: String,
        maxResults: Int,
        caseSensitive: Boolean = false
    ): List<FileItem> {
        if (!isDirectoryAccessible(directory)) return emptyList()

        val results = mutableListOf<FileItem>()
        try {
            val files = directory.listFiles() ?: return emptyList()

            for (file in files) {
                if (results.size >= maxResults) break
                if (file.name.startsWith(".")) continue

                val fileName = if (caseSensitive) file.name else file.name.lowercase()
                if (fileName.contains(query)) {
                    createFileItemSafe(file)?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in current dir search: ${e.message}")
        }

        // Sort: folders first, then by match quality (starts with > contains)
        return results.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }
                .thenByDescending {
                    val name = if (caseSensitive) it.name else it.name.lowercase()
                    name.startsWith(query)
                }
                .thenBy {
                    if (caseSensitive) it.name else it.name.lowercase()
                }
        )
    }

    /**
     * Search subdirectories recursively with incremental results
     */
    private suspend fun searchSubdirectoriesRecursive(
        directory: File,
        queryLower: String,
        maxResults: Int,
        existingResults: MutableList<FileItem>,
        onNewResults: suspend (List<FileItem>) -> Unit,
        depth: Int = 0,
        caseSensitive: Boolean = false
    ) {
        if (!isDirectoryAccessible(directory)) return
        if (existingResults.size >= maxResults) return
        if (depth > 10) return // Limit recursion depth
        if (!coroutineContext.isActive) return

        try {
            val files = directory.listFiles() ?: return
            val subdirs = mutableListOf<File>()
            val batchResults = mutableListOf<FileItem>()

            for (file in files) {
                if (!coroutineContext.isActive) return
                if (existingResults.size + batchResults.size >= maxResults) break

                if (file.name.startsWith(".")) continue

                val fileName = if (caseSensitive) file.name else file.name.lowercase()

                if (file.isDirectory) {
                    subdirs.add(file)

                    // Check if directory name matches
                    if (fileName.contains(queryLower)) {
                        // Avoid duplicates
                        if (existingResults.none { it.path == file.absolutePath }) {
                            createFileItemSafe(file)?.let { batchResults.add(it) }
                        }
                    }
                } else {
                    // Check if file name matches
                    if (fileName.contains(queryLower)) {
                        if (existingResults.none { it.path == file.absolutePath }) {
                            createFileItemSafe(file)?.let { batchResults.add(it) }
                        }
                    }
                }
            }

            // Emit batch results if any
            if (batchResults.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    onNewResults(batchResults)
                }
            }

            // Yield to allow cancellation
            yield()

            // Recurse into subdirectories
            for (subdir in subdirs) {
                if (!coroutineContext.isActive) return
                if (existingResults.size >= maxResults) return

                searchSubdirectoriesRecursive(
                    directory = subdir,
                    queryLower = queryLower,
                    maxResults = maxResults,
                    existingResults = existingResults,
                    onNewResults = onNewResults,
                    depth = depth + 1,
                    caseSensitive = caseSensitive
                )
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in recursive search: ${e.message}")
        }
    }

    /**
     * Live search with advanced filters
     * Supports type filter (files only, folders only, all), size filter, date filter, and depth limit
     */
    suspend fun liveSearchAsyncWithFilters(
        directory: File,
        query: String,
        caseSensitive: Boolean = false,
        typeFilter: com.decloud.ui.SearchActivity.TypeFilter = com.decloud.ui.SearchActivity.TypeFilter.ALL,
        sizeFilter: com.decloud.ui.SearchActivity.SizeFilter = com.decloud.ui.SearchActivity.SizeFilter.ANY,
        dateFilter: com.decloud.ui.SearchActivity.DateFilter = com.decloud.ui.SearchActivity.DateFilter.ANY,
        maxDepth: Int = 10,
        maxResults: Int = 500,
        onResults: (List<FileItem>, isComplete: Boolean) -> Unit
    ) {
        if (query.isBlank()) {
            onResults(emptyList(), true)
            return
        }

        val searchQuery = if (caseSensitive) query.trim() else query.lowercase().trim()
        val allResults = mutableListOf<FileItem>()

        // Calculate date thresholds
        val now = System.currentTimeMillis()
        val dateThreshold = when (dateFilter) {
            com.decloud.ui.SearchActivity.DateFilter.TODAY -> now - 24 * 60 * 60 * 1000L
            com.decloud.ui.SearchActivity.DateFilter.WEEK -> now - 7 * 24 * 60 * 60 * 1000L
            com.decloud.ui.SearchActivity.DateFilter.MONTH -> now - 30L * 24 * 60 * 60 * 1000L
            com.decloud.ui.SearchActivity.DateFilter.YEAR -> now - 365L * 24 * 60 * 60 * 1000L
            else -> 0L
        }

        // Size thresholds in bytes (LARGEST_FIRST and SMALLEST_FIRST don't filter, just sort)
        val (minSize, maxSize) = when (sizeFilter) {
            com.decloud.ui.SearchActivity.SizeFilter.SMALL -> 0L to 1024L * 1024L  // < 1MB
            com.decloud.ui.SearchActivity.SizeFilter.MEDIUM -> 1024L * 1024L to 100L * 1024 * 1024  // 1-100MB
            com.decloud.ui.SearchActivity.SizeFilter.LARGE -> 100L * 1024 * 1024 to 1024L * 1024 * 1024  // 100MB-1GB
            com.decloud.ui.SearchActivity.SizeFilter.HUGE -> 1024L * 1024 * 1024 to Long.MAX_VALUE  // > 1GB
            com.decloud.ui.SearchActivity.SizeFilter.LARGEST_FIRST -> 0L to Long.MAX_VALUE  // No filter, just sort
            com.decloud.ui.SearchActivity.SizeFilter.SMALLEST_FIRST -> 0L to Long.MAX_VALUE  // No filter, just sort
            else -> 0L to Long.MAX_VALUE
        }

        try {
            searchWithFiltersRecursive(
                directory = directory,
                query = searchQuery,
                caseSensitive = caseSensitive,
                typeFilter = typeFilter,
                dateThreshold = dateThreshold,
                minSize = minSize,
                maxSize = maxSize,
                maxDepth = maxDepth,
                maxResults = maxResults,
                results = allResults,
                onNewResults = { newItems ->
                    withContext(Dispatchers.Main) {
                        onResults(allResults.toList(), false)
                    }
                }
            )

            // Final callback
            withContext(Dispatchers.Main) {
                onResults(allResults.toList(), true)
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Filtered search error: ${e.message}")
            withContext(Dispatchers.Main) {
                onResults(allResults.toList(), true)
            }
        }
    }

    /**
     * Recursive search with filters
     */
    private suspend fun searchWithFiltersRecursive(
        directory: File,
        query: String,
        caseSensitive: Boolean,
        typeFilter: com.decloud.ui.SearchActivity.TypeFilter,
        dateThreshold: Long,
        minSize: Long,
        maxSize: Long,
        maxDepth: Int,
        maxResults: Int,
        results: MutableList<FileItem>,
        onNewResults: suspend (List<FileItem>) -> Unit,
        depth: Int = 0
    ) {
        if (!isDirectoryAccessible(directory)) return
        if (results.size >= maxResults) return
        if (depth > maxDepth) return
        if (!coroutineContext.isActive) return

        try {
            val files = directory.listFiles() ?: return
            val subdirs = mutableListOf<File>()
            val batchResults = mutableListOf<FileItem>()

            for (file in files) {
                if (!coroutineContext.isActive) return
                if (results.size + batchResults.size >= maxResults) break
                if (file.name.startsWith(".")) continue

                val fileName = if (caseSensitive) file.name else file.name.lowercase()

                if (file.isDirectory) {
                    subdirs.add(file)

                    // Folders show in ALL and OTHER filters
                    if (typeFilter != com.decloud.ui.SearchActivity.TypeFilter.ALL &&
                        typeFilter != com.decloud.ui.SearchActivity.TypeFilter.OTHER) continue

                    // Check if directory name matches
                    if (fileName.contains(query)) {
                        if (results.none { it.path == file.absolutePath }) {
                            createFileItemSafe(file)?.let { item ->
                                // Check date filter for folders
                                if (dateThreshold == 0L || item.lastModified >= dateThreshold) {
                                    batchResults.add(item)
                                }
                            }
                        }
                    }
                } else {
                    // Check if file name matches
                    if (fileName.contains(query)) {
                        if (results.none { it.path == file.absolutePath }) {
                            createFileItemSafe(file)?.let { item ->
                                // Check type filter
                                val passesTypeFilter = when (typeFilter) {
                                    com.decloud.ui.SearchActivity.TypeFilter.ALL -> true
                                    com.decloud.ui.SearchActivity.TypeFilter.IMAGES -> item.isImage
                                    com.decloud.ui.SearchActivity.TypeFilter.VIDEOS -> item.isVideo
                                    com.decloud.ui.SearchActivity.TypeFilter.AUDIO -> item.isAudio
                                    com.decloud.ui.SearchActivity.TypeFilter.OTHER -> !item.isImage && !item.isVideo && !item.isAudio
                                }

                                if (!passesTypeFilter) return@let

                                // Apply other filters
                                val passesDateFilter = dateThreshold == 0L || item.lastModified >= dateThreshold
                                val passesSizeFilter = item.size in minSize..maxSize

                                if (passesDateFilter && passesSizeFilter) {
                                    batchResults.add(item)
                                }
                            }
                        }
                    }
                }
            }

            // Emit batch results if any
            if (batchResults.isNotEmpty()) {
                results.addAll(batchResults)
                onNewResults(batchResults)
            }

            // Yield to allow cancellation
            yield()

            // Recurse into subdirectories
            for (subdir in subdirs) {
                if (!coroutineContext.isActive) return
                if (results.size >= maxResults) return

                searchWithFiltersRecursive(
                    directory = subdir,
                    query = query,
                    caseSensitive = caseSensitive,
                    typeFilter = typeFilter,
                    dateThreshold = dateThreshold,
                    minSize = minSize,
                    maxSize = maxSize,
                    maxDepth = maxDepth,
                    maxResults = maxResults,
                    results = results,
                    onNewResults = onNewResults,
                    depth = depth + 1
                )
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in filtered recursive search: ${e.message}")
        }
    }

    /**
     * Search for files/folders matching a query (case-insensitive)
     * Searches recursively in the given directory
     *
     * @param directory The directory to search in
     * @param query The search query (case-insensitive)
     * @param onProgress Callback for progress updates (number of files scanned)
     * @return List of matching FileItems
     */
    suspend fun searchFilesAsync(
        directory: File,
        query: String,
        maxResults: Int = 100,
        onProgress: ((Int) -> Unit)? = null
    ): List<FileItem> {
        if (query.isBlank()) return emptyList()

        return try {
            withTimeout(DIRECTORY_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    searchFilesRecursive(directory, query.lowercase().trim(), maxResults, onProgress)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout searching in: ${directory.absolutePath}")
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error searching: ${e.message}")
            emptyList()
        }
    }

    /**
     * Recursive search helper
     */
    private suspend fun searchFilesRecursive(
        directory: File,
        queryLower: String,
        maxResults: Int,
        onProgress: ((Int) -> Unit)?,
        results: MutableList<FileItem> = mutableListOf(),
        scannedCount: IntArray = intArrayOf(0)
    ): List<FileItem> {
        if (!isDirectoryAccessible(directory)) return results
        if (results.size >= maxResults) return results

        try {
            val files = directory.listFiles() ?: return results

            for (file in files) {
                // Check if we should stop
                if (results.size >= maxResults) break
                if (!coroutineContext.isActive) break

                scannedCount[0]++

                // Report progress every 50 files
                if (scannedCount[0] % 50 == 0) {
                    onProgress?.invoke(scannedCount[0])
                    yield() // Allow cancellation
                }

                // Skip hidden files
                if (file.name.startsWith(".")) continue

                // Check if name matches (case-insensitive)
                if (file.name.lowercase().contains(queryLower)) {
                    val item = createFileItemSafe(file)
                    if (item != null && results.size < maxResults) {
                        results.add(item)
                    }
                }

                // Recurse into subdirectories
                if (file.isDirectory && isDirectoryAccessible(file)) {
                    searchFilesRecursive(file, queryLower, maxResults, onProgress, results, scannedCount)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in recursive search: ${e.message}")
        }

        return results
    }
}

data class StorageRoot(
    val name: String,
    val path: String,
    val file: File
)

data class QuickAccessFolder(
    val name: String,
    val path: String,
    val file: File,
    val fileCount: Int
)

data class PaginatedResult(
    val items: List<FileItem>,
    val totalCount: Int,
    val hasMore: Boolean,
    val nextOffset: Int
)
