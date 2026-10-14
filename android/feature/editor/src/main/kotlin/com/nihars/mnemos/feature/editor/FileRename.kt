package com.nihars.mnemos.feature.editor

import com.nihars.mnemos.core.vaultfs.VaultPaths

/**
 * Rules for renaming the open file from the editor's title. The user edits the name without its
 * extension; the extension and the folder stay the same, so a rename never moves or retypes a file.
 */
object FileRename {
    sealed interface Result {
        data class Ok(val path: String) : Result
        data class Invalid(val reason: String) : Result
        /** Nothing to do (same name). */
        data object Same : Result
    }

    // Characters Windows, macOS or Syncthing refuse, plus control characters.
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val reserved = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

    /** What the title field shows for [path]: the name without its extension. */
    fun editable(path: String): String {
        val name = VaultPaths.name(path)
        return if (VaultPaths.ext(name).isEmpty()) name else VaultPaths.stem(name)
    }

    /** The extension shown after the field (".md"), or "" for files without one. */
    fun suffix(path: String): String {
        val name = VaultPaths.name(path)
        val ext = VaultPaths.ext(name)
        return if (ext.isEmpty()) "" else name.substring(name.length - ext.length - 1)
    }

    private val placeholder = Regex("""Untitled( \d+)?""", RegexOption.IGNORE_CASE)

    /** "Untitled", "Untitled 2"…: the names + gives new notes. */
    fun isPlaceholder(path: String): Boolean = placeholder.matches(editable(path))

    fun target(path: String, input: String): Result {
        val stem = input.trim().trimEnd('.', ' ')
        if (stem.isEmpty()) return Result.Invalid("The name can't be empty.")
        if (stem.startsWith(".")) return Result.Invalid("Names starting with a dot are hidden. Pick another.")
        if (forbidden.containsMatchIn(stem)) return Result.Invalid("Names can't contain / \\ : * ? \" < > |")
        if (stem.lowercase() in reserved) return Result.Invalid("“$stem” is reserved on Windows. Pick another.")
        val suffix = suffix(path)
        // Typing the extension again ("idea.md") shouldn't give "idea.md.md".
        val full = if (suffix.isNotEmpty() && stem.endsWith(suffix, ignoreCase = true) && stem.length > suffix.length) {
            stem.dropLast(suffix.length) + suffix
        } else {
            stem + suffix
        }
        if (full.toByteArray().size > 255) return Result.Invalid("That name is too long.")
        if (full == VaultPaths.name(path)) return Result.Same
        return Result.Ok(VaultPaths.join(VaultPaths.parent(path), full))
    }
}
