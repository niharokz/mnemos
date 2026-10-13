package com.nihars.mnemos.feature.tree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.data.FileKind
import com.nihars.mnemos.core.data.FileKinds
import com.nihars.mnemos.core.data.HomeView
import com.nihars.mnemos.core.data.UserKind
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.vaultfs.ConflictFiles
import com.nihars.mnemos.core.vaultfs.TreeSort
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultPaths
import com.nihars.mnemos.core.vaultfs.sortedForTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The file tree: lazily loaded folders, pins, filter, multi-select and every row action. */
class TreeViewModel(private val session: VaultSession) : ViewModel() {
    private val _state = MutableStateFlow(TreeState(isDemo = session.kind == UserKind.DEMO))
    val state: StateFlow<TreeState> = _state.asStateFlow()

    private val children = ConcurrentHashMap<String, List<VaultEntry>>()
    private val expanded = LinkedHashSet<String>()
    private var pins: List<String> = emptyList()
    private var filterResults: List<VaultEntry> = emptyList()
    private var filterJob: Job? = null

    init {
        viewModelScope.launch { session.scan.collect { s -> _state.update { it.copy(scan = s) } } }
        viewModelScope.launch {
            session.settings.collect { s ->
                val changed = s.treeSort != _state.value.sort || s.homeView != _state.value.view
                _state.update { it.copy(sort = s.treeSort, view = s.homeView, defaultFolder = s.newNoteFolder) }
                if (changed) {
                    rebuild()
                    loadPreviews()
                }
            }
        }
        viewModelScope.launch {
            session.settingsStore.pins.collect { p ->
                pins = p
                rebuild()
            }
        }
        viewModelScope.launch {
            session.hidden.collect {
                children.clear()
                load("")
            }
        }
        // Outside changes: reload only folders we have loaded that contain a changed path.
        viewModelScope.launch {
            session.changes.collect { paths ->
                val dirs = if (paths.isEmpty()) children.keys.toSet() else paths.map { VaultPaths.parent(it) }.filter { children.containsKey(it) }.toSet()
                dirs.forEach { reloadDir(it) }
                // Changed notes get fresh Box previews.
                if (paths.isNotEmpty()) _state.update { it.copy(previews = it.previews - paths) }
                rebuild()
                loadPreviews()
            }
        }
        viewModelScope.launch { _state.update { it.copy(templates = findTemplates()) } }
    }

    fun toggle(dir: String) {
        if (dir in expanded) {
            expanded.remove(dir)
            rebuild()
        } else {
            expanded += dir
            viewModelScope.launch { load(dir) }
        }
        session.watcher.watch(expanded)
    }

    fun setFilter(text: String) {
        _state.update { it.copy(filter = text) }
        filterJob?.cancel()
        if (text.isBlank()) {
            filterResults = emptyList()
            rebuild()
            return
        }
        // Matches come from the index, read off the main thread.
        filterJob = viewModelScope.launch {
            val words = text.lowercase().split(' ').filter { it.isNotBlank() }
            filterResults = withContext(Dispatchers.IO) {
                runCatching { session.index.allFiles() }.getOrDefault(emptyList()).asSequence()
                    .filter { f -> words.all { w -> f.name.lowercase().contains(w) } && !session.hidden.value.isHiddenPath(f.path) }
                    .take(300)
                    .map { f -> VaultEntry(f.path, f.name, false, f.size, f.mtime, false) }
                    .toList()
            }
            rebuild()
        }
    }

    fun setSort(sort: TreeSort) = viewModelScope.launch { session.settingsStore.update { it.copy(treeSort = sort) } }

    fun setView(view: HomeView) = viewModelScope.launch { session.settingsStore.update { it.copy(homeView = view) } }

    /** Grid, Box and Tabs show one folder at a time. */
    fun openDir(dir: String) {
        _state.update { it.copy(currentDir = VaultPaths.normalize(dir)) }
        viewModelScope.launch {
            load(_state.value.currentDir)
            loadPreviews()
        }
        session.watcher.watch(expanded + _state.value.currentDir)
    }

    /** Tabs view: "" is All (vault root), otherwise a top-level folder. */
    fun selectTab(folder: String) {
        _state.update { it.copy(tab = folder) }
        openDir(folder)
    }

    /** Back in a folder-at-a-time view; false when already at the top. */
    fun up(): Boolean {
        val s = _state.value
        val top = if (s.view == HomeView.TABS) s.tab else ""
        if (s.currentDir == top || s.currentDir.isEmpty()) return false
        openDir(VaultPaths.parent(s.currentDir))
        return true
    }

