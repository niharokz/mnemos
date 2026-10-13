package com.nihars.mnemos.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private val parser = MarkdownParser()

    @Test fun parsesBlocksWithFileLines() {
        val text = "---\ntags: [x]\n---\n# Title\n\nline one\nline two\n\n- [ ] task\n- [x] done\n\n| a | b |\n|:-|-:|\n| 1 | 2 |\n\n```kt\nval x = 1\n```\n\n<div>raw</div>\n"
        val doc = parser.parse(text, "stem")
        assertEquals("Title", doc.title)
        assertEquals(listOf("x"), doc.tags)
        val kinds = doc.blocks.map { it.javaClass.simpleName }
        assertEquals(listOf("Properties", "Heading", "Paragraph", "ListBlock", "Table", "CodeBlock", "RawHtml"), kinds)
        val para = doc.blocks[2] as Block.Paragraph
        assertTrue(para.inlines.contains(Inline.Break))
        val list = doc.blocks[3] as Block.ListBlock
        assertEquals(false, list.items[0].task)
        assertEquals(8, list.items[0].line)
        assertEquals(true, list.items[1].task)
        assertEquals("- [x] task", TaskToggle.toggle(text, list.items[0].line)!!.lines()[8])
        val table = doc.blocks[4] as Block.Table
        assertEquals(listOf(Align.LEFT, Align.RIGHT), table.align)
        assertEquals("kt", (doc.blocks[5] as Block.CodeBlock).language)
    }

    @Test fun editingOneParagraphKeepsOtherKeys() {
        val a = parser.parse("# H\n\none\n\ntwo\n").blocks.map { it.key }
        val b = parser.parse("# H\n\none!\n\ntwo\n").blocks.map { it.key }
        assertEquals(a[0], b[0])
        assertEquals(a[2], b[2])
        assertTrue(a[1] != b[1])
    }

    @Test fun footnotesAndStrike() {
        val doc = parser.parse("A ~~b~~ c[^1]\n\n[^1]: note\n")
        val p = doc.blocks[0] as Block.Paragraph
        assertTrue(p.inlines.any { it is Inline.Strike })
        assertTrue(p.inlines.any { it is Inline.FootnoteRef })
        assertTrue(doc.blocks.any { it is Block.Footnote })
    }
}
