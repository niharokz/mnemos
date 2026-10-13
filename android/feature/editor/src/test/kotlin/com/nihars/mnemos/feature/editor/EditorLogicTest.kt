package com.nihars.mnemos.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLogicTest {
    private fun apply(text: String, e: TextEdit) = text.substring(0, e.start) + e.text + text.substring(e.end)

    @Test fun enterContinuesLists() {
        val t = "- one"
        val e = MarkdownActions.onEnter(t, t.length)!!
        assertEquals("- one\n- ", apply(t, e))
        val n = "3. three"
        assertEquals("3. three\n4. ", apply(n, MarkdownActions.onEnter(n, n.length)!!))
        val task = "  - [x] done"
        assertEquals("  - [x] done\n  - [ ] ", apply(task, MarkdownActions.onEnter(task, task.length)!!))
        assertNull(MarkdownActions.onEnter("plain", 5))
    }

    @Test fun enterOnEmptyItemEndsOrOutdents() {
        val t = "- a\n- "
        assertEquals("- a\n", apply(t, MarkdownActions.onEnter(t, t.length)!!))
        val i = "- a\n  - "
        assertEquals("- a\n- ", apply(i, MarkdownActions.onEnter(i, i.length)!!))
    }

    @Test fun indentAndOutdent() {
        val t = "a\nb\nc"
        val e = MarkdownActions.indent(t, 0, 3, outdent = false)
        assertEquals("  a\n  b\nc", apply(t, e))
        val back = apply(apply(t, e), MarkdownActions.indent(apply(t, e), 0, 7, outdent = true))
        assertEquals(t, back)
    }

    @Test fun autoCloseAndWrap() {
        assertEquals("()", apply("", MarkdownActions.autoClose("", 0, 0, '(')!!))
        assertEquals("[sel]", apply("sel", MarkdownActions.autoClose("sel", 0, 3, '[')!!))
        assertNull(MarkdownActions.autoClose("it", 2, 2, '"'))
        assertTrue(MarkdownActions.skipClose("()", 1, ')'))
        val b = MarkdownActions.wrap("word", 0, 4, "**")
        assertEquals("**word**", apply("word", b))
        assertEquals("word", apply("**word**", MarkdownActions.wrap("**word**", 2, 6, "**")))
    }

    @Test fun prefixesHeadingsLinksTables() {
        assertEquals("- a\n- b", apply("a\nb", MarkdownActions.linePrefix("a\nb", 0, 3, "- ")))
        assertEquals("a\nb", apply("- a\n- b", MarkdownActions.linePrefix("- a\n- b", 0, 7, "- ")))
        assertEquals("- [ ] a", apply("- a", MarkdownActions.linePrefix("- a", 0, 3, "- [ ] ")))
        assertEquals("# T", apply("T", MarkdownActions.heading("T", 0)))
        assertEquals("T", apply("### T", MarkdownActions.heading("### T", 0)))
        assertEquals("[x](https://a.b)", apply("x", MarkdownActions.link("x", 0, 1, "https://a.b")))
        assertTrue(MarkdownActions.isUrl("https://example.com/x?y"))
        assertFalse(MarkdownActions.isUrl("not a url"))
        assertTrue(apply("x", MarkdownActions.table("x", 1)).contains("| --- | --- |"))
        assertEquals("```\na\nb\n```", apply("a\nb", MarkdownActions.code("a\nb", 0, 3)))
    }

    @Test fun historyGroupsTypingAndUndoes() {
        val h = EditHistory()
        "hello".forEachIndexed { i, c -> h.record(Change(i, "", c.toString(), 100L + i, i)) }
        h.record(Change(5, "", "\n", 5000, 5))
        val u1 = h.undo()!!
        assertEquals("\n", u1.removed)
        val u2 = h.undo()!!
        assertEquals("hello", u2.removed)
        assertEquals(0, u2.start)
        assertTrue(h.canRedo)
        assertEquals("hello", h.redo()!!.inserted)
    }

    @Test fun findAndReplace() {
        val text = "Cat cat catalog"
        assertEquals(3, FindEngine.find(text, "cat", FindOptions()).getOrThrow().size)
        assertEquals(1, FindEngine.find(text, "cat", FindOptions(matchCase = true, wholeWord = true)).getOrThrow().size)
        assertTrue(FindEngine.find(text, "(", FindOptions(regex = true)).isFailure)
        assertEquals("dog dog dogalog", FindEngine.replaceAll(text, "cat", "dog", FindOptions()).getOrThrow())
        assertEquals("[Cat] [cat] catalog", FindEngine.replaceAll(text, "\\b(cat)\\b", "[$1]", FindOptions(regex = true)).getOrThrow())
        assertEquals("a\$b", FindEngine.replaceAll("x", "x", "a\$b", FindOptions()).getOrThrow())
    }

    @Test fun tokensForMarkdownAndFences() {
        val md = "---\ntitle: x\n---\n# Head\n- [ ] task **bold** `code`\n```json\n{\"k\": 1}\n```\nafter"
        val toks = SyntaxTokens.tokenize(md, Language.MARKDOWN)
        val kinds = toks.map { it.kind }.toSet()
        assertTrue(kinds.containsAll(listOf(TokenKind.FRONT_MATTER, TokenKind.HEADING, TokenKind.TASK_OPEN, TokenKind.BOLD, TokenKind.CODE, TokenKind.CODE_BLOCK, TokenKind.KEY)))
        val after = md.indexOf("after")
        assertTrue(toks.none { it.start <= after && it.end > after })
        assertTrue(SyntaxTokens.endsInFence("a\n```\nb"))
        val log = SyntaxTokens.tokenize("2026 ERROR boom\nWARN x\nINFO y", Language.LOG).map { it.kind }
        assertTrue(log.containsAll(listOf(TokenKind.LOG_ERROR, TokenKind.LOG_WARN, TokenKind.LOG_INFO)))
        assertEquals(Language.YAML, Language.forExtension("yml"))
    }
}
