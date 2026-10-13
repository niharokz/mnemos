package com.nihars.mnemos.core.index

import android.database.sqlite.SQLiteException
import com.nihars.mnemos.core.vaultfs.VaultPaths

enum class HitKind { NAME, CONTENT, TAG, PROPERTY }

/** One search result. [snippet] marks matches with \u0002…\u0003 for the UI to highlight. */
data class SearchHit(
    val path: String,
    val name: String,
    val kind: HitKind,
    val snippet: String?,
    val matches: Int,
    val score: Int,
    val mtime: Long,
)

data class SearchFilters(
    val folder: String = "",
    val extensions: Set<String> = emptySet(),
    val modifiedAfter: Long? = null,
)

/** One box searches names (fuzzy), full text, `#tags` and `key: value` front matter. */
class SearchEngine(private val db: IndexDb) {
    fun search(raw: String, filters: SearchFilters = SearchFilters(), limit: Int = 60): List<SearchHit> {
        val q = raw.trim()
        if (q.isEmpty()) return emptyList()
        val files = db.allFiles().filter { passes(it, filters) }
        val byPath = files.associateBy { it.path }
        val hits = LinkedHashMap<String, SearchHit>()
        fun add(h: SearchHit) {
            val prev = hits[h.path]
            if (prev == null || h.score > prev.score) hits[h.path] = h
        }

        PROPERTY.matchEntire(q)?.let { m ->
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim().lowercase()
            files.filter { f -> f.properties.lineSequence().any { it.startsWith("$key=") && it.substringAfter('=').contains(value) } }
                .forEach { add(SearchHit(it.path, it.name, HitKind.PROPERTY, "$key: ${it.properties.lineSequence().first { l -> l.startsWith("$key=") }.substringAfter('=')}", 1, 900, it.mtime)) }
            return hits.values.sortedByDescending { it.score }.take(limit)
        }

        val tags = q.split(' ').filter { it.startsWith('#') && it.length > 1 }.map { it.substring(1).lowercase() }
        val words = q.split(' ').filter { it.isNotBlank() && !it.startsWith('#') }
        if (tags.isNotEmpty()) {
            files.filter { f -> tags.all { t -> f.tags.any { it == t || it.startsWith("$t/") } } }
                .forEach { add(SearchHit(it.path, it.name, HitKind.TAG, it.tags.joinToString(" ") { t -> "#$t" }, 1, 800, it.mtime)) }
            if (words.isEmpty()) return hits.values.sortedByDescending { it.mtime }.take(limit)
        }

        val text = words.joinToString(" ")
        for (f in files) {
            val s = Fuzzy.score(text, f.name)
            if (s > 0) add(SearchHit(f.path, f.name, HitKind.NAME, f.title, 1, 1000 + s, f.mtime))
        }
        for ((path, snippet, count) in fullText(words)) {
            val f = byPath[path] ?: continue
            if (tags.isNotEmpty() && !tags.all { t -> f.tags.contains(t) }) continue
            val prev = hits[path]
            if (prev?.kind == HitKind.NAME) {
                hits[path] = prev.copy(snippet = snippet, matches = count)
            } else {
                add(SearchHit(path, f.name, HitKind.CONTENT, snippet, count, 100 + minOf(count, 50), f.mtime))
            }
        }
        return hits.values.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.mtime }).take(limit)
    }

    private fun passes(f: IndexedFile, filters: SearchFilters): Boolean =
        VaultPaths.isInside(f.path, filters.folder) &&
            (filters.extensions.isEmpty() || f.ext in filters.extensions) &&
            (filters.modifiedAfter == null || f.mtime >= filters.modifiedAfter)

    // Every word as a prefix term, all required.
    private fun fullText(words: List<String>): List<Triple<String, String, Int>> {
        val terms = words.map { w -> w.filter { it.isLetterOrDigit() || it == '_' } }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" ") { "\"$it\"*" }
        val sql = if (db.ftsKind == "fts5") {
            "SELECT path, snippet(content, 1, char(2), char(3), '…', 12), body FROM content WHERE content MATCH ? LIMIT 60"
        } else {
            "SELECT path, snippet(content, char(2), char(3), '…', 1, 12), body FROM content WHERE content MATCH ? LIMIT 60"
        }
        val out = ArrayList<Triple<String, String, Int>>()
        try {
            db.readableDatabase.rawQuery(sql, arrayOf(match)).use { c ->
                while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), countMatches(c.getString(2), terms))
            }
        } catch (_: SQLiteException) {
            // A query FTS can't parse just gives no content hits; names still match.
        }
        return out
    }

    private fun countMatches(body: String, terms: List<String>): Int {
        val lower = body.lowercase()
        var n = 0
        for (t in terms) {
            var i = lower.indexOf(t.lowercase())
            while (i >= 0 && n < 999) {
                n++
                i = lower.indexOf(t.lowercase(), i + t.length)
            }
        }
        return n
    }

    private companion object {
        val PROPERTY = Regex("""^([A-Za-z0-9_-]+):\s*(.+)$""")
    }
}
