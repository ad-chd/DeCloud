package com.decloud.util

import android.Manifest
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import com.decloud.R

/**
 * Backup categories using native Android APIs
 * No hardcoded paths - uses MediaStore, ContactsContract, Telephony providers
 */
enum class BackupCategory(
    val displayName: String,
    val description: String,
    val iconResId: Int,
    val sourceType: SourceType,
    val contentUri: Uri?,
    val requiredPermission: String?,
    val defaultEnabled: Boolean = true
) {
    IMAGES(
        displayName = "Images",
        description = "Photos, screenshots, downloaded images",
        iconResId = R.drawable.ic_image,
        sourceType = SourceType.MEDIA_STORE,
        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        requiredPermission = null,  // Already have storage permission
        defaultEnabled = true
    ),

    VIDEOS(
        displayName = "Videos",
        description = "Recorded videos, downloaded videos",
        iconResId = R.drawable.ic_video,
        sourceType = SourceType.MEDIA_STORE,
        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        requiredPermission = null,
        defaultEnabled = true
    ),

    AUDIO(
        displayName = "Audio & Music",
        description = "Music, recordings, ringtones, podcasts",
        iconResId = R.drawable.ic_audio,
        sourceType = SourceType.MEDIA_STORE,
        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        requiredPermission = null,
        defaultEnabled = true
    ),

    DOCUMENTS(
        displayName = "Documents",
        description = "PDFs, Office files, text files",
        iconResId = R.drawable.ic_document,
        sourceType = SourceType.MEDIA_STORE,
        contentUri = MediaStore.Files.getContentUri("external"),
        requiredPermission = null,
        defaultEnabled = true
    ),

    DOWNLOADS(
        displayName = "Downloads",
        description = "All downloaded files",
        iconResId = R.drawable.ic_download,
        sourceType = SourceType.FOLDER,
        contentUri = null,
        requiredPermission = null,
        defaultEnabled = true
    ),

    CONTACTS(
        displayName = "Contacts",
        description = "Phone contacts (exported as VCF)",
        iconResId = R.drawable.ic_contacts,
        sourceType = SourceType.CONTENT_PROVIDER,
        contentUri = ContactsContract.Contacts.CONTENT_URI,
        requiredPermission = Manifest.permission.READ_CONTACTS,
        defaultEnabled = true
    ),

    OTHER(
        displayName = "Other Files",
        description = "Files not in other categories",
        iconResId = R.drawable.ic_document,
        sourceType = SourceType.FOLDER,
        contentUri = null,
        requiredPermission = null,
        defaultEnabled = true
    );

    /**
     * Type of data source for this category
     */
    enum class SourceType {
        MEDIA_STORE,        // Uses MediaStore API (Images, Videos, Audio, Documents)
        FOLDER,             // Direct folder scan (Downloads)
        CONTENT_PROVIDER    // Uses ContentProvider (Contacts)
    }

    companion object {
        /**
         * Get all media categories (no special permissions needed)
         */
        fun getMediaCategories(): List<BackupCategory> = listOf(
            IMAGES, VIDEOS, AUDIO, DOCUMENTS, DOWNLOADS
        )

        /**
         * Get categories that need special permissions
         */
        fun getPermissionCategories(): List<BackupCategory> = listOf(
            CONTACTS
        )

        /**
         * Get all categories enabled by default
         */
        fun getDefaultEnabled(): List<BackupCategory> = entries.filter { it.defaultEnabled }

        /**
         * Categorize a file path by its extension into a BackupCategory.
         */
        fun fromFilePath(path: String): BackupCategory {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg","jpeg","png","gif","webp","bmp","svg","heic","heif","ico","tiff","tif" -> IMAGES
                "mp4","mkv","avi","mov","wmv","flv","webm","3gp","m4v","ts" -> VIDEOS
                "mp3","wav","ogg","flac","aac","m4a","wma","opus","amr" -> AUDIO
                "pdf","doc","docx","xls","xlsx","ppt","pptx","txt","csv",
                "json","xml","html","htm","rtf","odt","ods","odp","epub","md" -> DOCUMENTS
                else -> OTHER
            }
        }
    }
}

