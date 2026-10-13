package com.nihars.mnemos.core.vaultfs

/** Delete = move into the vault's flat `.trash/` folder (the one Obsidian uses). Nothing is ever erased. */
class Trash(private val fs: VaultFs) {
    /** Moves [path] (file or folder) to the trash and returns its new vault path. */
    fun moveToTrash(path: String): String {
        val src = VaultPaths.normalize(path)
        if (VaultPaths.isInside(src, VaultPaths.TRASH)) throw VaultException.PathRejected(path, "it is already in the trash.")
        if (!fs.exists(VaultPaths.TRASH)) fs.mkdirs(VaultPaths.TRASH)
        val name = VaultPaths.uniqueName(VaultPaths.TRASH, VaultPaths.name(src)) { fs.exists(it) }
        val dst = VaultPaths.join(VaultPaths.TRASH, name)
        fs.move(src, dst)
        return dst
    }

    /** Restores [trashPath] to [target]; fails rather than overwriting if the target is taken. */
    fun restore(trashPath: String, target: String) {
        fs.move(trashPath, target)
    }

    fun list(): List<VaultEntry> =
        if (!fs.exists(VaultPaths.TRASH)) emptyList() else fs.list(VaultPaths.TRASH).sortedByDescending { it.modified }
}
