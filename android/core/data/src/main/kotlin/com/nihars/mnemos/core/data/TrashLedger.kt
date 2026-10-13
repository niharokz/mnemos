package com.nihars.mnemos.core.data

import java.io.File

/**
 * Remembers where trashed files came from, so Restore can put them back. Kept app-private:
 * the vault's `.trash/` stays plain (Obsidian-compatible) and gets no sidecar files.
 */
class TrashLedger(private val file: File) {
    @Synchronized
    fun record(trashPath: String, original: String) {
        val map = read().toMutableMap()
        map[trashPath] = original
        save(map)
    }

    @Synchronized
    fun originalOf(trashPath: String): String? = read()[trashPath]

    @Synchronized
    fun forget(trashPath: String) {
        val map = read().toMutableMap()
        if (map.remove(trashPath) != null) save(map)
    }

    private fun read(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
    }

    private fun save(map: Map<String, String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(map.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        tmp.renameTo(file)
    }
}
