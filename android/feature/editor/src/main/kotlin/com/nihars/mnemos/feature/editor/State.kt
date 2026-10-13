package com.nihars.mnemos.feature.editor

import androidx.compose.runtime.Immutable
import com.nihars.mnemos.core.data.ViewMode
import com.nihars.mnemos.core.design.SaveStatus
import com.nihars.mnemos.core.markdown.MarkdownDoc

/** Why saving stopped and what the banner offers. */
sealed interface Blocker {
    /** Disk changed under us. [theirs] is the text on disk. */
    data class Conflict(val theirs: String) : Blocker

    /** The file was deleted or moved elsewhere. */
    data object Missing : Blocker
}

@Immutable
data class FindState(
    val open: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val options: FindOptions = FindOptions(),
    val count: Int = 0,
    val current: Int = -1,
    val error: String? = null,
)

@Immutable
data class EditorState(
    val path: String,
    val loading: Boolean = true,
    val language: Language = Language.PLAIN,
    val isMarkdown: Boolean = false,
    val isCsv: Boolean = false,
    val readOnly: Boolean = false,
    val status: SaveStatus = SaveStatus.SAVED,
    val view: ViewMode = ViewMode.EDIT,
    val blocker: Blocker? = null,
    // Shown briefly after an outside change was loaded in place.
    val reloadedChip: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val doc: MarkdownDoc? = null,
    val find: FindState = FindState(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val mixedEndings: Boolean = false,
    val previewLine: Int = 0,
    val largeWarning: Boolean = false,
)

/** One-shot instructions for the EditText (which owns the live text). */
sealed interface EditorCommand {
    data class SetText(val text: String, val keepSelection: Boolean) : EditorCommand
    data class Select(val start: Int, val end: Int) : EditorCommand
    data class Replace(val start: Int, val end: Int, val text: String) : EditorCommand
    data class Apply(val edit: TextEdit) : EditorCommand
    data object Undo : EditorCommand
    data object Redo : EditorCommand
}
