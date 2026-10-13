package com.nihars.mnemos.core.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCodecTest {
    private fun roundTrip(bytes: ByteArray) {
        val d = TextCodec.decode(bytes)
        assertArrayEquals(bytes, TextCodec.encode(d.text, d.format))
    }

    @Test fun lfFileRoundTrips() = roundTrip("a\nb\n".toByteArray())

    @Test fun crlfFileStaysCrlf() {
        val bytes = "one\r\ntwo\r\n".toByteArray()
        val d = TextCodec.decode(bytes)
        assertEquals("one\ntwo\n", d.text)
        assertEquals(LineEnding.CRLF, d.format.lineEnding)
        roundTrip(bytes)
        // Edited text keeps CRLF.
        assertArrayEquals("one\r\ntwo\r\nthree".toByteArray(), TextCodec.encode(d.text + "three", d.format))
    }

    @Test fun utf8BomIsKept() = roundTrip(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo\n".toByteArray())

    @Test fun utf16LeWithBomRoundTrips() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi\r\nthere".toByteArray(Charsets.UTF_16LE)
        val d = TextCodec.decode(bytes)
        assertEquals(Encoding.UTF16LE, d.format.encoding)
        assertEquals("hi\nthere", d.text)
        roundTrip(bytes)
    }

    @Test fun mixedEndingsAreReported() {
        val d = TextCodec.decode("a\r\nb\nc\r\n".toByteArray())
        assertTrue(d.format.mixedEndings)
        assertEquals(LineEnding.CRLF, d.format.lineEnding)
    }

    @Test fun binaryIsDetected() {
        assertNull(TextDetector.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01)))
        assertNull(TextDetector.detect(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test fun multibyteCutAtSampleEdgeIsStillText() {
        val big = ByteArray(TextDetector.SAMPLE_BYTES - 1) { 'a'.code.toByte() } + "é".toByteArray()
        assertEquals(Encoding.UTF8, TextDetector.detect(big.copyOf(TextDetector.SAMPLE_BYTES)))
    }

    @Test(expected = NotTextException::class)
    fun invalidUtf8AfterSampleIsRejected() {
        val bytes = ByteArray(TextDetector.SAMPLE_BYTES + 10) { 'a'.code.toByte() }
        bytes[bytes.size - 2] = 0xC3.toByte()
        bytes[bytes.size - 1] = 0x28
        TextCodec.decode(bytes)
    }

    @Test fun versionIs24HexChars() {
        val v = Versions.of("x".toByteArray())
        assertEquals(24, v.length)
        assertEquals(v, Versions.of("x".toByteArray()))
    }
}
