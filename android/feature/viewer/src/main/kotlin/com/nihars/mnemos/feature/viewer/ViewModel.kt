package com.nihars.mnemos.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.data.FileKinds
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Read-only viewer for big text files: the file is memory-mapped, the line index is built in
 * the background, and only visible lines are decoded. Logs can follow their tail.
 */
class LargeTextViewModel(private val session: VaultSession, val path: String) : ViewModel() {
    private val _state = MutableStateFlow(LargeTextState(path, follow = VaultPaths.ext(path) == "log"))
    val state: StateFlow<LargeTextState> = _state.asStateFlow()

    @Volatile private var buf: ByteBuffer? = null
    @Volatile private var size = 0L
    private val index = LineIndex()
    private var searchJob: Job? = null
    private var tailJob: Job? = null

    init {
        viewModelScope.launch { open() }
        startTail()
    }

    private suspend fun open() {
        try {
            withContext(Dispatchers.IO) { remap() }
            _state.update { it.copy(size = size, canEdit = size <= FileKinds.EDIT_ANYWAY_LIMIT) }
            withContext(Dispatchers.Default) {
                index.extend(buf!!, size) { n -> _state.update { it.copy(lineCount = n) } }
            }
            _state.update { it.copy(lineCount = index.visibleCount(size), indexing = false) }
        } catch (e: VaultException) {
            _state.update { it.copy(error = e.message, indexing = false) }
        }
    }

    private fun remap() {
        session.fs.openChannel(path).use { ch ->
            size = ch.size()
            // The mapping stays valid after the channel closes.
            buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size)
        }
    }

    /** Decodes one line (capped so a minified 2 MB single line can't freeze a row). */
    fun line(i: Int): String {
        val b = buf ?: return ""
        if (i >= index.count) return ""
        val start = index.start(i)
        val end = minOf(index.end(i, size), start + MAX_LINE_BYTES)
        val len = (end - start).toInt().coerceAtLeast(0)
        val bytes = ByteArray(len)
        val dup = b.duplicate()
        dup.position(start.toInt())
        dup.get(bytes)
        val s = String(bytes, Charsets.UTF_8).trimEnd('\r')
        return if (end < index.end(i, size)) "$s …" else s
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(hits = emptyList(), hitIndex = -1, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(200)
            _state.update { it.copy(searching = true) }
            val hits = withContext(Dispatchers.Default) { buf?.let { ByteSearch.lines(it, size, query, index) }.orEmpty() }
            _state.update { it.copy(hits = hits, hitIndex = if (hits.isEmpty()) -1 else 0, searching = false) }
        }
    }

    fun nextHit(step: Int) = _state.update { s ->
        if (s.hits.isEmpty()) s else s.copy(hitIndex = Math.floorMod(s.hitIndex + step, s.hits.size))
    }

    fun setFollow(on: Boolean) = _state.update { it.copy(follow = on) }

    // Polls once a second; when the file grew, remap and index only the new part.
    private fun startTail() {
        tailJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (_state.value.indexing) continue
                val newSize = withContext(Dispatchers.IO) { runCatching { session.fs.stat(path)?.size }.getOrNull() } ?: continue
                if (newSize > size) {
                    withContext(Dispatchers.IO) { runCatching { remap() } }
                    withContext(Dispatchers.Default) { buf?.let { index.extend(it, size) } }
                    _state.update { it.copy(size = size, lineCount = index.visibleCount(size), revision = it.revision + 1) }
                } else if (newSize < size) {
                    // Truncated or rotated: start over.
                    _state.update { it.copy(error = "The file was replaced. Reopen it to see the new content.") }
                }
            }
        }
    }

    private companion object {
        const val MAX_LINE_BYTES = 8 * 1024L
    }
}
