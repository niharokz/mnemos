package com.nihars.mnemos.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Images go on lines of their own with blank lines around them, as in the web editor. */
class InsertBlockTest {
    private fun insert(text: String, cursor: Int, block: String = "![a](attachments/a.png)"): String {
        val e = MarkdownActions.insertBlock(text, cursor, block)
        return text.substring(0, e.start) + e.text + text.substring(e.end)
    }

    @Test fun emptyNote() = assertEquals("![a](attachments/a.png)\n", insert("", 0))

    @Test fun afterAParagraph() = assertEquals("text\n\n![a](attachments/a.png)\n", insert("text\n", 5))

    @Test fun atTheEndOfALine() = assertEquals("text\n\n![a](attachments/a.png)\n", insert("text", 4))

    @Test fun inTheMiddleOfALine() = assertEquals("te\n\n![a](attachments/a.png)\n\nxt", insert("text", 2))

    @Test fun afterABlankLine() = assertEquals("# H\n\n![a](attachments/a.png)\n", insert("# H\n\n", 5))

    @Test fun cursorEndsAfterTheBlock() {
        val e = MarkdownActions.insertBlock("text", 4, "B")
        assertEquals(4 + "\n\nB\n".length, e.selStart)
    }
}
