package com.nihars.mnemos.core.vaultfs

/** Vault-relative path rules shared by every [VaultFs]. */
object VaultPaths {
    const val TRASH = ".trash"

    /** Normalizes "a//b/" to "a/b" and rejects anything that could leave the vault. */
    fun normalize(path: String): String {
        if (path.indexOf('\u0000') >= 0) throw VaultException.PathRejected(path, "it contains a NUL character.")
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw VaultException.PathRejected(path, "“..” is not allowed.")
        return parts.joinToString("/")
    }

    /** Hidden folders are read-only to the app, except the trash. */
    fun checkWritable(path: String): String {
        val norm = normalize(path)
        if (norm.isEmpty()) throw VaultException.PathRejected(path, "that is the vault folder itself.")
        val parts = norm.split('/')
        parts.forEachIndexed { i, p ->
            val allowed = i == 0 && p == TRASH
            if (p.startsWith('.') && !allowed) {
                throw VaultException.PathRejected(path, "names starting with “.” are hidden folders the app doesn't write.")
            }
        }
        return norm
    }

    fun name(path: String): String = path.substringAfterLast('/')
    fun parent(path: String): String = if ('/' in path) path.substringBeforeLast('/') else ""
    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    /** Extension without the dot, lower-case; "" when none. Dotfiles like ".env" have no extension. */
    fun ext(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }

    fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /** "note.md" → "note 2.md", "note 3.md"… until [exists] says no. */
    fun uniqueName(dir: String, name: String, exists: (String) -> Boolean): String {
        if (!exists(join(dir, name))) return name
        val stem = stem(name)
        val ext = if (stem == name) "" else name.substring(stem.length)
        var n = 2
        while (exists(join(dir, "$stem $n$ext"))) n++
        return "$stem $n$ext"
    }

    fun isInside(path: String, dir: String): Boolean = dir.isEmpty() || path == dir || path.startsWith("$dir/")
}
