package com.nihars.mnemos.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text as the editor sees it, plus the on-disk format needed to write it back unchanged. */
data class DecodedText(val text: String, val format: TextFormat)

class NotTextException(message: String) : Exception(message)

/** Bytes ↔ text with BOM, encoding and line endings preserved. */
object TextCodec {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * Decodes [bytes]. With [normalizeNewlines] the text uses "\n" only and the original
     * ending is kept in the format; CSV turns it off so quoted newlines stay exact.
     */
    fun decode(bytes: ByteArray, normalizeNewlines: Boolean = true): DecodedText {
        val head = if (bytes.size > TextDetector.SAMPLE_BYTES) bytes.copyOf(TextDetector.SAMPLE_BYTES) else bytes
        val encoding = TextDetector.detect(head) ?: throw NotTextException("This file is not valid text.")
        val bom = when (encoding) {
            Encoding.UTF8 -> bytes.startsWith(UTF8_BOM)
            Encoding.UTF16LE, Encoding.UTF16BE -> true
        }
        val skip = when {
            !bom -> 0
            encoding == Encoding.UTF8 -> 3
            else -> 2
        }
        val raw = strictDecode(bytes, skip, charsetOf(encoding))
        val (ending, mixed) = detectEnding(raw)
        val text = if (normalizeNewlines) normalize(raw) else raw
        return DecodedText(text, TextFormat(encoding, bom, ending, mixed))
    }

    /** Encodes editor [text] ("\n" newlines) back to bytes in [format]. */
    fun encode(text: String, format: TextFormat, textIsNormalized: Boolean = true): ByteArray {
        val withEndings = if (textIsNormalized && format.lineEnding != LineEnding.LF) {
            text.replace("\n", format.lineEnding.chars)
        } else {
            text
        }
        val body = withEndings.toByteArray(charsetOf(format.encoding))
        val bom = if (!format.bom) {
            ByteArray(0)
        } else {
            when (format.encoding) {
                Encoding.UTF8 -> UTF8_BOM
                Encoding.UTF16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                Encoding.UTF16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            }
        }
        return bom + body
    }

    /** Converts CRLF and lone CR to LF. */
    fun normalize(raw: String): String = if (raw.indexOf('\r') < 0) raw else raw.replace("\r\n", "\n").replace('\r', '\n')

    /** Dominant line ending and whether more than one style is present. Files without newlines are LF. */
    fun detectEnding(raw: String): Pair<LineEnding, Boolean> {
        var lf = 0
        var crlf = 0
        var cr = 0
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '\r' -> if (i + 1 < raw.length && raw[i + 1] == '\n') {
                    crlf++
                    i++
                } else {
                    cr++
                }
                '\n' -> lf++
            }
            i++
        }
        val kinds = listOf(lf, crlf, cr).count { it > 0 }
        val ending = when {
            crlf >= lf && crlf >= cr && crlf > 0 -> LineEnding.CRLF
            cr > lf -> LineEnding.CR
            else -> LineEnding.LF
        }
        return ending to (kinds > 1)
    }

    fun charsetOf(encoding: Encoding): Charset = when (encoding) {
        Encoding.UTF8 -> Charsets.UTF_8
        Encoding.UTF16LE -> Charsets.UTF_16LE
        Encoding.UTF16BE -> Charsets.UTF_16BE
    }

    // Strict so a file with invalid bytes past 64 KB is reported instead of silently mangled on save.
    private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: CharacterCodingException) {
            throw NotTextException("This file has bytes that are not valid ${charset.name()} text.")
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
