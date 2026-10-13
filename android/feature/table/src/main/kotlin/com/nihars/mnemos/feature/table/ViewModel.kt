package com.nihars.mnemos.feature.table

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.csv.ColumnStats
import com.nihars.mnemos.core.csv.CsvDocument
import com.nihars.mnemos.core.csv.CsvRecord
import com.nihars.mnemos.core.csv.CsvSort
import com.nihars.mnemos.core.csv.Delimiters
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.design.SaveStatus
import com.nihars.mnemos.core.text.NotTextException
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextFormat
import com.nihars.mnemos.core.text.Versions
import com.nihars.mnemos.core.vaultfs.SaveResult
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CSV/TSV table. The document keeps every untouched record's original bytes, so a save after
 * a one-cell edit changes only that row.
 */
class TableViewModel(private val session: VaultSession, val path: String) : ViewModel() {
    private val _state = MutableStateFlow(TableState(path))
    val state: StateFlow<TableState> = _state.asStateFlow()

    private var doc: CsvDocument? = null
    private var format: TextFormat = TextFormat.NEW_FILE
    private var baseVersion: String? = null
    private var dirty = false
    private var saveJob: Job? = null
    private var deleted: Pair<Int, CsvRecord>? = null

    init {
        load()
        viewModelScope.launch { session.changes.collect { if (VaultPaths.normalize(path) in it && !dirty) load() } }
    }

    fun cell(record: Int, col: Int): String = doc?.cell(record, col).orEmpty()

    private fun load() = viewModelScope.launch {
        try {
            val (bytes, entry) = withContext(Dispatchers.IO) { session.fs.readBytes(path) to session.fs.stat(path) }
            val (d, f) = withContext(Dispatchers.Default) {
                // No newline normalising: quoted fields may contain line breaks that must stay exact.
                val decoded = TextCodec.decode(bytes, normalizeNewlines = false)
                CsvDocument.parse(decoded.text, Delimiters.forExtension(VaultPaths.ext(path))) to decoded.format
            }
            doc = d
            format = f
            baseVersion = Versions.of(bytes)
            dirty = false
            _state.update { it.copy(loading = false, readOnly = entry?.readOnly == true, status = SaveStatus.SAVED) }
            refreshView(measure = true)
        } catch (e: VaultException) {
            _state.update { it.copy(loading = false, message = e.message) }
        } catch (e: NotTextException) {
            _state.update { it.copy(loading = false, message = e.message) }
        }
    }

    fun setCell(record: Int, col: Int, value: String) = edit { it.setCell(record, col, value) }

    fun insertRow(at: Int) = edit { it.insertRecord(at.coerceIn(1, it.recordCount)) }

    fun deleteRow(record: Int) = edit { d ->
        deleted = record to d.deleteRecord(record)
        _state.update { it.copy(undoDelete = record) }
    }

    fun undoDelete() = edit { d ->
        deleted?.let { (i, r) -> d.restoreRecord(i, r) }
        deleted = null
        _state.update { it.copy(undoDelete = null) }
    }

    fun undoShown() = _state.update { it.copy(undoDelete = null) }

    fun addColumn(name: String) = edit { it.addColumn(name) }
    fun renameColumn(col: Int, name: String) = edit { it.renameColumn(col, name) }

    fun sortBy(col: Int) {
        val cur = _state.value.sort
        val next = when {
            cur?.column != col -> SortSpec(col, true)
            cur.ascending -> SortSpec(col, false)
            else -> null
        }
        _state.update { it.copy(sort = next) }
        refreshView()
    }

    fun setFilter(col: Int?, text: String) {
        _state.update { it.copy(filterColumn = col, filterText = text) }
        refreshView()
    }

    /** Rewrites the file in the current sorted order (after the user confirmed). */
    fun applySort() = edit { d ->
        val s = _state.value.sort ?: return@edit
        d.reorderRows(CsvSort.order(d, s.column, s.ascending))
        _state.update { it.copy(sort = null) }
    }

    fun showStats(col: Int) {
        val d = doc ?: return
        val stats = ColumnStats.of(d, col)
        _state.update { it.copy(stats = d.header().getOrElse(col) { "Column ${col + 1}" } to stats) }
    }

