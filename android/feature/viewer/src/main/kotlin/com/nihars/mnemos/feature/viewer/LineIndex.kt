package com.nihars.mnemos.feature.viewer

import java.nio.ByteBuffer

/**
 * Start offsets of every line in a byte buffer, built incrementally so a growing log can be
 * extended without rescanning. Pure Kotlin, so it is unit tested on the JVM.
 */
class LineIndex {
    // Written by the indexing thread, read by the UI: publish the value before the count.
    @Volatile private var starts = LongArray(1024)

    @Volatile var count = 0
        private set
    private var scanned = 0L

    init {
        starts[0] = 0
        count = 1
    }

    /** Scans [buf] from where the last call stopped up to [limit]. */
    fun extend(buf: ByteBuffer, limit: Long, onProgress: (Int) -> Unit = {}) {
        var i = scanned
        while (i < limit) {
            if (buf.get(i.toInt()) == NL) add(i + 1)
            i++
            if (i and 0xFFFFF == 0L) onProgress(count)
        }
        scanned = limit
    }

    fun start(line: Int): Long = starts[line]

    /** End offset (exclusive, before the newline) of [line] in a file of [size] bytes. */
    fun end(line: Int, size: Long): Long = if (line + 1 < count) starts[line + 1] - 1 else size

    /** The final "line" after a trailing newline is empty; hide it. */
    fun visibleCount(size: Long): Int = if (count > 1 && starts[count - 1] >= size) count - 1 else count

    /** Line containing byte [offset]. */
    fun lineOf(offset: Long): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun add(offset: Long) {
        if (count == starts.size) starts = starts.copyOf(starts.size * 2)
        starts[count] = offset
        count++
    }

    private companion object {
        const val NL = '\n'.code.toByte()
    }
}

/** Case-insensitive (ASCII) byte search over a buffer; returns matching line numbers. */
object ByteSearch {
    fun lines(buf: ByteBuffer, size: Long, query: String, index: LineIndex, cap: Int = 5000): List<Int> {
        val needle = query.lowercase().toByteArray(Charsets.UTF_8)
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var lastLine = -1
        var i = 0L
        val end = size - needle.size
        while (i <= end && out.size < cap) {
            var j = 0
            while (j < needle.size && lower(buf.get((i + j).toInt())) == needle[j]) j++
            if (j == needle.size) {
                val line = index.lineOf(i)
                if (line != lastLine) {
                    out += line
                    lastLine = line
                }
            }
            i++
        }
        return out
    }

    private fun lower(b: Byte): Byte = if (b in 'A'.code.toByte()..'Z'.code.toByte()) (b + 32).toByte() else b
}