    /** The + button: a new note in the default folder, opened straight away. */
    fun quickNote(onCreated: (String) -> Unit) = act {
        onCreated(session.createNote(session.settings.value.newNoteFolder, "Untitled"))
    }

    /** Pull to refresh: re-list visible folders and rescan the index for changes. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            (listOf("") + expanded).forEach { reloadDir(it) }
            rebuild()
            session.rescan()
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun togglePin(path: String) = viewModelScope.launch { session.settingsStore.togglePin(path) }

    fun select(path: String) = _state.update { s -> s.copy(selection = if (path in s.selection) s.selection - path else s.selection + path) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /** Files only: folders can't be removed from the home screen (Manage folders does that). */
    fun trash(paths: Collection<String>) = act {
        val files = paths.filter { !isDir(it) }
        if (files.size < paths.size) _state.update { it.copy(message = "Folders are removed from Manage folders, not here.") }
        if (files.isEmpty()) {
            _state.update { it.copy(selection = emptySet()) }
            return@act
        }
        val done = files.map { p -> session.moveToTrash(p) to p }
        _state.update {
            it.copy(
                selection = emptySet(),
                undo = UndoAction.Trashed(done, if (done.size == 1) "Moved “${VaultPaths.name(done[0].second)}” to trash" else "Moved ${done.size} files to trash"),
            )
        }
    }

    private suspend fun isDir(path: String): Boolean =
        children[VaultPaths.parent(path)]?.firstOrNull { it.path == path }?.isDirectory
            ?: withContext(Dispatchers.IO) { runCatching { session.fs.stat(path)?.isDirectory == true }.getOrDefault(false) }

    fun undo() = act {
        when (val u = _state.value.undo) {
            is UndoAction.Trashed -> u.items.forEach { (trashPath, original) -> session.restore(trashPath, original) }
            null -> Unit
        }
        _state.update { it.copy(undo = null) }
    }

    fun undoShown() = _state.update { it.copy(undo = null) }

    fun move(from: String, to: String) = act {
        session.move(from, to)
        if (from in expanded) {
            expanded.remove(from)
            expanded += VaultPaths.normalize(to)
        }
    }

    /** Multi-select move into [folder]; names are kept, clashes are reported and skipped. */
    fun moveSelection(folder: String) = act {
        for (p in _state.value.selection) session.move(p, VaultPaths.join(VaultPaths.normalize(folder), VaultPaths.name(p)))
        _state.update { it.copy(selection = emptySet()) }
    }

    fun pinSelection() = viewModelScope.launch {
        session.settingsStore.setPins(pins + _state.value.selection)
        clearSelection()
    }

    fun duplicate(path: String) = act { session.duplicate(path) }

    fun newNote(dir: String, title: String, onCreated: (String) -> Unit) = act { onCreated(session.createNote(dir, title)) }

    fun newFolder(dir: String, name: String) = act {
        val path = session.createFolder(dir, name)
        expanded += VaultPaths.parent(path)
    }

