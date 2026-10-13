package com.nihars.mnemos.feature.editor

/** One text change: replace [start, end) with [text], then select [selStart, selEnd]. */
data class TextEdit(val start: Int, val end: Int, val text: String, val selStart: Int, val selEnd: Int)

/** Pure markdown editing helpers; the EditText applies the returned [TextEdit]. */
object MarkdownActions {
    private val LIST = Regex("""^(\s*)([-*+]|(\d+)([.)]))(\s+)(\[[ xX]]\s+)?""")
    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '*' to '*', '`' to '`')

    fun lineStart(text: CharSequence, pos: Int): Int {
        var i = pos.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    fun lineEnd(text: CharSequence, pos: Int): Int {
        var i = pos.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    /**
     * Enter after a list item: continue the list (next number, fresh checkbox). Enter on an empty
     * item ends the list by removing its marker. Returns null for normal lines.
     */
    fun onEnter(text: CharSequence, cursor: Int): TextEdit? {
        val ls = lineStart(text, cursor)
        val line = text.subSequence(ls, cursor).toString()
        val m = LIST.find(line) ?: return null
        if (m.range.first != 0) return null
        val rest = line.substring(m.range.last + 1)
        if (rest.isBlank() && lineEnd(text, cursor) == cursor) {
            // Empty item: outdent one level if indented, else drop the marker.
            val indent = m.groupValues[1]
            return if (indent.isNotEmpty()) {
                val newIndent = indent.drop(minOf(indent.length, 2))
                val repl = newIndent + line.substring(indent.length)
                TextEdit(ls, cursor, repl, ls + repl.length, ls + repl.length)
            } else {
                TextEdit(ls, cursor, "", ls, ls)
            }
        }
        val indent = m.groupValues[1]
        val marker = m.groupValues[3].toIntOrNull()?.let { "${it + 1}${m.groupValues[4]}" } ?: m.groupValues[2]
        val task = if (m.groupValues[6].isNotEmpty()) "[ ] " else ""
        val insert = "\n" + indent + marker + m.groupValues[5] + task
        return TextEdit(cursor, cursor, insert, cursor + insert.length, cursor + insert.length)
    }

    /** Tab / Shift-Tab on the selected lines: add or remove two spaces of indent. */
    fun indent(text: CharSequence, selStart: Int, selEnd: Int, outdent: Boolean): TextEdit {
        val ls = lineStart(text, selStart)
        // A selection ending right after a newline doesn't include the next line.
        val endPos = if (selEnd > selStart && text[selEnd - 1] == '\n') selEnd - 1 else selEnd
        val le = lineEnd(text, endPos)
        val lines = text.subSequence(ls, le).toString().split('\n')
        var firstDelta = 0
        var total = 0
        val changed = lines.mapIndexed { i, l ->
            val newLine = when {
                !outdent -> "  $l"
                l.startsWith("  ") -> l.drop(2)
                l.startsWith("\t") || l.startsWith(" ") -> l.drop(1)
                else -> l
            }
            val d = newLine.length - l.length
            if (i == 0) firstDelta = d
            total += d
            newLine
        }.joinToString("\n")
        val newStart = maxOf(ls, selStart + firstDelta)
        return TextEdit(ls, le, changed, newStart, maxOf(newStart, selEnd + total))
    }

    /** Typing an opening char inserts its pair; with a selection it wraps the selection instead. */
    fun autoClose(text: CharSequence, selStart: Int, selEnd: Int, typed: Char): TextEdit? {
        val close = PAIRS[typed] ?: return null
        if (selEnd > selStart) {
            val inner = text.subSequence(selStart, selEnd)
            return TextEdit(selStart, selEnd, "$typed$inner$close", selStart + 1, selEnd + 1)
        }
        // Don't pair a quote or star typed right after a word character (e.g. it's, 2*3).
        val prev = text.getOrNull(selStart - 1)
        if ((typed == '"' || typed == '*') && prev != null && prev.isLetterOrDigit()) return null
        return TextEdit(selStart, selEnd, "$typed$close", selStart + 1, selStart + 1)
    }

    /** Typing a closing char right before the same auto-inserted char just steps over it. */
    fun skipClose(text: CharSequence, cursor: Int, typed: Char): Boolean =
        typed in PAIRS.values && text.getOrNull(cursor) == typed

    /** Wraps the selection (or inserts an empty pair) with [marker], e.g. ** for bold. Toggles off if already wrapped. */
    fun wrap(text: CharSequence, selStart: Int, selEnd: Int, marker: String): TextEdit {
        val before = text.subSequence(maxOf(0, selStart - marker.length), selStart).toString()
        val after = text.subSequence(selEnd, minOf(text.length, selEnd + marker.length)).toString()
        if (before == marker && after == marker) {
            val inner = text.subSequence(selStart, selEnd).toString()
            return TextEdit(selStart - marker.length, selEnd + marker.length, inner, selStart - marker.length, selEnd - marker.length)
        }
        val inner = text.subSequence(selStart, selEnd).toString()
        return TextEdit(selStart, selEnd, marker + inner + marker, selStart + marker.length, selEnd + marker.length)
    }

    /** Sets or toggles a line prefix ("- ", "> ", "- [ ] ") on every selected line. */
    fun linePrefix(text: CharSequence, selStart: Int, selEnd: Int, prefix: String): TextEdit {
        val ls = lineStart(text, selStart)
        val le = lineEnd(text, selEnd)
        val lines = text.subSequence(ls, le).toString().split('\n')
        val allHave = lines.all { it.trimStart().startsWith(prefix) }
        val changed = lines.joinToString("\n") { l ->
            val indent = l.takeWhile { it == ' ' || it == '\t' }
            val body = l.substring(indent.length)
            when {
                allHave -> indent + body.removePrefix(prefix)
                // Replace an existing list marker instead of stacking two.
                prefix.startsWith("- ") && LIST.find(body)?.range?.first == 0 -> indent + prefix + body.substring(LIST.find(body)!!.range.last + 1)
                else -> indent + prefix + body
            }
        }
        val end = ls + changed.length
        return TextEdit(ls, le, changed, if (lines.size == 1) end else ls, end)
    }

    /** Cycles the heading level of the current line: none → # → ## → ### → none. */
    fun heading(text: CharSequence, cursor: Int): TextEdit {
        val ls = lineStart(text, cursor)
        val le = lineEnd(text, cursor)
        val line = text.subSequence(ls, le).toString()
        val hashes = line.takeWhile { it == '#' }.length
        val body = line.drop(hashes).trimStart()
        val next = if (hashes >= 3) body else "#".repeat(hashes + 1) + " " + body
        return TextEdit(ls, le, next, ls + next.length, ls + next.length)
    }

    /** `[selection](url)` with the cursor in the url; a pasted URL becomes the target directly. */
    fun link(text: CharSequence, selStart: Int, selEnd: Int, url: String = ""): TextEdit {
        val label = text.subSequence(selStart, selEnd).toString()
        val inserted = "[$label]($url)"
        val cursor = if (url.isEmpty()) selStart + label.length + 3 else selStart + inserted.length
        val anchor = if (label.isEmpty() && url.isNotEmpty()) selStart + 1 else cursor
        return TextEdit(selStart, selEnd, inserted, anchor, cursor)
    }

    /** Inline code for a single-line selection, a fenced block for multi-line. */
    fun code(text: CharSequence, selStart: Int, selEnd: Int): TextEdit {
        val sel = text.subSequence(selStart, selEnd).toString()
        if ('\n' !in sel) return wrap(text, selStart, selEnd, "`")
        val block = "```\n$sel\n```"
        return TextEdit(selStart, selEnd, block, selStart + 4, selStart + 4 + sel.length)
    }

    fun table(text: CharSequence, cursor: Int): TextEdit {
        val needsBreak = cursor > 0 && text[cursor - 1] != '\n'
        val t = (if (needsBreak) "\n\n" else "") + "| Column | Column |\n| --- | --- |\n|  |  |\n"
        val cell = cursor + t.indexOf("|  |") + 2
        return TextEdit(cursor, cursor, t, cell, cell)
    }

    /**
     * Inserts a block (images) at [cursor] on lines of its own, with a blank line around it so it
     * never merges into a paragraph. Same spacing as the web editor.
     */
    fun insertBlock(text: CharSequence, cursor: Int, block: String): TextEdit {
        val at = cursor.coerceIn(0, text.length)
        val ls = lineStart(text, at)
        val le = lineEnd(text, at)
        val prevBlank = ls == 0 || text.subSequence(lineStart(text, ls - 1), ls - 1).isBlank()
        val before = when {
            at > ls -> "\n\n"
            prevBlank -> ""
            else -> "\n"
        }
        val after = if (at < le) "\n\n" else "\n"
        val insert = before + block + after
        return TextEdit(at, at, insert, at + insert.length, at + insert.length)
    }

    fun isUrl(s: String): Boolean = Regex("""^https?://\S+$""").matches(s.trim())
}
