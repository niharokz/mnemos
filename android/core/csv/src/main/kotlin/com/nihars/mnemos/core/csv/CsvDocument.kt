package com.nihars.mnemos.core.csv

/** One cell as read from the file; [quoted] is remembered so a rewrite keeps the original style. */
data class CsvField(val value: String, val quoted: Boolean = false)

/**
 * One record (line or multi-line quoted record). [rawBody] is the exact source text without the
 * terminator; it is dropped when the record is edited, and only then is the record re-serialized.
 */
class CsvRecord internal constructor(
    internal var rawBody: String?,
    internal var terminator: String,
    internal val fields: MutableList<CsvField>,
) {
    val size: Int get() = fields.size
    fun value(col: Int): String = fields.getOrNull(col)?.value ?: ""
    fun values(): List<String> = fields.map { it.value }
    val isDirty: Boolean get() = rawBody == null
}

/**
 * A CSV/TSV file that writes back byte-identically when untouched, and rewrites only the
 * records that changed. Record 0 is the header row; data rows start at 1.
 */
class CsvDocument private constructor(
    val delimiter: Char,
    // Ending for new records when nothing else tells us; taken from the file.
    val lineEnding: String,
    private val records: MutableList<CsvRecord>,
) {
    val recordCount: Int get() = records.size
    val rowCount: Int get() = maxOf(0, records.size - 1)
    val columnCount: Int get() = records.maxOfOrNull { it.size } ?: 0

    fun record(index: Int): CsvRecord = records[index]
    fun header(): List<String> = records.firstOrNull()?.values() ?: emptyList()
    fun cell(record: Int, col: Int): String = records[record].value(col)

    /** Sets one cell. Short (ragged) records are padded only as far as needed. */
    fun setCell(record: Int, col: Int, value: String) {
        val rec = records[record]
        if (rec.value(col) == value && col < rec.size) return
        while (rec.fields.size <= col) rec.fields += CsvField("")
        val old = rec.fields[col]
        rec.fields[col] = CsvField(value, old.quoted)
        rec.rawBody = null
    }

    /** Inserts a new record at [index] (1..recordCount). */
    fun insertRecord(index: Int, values: List<String> = emptyList()) {
        require(index in 1..records.size) { "Row index out of range" }
        val rec = CsvRecord(null, lineEnding, values.map { CsvField(it) }.toMutableList())
        if (index == records.size) {
            // A file without a trailing newline keeps that shape: the new last row gets none.
            val hadTrailing = endsWithTerminator()
            appendTerminatorToLast()
            rec.terminator = if (hadTrailing) lineEnding else ""
        }
        records.add(index, rec)
    }

    /** Removes a data record and returns it so the UI can undo. */
    fun deleteRecord(index: Int): CsvRecord {
        require(index in 1 until records.size) { "Row index out of range" }
        val wasLast = index == records.size - 1
        val removed = records.removeAt(index)
        // Keep "no trailing newline" files that way when their last row goes.
        if (wasLast && removed.terminator.isEmpty()) records.last().terminator = ""
        return removed
    }

    /** Puts back a record removed by [deleteRecord]. */
    fun restoreRecord(index: Int, record: CsvRecord) {
        if (index == records.size && record.terminator.isEmpty()) appendTerminatorToLast()
        if (index < records.size && record.terminator.isEmpty()) record.terminator = lineEnding
        records.add(index, record)
    }

    /** Adds a header cell only; data rows stay untouched (ragged rows are valid). */
    fun addColumn(name: String) {
        if (records.isEmpty()) {
            records += CsvRecord(null, "", mutableListOf(CsvField(name)))
            return
        }
        val header = records[0]
        val col = columnCount
        while (header.fields.size < col) header.fields += CsvField("")
        header.fields += CsvField(name)
        header.rawBody = null
    }

    fun renameColumn(col: Int, name: String) = setCell(0, col, name)

    /** Rewrites data-row order (header stays first); used by "Apply sort to file". */
    fun reorderRows(order: List<Int>) {
        require(order.sorted() == (1 until records.size).toList()) { "Order must list every data row once" }
        val endedWithTerminator = endsWithTerminator()
        val rows = order.map { records[it] }
        for (i in records.size - 1 downTo 1) records.removeAt(i)
        records += rows
        // Every record but the last needs an ending; the last keeps the file's original choice.
        for (i in 0 until records.size - 1) if (records[i].terminator.isEmpty()) records[i].terminator = lineEnding
        records.last().terminator = if (endedWithTerminator) records.last().terminator.ifEmpty { lineEnding } else ""
    }

    /** Serializes; unchanged records come out exactly as read. */
    fun toText(): String {
        val sb = StringBuilder()
        for (rec in records) {
            sb.append(rec.rawBody ?: serialize(rec))
            sb.append(rec.terminator)
        }
        return sb.toString()
    }

    private fun serialize(rec: CsvRecord): String =
        rec.fields.joinToString(delimiter.toString()) { f ->
            if (f.quoted || needsQuotes(f.value)) "\"" + f.value.replace("\"", "\"\"") + "\"" else f.value
        }

    private fun needsQuotes(v: String): Boolean =
        v.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }

    private fun endsWithTerminator(): Boolean = records.lastOrNull()?.terminator?.isNotEmpty() ?: false

    private fun appendTerminatorToLast() {
        val last = records.lastOrNull() ?: return
        if (last.terminator.isEmpty()) last.terminator = lineEnding
    }

    companion object {
        /** Parses [text] (not newline-normalized). [delimiter] null means auto-detect. */
        fun parse(text: String, delimiter: Char? = null): CsvDocument {
            val delim = delimiter ?: Delimiters.detect(text)
            val records = CsvParser(text, delim).parse()
            return CsvDocument(delim, dominantEnding(records), records)
        }

        private fun dominantEnding(records: List<CsvRecord>): String =
            records.map { it.terminator }.filter { it.isNotEmpty() }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "\n"
    }
}

/** RFC 4180 reader, tolerant: stray quotes inside unquoted fields and text after a closing quote are kept. */
private class CsvParser(private val text: String, private val delim: Char) {
    private var i = 0

    fun parse(): MutableList<CsvRecord> {
        val out = ArrayList<CsvRecord>()
        while (i < text.length) out += readRecord()
        return out
    }

    private fun readRecord(): CsvRecord {
        val start = i
        val fields = ArrayList<CsvField>()
        while (true) {
            fields += readField()
            if (i >= text.length) return CsvRecord(text.substring(start, i), "", fields)
            val c = text[i]
            if (c == delim) {
                i++
                continue
            }
            val bodyEnd = i
            val term = if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') "\r\n" else c.toString()
            i += term.length
            return CsvRecord(text.substring(start, bodyEnd), term, fields)
        }
    }

    private fun readField(): CsvField {
        if (i < text.length && text[i] == '"') {
            i++
            val sb = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        sb.append('"')
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                sb.append(c)
                i++
            }
            // Tolerate junk after the closing quote: keep it as part of the value.
            while (i < text.length && text[i] != delim && text[i] != '\n' && text[i] != '\r') sb.append(text[i++])
            return CsvField(sb.toString(), quoted = true)
        }
        val start = i
        while (i < text.length && text[i] != delim && text[i] != '\n' && text[i] != '\r') i++
        return CsvField(text.substring(start, i))
    }
}
