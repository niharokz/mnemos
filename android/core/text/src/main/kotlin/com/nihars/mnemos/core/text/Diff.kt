package com.nihars.mnemos.core.text

/** One run of a diff: equal, only on the left (removed) or only on the right (added). */
enum class DiffOp { EQUAL, DELETE, INSERT }

data class DiffRun<T>(val op: DiffOp, val items: List<T>)

/** A row of the side-by-side view; a null side means a blank gap. */
data class DiffRow(
    val left: String?,
    val right: String?,
    val changed: Boolean,
    // Word-level runs for changed pairs, used to highlight only the words that differ.
    val leftWords: List<DiffRun<String>> = emptyList(),
    val rightWords: List<DiffRun<String>> = emptyList(),
)

/** Myers O((N+M)·D) diff, used for conflict copies (line level) and changed lines (word level). */
object Diff {
    fun <T> diff(a: List<T>, b: List<T>): List<DiffRun<T>> {
        // Trim the common head and tail first: conflicts usually differ in a few places.
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val runs = ArrayList<DiffRun<T>>()
        if (start > 0) runs += DiffRun(DiffOp.EQUAL, a.subList(0, start))
        runs += myers(a.subList(start, endA), b.subList(start, endB))
        if (endA < a.size) runs += DiffRun(DiffOp.EQUAL, a.subList(endA, a.size))
        return merge(runs)
    }

    /** Line diff shaped into side-by-side rows, with word highlights on paired changed lines. */
    fun sideBySide(left: String, right: String): List<DiffRow> {
        val runs = diff(left.split('\n'), right.split('\n'))
        val rows = ArrayList<DiffRow>()
        var i = 0
        while (i < runs.size) {
            val run = runs[i]
            when (run.op) {
                DiffOp.EQUAL -> run.items.forEach { rows += DiffRow(it, it, changed = false) }
                DiffOp.DELETE -> {
                    val next = runs.getOrNull(i + 1)
                    val added = if (next?.op == DiffOp.INSERT) next.items else emptyList()
                    val n = maxOf(run.items.size, added.size)
                    for (k in 0 until n) {
                        val l = run.items.getOrNull(k)
                        val r = added.getOrNull(k)
                        rows += pairRow(l, r)
                    }
                    if (next?.op == DiffOp.INSERT) i++
                }
                DiffOp.INSERT -> run.items.forEach { rows += DiffRow(null, it, changed = true) }
            }
            i++
        }
        return rows
    }

    /** Splits into words and the whitespace/punctuation between them, so joining gives the line back. */
    fun words(line: String): List<String> = WORD.findAll(line).map { it.value }.toList()

    private fun pairRow(l: String?, r: String?): DiffRow {
        if (l == null || r == null) return DiffRow(l, r, changed = true)
        val runs = diff(words(l), words(r))
        val leftRuns = runs.filter { it.op != DiffOp.INSERT }
        val rightRuns = runs.filter { it.op != DiffOp.DELETE }
        return DiffRow(l, r, changed = true, leftWords = leftRuns.map { it.asString() }, rightWords = rightRuns.map { it.asString() })
    }

    private fun DiffRun<String>.asString(): DiffRun<String> = DiffRun(op, listOf(items.joinToString("")))

    private val WORD = Regex("""\w+|\s+|[^\w\s]""")

    private fun <T> myers(a: List<T>, b: List<T>): List<DiffRun<T>> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return listOf(DiffRun(DiffOp.INSERT, b))
        if (m == 0) return listOf(DiffRun(DiffOp.DELETE, a))
        val max = n + m
        val offset = max
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    v[offset + k + 1]
                } else {
                    v[offset + k - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    found = true
                    break
                }
                k += 2
            }
            if (found) break
        }
        return backtrack(a, b, trace, offset)
    }

    // Walks the saved V arrays backwards to rebuild the edit script.
    private fun <T> backtrack(a: List<T>, b: List<T>, trace: List<IntArray>, offset: Int): List<DiffRun<T>> {
        val ops = ArrayList<Pair<DiffOp, T>>()
        var x = a.size
        var y = b.size
        for (d in trace.size - 1 downTo 0) {
            val v = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) k + 1 else k - 1
            val prevX = v[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                ops += DiffOp.EQUAL to a[x - 1]
                x--
                y--
            }
            if (d > 0) {
                if (x == prevX) ops += DiffOp.INSERT to b[y - 1] else ops += DiffOp.DELETE to a[x - 1]
            }
            x = prevX
            y = prevY
        }
        ops.reverse()
        val runs = ArrayList<DiffRun<T>>()
        for ((op, item) in ops) {
            val last = runs.lastOrNull()
            if (last != null && last.op == op) {
                (last.items as MutableList<T>).add(item)
            } else {
                runs += DiffRun(op, mutableListOf(item))
            }
        }
        return runs
    }

    // Joins neighbouring runs of the same kind; puts deletes before inserts inside a change block.
    private fun <T> merge(runs: List<DiffRun<T>>): List<DiffRun<T>> {
        val out = ArrayList<DiffRun<T>>()
        var i = 0
        while (i < runs.size) {
            if (runs[i].op == DiffOp.EQUAL) {
                val items = ArrayList<T>()
                while (i < runs.size && runs[i].op == DiffOp.EQUAL) items += runs[i++].items
                if (items.isNotEmpty()) out += DiffRun(DiffOp.EQUAL, items)
            } else {
                val del = ArrayList<T>()
                val ins = ArrayList<T>()
                while (i < runs.size && runs[i].op != DiffOp.EQUAL) {
                    if (runs[i].op == DiffOp.DELETE) del += runs[i].items else ins += runs[i].items
                    i++
                }
                if (del.isNotEmpty()) out += DiffRun(DiffOp.DELETE, del)
                if (ins.isNotEmpty()) out += DiffRun(DiffOp.INSERT, ins)
            }
        }
        return out
    }
}
