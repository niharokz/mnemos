package com.nihars.mnemos.feature.editor

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.data.Attachments
import com.nihars.mnemos.core.data.FileKinds
import com.nihars.mnemos.core.data.Log
import com.nihars.mnemos.core.data.ViewMode
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.design.SaveStatus
import com.nihars.mnemos.core.markdown.MarkdownParser
import com.nihars.mnemos.core.markdown.TaskToggle
import com.nihars.mnemos.core.security.LockState
import com.nihars.mnemos.core.text.DecodedText
import com.nihars.mnemos.core.text.NotTextException
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextFormat
import com.nihars.mnemos.core.text.Versions
import com.nihars.mnemos.core.vaultfs.ConflictFiles
import com.nihars.mnemos.core.vaultfs.SaveResult
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * One open text file. The EditText owns the live text; this keeps the on-disk version,
 * decides when to save, and applies the web app's conflict rules (spec §6).
 */
class EditorViewModel(
    private val session: VaultSession,
    val path: String,
    lockState: StateFlow<LockState>,
    private val editAnyway: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(
        EditorState(
            path = path,
            language = Language.forExtension(VaultPaths.ext(path)),
            isMarkdown = FileKinds.viewKey(VaultPaths.name(path)) == "md",
            isCsv = FileKinds.viewKey(VaultPaths.name(path)) == "csv",
        ),
    )
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<EditorCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<EditorCommand> = _commands

    val history = EditHistory()
    private val parser = MarkdownParser()

    // Disk state the editor is based on.
    private var format: TextFormat = TextFormat.NEW_FILE
    private var baseVersion: String? = null
    private var savedText: String = ""

    // Live text from the EditText, read only when saving or parsing.
    private var live: CharSequence? = null
    private var editCounter = 0L
    private var savedCounter = 0L
    private var autosaveJob: Job? = null
    private var previewJob: Job? = null
    private var findJob: Job? = null
    private var matches: List<IntRange> = emptyList()

    /** The text the EditText should show after (re)creation, e.g. after rotation. */
    var initialText: String? = null
        private set

    init {
        load()
        viewModelScope.launch {
            val mode = session.settingsStore.viewMode(FileKinds.viewKey(VaultPaths.name(path)))
            if (mode != null && _state.value.isMarkdown) _state.update { it.copy(view = mode) }
        }
        // Outside changes reported by the watcher or our own index refresh.
        viewModelScope.launch {
            session.changes.collect { paths -> if (paths.isEmpty() || VaultPaths.normalize(path) in paths) checkOutside() }
        }
        // Autosave on lock (spec §6.2).
        viewModelScope.launch { lockState.collect { if (it == LockState.LOCKED) flush() } }
        // 20 s outside-change check while the screen exists; paused by the screen when hidden.
        viewModelScope.launch {
            while (isActive) {
                delay(20_000)
                if (visible) checkOutside()
            }
        }
    }

    var visible = true

    private fun isDirty() = editCounter != savedCounter

    private fun load() = viewModelScope.launch {
        try {
            val (bytes, entry) = withContext(Dispatchers.IO) { session.fs.readBytes(path) to session.fs.stat(path) }
            val decoded = withContext(Dispatchers.Default) { TextCodec.decode(bytes) }
            format = decoded.format
            baseVersion = Versions.of(bytes)
            savedText = decoded.text
            initialText = decoded.text
            val big = bytes.size > FileKinds.LARGE_TEXT_BYTES && editAnyway
            _state.update {
                it.copy(
                    loading = false,
                    readOnly = entry?.readOnly == true || (bytes.size > FileKinds.EDIT_ANYWAY_LIMIT),
                    status = if (entry?.readOnly == true) SaveStatus.READ_ONLY else SaveStatus.SAVED,
                    mixedEndings = decoded.format.mixedEndings,
                    largeWarning = big,
                )
            }
            _commands.emit(EditorCommand.SetText(decoded.text, keepSelection = false))
            withContext(Dispatchers.IO) { runCatching { session.index.markOpened(path, System.currentTimeMillis()) } }
            session.settingsStore.setLastOpen(path)
            parsePreview(decoded.text)
        } catch (e: VaultException) {
            _state.update { it.copy(loading = false, error = e.message) }
        } catch (e: NotTextException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    /** Called by the EditText on every user edit. */
    fun onEdited(text: CharSequence) {
        live = text
        editCounter++
        _state.update { it.copy(status = if (it.blocker != null) SaveStatus.CONFLICT else SaveStatus.EDITED, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleAutosave()
        if (_state.value.isMarkdown && _state.value.view != ViewMode.EDIT) schedulePreview()
        if (_state.value.find.open && _state.value.find.query.isNotEmpty()) scheduleFind()
    }

    fun attach(text: CharSequence) {
        live = text
    }

    /** The editor's current text (for the diff view). */
    fun currentText(): String = live?.toString() ?: savedText

    private fun scheduleAutosave() {
        if (renaming) return
        if (_state.value.blocker != null || _state.value.readOnly) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(session.settings.value.autosaveMs)
            save()
        }
    }

    /** True from the start of a rename until the screen moves to the new path; stops "missing file" checks. */
    private var renaming = false

    /**
     * Renames the open file to [input] (name without extension; same folder, same extension).
     * Unsaved edits are saved first under the old name, then the file is moved; never overwrites.
     * [onRenamed] gets the new path so the screen can reopen there.
     */
    fun rename(input: String, onRenamed: (String) -> Unit) {
        if (renaming) return
        val target = when (val r = FileRename.target(path, input)) {
            FileRename.Result.Same -> return
            is FileRename.Result.Invalid -> {
                _state.update { it.copy(error = r.reason) }
                return
            }
            is FileRename.Result.Ok -> r.path
        }
        val s = _state.value
        if (s.readOnly || s.loading) {
            _state.update { it.copy(error = "This file can't be renamed right now.") }
            return
        }
        if (s.blocker != null) {
            _state.update { it.copy(error = "Resolve the banner first, then rename.") }
            return
        }
        renaming = true
        autosaveJob?.cancel()
        viewModelScope.launch {
            try {
                if (isDirty()) save()
                if (isDirty() || _state.value.blocker != null) {
                    _state.update { it.copy(error = if (it.blocker != null) "Resolve the banner first, then rename." else "Couldn't save before renaming. Try again.") }
                    renaming = false
                    return@launch
                }
                session.rename(path, target)
                // Anything typed while the move ran goes into the renamed file (same bytes on disk, so the version still matches).
                val late = live?.toString()
                if (isDirty() && late != null && late != savedText) {
                    val bytes = withContext(Dispatchers.Default) { TextCodec.encode(late, format) }
                    withContext(Dispatchers.IO) { session.saver.save(target, bytes, baseVersion) }
                    session.notifyChanged(target)
                }
                onRenamed(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                renaming = false
                _state.update { it.copy(error = e.message ?: "Couldn't rename.") }
                if (isDirty()) scheduleAutosave()
            }
        }
    }

    /** Saves now if there are unsaved edits (leaving the screen, background, lock, Ctrl+S). */
    fun flush() {
        if (renaming) return
        autosaveJob?.cancel()
        if (isDirty()) viewModelScope.launch { save() }
    }

    private suspend fun save(force: Boolean = false) {
        if (_state.value.readOnly) return
        if (_state.value.blocker != null && !force) return
        val text = live?.toString() ?: return
        val counter = editCounter
        if (text == savedText && !force) {
            savedCounter = counter
            _state.update { it.copy(status = SaveStatus.SAVED) }
            return
        }
        _state.update { it.copy(status = SaveStatus.SAVING) }
        val bytes = withContext(Dispatchers.Default) { TextCodec.encode(text, format) }
        val result = withContext(Dispatchers.IO) { session.saver.save(path, bytes, baseVersion, force) }
        handle(result, text, counter)
    }

    private suspend fun handle(result: SaveResult, text: String, counter: Long) {
        when (result) {
            is SaveResult.Saved -> {
                if (result.inPlace) Log.w("saved in place (folder refused a temp file): $path")
                markSaved(result.version, text, counter)
                session.notifyChanged(path)
            }
            is SaveResult.Unchanged -> markSaved(result.version, text, counter)
            is SaveResult.Conflict -> {
                val theirs = withContext(Dispatchers.Default) { runCatching { TextCodec.decode(result.diskBytes).text }.getOrDefault("") }
                _state.update { it.copy(blocker = Blocker.Conflict(theirs), status = SaveStatus.CONFLICT) }
            }
            SaveResult.Missing -> _state.update { it.copy(blocker = Blocker.Missing, status = SaveStatus.CONFLICT) }
            is SaveResult.Failed -> _state.update { it.copy(status = SaveStatus.EDITED, error = result.message) }
        }
    }

    private fun markSaved(version: String, text: String, counter: Long) {
        baseVersion = version
        savedText = text
        savedCounter = counter
        // If the user typed during the save, stay "Edited" and let the next autosave run.
        _state.update { it.copy(status = if (isDirty()) SaveStatus.EDITED else SaveStatus.SAVED, blocker = null) }
        if (isDirty()) scheduleAutosave()
    }

    /** Version check against disk: reload clean editors in place; dirty ones will hit the conflict banner on save. */
    fun checkOutside() = viewModelScope.launch {
        if (renaming || _state.value.loading || _state.value.blocker != null) return@launch
        val disk = withContext(Dispatchers.IO) { runCatching { if (session.fs.exists(path)) session.fs.readBytes(path) else null }.getOrNull() }
        if (disk == null) {
            if (!isDirty()) _state.update { it.copy(blocker = Blocker.Missing, status = SaveStatus.CONFLICT) }
            return@launch
        }
        val v = Versions.of(disk)
        if (v == baseVersion) return@launch
        if (isDirty()) return@launch
        val decoded: DecodedText = withContext(Dispatchers.Default) { runCatching { TextCodec.decode(disk) }.getOrNull() } ?: return@launch
        format = decoded.format
        baseVersion = v
        savedText = decoded.text
        initialText = decoded.text
        history.clear()
        _commands.emit(EditorCommand.SetText(decoded.text, keepSelection = true))
        _state.update { it.copy(reloadedChip = true, canUndo = false, canRedo = false) }
        parsePreview(decoded.text)
        delay(2500)
        _state.update { it.copy(reloadedChip = false) }
    }

    // Conflict banner actions.

    fun loadTheirs() = viewModelScope.launch {
        val disk = withContext(Dispatchers.IO) { runCatching { session.fs.readBytes(path) }.getOrNull() } ?: return@launch
        val decoded = withContext(Dispatchers.Default) { TextCodec.decode(disk) }
        format = decoded.format
        baseVersion = Versions.of(disk)
        savedText = decoded.text
        savedCounter = editCounter
        history.clear()
        _commands.emit(EditorCommand.SetText(decoded.text, keepSelection = true))
        _state.update { it.copy(blocker = null, status = SaveStatus.SAVED) }
        parsePreview(decoded.text)
    }

    fun keepMine() = viewModelScope.launch {
        _state.update { it.copy(blocker = null) }
        editCounter++
        save(force = true)
    }

    /** "Recreate" for a file deleted elsewhere: writes it back at the same path. */
    fun recreate() = keepMine()

    fun saveMineAsCopy() = viewModelScope.launch {
        val text = live?.toString() ?: return@launch
        try {
            val copy = withContext(Dispatchers.IO) {
                val target = ConflictFiles.copyName(path, LocalDateTime.now()) { session.fs.exists(it) }
                session.fs.createNew(target, TextCodec.encode(text, format))
                target
            }
            session.notifyChanged(copy)
            _state.update { it.copy(notice = "Saved your version as “${VaultPaths.name(copy)}”") }
            if (_state.value.blocker is Blocker.Missing) {
                _state.update { it.copy(blocker = null) }
                savedCounter = editCounter
            } else {
                loadTheirs()
            }
        } catch (e: VaultException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    // View modes and preview.

    fun setView(mode: ViewMode) {
        _state.update { it.copy(view = mode) }
        viewModelScope.launch { session.settingsStore.setViewMode(FileKinds.viewKey(VaultPaths.name(path)), mode) }
        if (mode != ViewMode.EDIT) schedulePreview(immediate = true)
    }

    private fun schedulePreview(immediate: Boolean = false) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(150)
            val text = live?.toString() ?: savedText
            parsePreview(text)
        }
    }

    private suspend fun parsePreview(text: String) {
        if (!_state.value.isMarkdown) return
        val doc = withContext(Dispatchers.Default) { parser.parse(text, VaultPaths.stem(VaultPaths.name(path))) }
        _state.update { it.copy(doc = doc) }
    }

    /** Preview checkbox tap: flip `[ ]`/`[x]` on that line and save (normal version check). */
    fun toggleTask(line: Int) = viewModelScope.launch {
        val text = live?.toString() ?: return@launch
        val next = TaskToggle.toggle(text, line) ?: return@launch
        _commands.emit(EditorCommand.SetText(next, keepSelection = true))
        live = next
        editCounter++
        save()
        parsePreview(next)
    }

    fun onScrolledToLine(line: Int) = _state.update { it.copy(previewLine = line) }

    // Toolbar and shortcuts.

    fun apply(edit: TextEdit) = _commands.tryEmit(EditorCommand.Apply(edit))
    fun undo() = _commands.tryEmit(EditorCommand.Undo)
    fun redo() = _commands.tryEmit(EditorCommand.Redo)

    // Find & replace.

    fun openFind(open: Boolean) {
        _state.update { it.copy(find = it.find.copy(open = open)) }
        if (!open) matches = emptyList()
    }

    fun setFind(query: String = _state.value.find.query, replacement: String = _state.value.find.replacement, options: FindOptions = _state.value.find.options) {
        _state.update { it.copy(find = it.find.copy(query = query, replacement = replacement, options = options)) }
        scheduleFind()
    }

    private fun scheduleFind() {
        findJob?.cancel()
        findJob = viewModelScope.launch {
            delay(120)
            val f = _state.value.find
            val text = live?.toString() ?: return@launch
            val r = withContext(Dispatchers.Default) { FindEngine.find(text, f.query, f.options) }
            matches = r.getOrDefault(emptyList())
            _state.update {
                it.copy(find = it.find.copy(count = matches.size, current = if (matches.isEmpty()) -1 else 0, error = r.exceptionOrNull()?.let { "Invalid pattern" }))
            }
            if (matches.isNotEmpty()) select(0)
        }
    }

    fun findNext(step: Int) {
        if (matches.isEmpty()) return
        val i = Math.floorMod(_state.value.find.current + step, matches.size)
        select(i)
    }

    private fun select(i: Int) {
        val m = matches.getOrNull(i) ?: return
        _state.update { it.copy(find = it.find.copy(current = i)) }
        _commands.tryEmit(EditorCommand.Select(m.first, m.last + 1))
    }

    fun replaceCurrent() {
        val f = _state.value.find
        val m = matches.getOrNull(f.current) ?: return
        val text = live ?: return
        val repl = FindEngine.replacementFor(text.subSequence(m.first, m.last + 1).toString(), f.query, f.replacement, f.options)
        _commands.tryEmit(EditorCommand.Replace(m.first, m.last + 1, repl))
    }

    fun replaceAll() = viewModelScope.launch {
        val f = _state.value.find
        val text = live?.toString() ?: return@launch
        val r = withContext(Dispatchers.Default) { FindEngine.replaceAll(text, f.query, f.replacement, f.options) }
        r.onSuccess { next ->
            if (next != text) _commands.emit(EditorCommand.Replace(0, text.length, next))
        }
    }

    fun messageShown() = _state.update { it.copy(error = null, notice = null) }

    /**
     * Copies pictures into `attachments/` next to this note and returns their markdown, one
     * image per paragraph (empty when none could be added). The caller inserts it at the cursor.
     */
    suspend fun addImages(resolver: ContentResolver, uris: List<Uri>): String {
        val added = ArrayList<String>()
        for (uri in uris) {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val (name, bytes) = ImageImport.read(resolver, uri)
                    Attachments.saveImage(session.fs, path, name, bytes, LocalDateTime.now())
                }
                session.notifyChanged(saved)
                added += Attachments.markdown(path, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("adding an image failed", e)
                _state.update { it.copy(error = "Couldn't add the image: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
        if (added.isNotEmpty()) _state.update { it.copy(notice = if (added.size == 1) "Image added" else "${added.size} images added") }
        return added.joinToString("\n\n")
    }

    /** Jumps to the first match of a search query (opened from Search) and flashes it. */
    fun revealQuery(query: String) = viewModelScope.launch {
        val text = live?.toString() ?: return@launch
        val words = query.split(' ').filter { it.isNotBlank() && !it.startsWith('#') && ':' !in it }
        val idx = words.map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: return@launch
        val word = words.first { text.indexOf(it, ignoreCase = true) == idx }
        _commands.emit(EditorCommand.Select(idx, idx + word.length))
    }

    override fun onCleared() {
        // Last chance: viewModelScope is already cancelled, so save synchronously off the main thread.
        if (isDirty() && _state.value.blocker == null && !_state.value.readOnly) {
            val text = live?.toString() ?: return
            val bytes = TextCodec.encode(text, format)
            val base = baseVersion
            Thread { session.saver.save(path, bytes, base) }.start()
        }
    }
}
