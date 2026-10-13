package com.nihars.mnemos.feature.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.text.Diff
import com.nihars.mnemos.core.text.NotTextException
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.Versions
import com.nihars.mnemos.core.vaultfs.ConflictFiles
import com.nihars.mnemos.core.vaultfs.SaveResult
import com.nihars.mnemos.core.vaultfs.VaultException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Resolves one Syncthing conflict copy. The losing file always goes to `.trash/`. */
class ConflictViewModel(private val session: VaultSession, private val conflictPath: String) : ViewModel() {
    private val original = ConflictFiles.parse(conflictPath)?.originalPath ?: conflictPath
    private val _state = MutableStateFlow(ConflictState(conflictPath, original))
    val state: StateFlow<ConflictState> = _state.asStateFlow()

    private var originalText = ""
    private var originalBytes: ByteArray? = null
    private var conflictText = ""

    init {
        load()
    }

    private fun load() = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                originalBytes = if (session.fs.exists(original)) session.fs.readBytes(original) else null
                originalText = originalBytes?.let { TextCodec.decode(it).text }.orEmpty()
                conflictText = TextCodec.decode(session.fs.readBytes(conflictPath)).text
            }
            val rows = withContext(Dispatchers.Default) { Diff.sideBySide(originalText, conflictText) }
            _state.update { it.copy(rows = rows, loading = false, originalExists = originalBytes != null) }
        } catch (e: VaultException) {
            _state.update { it.copy(loading = false, message = e.message) }
        } catch (_: NotTextException) {
            _state.update { it.copy(loading = false, message = "These files aren't text, so they can't be compared here. Use Open with… instead.") }
        }
    }

    fun keepOriginal() = act {
        session.moveToTrash(conflictPath)
        done(original)
    }

    fun keepConflictCopy() = act {
        if (session.fs.exists(original)) session.moveToTrash(original)
        session.move(conflictPath, original)
        done(original)
    }

    /** Both versions in the original file, between git-style markers; the copy goes to trash. */
    fun mergeByHand() = act {
        val merged = buildString {
            append("<<<<<<< ").append(original).append('\n')
            append(originalText.trimEnd('\n')).append('\n')
            append("=======\n")
            append(conflictText.trimEnd('\n')).append('\n')
            append(">>>>>>> ").append(conflictPath).append('\n')
        }
        val format = originalBytes?.let { TextCodec.decode(it).format } ?: com.nihars.mnemos.core.text.TextFormat.NEW_FILE
        val bytes = TextCodec.encode(merged, format)
        val r = withContext(Dispatchers.IO) { session.saver.save(original, bytes, originalBytes?.let { Versions.of(it) }) }
        when (r) {
            is SaveResult.Saved, is SaveResult.Unchanged -> {
                session.notifyChanged(original)
                session.moveToTrash(conflictPath)
                done(original)
            }
            is SaveResult.Conflict -> _state.update { it.copy(busy = false, message = "The original changed meanwhile. Reload and try again.") }
            SaveResult.Missing -> _state.update { it.copy(busy = false, message = "The original was removed meanwhile.") }
            is SaveResult.Failed -> _state.update { it.copy(busy = false, message = r.message) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun done(open: String) = _state.update { it.copy(busy = false, resolved = true, resolvedOpen = open) }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: VaultException) {
            _state.update { it.copy(busy = false, message = e.message) }
        }
    }
}
