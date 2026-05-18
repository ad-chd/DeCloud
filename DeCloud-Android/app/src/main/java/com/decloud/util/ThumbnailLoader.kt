package com.decloud.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Native thumbnail loader - no third-party libraries
 * Uses Android's ThumbnailUtils and BitmapFactory
 */
object ThumbnailLoader {

    // Thumbnail size
    private const val THUMBNAIL_SIZE = 80

    // Memory cache - uses 1/8th of available memory
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // Track loading jobs to cancel if needed
    private val loadingJobs = ConcurrentHashMap<String, Job>()

    // Coroutine scope for background loading
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Preload scope for idle preloading
    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Get cached thumbnail synchronously (returns null if not cached)
     */
    fun getCached(path: String): Bitmap? {
        return memoryCache.get(path)
    }

    /**
     * Load thumbnail asynchronously
     */
    fun loadAsync(
        path: String,
        isVideo: Boolean,
        onLoaded: (Bitmap?) -> Unit
    ): Job {
        // Cancel any existing job for this path
        loadingJobs[path]?.cancel()

        val job = scope.launch {
            val bitmap = loadThumbnail(path, isVideo)

            if (isActive) {
                withContext(Dispatchers.Main) {
                    onLoaded(bitmap)
                }
            }
        }

        loadingJobs[path] = job
        return job
    }

    /**
     * Cancel loading for a specific path
     */
    fun cancelLoad(path: String) {
        loadingJobs[path]?.cancel()
        loadingJobs.remove(path)
    }

    /**
     * Preload thumbnails for a list of paths (called when idle)
     */
    fun preloadInBackground(paths: List<Pair<String, Boolean>>) {
        preloadScope.launch {
            for ((path, isVideo) in paths) {
                if (!isActive) break

                // Skip if already cached
                if (memoryCache.get(path) != null) continue

                // Load with low priority
                delay(50) // Small delay to not overwhelm
                loadThumbnail(path, isVideo)
            }
        }
    }

    /**
     * Cancel all preloading
     */
    fun cancelPreload() {
        preloadScope.coroutineContext.cancelChildren()
    }

    /**
     * Load thumbnail using native Android APIs
     */
    private fun loadThumbnail(path: String, isVideo: Boolean): Bitmap? {
        // Check cache first
        memoryCache.get(path)?.let { return it }

        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null

            val bitmap = if (isVideo) {
                loadVideoThumbnail(file)
            } else {
                loadImageThumbnail(file)
            }

            // Cache the result
            bitmap?.let { memoryCache.put(path, it) }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load image thumbnail using BitmapFactory with sampling
     */
    private fun loadImageThumbnail(file: File): Bitmap? {
        return try {
            // First, get image dimensions without loading full bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            // Calculate sample size for efficient loading
            val sampleSize = calculateSampleSize(
                options.outWidth,
                options.outHeight,
                THUMBNAIL_SIZE,
                THUMBNAIL_SIZE
            )

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Less memory
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                ?: return null

            // Create centered square thumbnail
            createSquareThumbnail(bitmap)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load video thumbnail using ThumbnailUtils
     */
    private fun loadVideoThumbnail(file: File): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ - use createVideoThumbnail with Size
                ThumbnailUtils.createVideoThumbnail(
                    file,
                    Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE),
                    CancellationSignal()
                )
            } else {
                // Older API - use deprecated method
                @Suppress("DEPRECATION")
                val bitmap = ThumbnailUtils.createVideoThumbnail(
                    file.absolutePath,
                    android.provider.MediaStore.Images.Thumbnails.MINI_KIND
                )
                bitmap?.let { createSquareThumbnail(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Calculate optimal sample size for decoding
     */
    private fun calculateSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var sampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / sampleSize >= reqHeight &&
                   halfWidth / sampleSize >= reqWidth) {
                sampleSize *= 2
            }
        }

        return sampleSize
    }

    /**
     * Create a centered square thumbnail from bitmap
     */
    private fun createSquareThumbnail(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2

        val cropped = Bitmap.createBitmap(source, x, y, size, size)

        // Scale to thumbnail size
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            THUMBNAIL_SIZE,
            THUMBNAIL_SIZE,
            true
        )

        // Recycle intermediate bitmaps if different
        if (cropped != source && cropped != scaled) {
            cropped.recycle()
        }

        return scaled
    }

    /**
     * Clear all cached thumbnails
     */
    fun clearCache() {
        memoryCache.evictAll()
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        cancelPreload()
    }

    /**
     * Check if a file type supports thumbnails
     */
    fun supportsThumbnail(extension: String): Boolean {
        return extension.lowercase() in listOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp",  // Images
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm"  // Videos
        )
    }

    /**
     * Check if file is a video (for thumbnail loading method selection)
     */
    fun isVideo(extension: String): Boolean {
        return extension.lowercase() in listOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm"
        )
    }
}
