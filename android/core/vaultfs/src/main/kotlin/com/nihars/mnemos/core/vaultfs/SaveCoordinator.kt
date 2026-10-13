package com.nihars.mnemos.core.vaultfs

import com.nihars.mnemos.core.text.Versions
import java.util.concurrent.ConcurrentHashMap

/** What happened to a save. The editor maps each case to its status dot and banner. */
sealed interface SaveResult {
    data class Saved(val version: String, val inPlace: Boolean) : SaveResult

    /** Disk already holds exactly these bytes; nothing was written (no mtime churn for Syncthing). */
    data class Unchanged(val version: String) : SaveResult

    /** Someone else changed the file since the editor loaded [baseVersion]. Nothing was written. */
    data class Conflict(val diskBytes: ByteArray, val diskVersion: String) : SaveResult {
        override fun equals(other: Any?) = other is Conflict && other.diskVersion == diskVersion
        override fun hashCode() = diskVersion.hashCode()
    }

    /** The file was deleted or moved away. Nothing was written. */
    data object Missing : SaveResult

    data class Failed(val message: String) : SaveResult
}

/**
 * The web app's save rule: re-read the file under a per-file lock and only write when the
 * disk still holds the version the editor started from.
 */
class SaveCoordinator(private val fs: VaultFs) {
    private val locks = ConcurrentHashMap<String, Any>()

    /** Saves [bytes] if disk still matches [baseVersion]. [force] skips the check ("Keep mine", "Recreate"). */
    fun save(path: String, bytes: ByteArray, baseVersion: String?, force: Boolean = false): SaveResult {
        val key = VaultPaths.normalize(path)
        val lock = locks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            try {
                saveLocked(key, bytes, baseVersion, force)
            } catch (e: VaultException) {
                SaveResult.Failed(e.message ?: "Couldn't save “$key”.")
            }
        }
    }

    private fun saveLocked(path: String, bytes: ByteArray, baseVersion: String?, force: Boolean): SaveResult {
        val newVersion = Versions.of(bytes)
        if (fs.stat(path) == null) {
            if (!force && baseVersion != null) return SaveResult.Missing
            val parent = VaultPaths.parent(path)
            if (parent.isNotEmpty() && !fs.exists(parent)) fs.mkdirs(parent)
            return writeNew(path, bytes, newVersion)
        }
        val disk = fs.readBytes(path)
        val diskVersion = Versions.of(disk)
        if (diskVersion == newVersion) return SaveResult.Unchanged(diskVersion)
        if (!force && diskVersion != baseVersion) return SaveResult.Conflict(disk, diskVersion)
        val outcome = fs.writeAtomic(path, bytes)
        return SaveResult.Saved(newVersion, outcome == WriteOutcome.IN_PLACE)
    }

    private fun writeNew(path: String, bytes: ByteArray, version: String): SaveResult {
        val outcome = fs.writeAtomic(path, bytes)
        return SaveResult.Saved(version, outcome == WriteOutcome.IN_PLACE)
    }
}
