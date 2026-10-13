package com.nihars.mnemos.core.csv

import com.nihars.mnemos.core.text.TextCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvDocumentTest {
    private fun golden(name: String): ByteArray = javaClass.getResourceAsStream("/golden/$name")!!.readBytes()

    private fun open(bytes: ByteArray, delim: Char? = null): Pair<CsvDocument, com.nihars.mnemos.core.text.TextFormat> {
        val d = TextCodec.decode(bytes, normalizeNewlines = false)
        return CsvDocument.parse(d.text, delim) to d.format
    }

    private fun save(doc: CsvDocument, fmt: com.nihars.mnemos.core.text.TextFormat) =
        TextCodec.encode(doc.toText(), fmt, textIsNormalized = false)

    @Test fun goldenFilesRoundTripByteIdentical() {
        for (name in listOf("crlf_quotes.csv", "bom_semicolon_ragged.csv", "no_trailing_newline.tsv", "oddities.csv")) {
            val bytes = golden(name)
            val (doc, fmt) = open(bytes, Delimiters.forExtension(name.substringAfterLast('.')))
            assertArrayEquals(name, bytes, save(doc, fmt))
        }
    }

    @Test fun parsesQuotesAndEmbeddedNewlines() {
        val (doc, _) = open(golden("crlf_quotes.csv"))
        assertEquals(',', doc.delimiter)
        assertEquals(3, doc.rowCount)
        assertEquals("says \"hi\"", doc.cell(1, 2))
        assertEquals("Multi\nline", doc.cell(2, 0))
        assertEquals("Rice, basmati", doc.cell(3, 0))
    }

    @Test fun detectsSemicolonAndKeepsRaggedRows() {
        val (doc, _) = open(golden("bom_semicolon_ragged.csv"))
        assertEquals(';', doc.delimiter)
        assertEquals(4, doc.columnCount)
        assertEquals("", doc.cell(2, 2))
    }

    @Test fun editingOneCellRewritesOnlyThatRow() {
        val bytes = golden("crlf_quotes.csv")
        val (doc, fmt) = open(bytes)
        doc.setCell(1, 1, "45")
        val out = String(save(doc, fmt))
        val expected = String(bytes).replace("Tea,40,", "Tea,45,")
        assertEquals(expected, out)
    }

    @Test fun editingKeepsQuoteStyleAndQuotesWhenNeeded() {
        val (doc, fmt) = open(golden("crlf_quotes.csv"))
        doc.setCell(3, 0, "Rice")
        doc.setCell(3, 2, "a,b")
        val out = String(save(doc, fmt))
        assertTrue(out.contains("\"Rice\",300,\"a,b\"\r\n"))
    }

    @Test fun appendKeepsNoTrailingNewline() {
        val (doc, fmt) = open(golden("no_trailing_newline.tsv"), '\t')
        doc.insertRecord(doc.recordCount, listOf("n", "m"))
        assertEquals("col1\tcol2\nx\ty\n\"q\tz\"\tw\nn\tm", String(save(doc, fmt)))
    }

    @Test fun deleteAndRestoreIsExact() {
        val bytes = golden("no_trailing_newline.tsv")
        val (doc, fmt) = open(bytes, '\t')
        val removed = doc.deleteRecord(2)
        assertEquals("col1\tcol2\nx\ty", String(save(doc, fmt)))
        doc.restoreRecord(2, removed)
        assertArrayEquals(bytes, save(doc, fmt))
    }

    @Test fun addColumnTouchesOnlyHeader() {
        val (doc, fmt) = open("a,b\n1,2\n".toByteArray())
        doc.addColumn("c")
        assertEquals("a,b,c\n1,2\n", String(save(doc, fmt)))
    }

    @Test fun applySortReordersRowsAndFixesEndings() {
        val (doc, fmt) = open("n\n10\n2\n1".toByteArray())
        doc.reorderRows(CsvSort.order(doc, 0, ascending = true))
        assertEquals("n\n1\n2\n10", String(save(doc, fmt)))
    }

    @Test fun columnStats() {
        val (doc, _) = open("v,t\n1,a\n2,a\n,b\n4,c\n".toByteArray())
        val s = ColumnStats.of(doc, 0)
        assertTrue(s.numeric)
        assertEquals(4, s.count)
        assertEquals(1, s.empty)
        assertEquals(7.0, s.sum!!, 0.0)
        val t = ColumnStats.of(doc, 1)
        assertEquals(3, t.unique)
    }
}
