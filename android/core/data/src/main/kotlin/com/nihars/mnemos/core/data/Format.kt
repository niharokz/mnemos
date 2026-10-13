package com.nihars.mnemos.core.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small display helpers shared by the tree, recent, search and viewers. */
object Format {
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0) return ""
        val d = (now - millis) / 1000
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86_400 -> "${d / 3600} h ago"
            d < 7 * 86_400 -> "${d / 86_400} d ago"
            else -> DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        }
    }

    fun dateTime(millis: Long): String = DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
}
