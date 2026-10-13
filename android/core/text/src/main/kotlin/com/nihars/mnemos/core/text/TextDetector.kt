package com.nihars.mnemos.core.text

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Decides text vs binary from the first bytes of a file; the extension never matters. */
object TextDetector {
    const val SAMPLE_BYTES = 64 * 1024

    /** Returns the encoding if [head] (the first ≤64 KB) is text, or null for binary. */
    fun detect(head: ByteArray): Encoding? {
        if (head.size >= 2) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            // UTF-16 is only trusted with a BOM, as the spec says.
            if (b0 == 0xFF && b1 == 0xFE) return Encoding.UTF16LE
            if (b0 == 0xFE && b1 == 0xFF) return Encoding.UTF16BE
        }
        if (head.any { it == 0.toByte() }) return null
        return if (isUtf8Prefix(head)) Encoding.UTF8 else null
    }

    // Decodes without endOfInput so a multibyte char cut at the 64 KB edge is not an error.
    private fun isUtf8Prefix(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(bytes.size + 1)
        return try {
            !decoder.decode(ByteBuffer.wrap(bytes), out, false).isError
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
