package com.nihars.mnemos.core.vaultfs

import java.nio.channels.FileChannel

/** A file or folder in the vault. [path] is vault-relative with "/" separators; "" is the root. */
data class VaultEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val readOnly: Boolean,
)

/** How a write landed: the normal temp-file + rename, or the in-place fallback for read-only folders. */
enum class WriteOutcome { ATOMIC, IN_PLACE }

/**
 * Everything the app does to the vault goes through here. There is deliberately no delete:
 * removing a file means moving it to `.trash/` (see [Trash]).
 */
interface VaultFs {
    /** Shown in Settings, e.g. the folder path or the SAF tree name. */
    val rootLabel: String

    /** True for the fast java.nio path; false for the Storage Access Framework fallback. */
    val isFast: Boolean

    fun list(dir: String): List<VaultEntry>
    fun stat(path: String): VaultEntry?
    fun exists(path: String): Boolean = stat(path) != null
    fun readBytes(path: String): ByteArray

    /** Reads at most [max] bytes from the start; used for text detection and widgets. */
    fun readHead(path: String, max: Int): ByteArray

    /** Read-only channel for the large-file viewer (memory mapping on the nio path). */
    fun openChannel(path: String): FileChannel

    /** Creates or replaces [path] atomically (temp file, fsync, rename). Parent folder must exist. */
    fun writeAtomic(path: String, bytes: ByteArray): WriteOutcome

    /** Creates a new file; fails with [VaultException.AlreadyExists] instead of overwriting. */
    fun createNew(path: String, bytes: ByteArray): WriteOutcome

    fun mkdirs(dir: String)

    /** Renames or moves; never overwrites an existing target. Creates the target's parent folders. */
    fun move(from: String, to: String)

    /** Every entry below [dir]. Hidden folders are skipped unless [includeHidden]. */
    fun walk(dir: String = "", includeHidden: Boolean = false): List<VaultEntry>

    /** Real filesystem path when one exists (nio), for FileObserver and FileProvider; null on SAF. */
    fun absolutePath(path: String): String?
}
