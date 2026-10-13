package com.nihars.mnemos.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.index.SearchFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Results while typing (80 ms debounce), straight from the SQLite index. */
class SearchViewModel(private val session: VaultSession) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { session.scan.collect { s -> _state.update { it.copy(indexing = s.running) } } }
        // New index content: refresh the current results quietly.
        viewModelScope.launch { session.changes.collect { if (_state.value.query.isNotBlank()) run(debounce = 300) } }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        run()
    }

    fun setFolder(f: String) {
        _state.update { it.copy(folder = f) }
        run()
    }

    fun setType(t: TypeFilter) {
        _state.update { it.copy(type = t) }
        run()
    }

    fun setDate(d: DateFilter) {
        _state.update { it.copy(date = d) }
        run()
    }

    private fun run(debounce: Long = 80) {
        job?.cancel()
        val s = _state.value
        if (s.query.isBlank()) {
            _state.update { it.copy(hits = emptyList(), searching = false) }
            return
        }
        job = viewModelScope.launch {
            delay(debounce)
            _state.update { it.copy(searching = true) }
            val started = System.nanoTime()
            val filters = SearchFilters(
                folder = s.folder.trim().trim('/'),
                extensions = s.type.exts,
                modifiedAfter = s.date.days?.let { System.currentTimeMillis() - it * 86_400_000L },
            )
            val hits = withContext(Dispatchers.IO) { runCatching { session.search.search(s.query, filters) }.getOrDefault(emptyList()) }
            _state.update { it.copy(hits = hits, searching = false, millis = (System.nanoTime() - started) / 1_000_000) }
        }
    }
}
