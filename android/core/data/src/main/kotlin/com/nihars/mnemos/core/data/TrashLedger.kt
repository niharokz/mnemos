package com.nihars.mnemos.core.data

import java.io.File

/**
 * Remembers where trashed files came from (so Restore can put them back) and when they were
 * trashed (so Cleanup can age them). Kept app-private: the vault's `.trash/` stays plain
 * (Obsidian-compatible) and gets no sidecar files. One line per item:
 * `trashPath \t original \t trashedAtMillis` (older two-column lines have no time).
 */
class TrashLedger(private val file: File) {
    private data class Row(val original: String, val at: Long?)

    @Synchronized
    fun record(trashPath: String, original: String, at: Long = System.currentTimeMillis()) {
        val map = read().toMutableMap()
        map[trashPath] = Row(original, at)
        save(map)
    }

    @Synchronized
    fun originalOf(trashPath: String): String? = read()[trashPath]?.original

    /** When [trashPath] was trashed by this app, or null when unknown. */
    @Synchronized
    fun trashedAt(trashPath: String): Long? = read()[trashPath]?.at

    @Synchronized
    fun forget(trashPath: String) {
        val map = read().toMutableMap()
        if (map.remove(trashPath) != null) save(map)
    }

    private fun read(): Map<String, Row> {
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0].isEmpty()) null else parts[0] to Row(parts[1], parts.getOrNull(2)?.toLongOrNull())
        }.toMap()
    }

    private fun save(map: Map<String, Row>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(map.entries.joinToString("\n") { (k, r) -> if (r.at == null) "$k\t${r.original}" else "$k\t${r.original}\t${r.at}" })
        tmp.renameTo(file)
    }
}
