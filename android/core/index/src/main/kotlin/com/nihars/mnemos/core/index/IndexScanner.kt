package com.nihars.mnemos.core.index

import com.nihars.mnemos.core.markdown.FrontMatter
import com.nihars.mnemos.core.markdown.Tags
import com.nihars.mnemos.core.text.NotTextException
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextDetector
import com.nihars.mnemos.core.vaultfs.HiddenRules
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanStats(val total: Int, val changed: Int, val removed: Int, val millis: Long)

/** Keeps [IndexDb] in step with the vault by comparing mtime and size; only changed files are read. */
class IndexScanner(
    private val fs: VaultFs,
    private val db: IndexDb,
    private val hidden: () -> HiddenRules,
) {
    // Bounded so a scan never floods the disk while the user is opening files.
    private val io = Dispatchers.IO.limitedParallelism(4)

    suspend fun scan(progress: (done: Int, total: Int) -> Unit = { _, _ -> }): ScanStats = withContext(io) {
        val started = System.currentTimeMillis()
        val rules = hidden()
        val files = fs.walk().filter { !it.isDirectory && !rules.isHiddenPath(it.path) }
        val known = db.signatures()
        val changed = files.filter { f -> known[f.path]?.let { it.first != f.modified || it.second != f.size } ?: true }
        val present = files.mapTo(HashSet()) { it.path }
        val removed = known.keys.filter { it !in present }
        db.transaction { removed.forEach(db::remove) }
        var done = 0
        changed.chunked(BATCH).forEach { batch ->
            ensureActive()
            val read = coroutineScope { batch.map { e -> async { read(e) } }.awaitAll() }
            db.transaction { read.forEach { (file, body) -> db.upsert(file, body) } }
            done += batch.size
            progress(done, changed.size)
        }
        ScanStats(files.size, changed.size, removed.size, System.currentTimeMillis() - started)
    }

    /** Re-indexes specific paths after an observer event; missing paths are removed. */
    suspend fun refresh(paths: Collection<String>) = withContext(io) {
        val rules = hidden()
        db.transaction {
            for (p in paths) {
                val e = runCatching { fs.stat(p) }.getOrNull()
                if (e == null || e.isDirectory || rules.isHiddenPath(p)) db.remove(p) else read(e).let { (f, b) -> db.upsert(f, b) }
            }
        }
    }

    private fun read(e: VaultEntry): Pair<IndexedFile, String?> {
        val ext = VaultPaths.ext(e.name)
        val base = IndexedFile(e.path, e.name, ext, e.size, e.modified, isText = false, title = null, tags = emptyList(), properties = "")
        return try {
            val head = fs.readHead(e.path, TextDetector.SAMPLE_BYTES)
            if (TextDetector.detect(head) == null) return base to null
            if (e.size > MAX_INDEXED_BYTES) return base.copy(isText = true) to null
            val text = TextCodec.decode(fs.readBytes(e.path)).text
            val (fm, body) = FrontMatter.split(text)
            val isMd = ext == "md" || ext == "markdown"
            val title = if (isMd) HEADING.find(body)?.groupValues?.get(1)?.trim() else null
            val tags = if (isMd) (fm?.tags.orEmpty().map { it.lowercase() } + Tags.extract(body)).distinct() else emptyList()
            val props = fm?.properties?.joinToString("\n", postfix = "\n") { (k, v) -> "${k.lowercase()}=${v.lowercase()}" } ?: ""
            base.copy(isText = true, title = title, tags = tags, properties = props) to text
        } catch (_: NotTextException) {
            base to null
        } catch (_: VaultException) {
            base to null
        }
    }

    private companion object {
        const val BATCH = 200
        const val MAX_INDEXED_BYTES = 2L * 1024 * 1024
        val HEADING = Regex("""(?m)^#\s+(.+)$""")
    }
}
