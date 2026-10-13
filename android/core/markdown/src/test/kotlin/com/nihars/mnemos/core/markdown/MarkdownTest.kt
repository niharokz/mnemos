package com.nihars.mnemos.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun frontMatterSplitKeepsLineNumbers() {
        val text = "---\ntitle: \"Hi\"\ntags: [a, b]\nstatus: open\nlist:\n  - x\n  - y\n---\n# Head\nbody"
        val (fm, body) = FrontMatter.split(text)
        assertEquals("Hi", fm!!.value("title"))
        assertEquals(listOf("a", "b"), fm.tags)
        assertEquals("x, y", fm.value("list"))
        assertEquals(text.lines().size, body.lines().size)
        assertEquals("# Head", body.lines()[8])
    }

    @Test fun noFrontMatter() {
        assertNull(FrontMatter.split("# x\n---\n").first)
        assertNull(FrontMatter.split("---\nunclosed").first)
    }

    @Test fun tags() {
        val tags = Tags.extract("Hello #work and #home/plan\n```\n#notatag\n```\n`#nope` #1 # Heading")
        assertEquals(listOf("work", "home/plan"), tags)
    }

    @Test fun taskToggle() {
        val text = "- [ ] one\r\n  - [x] two\r\nplain"
        assertEquals("- [x] one\r\n  - [x] two\r\nplain", TaskToggle.toggle(text, 0))
        assertEquals("- [ ] one\r\n  - [ ] two\r\nplain", TaskToggle.toggle(text, 1))
        assertNull(TaskToggle.toggle(text, 2))
        assertNull(TaskToggle.toggle(text, 9))
        assertEquals("1. [x] n", TaskToggle.toggle("1. [ ] n", 0))
    }

    @Test fun blockKeysAreStableForUnchangedBlocks() {
        val a = BlockKeys.assign(listOf("# A", "para", "para"))
        val b = BlockKeys.assign(listOf("# A", "para edited", "para"))
        assertEquals(a[0], b[0])
        assertEquals(3, a.toSet().size)
        assertEquals(2, BlockKeys.reused(a, b))
    }
}