    fun hideStats() = _state.update { it.copy(stats = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    fun widen(col: Int, deltaDp: Int) = _state.update { s ->
        s.copy(widthsDp = s.widthsDp.mapIndexed { i, w -> if (i == col) (w + deltaDp).coerceIn(48, 600) else w })
    }

    fun loadTheirs() = load()

    fun keepMine() = viewModelScope.launch {
        _state.update { it.copy(conflict = false) }
        save(force = true)
    }

    private fun edit(block: (CsvDocument) -> Unit) {
        val d = doc ?: return
        if (_state.value.readOnly) return
        block(d)
        dirty = true
        _state.update { it.copy(status = SaveStatus.EDITED, revision = it.revision + 1) }
        refreshView()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(session.settings.value.autosaveMs)
            save()
        }
    }

    fun flush() {
        saveJob?.cancel()
        if (dirty) viewModelScope.launch { save() }
    }

    private suspend fun save(force: Boolean = false) {
        val d = doc ?: return
        if (_state.value.conflict && !force) return
        _state.update { it.copy(status = SaveStatus.SAVING) }
        // The document is only touched on the main thread; the immutable text snapshot is encoded elsewhere.
        val snapshot = d.toText()
        val bytes = withContext(Dispatchers.Default) { TextCodec.encode(snapshot, format, textIsNormalized = false) }
        when (val r = withContext(Dispatchers.IO) { session.saver.save(path, bytes, baseVersion, force) }) {
            is SaveResult.Saved -> {
                baseVersion = r.version
                dirty = false
                session.notifyChanged(path)
                _state.update { it.copy(status = SaveStatus.SAVED) }
            }
            is SaveResult.Unchanged -> {
                baseVersion = r.version
                dirty = false
                _state.update { it.copy(status = SaveStatus.SAVED) }
            }
            is SaveResult.Conflict -> _state.update { it.copy(conflict = true, status = SaveStatus.CONFLICT) }
            SaveResult.Missing -> _state.update { it.copy(conflict = true, status = SaveStatus.CONFLICT, message = "The file was deleted or moved elsewhere.") }
            is SaveResult.Failed -> _state.update { it.copy(status = SaveStatus.EDITED, message = r.message) }
        }
    }

    // Recomputes display order and (first time) column widths. Runs on the main thread because the
    // document is confined there; sorting a few thousand rows takes well under a frame.
    private fun refreshView(measure: Boolean = false) {
        val d = doc ?: return
        val s = _state.value
        val (order, numeric, widths) = run {
            var rows = s.sort?.let { CsvSort.order(d, it.column, it.ascending) } ?: (1 until d.recordCount).toList()
            if (s.filterText.isNotBlank()) {
                val q = s.filterText.lowercase()
                rows = rows.filter { r ->
                    val c = s.filterColumn
                    if (c != null) d.cell(r, c).lowercase().contains(q) else d.record(r).values().any { it.lowercase().contains(q) }
                }
            }
            val cols = d.columnCount
            val numeric = (0 until cols).filter { CsvSort.isNumericColumn(d, it) }.toSet()
            // Auto-fit from a sample of rows: ~8 dp per character, clamped.
            val widths = if (measure || s.widthsDp.size != cols) {
                (0 until cols).map { c ->
                    val sample = (0 until minOf(d.recordCount, 200)).maxOfOrNull { d.cell(it, c).lineSequence().firstOrNull().orEmpty().length } ?: 4
                    (sample * 8 + 24).coerceIn(64, 320)
                }
            } else {
                s.widthsDp
            }
            Triple(rows, numeric, widths)
        }
        _state.update { it.copy(header = d.header(), columnCount = d.columnCount, order = order, numeric = numeric, widthsDp = widths, revision = it.revision + 1) }
    }

    override fun onCleared() {
        val d = doc ?: return
        if (!dirty || _state.value.conflict) return
        val bytes = TextCodec.encode(d.toText(), format, textIsNormalized = false)
        val base = baseVersion
        Thread { session.saver.save(path, bytes, base) }.start()
    }
}
