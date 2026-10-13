package com.nihars.mnemos.core.index

/** Subsequence fuzzy match: consecutive and word-start hits score higher. 0 means no match. */
object Fuzzy {
    fun score(query: String, name: String): Int {
        val q = query.lowercase().filter { !it.isWhitespace() }
        if (q.isEmpty()) return 0
        val n = name.lowercase()
        if (n.startsWith(q)) return 500 + q.length
        val idx = n.indexOf(q)
        if (idx >= 0) return 400 - minOf(idx, 100)
        var qi = 0
        var score = 0
        var last = -2
        for ((i, c) in n.withIndex()) {
            if (qi < q.length && c == q[qi]) {
                score += if (i == last + 1) 6 else 2
                if (i == 0 || !n[i - 1].isLetterOrDigit()) score += 4
                last = i
                qi++
            }
        }
        return if (qi == q.length) score else 0
    }
}