/**
 * Result of scanning a backup category
 */
data class CategoryScanResult(
    val category: BackupCategory,
    val fileCount: Int,
    val totalSize: Long,
    val excludedCount: Int,
    val excludedSize: Long,
    val isEnabled: Boolean,
    val hasPermission: Boolean,
    val errorMessage: String? = null,
    val excludedFiles: List<Pair<String, String>> = emptyList()  // (fileName, reason)
) {
    fun getFormattedSize(): String {
        return when {
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
        return if (fileCount > 0) {
            "$fileCount items (${getFormattedSize()})"
        } else {
            "No items"
        }
    }
}

/**
 * Exclusion rules for backup
 * Files matching these patterns are automatically excluded
 */
object ExclusionRules {

    /**
     * Folder names to exclude (case-insensitive)
     */
    val EXCLUDED_FOLDERS = setOf(
        ".thumbnails",
        "thumbnails",
        "cache",
        ".cache",
        "code_cache",
        "no_backup",
        ".no_backup",
        "temp",
        ".temp",
        "tmp",
        ".tmp",
        "logs",
        ".logs",
        ".trash",
        "trash",
        ".recycle"
    )

    /**
     * File extensions to exclude
     */
    val EXCLUDED_EXTENSIONS = setOf(
        "tmp", "temp", "cache",
        "log", "logs",
        "bak", "backup",
        "dex", "odex", "vdex", "art",  // Android compiled code
        "journal", "wal", "shm",        // SQLite temp files
        "crdownload", "part", "partial", "download",  // Incomplete downloads
        "nomedia"
    )

    /**
     * File name patterns to exclude (regex)
     */
    val EXCLUDED_PATTERNS = listOf(
        Regex("^\\..+"),              // Hidden files starting with .
        Regex(".*-journal$"),         // SQLite journals
        Regex(".*-wal$"),             // SQLite WAL
        Regex(".*-shm$"),             // SQLite SHM
        Regex(".*\\.tmp\\d*$"),       // Temp files with numbers
        Regex("~\\$.*")               // Office temp files
    )

    /**
     * Check if a file path should be excluded
     */
    fun shouldExclude(filePath: String, fileName: String): Boolean {
        // Check file name patterns
        for (pattern in EXCLUDED_PATTERNS) {
            if (pattern.matches(fileName)) {
                return true
            }
        }

        // Check extension
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in EXCLUDED_EXTENSIONS) {
            return true
        }

        return false
    }

    /**
     * Check if a folder should be excluded
     */
    fun shouldExcludeFolder(folderName: String): Boolean {
        return folderName.lowercase() in EXCLUDED_FOLDERS.map { it.lowercase() }
    }

    /**
     * Get the reason a file was excluded (for display purposes)
     */
    fun getExclusionReason(filePath: String, fileName: String): String {
        for (pattern in EXCLUDED_PATTERNS) {
            if (pattern.matches(fileName)) {
                return "Pattern: ${pattern.pattern}"
            }
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in EXCLUDED_EXTENSIONS) {
            return "Extension: .$extension"
        }
        return "Excluded"
    }

    /**
     * Get human-readable exclusion summary
     */
    fun getExclusionSummary(excludedCount: Int, excludedSize: Long): String {
        val sizeStr = when {
            excludedSize >= 1024L * 1024 * 1024 ->
                String.format("%.1f GB", excludedSize / (1024.0 * 1024 * 1024))
            excludedSize >= 1024L * 1024 ->
                String.format("%.1f MB", excludedSize / (1024.0 * 1024))
            else -> String.format("%.1f KB", excludedSize / 1024.0)
        }
        return "$excludedCount files excluded ($sizeStr saved)"
    }
}
