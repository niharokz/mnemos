package com.nihars.mnemos.core.data

import android.os.FileObserver
import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Watches the folders the user can currently see (and the open file's folder) for changes by
 * Syncthing, Obsidian or scripts. Events are batched for 300 ms. On the SAF path there is no
 * FileObserver; the 20 s version check and the resume scan cover it instead.
 */
class VaultWatcher(private val fs: VaultFs, scope: CoroutineScope) {
    private val raw = Channel<String>(Channel.UNLIMITED)
    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    val changes: SharedFlow<Set<String>> = _changes
    private val observers = HashMap<String, FileObserver>()

    init {
        scope.launch {
            while (true) {
                val batch = HashSet<String>()
                batch += raw.receive()
                // Keep collecting until 300 ms pass without a new event.
                while (true) {
                    val next = withTimeoutOrNull(DEBOUNCE_MS) { raw.receive() } ?: break
                    batch += next
                }
                _changes.emit(batch)
            }
        }
    }

    /** Replaces the watched set. The vault root is always watched. */
    @Synchronized
    fun watch(dirs: Set<String>) {
        val wanted = dirs.map { VaultPaths.normalize(it) }.toSet() + ""
        (observers.keys - wanted).forEach { observers.remove(it)?.stopWatching() }
        for (dir in wanted - observers.keys) {
            val abs = fs.absolutePath(dir) ?: continue
            val obs = object : FileObserver(File(abs), MASK) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path.startsWith('.')) return
                    raw.trySend(VaultPaths.join(dir, path))
                }
            }
            obs.startWatching()
            observers[dir] = obs
        }
    }

    @Synchronized
    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val MASK = FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.MODIFY
    }
}
