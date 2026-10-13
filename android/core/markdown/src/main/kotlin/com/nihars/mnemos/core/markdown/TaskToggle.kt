package com.nihars.mnemos.core.markdown

/** Flips `[ ]` ↔ `[x]` on one line of a note, as a preview checkbox tap does. */
object TaskToggle {
    private val TASK = Regex("""^(\s*(?:>\s*)*(?:[-*+]|\d+[.)])\s+\[)([ xX])(\].*)$""")

    /** Returns the new text, or null when [line] is not a task item (e.g. the file changed meanwhile). */
    fun toggle(text: String, line: Int): String? {
        var start = 0
        repeat(line) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return null
            start = nl + 1
        }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val current = text.substring(start, end)
        // Strip a trailing \r so CRLF text still matches; it is put back untouched.
        val cr = current.endsWith('\r')
        val body = if (cr) current.dropLast(1) else current
        val m = TASK.matchEntire(body) ?: return null
        val flipped = if (m.groupValues[2] == " ") "x" else " "
        val newLine = m.groupValues[1] + flipped + m.groupValues[3] + if (cr) "\r" else ""
        return text.substring(0, start) + newLine + text.substring(end)
    }
}
