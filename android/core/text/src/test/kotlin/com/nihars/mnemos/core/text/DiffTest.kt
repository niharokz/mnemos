package com.nihars.mnemos.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {
    private fun <T> apply(a: List<T>, runs: List<DiffRun<T>>): List<T> {
        val out = ArrayList<T>()
        var i = 0
        for (r in runs) {
            when (r.op) {
                DiffOp.EQUAL -> {
                    assertEquals(a.subList(i, i + r.items.size), r.items)
                    out += r.items
                    i += r.items.size
                }
                DiffOp.DELETE -> {
                    assertEquals(a.subList(i, i + r.items.size), r.items)
                    i += r.items.size
                }
                DiffOp.INSERT -> out += r.items
            }
        }
        assertEquals(a.size, i)
        return out
    }

    @Test fun diffRebuildsTarget() {
        val cases = listOf(
            "abcabba" to "cbabac",
            "" to "abc",
            "abc" to "",
            "same" to "same",
            "the quick brown fox" to "the quack brown dog fox",
        )
        for ((x, y) in cases) {
            val a = x.toList()
            val b = y.toList()
            assertEquals(b, apply(a, Diff.diff(a, b)))
        }
    }

    @Test fun randomDiffsRebuildTarget() {
        val rnd = java.util.Random(7)
        repeat(300) {
            val a = List(rnd.nextInt(30)) { "abcd"[rnd.nextInt(4)] }
            val b = List(rnd.nextInt(30)) { "abcd"[rnd.nextInt(4)] }
            assertEquals(b, apply(a, Diff.diff(a, b)))
        }
    }

    @Test fun sideBySidePairsChangedLinesWithWordHighlights() {
        val rows = Diff.sideBySide("a\nhello world\nc", "a\nhello there\nc\nd")
        assertEquals(4, rows.size)
        assertFalse(rows[0].changed)
        assertTrue(rows[1].changed)
        assertEquals("hello world", rows[1].left)
        assertEquals("hello there", rows[1].right)
        assertTrue(rows[1].leftWords.any { it.op == DiffOp.DELETE && it.items.single() == "world" })
        assertEquals(null, rows[3].left)
        assertEquals("d", rows[3].right)
    }

    @Test fun naturalOrder() {
        val sorted = listOf("note 10", "Note 2", "note 1", "apple").sortedWith(NaturalCompare)
        assertEquals(listOf("apple", "note 1", "Note 2", "note 10"), sorted)
    }
}
