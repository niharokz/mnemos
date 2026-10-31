package com.nihars.mnemos.core.data

import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What Cleanup moved and erased (or would, for a preview). Same shape as the server's report. */
data class CleanupReport(
    val dryRun: Boolean,
    val days: Int,
    val moved: List<String>,
    val deleted: List<String>,
    val freedBytes: Long,
)

/**
 * The server's cleanupSync.sh, on the phone:
 *  1. every Syncthing conflict copy (`*sync-conflict*`) outside hidden folders goes to the trash;
 *  2. trash items older than [days] are erased. Age = when it was trashed (this app's ledger,
 *     then the server's `.trash/.mnemos-trash.tsv`), else the item's modified time.
 * Runs only when asked (Settings → Cleanup).
 */
class VaultCleanup(private val session: VaultSession) {

    suspend fun run(days: Int, dryRun: Boolean, now: Long = System.currentTimeMillis()): CleanupReport = withContext(Dispatchers.IO) {
        require(days >= 1) { "Keep trash for at least 1 day." }
        val fs = session.fs

        // 1. conflict copies → trash
        val moved = fs.walk().filter { !it.isDirectory && it.name.contains("sync-conflict") }.map { it.path }.sorted()
        if (!dryRun) moved.forEach { session.moveToTrash(it) }

        // 2. old trash items → erased
        val serverTimes = serverLedger()
        val cutoff = now - days * DAY_MS
        val deleted = ArrayList<String>()
        var freed = 0L
        for (e in session.trash.list()) {
            val at = session.ledger.trashedAt(e.path) ?: serverTimes[e.name] ?: e.modified
            if (at >= cutoff) continue
            freed += if (e.isDirectory) fs.walk(e.path, includeHidden = true).filter { !it.isDirectory }.sumOf { it.size } else e.size
            if (!dryRun) {
                fs.deleteForever(e.path)
                session.ledger.forget(e.path)
            }
            deleted += e.name
        }
        if (!dryRun && (moved.isNotEmpty() || deleted.isNotEmpty())) session.notifyChanged(VaultPaths.TRASH)
        CleanupReport(dryRun, days, moved, deleted.sorted(), freed)
    }

    // name → trashed-at millis, from a Mnemos server sharing this folder (best effort)
    private fun serverLedger(): Map<String, Long> = runCatching {
        String(session.fs.readBytes("${VaultPaths.TRASH}/$SERVER_LEDGER")).lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            val sec = p.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            p[0] to sec * 1000
        }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val SERVER_LEDGER = ".mnemos-trash.tsv"
    }
}
