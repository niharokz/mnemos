package com.nihars.mnemos.core.data

import com.nihars.mnemos.core.text.TextDetector
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.vaultfs.VaultPaths

/** Which screen opens a file. Decided by content (text vs binary), then size, then extension. */
enum class FileKind { MARKDOWN, CSV, TEXT, LARGE_TEXT, IMAGE, BINARY }

object FileKinds {
    const val LARGE_TEXT_BYTES = 2L * 1024 * 1024
    const val EDIT_ANYWAY_LIMIT = 10L * 1024 * 1024
    private val IMAGES = setOf("png", "jpg", "jpeg", "webp", "gif", "avif")

    /** Reads at most 64 KB; call off the main thread. */
    fun classify(fs: VaultFs, entry: VaultEntry): FileKind {
        val ext = VaultPaths.ext(entry.name)
        if (ext in IMAGES) return FileKind.IMAGE
        val head = fs.readHead(entry.path, TextDetector.SAMPLE_BYTES)
        if (TextDetector.detect(head) == null) return FileKind.BINARY
        return when {
            entry.size > LARGE_TEXT_BYTES -> FileKind.LARGE_TEXT
            ext == "md" || ext == "markdown" -> FileKind.MARKDOWN
            ext == "csv" || ext == "tsv" -> FileKind.CSV
            else -> FileKind.TEXT
        }
    }

    /** Best guess from the name alone, for icons in the tree (no disk access). */
    fun guess(name: String): FileKind = when (VaultPaths.ext(name)) {
        "md", "markdown" -> FileKind.MARKDOWN
        "csv", "tsv" -> FileKind.CSV
        in IMAGES -> FileKind.IMAGE
        "pdf", "zip", "xlsx", "docx", "pptx", "apk", "mp3", "mp4", "m4a", "ogg", "epub", "7z", "gz" -> FileKind.BINARY
        else -> FileKind.TEXT
    }

    /** Settings key for "remember the last view per file type". */
    fun viewKey(name: String): String = when (guess(name)) {
        FileKind.MARKDOWN -> "md"
        FileKind.CSV -> "csv"
        else -> "text"
    }

    fun mimeType(name: String): String = when (VaultPaths.ext(name)) {
        "md", "markdown" -> "text/markdown"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "svg" -> "image/svg+xml"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "application/octet-stream"
    }
}
