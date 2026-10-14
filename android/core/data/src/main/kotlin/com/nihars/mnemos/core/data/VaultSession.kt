package com.nihars.mnemos.core.data

import com.nihars.mnemos.core.index.IndexDb
import com.nihars.mnemos.core.index.IndexScanner
import com.nihars.mnemos.core.index.SearchEngine
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextFormat
import com.nihars.mnemos.core.vaultfs.HiddenRules
import com.nihars.mnemos.core.vaultfs.SaveCoordinator
import com.nihars.mnemos.core.vaultfs.Trash
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class UserKind(val key: String) { OWNER("owner"), DEMO("demo") }

data class ScanProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/**
 * One signed-in user: their vault, index, settings and the vault operations every screen uses.
 * Owner and demo sessions share nothing.
 */
class VaultSession(
    val kind: UserKind,
    val fs: VaultFs,
    val settingsStore: SettingsStore,
    val index: IndexDb,
    filesDir: File,
) {
    /** Unique per sign-in, so screens build fresh ViewModels after a user switch or demo reset. */
    val id: Int = nextId.incrementAndGet()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val saver = SaveCoordinator(fs)
    val trash = Trash(fs)
    val ledger = TrashLedger(File(filesDir, "trash-${kind.key}.tsv"))
    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val hidden: StateFlow<HiddenRules> = settings.map { HiddenRules(it.hiddenPatterns, it.showHidden) }
        .stateIn(scope, SharingStarted.Eagerly, HiddenRules())
    val scanner = IndexScanner(fs, index) { hidden.value }
    val search = SearchEngine(index)
    val watcher = VaultWatcher(fs, scope)

    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 32)

    /** Called after this app moves or renames a file (old path, new path), so widgets can follow it. */
    var onMoved: (String, String) -> Unit = { _, _ -> }

    /** Paths changed by anyone (watcher) or by us (saves, moves). Screens refresh from this. */
    val changes: SharedFlow<Set<String>> = _changes

    private val _scan = MutableStateFlow(ScanProgress())
    val scan: StateFlow<ScanProgress> = _scan.asStateFlow()
    private var scanJob: Job? = null

    init {
        scope.launch {
            watcher.changes.collect { paths ->
                _changes.emit(paths)
                runCatching { scanner.refresh(paths) }
            }
        }
    }

    /** Background index scan; never blocks opening files. A second call while running is ignored. */
    fun rescan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _scan.value = ScanProgress(running = true)
            try {
                val stats = scanner.scan { done, total -> _scan.value = ScanProgress(true, done, total) }
                Log.d { "index: ${stats.total} files, ${stats.changed} changed, ${stats.removed} removed in ${stats.millis} ms" }
                if (stats.changed + stats.removed > 0) _changes.emit(emptySet())
            } catch (e: VaultException) {
                Log.w("index scan stopped", e)
            } finally {
                _scan.value = ScanProgress()
            }
        }
    }

    /** When this app last wrote each path (memory only), so Recent can tell our edits from Syncthing's. */
    val ownWrites = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Tell everyone (tree, recent, index, widgets) that these paths changed. Only our own code calls this. */
    fun notifyChanged(vararg paths: String) {
        val set = paths.toSet()
        val now = System.currentTimeMillis()
        set.forEach { ownWrites[it] = now }
        scope.launch {
            _changes.emit(set)
            runCatching { scanner.refresh(set) }
        }
    }

    /** Creates a note named from the settings template; [body] goes under the title heading. */
    suspend fun createNote(dir: String, title: String, body: String = ""): String = io {
        val s = settings.value
        val date = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val safeTitle = title.trim().ifEmpty { "Untitled" }.replace('/', '-')
        val name = s.newNoteTemplate.replace("{title}", safeTitle).replace("{date}", date).let { if ('.' in it) it else "$it.md" }
        if (dir.isNotEmpty() && !fs.exists(dir)) fs.mkdirs(dir)
        val unique = VaultPaths.uniqueName(dir, name) { fs.exists(it) }
        val path = VaultPaths.join(dir, unique)
        val heading = if (VaultPaths.ext(unique) == "md") "# $safeTitle\n\n" else ""
        val content = heading + if (body.isEmpty()) "" else body.trimEnd() + "\n"
        fs.createNew(path, TextCodec.encode(content, TextFormat.NEW_FILE))
        notifyChanged(path)
        path
    }

    suspend fun createFolder(dir: String, name: String): String = io {
        val path = VaultPaths.join(dir, VaultPaths.uniqueName(dir, name.trim().replace('/', '-')) { fs.exists(it) })
        fs.mkdirs(path)
        notifyChanged(path)
        path
    }

    /** Rename/move; never overwrites. Pins follow the file. */
    suspend fun move(from: String, to: String) {
        io { fs.move(from, to) }
        moved(VaultPaths.normalize(from), VaultPaths.normalize(to))
    }

    private suspend fun moved(from: String, to: String) {
        settingsStore.renamePinned(from, to)
        settingsStore.lastOpen()?.let { last ->
            if (last == from) settingsStore.setLastOpen(to)
            else if (last.startsWith("$from/")) settingsStore.setLastOpen(to + last.substring(from.length))
        }
        runCatching { onMoved(from, to) }
        notifyChanged(from, to)
    }

    /**
     * Renames [from] to [to] (normally the same folder). Never overwrites. A change of letter case
     * only ("idea.md" → "Idea.md") goes through a temporary name, because Android's shared storage
     * ignores case and would report the target as taken.
     */
    suspend fun rename(from: String, to: String) {
        val src = VaultPaths.normalize(from)
        val dst = VaultPaths.normalize(to)
        if (src != dst && src.equals(dst, ignoreCase = true)) {
            val dir = VaultPaths.parent(dst)
            val tmp = VaultPaths.join(dir, VaultPaths.uniqueName(dir, "${VaultPaths.stem(VaultPaths.name(dst))} renaming.tmp") { fs.exists(it) })
            io {
                fs.move(src, tmp)
                try {
                    fs.move(tmp, dst)
                } catch (e: Exception) {
                    runCatching { fs.move(tmp, src) }
                    throw e
                }
            }
            moved(src, dst)
        } else {
            move(src, dst)
        }
    }

    suspend fun duplicate(path: String): String = io {
        val dir = VaultPaths.parent(path)
        val name = VaultPaths.name(path)
        val copy = VaultPaths.join(dir, VaultPaths.uniqueName(dir, name) { fs.exists(it) })
        fs.createNew(copy, fs.readBytes(path))
        notifyChanged(copy)
        copy
    }

    suspend fun moveToTrash(path: String): String {
        val dst = io { trash.moveToTrash(path) }
        ledger.record(dst, VaultPaths.normalize(path))
        val pins = settingsStore.pins.first()
        if (path in pins) settingsStore.setPins(pins - path)
        notifyChanged(path, dst)
        return dst
    }

    /** Restores to [target] (default: where it came from). Throws AlreadyExists if that spot is taken. */
    suspend fun restore(trashPath: String, target: String? = null): String {
        val dst = target ?: ledger.originalOf(trashPath) ?: VaultPaths.name(trashPath)
        io { trash.restore(trashPath, dst) }
        ledger.forget(trashPath)
        notifyChanged(trashPath, dst)
        return dst
    }

    /** Appends a captured line to the capture note (see [Capture]). */
    suspend fun capture(text: String, now: LocalDateTime = LocalDateTime.now()): String {
        val path = Capture.append(fs, saver, settings.value, text, now)
        notifyChanged(path)
        return path
    }

    fun close() {
        watcher.stop()
        scope.cancel()
        index.close()
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        val nextId = java.util.concurrent.atomic.AtomicInteger()
    }
}
