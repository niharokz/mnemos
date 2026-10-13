package com.nihars.mnemos.feature.tree

import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextDetector

/** Plain-text preview for Box cards: skips front matter and markdown markers, keeps a few lines. */
object Previews {
    private val MARKERS = Regex("""^(\s*#{1,6}\s+|\s*[-*+]\s+(\[[ xX]]\s+)?|\s*\d+[.)]\s+|\s*>\s?)""")

    fun of(head: ByteArray, maxLines: Int = 6): String {
        if (TextDetector.detect(head) == null) return ""
        val text = runCatching { TextCodec.decode(head).text }.getOrElse { String(head, Charsets.UTF_8) }
        return text(text, maxLines)
    }

    fun text(text: String, maxLines: Int = 6): String {
        var lines = text.lines()
        if (lines.firstOrNull()?.trimEnd() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trimEnd() == "---" || it.trimEnd() == "..." }
            if (end >= 0) lines = lines.drop(end + 2)
        }
        return lines.asSequence()
            .map { it.replace(MARKERS, "").replace("**", "").replace("`", "").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .take(maxLines)
            .joinToString("\n")
    }
}
