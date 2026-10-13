package com.nihars.mnemos.core.csv

/** Picks the delimiter that splits the first lines most consistently. */
object Delimiters {
    private val CANDIDATES = charArrayOf(',', ';', '\t', '|')

    fun forExtension(ext: String): Char? = if (ext.equals("tsv", ignoreCase = true)) '\t' else null

    fun detect(text: String): Char {
        val lines = sampleLines(text, 20)
        if (lines.isEmpty()) return ','
        var best = ','
        var bestScore = -1.0
        for (d in CANDIDATES) {
            val counts = lines.map { countOutsideQuotes(it, d) }
            val first = counts.first()
            if (first == 0) continue
            // Same count on every line scores highest; more columns breaks ties.
            val consistent = counts.count { it == first }.toDouble() / counts.size
            val score = consistent * 1000 + first
            if (score > bestScore) {
                bestScore = score
                best = d
            }
        }
        return best
    }

    private fun sampleLines(text: String, max: Int): List<String> =
        text.lineSequence().filter { it.isNotBlank() }.take(max).toList()

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var inQuotes = false
        var n = 0
        for (c in line) {
            if (c == '"') inQuotes = !inQuotes else if (c == d && !inQuotes) n++
        }
        return n
    }
}
