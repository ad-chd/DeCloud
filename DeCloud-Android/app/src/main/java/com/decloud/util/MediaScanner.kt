package com.decloud.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.decloud.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MediaScanner - Uses MediaStore API to fetch ALL media files from the device
 * This is the industry-standard approach used by Google Files, Samsung My Files, etc.
 * MediaStore indexes all files automatically, so we won't miss any files.
 */
object MediaScanner {

    private const val TAG = "MediaScanner"

    /**
     * Category types for file browsing
     */
    enum class Category {
        IMAGES,
        VIDEOS,
        AUDIO,
        DOCUMENTS,
        DOWNLOADS,
        APPLICATIONS
    }

    /**
     * Get all images from the device using MediaStore
     */
    suspend fun getImages(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val images = mutableListOf<FileItem>()

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(dataColumn)
                        val file = File(path)

                        if (file.exists() && file.canRead()) {
                            images.add(FileItem.fromFile(file))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading image: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying images: ${e.message}")
        }

        images
    }

    /**
     * Get all videos from the device using MediaStore
     */
    suspend fun getVideos(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<FileItem>()

        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(dataColumn)
                        val file = File(path)

                        if (file.exists() && file.canRead()) {
                            videos.add(FileItem.fromFile(file))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading video: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying videos: ${e.message}")
        }

        videos
    }

    /**
     * Get all audio files from the device using MediaStore
     */
    suspend fun getAudio(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val audioFiles = mutableListOf<FileItem>()

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        // Exclude notification sounds, ringtones, alarms
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(dataColumn)
                        val file = File(path)

                        if (file.exists() && file.canRead()) {
                            audioFiles.add(FileItem.fromFile(file))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading audio: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying audio: ${e.message}")
        }

        audioFiles
    }

    /**
     * Get all documents from the device using MediaStore
     * Includes: PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX, TXT, etc.
     */
    suspend fun getDocuments(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val documents = mutableListOf<FileItem>()

        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        // Document MIME types
        val documentMimeTypes = listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            "application/rtf",
            "application/json",
            "application/xml"
        )

        val placeholders = documentMimeTypes.joinToString(",") { "?" }
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IN ($placeholders)"
        val selectionArgs = documentMimeTypes.toTypedArray()
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(dataColumn)
                        if (path != null) {
                            val file = File(path)
                            if (file.exists() && file.canRead()) {
                                documents.add(FileItem.fromFile(file))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading document: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying documents: ${e.message}")
        }

        documents
    }

    /**
     * Get all files from Downloads folder
     */
    suspend fun getDownloads(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val downloads = mutableListOf<FileItem>()

        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            if (downloadDir.exists() && downloadDir.canRead()) {
                downloadDir.listFiles()?.forEach { file ->
                    if (!file.name.startsWith(".") && file.isFile) {
                        try {
                            downloads.add(FileItem.fromFile(file))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading download file: ${e.message}")
                        }
                    }
                }
            }

            // Sort by date modified (newest first)
            downloads.sortByDescending { it.lastModified }

        } catch (e: Exception) {
            Log.e(TAG, "Error listing downloads: ${e.message}")
        }

        downloads
    }

    /**
     * Get all APK files from the device using MediaStore
     */
    suspend fun getApplications(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val apks = mutableListOf<FileItem>()

        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        // Query for APK files
        val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
        val selectionArgs = arrayOf("%.apk")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(dataColumn)
                        if (path != null) {
                            val file = File(path)
                            if (file.exists() && file.canRead()) {
                                apks.add(FileItem.fromFile(file))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading APK: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying APKs: ${e.message}")
        }

        apks
    }

    /**
     * Get files by category
     */
    suspend fun getFilesByCategory(context: Context, category: Category): List<FileItem> {
        return when (category) {
            Category.IMAGES -> getImages(context)
            Category.VIDEOS -> getVideos(context)
            Category.AUDIO -> getAudio(context)
            Category.DOCUMENTS -> getDocuments(context)
            Category.DOWNLOADS -> getDownloads(context)
            Category.APPLICATIONS -> getApplications(context)
        }
    }

    /**
     * Get count of files in each category (fast, for display purposes)
     */
    suspend fun getCategoryCounts(context: Context): Map<Category, Int> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf<Category, Int>()

        // Images count
        counts[Category.IMAGES] = getCount(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            null,
            null
        )

        // Videos count
        counts[Category.VIDEOS] = getCount(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            null,
            null
        )

        // Audio count
        counts[Category.AUDIO] = getCount(
            context,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            null,
            null
        )

        // Documents and APKs need to be counted from Files
        // These are approximate counts
        counts[Category.DOCUMENTS] = 0
        counts[Category.DOWNLOADS] = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
        } catch (e: Exception) { 0 }

        counts[Category.APPLICATIONS] = 0

        counts
    }

    /**
     * Stream files by category in batches for progressive loading.
     * Emits List<FileItem> chunks as they are scanned.
     */
    fun streamFilesByCategory(context: Context, category: Category, batchSize: Int = 100): Flow<List<FileItem>> = flow {
        when (category) {
            Category.DOWNLOADS -> {
                // Downloads is filesystem-based, just emit all at once (usually small)
                val files = getDownloads(context)
                if (files.isNotEmpty()) emit(files)
                return@flow
            }
            else -> { /* MediaStore-based categories handled below */ }
        }

        val (uri, projection, sel, selArgs, sortOrder) = when (category) {
            Category.IMAGES -> MediaStoreQuery(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA, MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED),
                null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )
            Category.VIDEOS -> MediaStoreQuery(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED),
                null, null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )
            Category.AUDIO -> MediaStoreQuery(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0",
                null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )
            Category.DOCUMENTS -> {
                val mimeTypes = listOf(
                    "application/pdf", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain", "text/csv", "application/rtf",
                    "application/json", "application/xml"
                )
                val placeholders = mimeTypes.joinToString(",") { "?" }
                MediaStoreQuery(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.MIME_TYPE),
                    "${MediaStore.Files.FileColumns.MIME_TYPE} IN ($placeholders)",
                    mimeTypes.toTypedArray(),
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                )
            }
            Category.APPLICATIONS -> MediaStoreQuery(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED),
                "${MediaStore.Files.FileColumns.DATA} LIKE ?",
                arrayOf("%.apk"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )
            else -> return@flow
        }

        try {
            context.contentResolver.query(uri, projection, sel, selArgs, sortOrder)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val batch = mutableListOf<FileItem>()

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(dataColumn) ?: continue
                        val file = File(path)
                        if (file.exists() && file.canRead()) {
                            batch.add(FileItem.fromFile(file))
                            if (batch.size >= batchSize) {
                                emit(batch.toList())
                                batch.clear()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading file: ${e.message}")
                    }
                }

                // Emit remaining items
                if (batch.isNotEmpty()) {
                    emit(batch.toList())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying $category: ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    private data class MediaStoreQuery(
        val uri: Uri,
        val projection: Array<String>,
        val selection: String?,
        val selectionArgs: Array<String>?,
        val sortOrder: String
    )

    private fun getCount(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                cursor.count
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
