package com.decloud.model

import java.io.File

/**
 * Represents a file or folder in the file explorer
 */
data class FileItem(
    val file: File,
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val extension: String,
    val mimeType: String,
    // Used for backup mode to specify the storage prefix (e.g., "Internal Storage" or "External Storage")
    val backupPrefix: String = ""
) {
    companion object {
        fun fromFile(file: File): FileItem {
            val extension = file.extension.lowercase()
            return FileItem(
                file = file,
                name = file.name,
                path = file.absolutePath,
                size = if (file.isDirectory) 0 else file.length(),
                lastModified = file.lastModified(),
                isDirectory = file.isDirectory,
                extension = extension,
                mimeType = getMimeType(extension)
            )
        }

        fun formatFileSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }

        private fun getMimeType(extension: String): String {
            return when (extension) {
                // Images
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "svg" -> "image/svg+xml"

                // Videos
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "wmv" -> "video/x-ms-wmv"
                "flv" -> "video/x-flv"
                "webm" -> "video/webm"
                "3gp" -> "video/3gpp"

                // Audio
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "aac" -> "audio/aac"
                "m4a" -> "audio/mp4"

                // Documents
                "pdf" -> "application/pdf"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "txt" -> "text/plain"
                "csv" -> "text/csv"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"

                // Archives
                "zip" -> "application/zip"
                "rar" -> "application/x-rar-compressed"
                "7z" -> "application/x-7z-compressed"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"

                // APK
                "apk" -> "application/vnd.android.package-archive"

                else -> "application/octet-stream"
            }
        }
    }

    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
    val isDocument: Boolean get() = mimeType.startsWith("application/") || mimeType.startsWith("text/")

    val fileType: FileType get() = when {
        isDirectory -> FileType.FOLDER
        isImage -> FileType.IMAGE
        isVideo -> FileType.VIDEO
        isAudio -> FileType.AUDIO
        else -> FileType.DOCUMENT
    }

    /**
     * Get human-readable size string
     */
    fun getFormattedSize(): String {
        if (isDirectory) return ""
        return formatFileSize(size)
    }
}

enum class FileType {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT
}

enum class SortType {
    NAME_ASC,
    NAME_DESC,
    SIZE_ASC,
    SIZE_DESC,
    DATE_ASC,
    DATE_DESC
}

enum class FilterType {
    ALL,
    IMAGES,
    VIDEOS,
    DOCUMENTS,
    AUDIO
}
