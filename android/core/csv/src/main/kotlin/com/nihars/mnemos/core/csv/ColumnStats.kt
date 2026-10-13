package com.nihars.mnemos.core.csv

import com.nihars.mnemos.core.text.NaturalCompare

/** Summary of one column for the stats sheet. Numeric fields are null when the column is not numeric. */
data class ColumnStats(
    val count: Int,
    val empty: Int,
    val unique: Int,
    val numeric: Boolean,
    val sum: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val average: Double? = null,
) {
    companion object {
        fun of(doc: CsvDocument, col: Int): ColumnStats {
            val values = (1 until doc.recordCount).map { doc.cell(it, col).trim() }
            val nonEmpty = values.filter { it.isNotEmpty() }
            val numbers = nonEmpty.mapNotNull { CsvSort.parseNumber(it) }
            // Numeric only if every non-empty cell parses, so "12 apples" columns stay text.
            val numeric = nonEmpty.isNotEmpty() && numbers.size == nonEmpty.size
            return if (!numeric) {
                ColumnStats(values.size, values.size - nonEmpty.size, nonEmpty.toSet().size, false)
            } else {
                ColumnStats(
                    count = values.size,
                    empty = values.size - nonEmpty.size,
                    unique = nonEmpty.toSet().size,
                    numeric = true,
                    sum = numbers.sum(),
                    min = numbers.min(),
                    max = numbers.max(),
                    average = numbers.average(),
                )
            }
        }
    }
}

/** View-only sort helpers; numbers compare as numbers, everything else naturally. */
object CsvSort {
    // Accepts 1,234.5 and ₹/$ prefixes because exported money columns look like that.
    fun parseNumber(s: String): Double? {
        val cleaned = s.trim().removePrefix("₹").removePrefix("$").replace(",", "").replace("_", "")
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()
    }

    fun isNumericColumn(doc: CsvDocument, col: Int): Boolean {
        val nonEmpty = (1 until doc.recordCount).map { doc.cell(it, col).trim() }.filter { it.isNotEmpty() }.take(200)
        return nonEmpty.isNotEmpty() && nonEmpty.all { parseNumber(it) != null }
    }

    /** Data-row indices (1-based record numbers) in sorted order. Empty cells go last. */
    fun order(doc: CsvDocument, col: Int, ascending: Boolean): List<Int> {
        val numeric = isNumericColumn(doc, col)
        val rows = (1 until doc.recordCount).toList()
        val cmp = Comparator<Int> { a, b ->
            val va = doc.cell(a, col).trim()
            val vb = doc.cell(b, col).trim()
            when {
                va.isEmpty() && vb.isEmpty() -> 0
                va.isEmpty() -> 1
                vb.isEmpty() -> -1
                else -> {
                    val c = if (numeric) {
                        compareValues(parseNumber(va), parseNumber(vb))
                    } else {
                        NaturalCompare.compare(va, vb)
                    }
                    if (ascending) c else -c
                }
            }
        }
        return rows.sortedWith(cmp)
    }
}
