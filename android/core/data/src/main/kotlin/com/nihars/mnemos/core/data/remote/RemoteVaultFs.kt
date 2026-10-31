package com.nihars.mnemos.core.data.remote

import com.nihars.mnemos.core.data.Log
import com.nihars.mnemos.core.text.Versions
import com.nihars.mnemos.core.vaultfs.ConflictFiles
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.vaultfs.VaultPaths
import com.nihars.mnemos.core.vaultfs.WriteOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime

/** What the server-mode status line shows. */
data class RemoteStatus(val online: Boolean = true, val pending: Int = 0, val lastSync: Long = 0, val error: String? = null)

/**
 * The vault on a Mnemos server (Settings → Storage → My Mnemos server).
 *
 * The server stays in charge: versions, conflicts, trash and the demo's read-only rule are
 * checked there, exactly as for the web app. The phone keeps a cache in app-private storage,
 * so opened notes stay readable offline; edits made offline are saved into the cache and
 * queued, then sent with their base version when the server is back. When the note changed on
 * the server meanwhile, the phone's text is saved beside it as "name (conflict …).md" instead
 * of overwriting anything.
 */
class RemoteVaultFs(
    private val client: ServerClient,
    private val home: File, // app-private folder for this account: files/, tree.json, manifest.json, pending.json
    label: String,
) : VaultFs {
    override val rootLabel: String = label
    override val isFast: Boolean = false

    private val files = File(home, "files")
    private val lock = Any()

    // last tree from the server (also kept on disk for offline starts)
    private var tree: Map<String, RemoteNode> = emptyMap()
    private var treeAt = 0L

    // version, size and mtime of each cached file, as they were on the server when fetched
    private val cached = HashMap<String, Meta>()

    // edits waiting for the server: path → base version ("" = new file), kind text/image
    private val pending = LinkedHashMap<String, Pending>()

    private val _status = MutableStateFlow(RemoteStatus())
    val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    private data class Meta(val version: String, val size: Long, val mtime: Long)
    private data class Pending(val base: String, val image: Boolean)

    init {
        files.mkdirs()
        loadState()
    }

    // ---- reading -----------------------------------------------------------------

    override fun list(dir: String): List<VaultEntry> {
        val d = VaultPaths.normalize(dir)
        if (d == VaultPaths.TRASH) return trashEntries()
        ensureTree()
        val out = tree.values.filter { VaultPaths.parent(it.path) == d }.map { it.entry() }.toMutableList()
        // notes made offline that the server has not seen yet
        pending.keys.filter { VaultPaths.parent(it) == d && it !in tree }.forEach { p -> localEntry(p)?.let { out += it } }
        return out
    }

    override fun stat(path: String): VaultEntry? {
        val p = VaultPaths.normalize(path)
        if (p.isEmpty() || p == VaultPaths.TRASH) return VaultEntry(p, VaultPaths.name(p), true, 0, 0, false)
        if (VaultPaths.isInside(p, VaultPaths.TRASH)) return trashEntries().firstOrNull { it.path == p }
        ensureTree()
        return tree[p]?.entry() ?: if (p in pending) localEntry(p) else null
    }

    override fun readBytes(path: String): ByteArray {
        val p = VaultPaths.normalize(path)
        val file = cacheFile(p)
        synchronized(lock) { if (p in pending && file.isFile) return file.readBytes() }
        val node = tree[p]
        val meta = synchronized(lock) { cached[p] }
        // trust the cache when the tree is fresh and still shows the same size and time
        if (meta != null && node != null && file.isFile && fresh() && node.size == meta.size && node.mtime == meta.mtime) return file.readBytes()
        return try {
            val current = online { client.version(p) } ?: throw VaultException.NotFound(p)
            if (meta != null && meta.version == current && file.isFile) return file.readBytes()
            val bytes = online { client.raw(p) }
            store(p, bytes, node)
            bytes
        } catch (e: OfflineException) {
            if (file.isFile) file.readBytes() else throw VaultException.Remote("“$p” isn't on this phone yet and the server can't be reached.")
        }
    }

    override fun readHead(path: String, max: Int): ByteArray {
        val b = readBytes(path)
        return if (b.size <= max) b else b.copyOf(max)
    }

    override fun openChannel(path: String): FileChannel {
        readBytes(path) // makes sure the cached copy is current
        return FileChannel.open(cacheFile(VaultPaths.normalize(path)).toPath(), StandardOpenOption.READ)
    }

    override fun walk(dir: String, includeHidden: Boolean): List<VaultEntry> {
        val d = VaultPaths.normalize(dir)
        ensureTree()
        val inTree = tree.values.filter { VaultPaths.isInside(it.path, d) && it.path != d }.map { it.entry() }
        val local = pending.keys.filter { VaultPaths.isInside(it, d) && it !in tree }.mapNotNull { localEntry(it) }
        return inTree + local
    }

    override fun absolutePath(path: String): String? = null

    // ---- writing -----------------------------------------------------------------

    override fun writeAtomic(path: String, bytes: ByteArray): WriteOutcome {
        val p = VaultPaths.normalize(path)
        val text = utf8(p, bytes)
        val base = synchronized(lock) { cached[p]?.version } ?: tree[p]?.let { runCatching { online { client.version(p) } }.getOrNull() }
        return try {
            val v = if (tree[p] == null && base == null) {
                online { client.create(p, folder = false) }
                if (bytes.isEmpty()) Versions.of(bytes) else online { client.write(p, text, Versions.of(ByteArray(0))) }
            } else {
                online { client.write(p, text, base ?: "") }
            }
            store(p, bytes, null, v)
            synchronized(lock) { pending.remove(p) }
            saveState()
            WriteOutcome.ATOMIC
        } catch (e: OfflineException) {
            queue(p, bytes, base.orEmpty(), image = false)
            WriteOutcome.ATOMIC
        } catch (e: ServerException) {
            if (e.status == 409) throw VaultException.Remote("“$p” was changed on the server meanwhile. Reload it to see the latest text.")
            throw VaultException.Remote(e.message ?: "The server refused the save.")
        }
    }

    override fun createNew(path: String, bytes: ByteArray): WriteOutcome {
        val p = VaultPaths.normalize(path)
        if (stat(p) != null) throw VaultException.AlreadyExists(p)
        val isText = runCatching { utf8(p, bytes) }.isSuccess
        return try {
            if (isText) {
                online { client.create(p, folder = false) }
                val v = if (bytes.isEmpty()) Versions.of(bytes) else online { client.write(p, String(bytes), Versions.of(ByteArray(0))) }
                store(p, bytes, null, v)
            } else {
                online { client.upload(p, bytes) }
                store(p, bytes, null)
            }
            refreshSoon()
            WriteOutcome.ATOMIC
        } catch (e: OfflineException) {
            queue(p, bytes, "", image = !isText)
            WriteOutcome.ATOMIC
        } catch (e: ServerException) {
            if (e.status == 409) throw VaultException.AlreadyExists(p)
            throw VaultException.Remote(e.message ?: "The server refused to create “$p”.")
        }
    }

    override fun mkdirs(dir: String) {
        val d = VaultPaths.normalize(dir)
        if (d.isEmpty() || d.split('/').any { it.startsWith('.') }) return // .trash etc. are the server's business
        try {
            online { client.create(d, folder = true) }
            refreshSoon()
        } catch (e: ServerException) {
            if (e.status != 409) throw VaultException.Remote(e.message ?: "Couldn't create “$d”.")
        } catch (e: OfflineException) {
            // a note queued inside it creates the folder on the server later
        }
    }

    // the server's own Cleanup erases its trash (Settings → Cleanup calls it)
    override fun deleteForever(path: String): Unit = throw VaultException.Remote("The server cleans its own trash — use Settings → Cleanup.")

    override fun move(from: String, to: String) {
        val src = VaultPaths.normalize(from)
        val dst = VaultPaths.normalize(to)
        try {
            when {
                VaultPaths.isInside(src, VaultPaths.TRASH) -> online { client.restore(VaultPaths.name(src), dst) }
                VaultPaths.isInside(dst, VaultPaths.TRASH) -> online { client.trash(src) }
                else -> online { client.move(src, dst) }
            }
        } catch (e: OfflineException) {
            throw VaultException.Remote("You're offline. Moving, renaming and deleting need the server.")
        } catch (e: ServerException) {
            if (e.status == 409) throw VaultException.AlreadyExists(dst)
            throw VaultException.Remote(e.message ?: "The server refused to move “$src”.")
        }
        synchronized(lock) {
            cached.remove(src)
            cacheFile(src).delete()
        }
        saveState()
        refreshSoon()
    }

    // ---- sync ----------------------------------------------------------------------

    /**
     * Sends queued edits, then fetches the tree. Returns the paths that changed on the server
     * since the last look (for the screens and the index). Safe to call often.
     */
    fun sync(): Set<String> {
        flush()
        val before = tree
        refreshTree()
        val changed = HashSet<String>()
        for ((p, n) in tree) {
            val o = before[p]
            if (o == null || o.size != n.size || o.mtime != n.mtime) changed += p
        }
        changed += before.keys - tree.keys
        return if (before.isEmpty()) emptySet() else changed
    }

    /** Sends every queued edit; a note that changed on the server meanwhile gets a conflict copy instead. */
    fun flush() {
        val queue = synchronized(lock) { pending.toList() }
        for ((p, item) in queue) {
            val bytes = cacheFile(p).takeIf { it.isFile }?.readBytes() ?: run { synchronized(lock) { pending.remove(p) }; null } ?: continue
            try {
                if (item.image) {
                    runCatching { online { client.upload(p, bytes) } }.onFailure { if (it !is ServerException || it.status != 409) throw it }
                } else {
                    val now = online { client.version(p) }
                    when {
                        now == null && item.base.isEmpty() -> {
                            online { client.create(p, false) }
                            store(p, bytes, null, online { client.write(p, String(bytes), Versions.of(ByteArray(0))) })
                        }
                        now == item.base -> store(p, bytes, null, online { client.write(p, String(bytes), item.base) })
                        else -> {
                            // changed (or deleted) on the server: keep both, never overwrite
                            val copy = ConflictFiles.copyName(p, LocalDateTime.now()) { tree.containsKey(it) }
                            online { client.create(copy, false) }
                            online { client.write(copy, String(bytes), Versions.of(ByteArray(0))) }
                            synchronized(lock) { cached.remove(p) }
                            cacheFile(p).delete()
                            Log.w("server sync: “$p” changed on the server, saved the phone's text as “$copy”")
                        }
                    }
                }
                synchronized(lock) { pending.remove(p) }
            } catch (e: OfflineException) {
                break // still offline: try again later
            } catch (e: Exception) {
                Log.w("server sync: couldn't send “$p”", e)
                _status.value = _status.value.copy(error = e.message)
                synchronized(lock) { pending.remove(p) } // a refusal (e.g. read-only) won't change by retrying
            }
        }
        saveState()
    }

    /** Fetches the whole tree now (pull to refresh, app resume). */
    fun refreshTree() {
        try {
            val nodes = online { client.tree() }
            synchronized(lock) {
                tree = nodes.associateBy { it.path }
                treeAt = System.currentTimeMillis()
            }
            saveTree(nodes)
            _status.value = _status.value.copy(online = true, lastSync = treeAt, error = null)
        } catch (e: OfflineException) {
            // keep the last known tree
        } catch (e: ServerException) {
            _status.value = _status.value.copy(error = e.message)
            if (e.status == 401) throw VaultException.Remote(e.message ?: "Signed out of the server.")
        }
    }

    /** The server's Cleanup (preview or run); the tree is refetched after a run. Needs the network. */
    fun cleanup(days: Int, run: Boolean): com.nihars.mnemos.core.data.CleanupReport =
        online { client.cleanup(days, run) }.also { if (run) refreshTree() }

    /** How many edits are waiting for the server. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /** Forgets everything cached for this account (after signing out or switching back to the phone's folder). */
    fun clearCache() {
        synchronized(lock) {
            pending.clear()
            cached.clear()
            tree = emptyMap()
        }
        home.deleteRecursively()
    }

    // ---- helpers -------------------------------------------------------------------

    private fun <T> online(block: () -> T): T = try {
        block().also { if (!_status.value.online) _status.value = _status.value.copy(online = true) }
    } catch (e: OfflineException) {
        _status.value = _status.value.copy(online = false)
        throw e
    }

    private fun fresh() = System.currentTimeMillis() - treeAt < TREE_TTL_MS

    private fun ensureTree() {
        if (fresh() || (tree.isNotEmpty() && !_status.value.online && System.currentTimeMillis() - treeAt < OFFLINE_RETRY_MS)) return
        refreshTree()
    }

    private fun refreshSoon() {
        treeAt = 0
    }

    private fun trashEntries(): List<VaultEntry> = try {
        online { client.trashList() }.map { VaultEntry(VaultPaths.join(VaultPaths.TRASH, it.name), it.name, it.dir, it.size, it.mtime, false) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun RemoteNode.entry() = VaultEntry(path, name, dir, size, mtime, false)

    private fun localEntry(p: String): VaultEntry? {
        val f = cacheFile(p)
        return if (f.isFile) VaultEntry(p, VaultPaths.name(p), false, f.length(), f.lastModified(), false) else null
    }

    private fun cacheFile(p: String) = File(files, p)

    /** The server stores text as UTF-8; other encodings can't be saved in server mode. */
    private fun utf8(p: String, bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw VaultException.Remote("“$p” isn't UTF-8 text, so it can't be saved on the server from the phone.")
    }

    private fun store(p: String, bytes: ByteArray, node: RemoteNode?, version: String = Versions.of(bytes)) {
        val f = cacheFile(p)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.part")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        val n = node ?: tree[p]
        synchronized(lock) { cached[p] = Meta(version, n?.size ?: bytes.size.toLong(), n?.mtime ?: 0) }
        saveState()
    }

    private fun queue(p: String, bytes: ByteArray, base: String, image: Boolean) {
        val f = cacheFile(p)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        synchronized(lock) { pending[p] = Pending(base, image) }
        saveState()
        Log.d { "server sync: queued “$p” until the server is reachable" }
    }

    private fun saveState() {
        val state = JSONObject()
        synchronized(lock) {
            state.put("cached", JSONObject().also { o -> cached.forEach { (k, m) -> o.put(k, JSONObject().put("v", m.version).put("s", m.size).put("t", m.mtime)) } })
            state.put("pending", JSONObject().also { o -> pending.forEach { (k, q) -> o.put(k, JSONObject().put("base", q.base).put("image", q.image)) } })
            _status.value = _status.value.copy(pending = pending.size)
        }
        runCatching { atomicText(File(home, "state.json"), state.toString()) }.onFailure { Log.w("server cache state", it) }
    }

    private fun saveTree(nodes: List<RemoteNode>) {
        val o = JSONObject()
        nodes.forEach { n -> o.put(n.path, JSONObject().put("d", n.dir).put("s", n.size).put("t", n.mtime)) }
        runCatching { atomicText(File(home, "tree.json"), o.toString()) }
    }

    private fun loadState() {
        runCatching {
            val s = JSONObject(File(home, "state.json").readText())
            s.optJSONObject("cached")?.let { o -> o.keys().forEach { k -> val m = o.getJSONObject(k); cached[k] = Meta(m.getString("v"), m.getLong("s"), m.getLong("t")) } }
            s.optJSONObject("pending")?.let { o -> o.keys().forEach { k -> val q = o.getJSONObject(k); pending[k] = Pending(q.getString("base"), q.optBoolean("image")) } }
        }
        runCatching {
            val o = JSONObject(File(home, "tree.json").readText())
            tree = o.keys().asSequence().associateWith { k -> val n = o.getJSONObject(k); RemoteNode(k, VaultPaths.name(k), n.getBoolean("d"), n.getLong("s"), n.getLong("t")) }
        }
        _status.value = RemoteStatus(pending = pending.size)
    }

    private fun atomicText(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    private companion object {
        const val TREE_TTL_MS = 15_000L // a tree this young is trusted without asking again
        const val OFFLINE_RETRY_MS = 60_000L // offline: retry the tree at most once a minute
    }
}
