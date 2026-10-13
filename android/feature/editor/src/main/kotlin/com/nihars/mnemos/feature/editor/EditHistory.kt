package com.nihars.mnemos.feature.editor

/** One recorded change: at [start], [removed] was replaced by [inserted]. */
data class Change(val start: Int, val removed: String, val inserted: String, val time: Long, val selBefore: Int)

/**
 * Undo/redo with grouping: typing a word is one step, not one per character. Lives in the
 * ViewModel so it survives rotation; cleared when the file is reloaded from disk.
 */
class EditHistory(private val max: Int = 500, private val groupMs: Long = 1000) {
    private val undo = ArrayDeque<Change>()
    private val redo = ArrayDeque<Change>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun record(c: Change) {
        if (c.removed == c.inserted) return
        redo.clear()
        val last = undo.lastOrNull()
        if (last != null && canMerge(last, c)) {
            undo.removeLast()
            undo.addLast(last.copy(inserted = last.inserted + c.inserted, time = c.time))
        } else {
            undo.addLast(c)
            if (undo.size > max) undo.removeFirst()
        }
    }

    /** Returns the inverse change to apply, or null. */
    fun undo(): Change? {
        val c = undo.removeLastOrNull() ?: return null
        redo.addLast(c)
        return Change(c.start, c.inserted, c.removed, c.time, c.start + c.inserted.length)
    }

    fun redo(): Change? {
        val c = redo.removeLastOrNull() ?: return null
        undo.addLast(c)
        return c
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    // Merge consecutive typing (or consecutive backspaces) at the same spot, within the time window, not across a newline.
    private fun canMerge(a: Change, b: Change): Boolean {
        if (b.time - a.time > groupMs) return false
        val typing = a.removed.isEmpty() && b.removed.isEmpty() && b.start == a.start + a.inserted.length &&
            b.inserted.length == 1 && !b.inserted.contains('\n') && !a.inserted.endsWith(" ")
        return typing
    }
}
