package com.nihars.mnemos.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class LineIndexTest {
    @Test fun indexesLinesAndExtends() {
        val text = "one\ntwo\r\nthree\n"
        val buf = ByteBuffer.wrap(text.toByteArray())
        val idx = LineIndex()
        idx.extend(buf, 8)
        assertEquals(2, idx.count)
        idx.extend(buf, text.length.toLong())
        assertEquals(3, idx.visibleCount(text.length.toLong()))
        assertEquals(4L, idx.start(1))
        assertEquals(1, idx.lineOf(5))
        assertEquals(listOf(1, 2), ByteSearch.lines(buf, text.length.toLong(), "T", idx))
    }
}
