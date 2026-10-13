package com.nihars.mnemos.core.vaultfs

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A Syncthing conflict copy next to its original. */
data class SyncConflict(val conflictPath: String, val originalPath: String, val created: LocalDateTime?, val device: String)

/** Recognises `name.sync-conflict-YYYYMMDD-HHMMSS-XXXXXXX.ext` and names our own "save as copy" files. */
object ConflictFiles {
    private val PATTERN = Regex("""^(.*)\.sync-conflict-(\d{8})-(\d{6})-([A-Za-z0-9]{7})(\.[^.]*)?$""")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(path: String): SyncConflict? {
        val name = VaultPaths.name(path)
        val m = PATTERN.matchEntire(name) ?: return null
        val (base, date, time, device) = m.destructured
        val ext = m.groupValues[5]
        val original = VaultPaths.join(VaultPaths.parent(path), base + ext)
        val created = runCatching { LocalDateTime.parse(date + time, STAMP) }.getOrNull()
        return SyncConflict(path, original, created, device)
    }

    fun isConflict(path: String): Boolean = parse(path) != null

    /** Groups conflict copies by their original path. Copies whose original is gone are kept too. */
    fun group(paths: Collection<String>): Map<String, List<SyncConflict>> =
        paths.mapNotNull { parse(it) }.groupBy { it.originalPath }.mapValues { (_, v) -> v.sortedBy { it.created } }

    /** `<name> (conflict YYYY-MM-DD HHmm).<ext>` beside the original, unique within the folder. */
    fun copyName(path: String, now: LocalDateTime, exists: (String) -> Boolean): String {
        val name = VaultPaths.name(path)
        val stem = VaultPaths.stem(name)
        val ext = if (stem == name) "" else name.substring(stem.length)
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
        val dir = VaultPaths.parent(path)
        val candidate = "$stem (conflict $stamp)$ext"
        return VaultPaths.join(dir, VaultPaths.uniqueName(dir, candidate, exists))
    }
}