    /** Copies a template's text into a new note; templates are any files in a top-level `templates/` folder. */
    fun newFromTemplate(template: String, title: String, onCreated: (String) -> Unit) = act {
        val dir = session.settings.value.newNoteFolder
        val ext = VaultPaths.ext(template).ifEmpty { "md" }
        val name = VaultPaths.uniqueName(dir, "${title.ifBlank { "Untitled" }}.$ext") { session.fs.exists(it) }
        val path = VaultPaths.join(dir, name)
        withContext(Dispatchers.IO) {
            if (dir.isNotEmpty() && !session.fs.exists(dir)) session.fs.mkdirs(dir)
            session.fs.createNew(path, session.fs.readBytes(template))
        }
        session.notifyChanged(path)
        onCreated(path)
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: VaultException) {
            _state.update { it.copy(message = e.message) }
        } catch (e: IllegalStateException) {
            _state.update { it.copy(message = e.message) }
        }
    }

    private suspend fun load(dir: String) {
        reloadDir(dir)
        rebuild()
        _state.update { it.copy(loading = false) }
    }

    private suspend fun reloadDir(dir: String) {
        val list = withContext(Dispatchers.IO) {
            try {
                session.fs.list(dir)
            } catch (e: VaultException) {
                _state.update { it.copy(message = e.message) }
                emptyList()
            }
        }
        children[dir] = list.filter { !session.hidden.value.isHidden(it.name) }
    }

    // Box view cards: the first few readable lines of each text file in the current folder.
    private fun loadPreviews() {
        val s = _state.value
        if (s.view != HomeView.BOX) return
        val wanted = s.browse.filter { !it.entry.isDirectory && it.kind != FileKind.BINARY && it.kind != FileKind.IMAGE && it.entry.path !in s.previews }
            .take(MAX_PREVIEWS).map { it.entry.path }
        if (wanted.isEmpty()) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                wanted.associateWith { p -> runCatching { Previews.of(session.fs.readHead(p, 2048)) }.getOrDefault("") }
            }
            _state.update { it.copy(previews = it.previews + found) }
        }
    }

    private suspend fun findTemplates(): List<String> = withContext(Dispatchers.IO) {
        listOf("templates", "Templates").firstOrNull { session.fs.stat(it)?.isDirectory == true }
            ?.let { dir -> runCatching { session.fs.list(dir) }.getOrDefault(emptyList()).filter { !it.isDirectory && !it.name.startsWith('.') }.map { it.path } }
            ?: emptyList()
    }

    // Flattens the expanded tree (or the filter results) into rows; cheap enough to redo on every change.
    private fun rebuild() {
        val s = _state.value
        val pinSet = pins.toSet()
        val rows = ArrayList<TreeRow>()
        if (s.filter.isNotBlank()) {
            filterResults.sortedForTree(s.sort).forEachIndexed { i, e -> rows += row(e, 0, pinSet, emptySet(), i) }
        } else {
            fun walk(dir: String, depth: Int) {
                val list = children[dir]?.sortedForTree(s.sort) ?: return
                val conflicts = ConflictFiles.group(list.map { it.path })
                val conflicted = conflicts.keys + conflicts.values.flatten().map { it.conflictPath }
                list.forEachIndexed { i, e ->
                    rows += row(e, depth, pinSet, conflicted, i)
                    if (e.isDirectory && e.path in expanded) walk(e.path, depth + 1)
                }
            }
            walk("", 0)
        }
        val pinned = pins.mapNotNull { p ->
            children[VaultPaths.parent(p)]?.firstOrNull { it.path == p } ?: VaultEntry(p, VaultPaths.name(p), false, 0, 0, false)
        }
        // Folder-at-a-time views: children of the current folder only.
        val dirList = children[s.currentDir]?.sortedForTree(s.sort).orEmpty()
        val dirConflicts = ConflictFiles.group(dirList.map { it.path }).let { g -> g.keys + g.values.flatten().map { it.conflictPath } }
        val browse = dirList.mapIndexed { i, e -> row(e, 0, pinSet, dirConflicts, i) }
        val top = children[""].orEmpty().filter { it.isDirectory }.sortedForTree(TreeSort.NAME).map { it.path }
        _state.update { it.copy(rows = rows, pinned = pinned, browse = browse, topFolders = top) }
    }

    private fun row(e: VaultEntry, depth: Int, pins: Set<String>, conflicted: Set<String>, i: Int) = TreeRow(
        entry = e,
        depth = depth,
        expanded = e.path in expanded,
        kind = FileKinds.guess(e.name),
        pinned = e.path in pins,
        conflict = e.path in conflicted,
        siblingIndex = i,
    )
}

private const val MAX_PREVIEWS = 60

/** The trash screen: list, restore to the original path (or ask), never delete. */
class TrashViewModel(private val session: VaultSession) : ViewModel() {
    private val _state = MutableStateFlow(TrashState())
    val state: StateFlow<TrashState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch { session.changes.collect { reload() } }
    }

    fun reload() = viewModelScope.launch {
        val rows = withContext(Dispatchers.IO) {
            runCatching { session.trash.list() }.getOrDefault(emptyList()).map { TrashRow(it, session.ledger.originalOf(it.path)) }
        }
        _state.update { it.copy(rows = rows, loading = false) }
    }

    fun restore(row: TrashRow) = viewModelScope.launch {
        val target = row.original
        val free = target != null && withContext(Dispatchers.IO) { !session.fs.exists(target) }
        if (!free) {
            _state.update { it.copy(askPathFor = row) }
            return@launch
        }
        restoreTo(row, target)
    }

    fun restoreTo(row: TrashRow, target: String) = viewModelScope.launch {
        try {
            session.restore(row.entry.path, target)
            _state.update { it.copy(askPathFor = null, message = "Restored to “$target”") }
        } catch (e: VaultException) {
            _state.update { it.copy(message = e.message) }
        }
    }

    fun dismissAsk() = _state.update { it.copy(askPathFor = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    /** Absolute folder path for "Open folder" (null on the SAF path). */
    suspend fun trashFolder(): String? = withContext(Dispatchers.IO) { runCatching { session.fs.absolutePath(VaultPaths.TRASH) }.getOrNull() }
}
