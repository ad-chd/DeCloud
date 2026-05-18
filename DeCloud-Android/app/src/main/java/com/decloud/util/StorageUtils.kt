package com.decloud.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Utility class to detect and manage storage volumes
 */
object StorageUtils {

    /**
     * Represents a storage volume (internal, SD card, USB, etc.)
     */
    data class StorageInfo(
        val name: String,           // Display name (e.g., "Internal Storage", "SD Card")
        val path: File,             // Root path of the storage
        val isRemovable: Boolean,   // True for SD card, USB, etc.
        val isPrimary: Boolean,     // True for internal storage
        val totalSpace: Long,       // Total space in bytes
        val freeSpace: Long         // Free space in bytes
    ) {
        fun getFormattedTotalSpace(): String = formatSize(totalSpace)
        fun getFormattedFreeSpace(): String = formatSize(freeSpace)

        // Short name for spinner selected display
        fun getShortName(): String {
            return when {
                isPrimary -> "Internal Storage"
                name.contains("SD", ignoreCase = true) -> "SD Card"
                name.contains("USB", ignoreCase = true) -> "USB Storage"
                name.length > 20 -> name.take(17) + "..."
                else -> name
            }
        }

        // Full display name with size info for dropdown
        fun getDisplayName(): String {
            val free = getFormattedFreeSpace()
            val total = getFormattedTotalSpace()
            return "$name ($free free of $total)"
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024 * 1024 * 1024 -> String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024))
                bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
                bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
                bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }

    /**
     * Get all available storage volumes
     */
    fun getStorageVolumes(context: Context): List<StorageInfo> {
        val volumes = mutableListOf<StorageInfo>()

        // Method 1: Use StorageManager (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val storageVolumes = storageManager.storageVolumes

            for (volume in storageVolumes) {
                val path = getVolumePath(volume)
                if (path != null && path.exists() && path.canRead()) {
                    val name = getVolumeName(volume)
                    volumes.add(StorageInfo(
                        name = name,
                        path = path,
                        isRemovable = volume.isRemovable,
                        isPrimary = volume.isPrimary,
                        totalSpace = path.totalSpace,
                        freeSpace = path.freeSpace
                    ))
                }
            }
        }

        // Fallback: Add internal storage if not already added
        if (volumes.isEmpty() || volumes.none { it.isPrimary }) {
            val internalStorage = Environment.getExternalStorageDirectory()
            if (internalStorage.exists() && internalStorage.canRead()) {
                volumes.add(0, StorageInfo(
                    name = "Internal Storage",
                    path = internalStorage,
                    isRemovable = false,
                    isPrimary = true,
                    totalSpace = internalStorage.totalSpace,
                    freeSpace = internalStorage.freeSpace
                ))
            }
        }

        // Method 2: Check common external storage paths
        val externalPaths = listOf(
            "/storage/sdcard1",
            "/storage/extSdCard",
            "/storage/external_SD",
            "/mnt/extSdCard",
            "/mnt/sdcard/external_sd",
            "/mnt/external_sd",
            "/mnt/media_rw/sdcard1",
            "/removable/sdcard1",
            "/mnt/ext_sdcard"
        )

        for (pathStr in externalPaths) {
            val path = File(pathStr)
            if (path.exists() && path.canRead() && path.isDirectory) {
                // Check if this path is already in our list
                if (volumes.none { it.path.absolutePath == path.absolutePath }) {
                    volumes.add(StorageInfo(
                        name = "SD Card",
                        path = path,
                        isRemovable = true,
                        isPrimary = false,
                        totalSpace = path.totalSpace,
                        freeSpace = path.freeSpace
                    ))
                }
            }
        }

        // Method 3: Check /storage directory for mounted volumes
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.canRead()) {
            storageDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.canRead() && file.name != "emulated" && file.name != "self") {
                    // Skip if already in list
                    if (volumes.none { it.path.absolutePath == file.absolutePath }) {
                        // Determine if it's likely an SD card or USB
                        val name = when {
                            file.name.contains("sd", ignoreCase = true) -> "SD Card"
                            file.name.contains("usb", ignoreCase = true) -> "USB Storage"
                            else -> file.name
                        }

                        // Only add if it has content (not empty mount point)
                        if ((file.totalSpace > 0)) {
                            volumes.add(StorageInfo(
                                name = name,
                                path = file,
                                isRemovable = true,
                                isPrimary = false,
                                totalSpace = file.totalSpace,
                                freeSpace = file.freeSpace
                            ))
                        }
                    }
                }
            }
        }

        return volumes.distinctBy { it.path.absolutePath }
    }

    /**
     * Get the path for a StorageVolume using reflection (for older APIs)
     */
    private fun getVolumePath(volume: StorageVolume): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory
            } else {
                // Use reflection for older APIs
                val getPath = volume.javaClass.getMethod("getPath")
                val path = getPath.invoke(volume) as? String
                path?.let { File(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get display name for a StorageVolume
     */
    private fun getVolumeName(volume: StorageVolume): String {
        return try {
            val description = volume.getDescription(null)
            when {
                volume.isPrimary -> "Internal Storage"
                description != null -> description
                volume.isRemovable -> "SD Card"
                else -> "External Storage"
            }
        } catch (e: Exception) {
            if (volume.isPrimary) "Internal Storage" else "External Storage"
        }
    }

    /**
     * Get quick access locations (Downloads, DCIM, etc.)
     */
    fun getQuickAccessLocations(): List<StorageInfo> {
        val locations = mutableListOf<StorageInfo>()
        val root = Environment.getExternalStorageDirectory()

        val quickPaths = listOf(
            "Download" to "Downloads",
            "DCIM" to "Camera",
            "Pictures" to "Pictures",
            "Documents" to "Documents",
            "Music" to "Music",
            "Movies" to "Videos"
        )

        for ((folder, name) in quickPaths) {
            val path = File(root, folder)
            if (path.exists() && path.canRead()) {
                locations.add(StorageInfo(
                    name = name,
                    path = path,
                    isRemovable = false,
                    isPrimary = false,
                    totalSpace = path.totalSpace,
                    freeSpace = path.freeSpace
                ))
            }
        }

        return locations
    }
}
